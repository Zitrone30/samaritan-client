package samaritan.fabric.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import samaritan.fabric.client.ui.LoginScreen;

import java.util.List;
import java.util.Locale;

public class SamaritanFabricClient implements ClientModInitializer {
    private static final SamaritanClientRuntime RUNTIME = new SamaritanClientRuntime();

    @Override
    public void onInitializeClient() {
        KeyBinding openLoginScreen = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.samaritan.open_login",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "key.category.samaritan"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openLoginScreen.wasPressed()) {
                client.setScreen(new LoginScreen(RUNTIME));
            }
            RUNTIME.onClientTick(client);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RUNTIME.onLeaveServer(client));
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> RUNTIME.renderOorHudIndicators(drawContext));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("samaritan")
                        .then(ClientCommandManager.literal("login")
                                .executes(ctx -> {
                                    MinecraftClient client = MinecraftClient.getInstance();
                                    RUNTIME.reconnectWithCurrentToken(client);
                                    ctx.getSource().sendFeedback(Text.literal("Samaritan token reconnect requested."));
                                    return 1;
                                })
                                .then(ClientCommandManager.argument("username", StringArgumentType.string())
                                        .then(ClientCommandManager.argument("password", StringArgumentType.string())
                                        .executes(ctx -> {
                                            MinecraftClient client = MinecraftClient.getInstance();
                                            String username = StringArgumentType.getString(ctx, "username");
                                            String password = StringArgumentType.getString(ctx, "password");

                                            RUNTIME.connectAsync(username, password, client);
                                            ctx.getSource().sendFeedback(Text.literal("Samaritan login started..."));
                                            return 1;
                                        }))))
                        .then(ClientCommandManager.literal("logout")
                                .executes(ctx -> {
                                    MinecraftClient client = MinecraftClient.getInstance();
                                    RUNTIME.disconnect(client);
                                    ctx.getSource().sendFeedback(Text.literal("Samaritan logout complete."));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("server")
                                .then(ClientCommandManager.argument("host", StringArgumentType.string())
                                        .then(ClientCommandManager.argument("port", IntegerArgumentType.integer(1, 65535))
                                                .executes(ctx -> {
                                                    String host = StringArgumentType.getString(ctx, "host");
                                                    int port = IntegerArgumentType.getInteger(ctx, "port");
                                                    RUNTIME.setServerEndpoint(host, port);
                                                    ctx.getSource().sendFeedback(Text.literal("Samaritan server set to " + host + ":" + port));
                                                    return 1;
                                                }))))
                        .then(ClientCommandManager.literal("status")
                                .executes(ctx -> {
                                    ctx.getSource().sendFeedback(Text.literal(RUNTIME.getDetailedStatus()));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("token")
                                .executes(ctx -> {
                                    long sec = RUNTIME.getTokenTimeRemainingMs() / 1000;
                                    ctx.getSource().sendFeedback(Text.literal("Token time remaining: " + sec + "s"));
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("players")
                                .executes(ctx -> {
                                    List<String> users = RUNTIME.getOnlineUsernames();
                                    if (users.isEmpty()) {
                                        ctx.getSource().sendFeedback(Text.literal("No online Samaritan users in your feed."));
                                    } else {
                                        ctx.getSource().sendFeedback(Text.literal("Online users (" + users.size() + "): " + String.join(", ", users)));
                                    }
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("pos")
                                .then(ClientCommandManager.argument("user", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            String input = builder.getRemainingLowerCase();
                                            for (String name : RUNTIME.getOnlineUsernames()) {
                                                if (name.toLowerCase(Locale.ROOT).startsWith(input)) {
                                                    builder.suggest(name);
                                                }
                                            }
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> {
                                            String username = StringArgumentType.getString(ctx, "user");
                                            var remote = RUNTIME.getRemotePlayerPosition(username);
                                            if (remote.isEmpty()) {
                                                ctx.getSource().sendFeedback(Text.literal("No online position for user: " + username));
                                                return 0;
                                            }
                                            var pos = remote.get();
                                            ctx.getSource().sendFeedback(Text.literal(String.format(
                                                    "%s -> x=%.0f y=%.0f z=%.0f | dim=%s | server=%s | ign=%s",
                                                    pos.username(), pos.x(), pos.y(), pos.z(),
                                                    pos.dimension(), pos.serverIp(), pos.ign()
                                            )));
                                            return 1;
                                        })))
                        .then(ClientCommandManager.literal("chat")
                                .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            MinecraftClient client = MinecraftClient.getInstance();
                                            String message = StringArgumentType.getString(ctx, "message");
                                            return RUNTIME.sendChat(client, message) ? 1 : 0;
                                        })))
                        .then(ClientCommandManager.literal("ping")
                                .executes(ctx -> {
                                    MinecraftClient client = MinecraftClient.getInstance();
                                    return RUNTIME.sendPing(client) ? 1 : 0;
                                }))
                        .then(ClientCommandManager.literal("help")
                                .executes(SamaritanFabricClient::sendHelp))
                        .executes(SamaritanFabricClient::sendHelp)
        ));

    }

    private static int sendHelp(CommandContext<FabricClientCommandSource> ctx) {
        ctx.getSource().sendFeedback(Text.literal("Samaritan commands:"));
        ctx.getSource().sendFeedback(Text.literal("/samaritan login [username] [password]"));
        ctx.getSource().sendFeedback(Text.literal("/samaritan logout"));
        ctx.getSource().sendFeedback(Text.literal("/samaritan server <host> <port>"));
        ctx.getSource().sendFeedback(Text.literal("/samaritan status"));
        ctx.getSource().sendFeedback(Text.literal("/samaritan token"));
        ctx.getSource().sendFeedback(Text.literal("/samaritan players"));
        ctx.getSource().sendFeedback(Text.literal("/samaritan pos <user>"));
        ctx.getSource().sendFeedback(Text.literal("/samaritan chat <message>"));
        ctx.getSource().sendFeedback(Text.literal("/samaritan ping"));
        return 1;
    }
}
