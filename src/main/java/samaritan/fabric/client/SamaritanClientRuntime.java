package samaritan.fabric.client;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Vec3d;
import samaritan.fabric.client.net.AuthClient;
import samaritan.fabric.client.net.GameWebSocketClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class SamaritanClientRuntime {
    private static final int DEFAULT_ESP_COLOR_RGB = 0x94D9FF;
    private static final int DEFAULT_MAX_RENDER_DISTANCE_BLOCKS = 5000;
    private static final float ESP_ALPHA = 1.00f;
    private static final int ESP_TEXT_COLOR = 0xBFE8FF;
    private static final int ESP_TEXT_LIGHT = 15728880;
    private static final long REMOTE_STALE_MS = 20_000L;
    private static final double OUT_OF_RANGE_MARKER_DISTANCE_PADDING = 12.0;

    private String serverHost = "127.0.0.1";
    private int serverPort = 8081;

    private AuthClient authClient;
    private GameWebSocketClient webSocketClient;
    private boolean connecting;
    private final Set<String> activeTransmitters = ConcurrentHashMap.newKeySet();
    private final Map<String, RemotePlayerState> remotePlayers = new ConcurrentHashMap<>();
    private final Map<Long, Long> pendingPings = new ConcurrentHashMap<>();
    private final AtomicLong pingNonceCounter = new AtomicLong();
    private final PersistentStateStore persistentStateStore = new PersistentStateStore();
    private volatile int espColorRgb = DEFAULT_ESP_COLOR_RGB;
    private volatile int maxRenderDistanceBlocks = DEFAULT_MAX_RENDER_DISTANCE_BLOCKS;

    public SamaritanClientRuntime() {
        loadPersistedState();
    }

    public synchronized void setServerEndpoint(String host, int port) {
        this.serverHost = host;
        this.serverPort = port;
        savePersistedState();
    }

    public synchronized String getServerHost() {
        return serverHost;
    }

    public synchronized int getServerPort() {
        return serverPort;
    }

    public synchronized int getEspColorRgb() {
        return espColorRgb;
    }

    public synchronized int getMaxRenderDistanceBlocks() {
        return maxRenderDistanceBlocks;
    }

    public synchronized void setEspSettings(int colorRgb, int maxDistanceBlocks) {
        this.espColorRgb = colorRgb & 0xFFFFFF;
        this.maxRenderDistanceBlocks = Math.max(1, maxDistanceBlocks);
        savePersistedState();
    }

    public synchronized boolean isConnected() {
        return webSocketClient != null && webSocketClient.isConnected() && authClient != null && authClient.isTokenValid();
    }

    public synchronized boolean isConnecting() {
        return connecting;
    }

    public synchronized String getUsername() {
        return authClient == null ? null : authClient.getUsername();
    }

    public void connectAsync(String username, String password, MinecraftClient client) {
        synchronized (this) {
            if (connecting) {
                sendMessage(client, "Already connecting...");
                return;
            }
            if (isConnected()) {
                sendMessage(client, "Already connected as " + getUsername());
                return;
            }
            connecting = true;
        }

        sendMessage(client, "Logging in as " + username + "...");

        CompletableFuture.runAsync(() -> {
            String host;
            int port;
            synchronized (this) {
                host = this.serverHost;
                port = this.serverPort;
            }

            AuthClient auth = new AuthClient("http://" + host + ":" + port);
            if (!auth.login(username, password)) {
                client.execute(() -> sendMessage(client, "Login failed for " + username));
                return;
            }

            GameWebSocketClient ws = new GameWebSocketClient(
                    "ws://" + host + ":" + port + "/ws/game",
                    auth,
                    createPositionListener(client)
            );
            ws.connect();
            if (!ws.isConnected()) {
                client.execute(() -> sendMessage(client, "WebSocket connection failed"));
                return;
            }

            synchronized (this) {
                this.authClient = auth;
                this.webSocketClient = ws;
                savePersistedState();
            }

            client.execute(() -> sendMessage(client, "Connected as " + username + " (" + host + ":" + port + ")"));
        }).whenComplete((unused, throwable) -> {
            synchronized (this) {
                connecting = false;
            }

            if (throwable != null) {
                client.execute(() -> sendMessage(client, "Connection error: " + throwable.getClass().getSimpleName()));
            }
        });
    }

    public void reconnectWithCurrentToken(MinecraftClient client) {
        ensureSessionLoadedFromDisk();

        synchronized (this) {
            if (connecting) {
                sendMessage(client, "Already connecting...");
                return;
            }
            if (webSocketClient != null && webSocketClient.isConnected()) {
                sendMessage(client, "Already connected as " + getUsername());
                return;
            }
            if (authClient == null || !authClient.isTokenValid()) {
                sendMessage(client, "No valid token available. Use /samaritan login <username> <password> first.");
                return;
            }
            connecting = true;
        }

        CompletableFuture.runAsync(() -> {
            String host;
            int port;
            AuthClient auth;
            synchronized (this) {
                host = this.serverHost;
                port = this.serverPort;
                auth = this.authClient;
            }

            GameWebSocketClient ws = new GameWebSocketClient(
                    "ws://" + host + ":" + port + "/ws/game",
                    auth,
                    createPositionListener(client)
            );
            ws.connect();
            if (!ws.isConnected()) {
                client.execute(() -> sendMessage(client, "WebSocket connection failed"));
                return;
            }

            synchronized (this) {
                this.webSocketClient = ws;
                savePersistedState();
            }

            client.execute(() -> sendMessage(client, "Reconnected with existing token"));
        }).whenComplete((unused, throwable) -> {
            synchronized (this) {
                connecting = false;
            }
            if (throwable != null) {
                client.execute(() -> sendMessage(client, "Connection error: " + throwable.getClass().getSimpleName()));
            }
        });
    }

    public synchronized void disconnect(MinecraftClient client) {
        if (webSocketClient != null) {
            webSocketClient.disconnect();
            webSocketClient = null;
        }
        activeTransmitters.clear();
        remotePlayers.clear();
        if (authClient != null) {
            authClient.logout();
            authClient = null;
        }
        savePersistedState();
        sendMessage(client, "Disconnected");
    }

    public synchronized void onLeaveServer(MinecraftClient client) {
        if (webSocketClient != null) {
            webSocketClient.disconnect();
            webSocketClient = null;
            activeTransmitters.clear();
            remotePlayers.clear();
            sendMessage(client, "Disconnected from Samaritan (left server). Use /samaritan login to reconnect.");
        }
    }

    public synchronized void onClientTick(MinecraftClient client) {
        if (client.player == null || webSocketClient == null || !webSocketClient.isConnected()) {
            return;
        }

        String dimension = client.player.getWorld().getRegistryKey().getValue().toString();
        String activeServerIp = client.getCurrentServerEntry() != null
                ? client.getCurrentServerEntry().address
                : "singleplayer";
        String localIgn = client.player.getGameProfile().getName();

        webSocketClient.updatePosition(
                client.player.getX(),
                client.player.getY(),
                client.player.getZ(),
                dimension,
                activeServerIp,
                localIgn
        );
    }

    public long getTokenTimeRemainingMs() {
        synchronized (this) {
            return authClient == null ? 0 : authClient.getTokenTimeRemaining();
        }
    }

    public String getDetailedStatus() {
        String host;
        int port;
        synchronized (this) {
            host = this.serverHost;
            port = this.serverPort;
        }

        if (isConnected()) {
            long seconds = getTokenTimeRemainingMs() / 1000L;
            return String.format(
                    "CONNECTED | user=%s | endpoint=%s:%d | token=%ss | online=%d",
                    safeLabel(getUsername()),
                    host,
                    port,
                    seconds,
                    getOnlineUsernames().size()
            );
        }
        if (isConnecting()) {
            return String.format("CONNECTING | endpoint=%s:%d", host, port);
        }
        return String.format("DISCONNECTED | endpoint=%s:%d", host, port);
    }

    public List<String> getOnlineUsernames() {
        long now = System.currentTimeMillis();
        List<String> usernames = new ArrayList<>();
        for (RemotePlayerState state : remotePlayers.values()) {
            if (now - state.lastUpdateEpochMs() <= REMOTE_STALE_MS) {
                usernames.add(state.username());
            }
        }
        usernames.sort(String.CASE_INSENSITIVE_ORDER);
        return usernames;
    }

    public Optional<PlayerPositionInfo> getRemotePlayerPosition(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        RemotePlayerState state = remotePlayers.get(username);
        if (state == null) {
            return Optional.empty();
        }
        if (System.currentTimeMillis() - state.lastUpdateEpochMs() > REMOTE_STALE_MS) {
            return Optional.empty();
        }
        return Optional.of(new PlayerPositionInfo(
                state.username(),
                state.ign(),
                state.x(),
                state.y(),
                state.z(),
                state.dimension(),
                state.serverIp()
        ));
    }

    public boolean sendChat(MinecraftClient client, String message) {
        if (message == null || message.isBlank()) {
            sendWarnMessage(client, "Usage: /samaritan chat <message>");
            return false;
        }

        GameWebSocketClient ws;
        synchronized (this) {
            ws = this.webSocketClient;
        }
        if (ws == null || !ws.isConnected()) {
            sendWarnMessage(client, "Not connected. Use /samaritan login first.");
            return false;
        }

        ws.sendChat(message.trim());
        String localIgn = client.player != null ? client.player.getGameProfile().getName() : getUsername();
        String activeServerIp = client.getCurrentServerEntry() != null
                ? client.getCurrentServerEntry().address
                : "singleplayer";
        String localDimension = client.player != null
                ? client.player.getWorld().getRegistryKey().getValue().toString()
                : "unknown";
        sendIncomingChatMessage(
                client,
                safeLabel(getUsername()),
                safeLabel(localIgn),
                activeServerIp,
                localDimension,
                message.trim()
        );
        return true;
    }

    public boolean sendPing(MinecraftClient client) {
        GameWebSocketClient ws;
        synchronized (this) {
            ws = this.webSocketClient;
        }
        if (ws == null || !ws.isConnected()) {
            sendWarnMessage(client, "Not connected. Use /samaritan login first.");
            return false;
        }

        long nonce = pingNonceCounter.incrementAndGet();
        pendingPings.put(nonce, System.currentTimeMillis());
        ws.sendPing(nonce);
        sendInfoMessage(client, "Ping sent...");
        return true;
    }

    public void renderEspMarkers(WorldRenderContext context) {
        // Disabled intentionally: world-space teammate box rendering removed.
    }

    public void renderOorHudIndicators(DrawContext drawContext) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            return;
        }

        String currentDimension = client.player.getWorld().getRegistryKey().getValue().toString();
        String currentServerIp = client.getCurrentServerEntry() != null
                ? client.getCurrentServerEntry().address
                : "singleplayer";
        long now = System.currentTimeMillis();

        List<RemotePlayerState> snapshot = new ArrayList<>(remotePlayers.values());

        Vec3d localPos = client.player.getPos();
        double maxDistanceSq = (double) maxRenderDistanceBlocks * (double) maxRenderDistanceBlocks;
        double viewCapDistance = Math.max(64.0, client.options.getViewDistance().getValue() * 16.0 - OUT_OF_RANGE_MARKER_DISTANCE_PADDING);
        Vec3d look = client.player.getRotationVec(1.0f);
        Vec3d forward = new Vec3d(look.x, 0.0, look.z);
        if (forward.lengthSquared() < 0.001) {
            forward = new Vec3d(0, 0, 1);
        } else {
            forward = forward.normalize();
        }
        Vec3d right = new Vec3d(-forward.z, 0.0, forward.x);

        TextRenderer tr = client.textRenderer;
        int centerX = drawContext.getScaledWindowWidth() / 2;
        int y = 12;
        int drawn = 0;

        for (RemotePlayerState state : snapshot) {
            if (now - state.lastUpdateEpochMs() > REMOTE_STALE_MS) {
                continue;
            }
            if (!currentDimension.equals(state.dimension())) {
                continue;
            }
            if (!safeLabel(currentServerIp).equals(safeLabel(state.serverIp()))) {
                continue;
            }

            double dx = state.x() - localPos.x;
            double dy = state.y() - localPos.y;
            double dz = state.z() - localPos.z;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq > maxDistanceSq) {
                continue;
            }

            double distance = Math.sqrt(distanceSq);
            if (distance <= viewCapDistance) {
                continue;
            }

            Vec3d horizontal = new Vec3d(dx, 0.0, dz);
            Vec3d dir = horizontal.lengthSquared() > 0.001 ? horizontal.normalize() : forward;
            double ahead = forward.dotProduct(dir);
            double side = right.dotProduct(dir);
            String arrow;
            if (ahead > 0.35 && side > 0.35) {
                arrow = "↗";
            } else if (ahead > 0.35 && side < -0.35) {
                arrow = "↖";
            } else if (ahead < -0.35 && side > 0.35) {
                arrow = "↘";
            } else if (ahead < -0.35 && side < -0.35) {
                arrow = "↙";
            } else if (ahead > 0.35) {
                arrow = "↑";
            } else if (ahead < -0.35) {
                arrow = "↓";
            } else {
                arrow = side >= 0 ? "→" : "←";
            }
            String label = String.format("%s %s %.0fm", arrow, safeLabel(state.ign()), distance);

            int width = tr.getWidth(label);
            drawContext.drawTextWithShadow(tr, label, centerX - (width / 2), y, 0xFFFF55);
            y += 12;
            drawn++;
            if (drawn >= 6) {
                break;
            }
        }
    }

    private String safeLabel(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value;
    }

    private GameWebSocketClient.PositionListener createPositionListener(MinecraftClient client) {
        return new GameWebSocketClient.PositionListener() {
            @Override
            public void onPositionUpdate(String remoteUsername, String ign, double x, double y, double z, String dimension, String serverIp) {
                String localUsername = getUsername();
                if (localUsername != null && localUsername.equals(remoteUsername)) {
                    return;
                }

                remotePlayers.put(remoteUsername, new RemotePlayerState(
                        remoteUsername, ign, x, y, z, dimension, serverIp, System.currentTimeMillis()
                ));

                if (activeTransmitters.add(remoteUsername)) {
                    client.execute(() -> sendInfoMessage(
                            client,
                            String.format("Player %s started transmitting (%s, %s)", remoteUsername, serverIp, dimension)
                    ));
                }
            }

            @Override
            public void onChatMessage(String username, String ign, String serverIp, String dimension, String message) {
                client.execute(() -> sendIncomingChatMessage(client, username, ign, serverIp, dimension, message));
            }

            @Override
            public void onPong(long nonce) {
                Long sentAt = pendingPings.remove(nonce);
                if (sentAt == null) {
                    return;
                }
                long rtt = Math.max(0, System.currentTimeMillis() - sentAt);
                client.execute(() -> sendSuccessMessage(client, "Ping: " + rtt + "ms"));
            }

            @Override
            public void onServerError(String message) {
                client.execute(() -> sendErrorMessage(client, "Server WS error: " + message));
            }
        };
    }

    private void sendInfoMessage(MinecraftClient client, String message) {
        sendMessage(client, message, Formatting.AQUA);
    }

    private void sendWarnMessage(MinecraftClient client, String message) {
        sendMessage(client, message, Formatting.YELLOW);
    }

    private void sendSuccessMessage(MinecraftClient client, String message) {
        sendMessage(client, message, Formatting.GREEN);
    }

    private void sendErrorMessage(MinecraftClient client, String message) {
        sendMessage(client, message, Formatting.RED);
    }

    private void sendIncomingChatMessage(
            MinecraftClient client,
            String username,
            String ign,
            String serverIp,
            String dimension,
            String message
    ) {
        MutableText payload = Text.empty()
                .append(Text.literal("[").formatted(Formatting.BLACK))
                .append(Text.literal("Samaritan").formatted(Formatting.RED))
                .append(Text.literal("] ").formatted(Formatting.BLACK))
                .append(Text.literal("<" + safeLabel(ign) + "/" + safeLabel(username) + "> ").formatted(Formatting.GOLD))
                .append(Text.literal(message).formatted(Formatting.WHITE))
                .append(Text.literal(" (" + safeLabel(serverIp) + ", " + safeLabel(dimension) + ")").formatted(Formatting.DARK_GRAY));
        if (client.player != null) {
            client.player.sendMessage(payload, false);
            return;
        }
        System.out.println("[Samaritan] <" + safeLabel(ign) + "/" + safeLabel(username) + "> " + message);
    }

    private void sendMessage(MinecraftClient client, String message) {
        sendMessage(client, message, Formatting.AQUA);
    }

    private void sendMessage(MinecraftClient client, String message, Formatting messageColor) {
        MutableText payload = Text.empty()
                .append(Text.literal("[").formatted(Formatting.BLACK))
                .append(Text.literal("Samaritan").formatted(Formatting.RED))
                .append(Text.literal("] ").formatted(Formatting.BLACK))
                .append(Text.literal(message).formatted(messageColor));
        if (client.player != null) {
            client.player.sendMessage(payload, false);
            return;
        }
        System.out.println("[Samaritan] " + message);
    }

    private synchronized void loadPersistedState() {
        PersistentStateStore.ClientState state = persistentStateStore.load();
        if (state == null) {
            return;
        }

        if (state.serverHost() != null && !state.serverHost().isBlank()) {
            this.serverHost = state.serverHost();
        }
        if (state.serverPort() > 0 && state.serverPort() <= 65535) {
            this.serverPort = state.serverPort();
        }
        if (state.maxRenderDistanceBlocks() > 0 || state.espColorRgb() != 0) {
            this.espColorRgb = state.espColorRgb() & 0xFFFFFF;
        }
        if (state.maxRenderDistanceBlocks() > 0) {
            this.maxRenderDistanceBlocks = state.maxRenderDistanceBlocks();
        }

        if (state.username() != null && state.token() != null) {
            AuthClient restored = new AuthClient("http://" + serverHost + ":" + serverPort);
            if (restored.restoreSession(state.username(), state.token(), state.tokenExpiresAtEpochMs())) {
                this.authClient = restored;
            }
        }
    }

    private synchronized void ensureSessionLoadedFromDisk() {
        if (authClient != null && authClient.isTokenValid()) {
            return;
        }

        PersistentStateStore.ClientState state = persistentStateStore.load();
        if (state == null || state.username() == null || state.token() == null) {
            return;
        }

        AuthClient restored = new AuthClient("http://" + serverHost + ":" + serverPort);
        if (restored.restoreSession(state.username(), state.token(), state.tokenExpiresAtEpochMs())) {
            this.authClient = restored;
        }
    }

    private synchronized void savePersistedState() {
        String username = null;
        String token = null;
        long tokenExpiry = 0;

        if (authClient != null && authClient.isTokenValid()) {
            username = authClient.getUsername();
            token = authClient.getToken();
            tokenExpiry = authClient.getTokenExpiresAtEpochMs();
        }

        persistentStateStore.save(new PersistentStateStore.ClientState(
                serverHost,
                serverPort,
                username,
                token,
                tokenExpiry,
                espColorRgb,
                maxRenderDistanceBlocks
        ));
    }

    public record PlayerPositionInfo(
            String username,
            String ign,
            double x,
            double y,
            double z,
            String dimension,
            String serverIp
    ) {
    }

    private record RemotePlayerState(
            String username,
            String ign,
            double x,
            double y,
            double z,
            String dimension,
            String serverIp,
            long lastUpdateEpochMs
    ) {
    }
}
