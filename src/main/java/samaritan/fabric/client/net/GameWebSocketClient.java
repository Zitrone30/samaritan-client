package samaritan.fabric.client.net;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public class GameWebSocketClient implements WebSocket.Listener {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);
    private static final long SEND_INTERVAL_MS = 100;

    private final String wsUrl;
    private final AuthClient authClient;
    private final PositionListener positionListener;

    private WebSocket webSocket;
    private boolean connected = false;
    private long lastPositionUpdate = 0;

    private double posX;
    private double posY;
    private double posZ;
    private String dimension = "unknown";
    private String serverIp = "unknown";
    private String ign = "unknown";

    public GameWebSocketClient(String wsUrl, AuthClient authClient, PositionListener positionListener) {
        this.wsUrl = wsUrl;
        this.authClient = authClient;
        this.positionListener = positionListener;
    }

    public void connect() {
        if (connected || !authClient.isTokenValid()) {
            return;
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();

            webSocket = client.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .buildAsync(URI.create(wsUrl), this)
                    .orTimeout(HANDSHAKE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .join();

            connected = true;
        } catch (Exception e) {
            connected = false;
        }
    }

    public void disconnect() {
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "Closing connection").join();
            } catch (Exception ignored) {
            }
        }
        connected = false;
    }

    public void updatePosition(double x, double y, double z, String dimension, String serverIp, String ign) {
        this.posX = x;
        this.posY = y;
        this.posZ = z;
        this.dimension = dimension;
        this.serverIp = serverIp;
        this.ign = ign;

        long now = System.currentTimeMillis();
        if (now - lastPositionUpdate >= SEND_INTERVAL_MS) {
            sendPositionUpdate();
            lastPositionUpdate = now;
        }
    }

    private void sendPositionUpdate() {
        if (!connected || webSocket == null) {
            return;
        }

        String token = authClient.getToken();
        if (token == null) {
            disconnect();
            return;
        }

        double roundedX = Math.ceil(posX);
        double roundedY = Math.ceil(posY);
        double roundedZ = Math.ceil(posZ);

        String message = String.format(
                "{\"token\":\"%s\",\"type\":\"position\",\"ign\":\"%s\",\"dimension\":\"%s\",\"serverIp\":\"%s\",\"position\":{\"x\":%.0f,\"y\":%.0f,\"z\":%.0f}}",
                token, escapeJson(ign), escapeJson(dimension), escapeJson(serverIp), roundedX, roundedY, roundedZ
        );

        webSocket.sendText(message, true);
    }

    public void sendChat(String message) {
        if (!connected || webSocket == null || message == null || message.isBlank()) {
            return;
        }
        String token = authClient.getToken();
        if (token == null) {
            disconnect();
            return;
        }

        String payload = String.format(
                "{\"token\":\"%s\",\"type\":\"chat\",\"message\":\"%s\"}",
                token,
                escapeJson(message)
        );
        webSocket.sendText(payload, true);
    }

    public void sendPing(long nonce) {
        if (!connected || webSocket == null) {
            return;
        }
        String token = authClient.getToken();
        if (token == null) {
            disconnect();
            return;
        }
        String payload = String.format(
                "{\"token\":\"%s\",\"type\":\"ping\",\"nonce\":%d}",
                token,
                nonce
        );
        webSocket.sendText(payload, true);
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        WebSocket.Listener.super.onOpen(webSocket);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        try {
            JsonObject msg = JsonParser.parseString(data.toString()).getAsJsonObject();
            String type = msg.has("type") ? msg.get("type").getAsString() : "";

            if ("position".equals(type) && msg.has("position")) {
                JsonObject pos = msg.getAsJsonObject("position");
                String username = msg.has("username") ? msg.get("username").getAsString() : "unknown";
                String ign = msg.has("ign") ? msg.get("ign").getAsString() : username;
                String dimension = msg.has("dimension") ? msg.get("dimension").getAsString() : "unknown";
                String serverIp = msg.has("serverIp") ? msg.get("serverIp").getAsString() : "unknown";
                double x = pos.has("x") ? pos.get("x").getAsDouble() : 0;
                double y = pos.has("y") ? pos.get("y").getAsDouble() : 0;
                double z = pos.has("z") ? pos.get("z").getAsDouble() : 0;

                if (positionListener != null) {
                    positionListener.onPositionUpdate(username, ign, x, y, z, dimension, serverIp);
                }
            } else if ("chat".equals(type) && msg.has("message")) {
                if (positionListener != null) {
                    String username = msg.has("username") ? msg.get("username").getAsString() : "unknown";
                    String ign = msg.has("ign") ? msg.get("ign").getAsString() : username;
                    String serverIp = msg.has("serverIp") ? msg.get("serverIp").getAsString() : "unknown";
                    String dimension = msg.has("dimension") ? msg.get("dimension").getAsString() : "unknown";
                    String message = msg.get("message").getAsString();
                    positionListener.onChatMessage(username, ign, serverIp, dimension, message);
                }
            } else if ("pong".equals(type) && msg.has("nonce")) {
                if (positionListener != null) {
                    positionListener.onPong(msg.get("nonce").getAsLong());
                }
            } else if ("error".equals(type) && msg.has("message")) {
                if (positionListener != null) {
                    positionListener.onServerError(msg.get("message").getAsString());
                }
            }
        } catch (Exception ignored) {
        }

        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        String decoded = StandardCharsets.UTF_8.decode(data).toString();
        return onText(webSocket, decoded, last);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        connected = false;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        connected = false;
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    public boolean isConnected() {
        return connected;
    }

    public interface PositionListener {
        void onPositionUpdate(String username, String ign, double x, double y, double z, String dimension, String serverIp);

        void onChatMessage(String username, String ign, String serverIp, String dimension, String message);

        void onPong(long nonce);

        void onServerError(String message);
    }
}
