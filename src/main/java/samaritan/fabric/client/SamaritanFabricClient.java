package samaritan.fabric.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import samaritan.fabric.client.ui.LoginScreen;

import java.util.List;
import java.util.Locale;

public class SamaritanFabricClient implements ClientModInitializer {
    private static final SamaritanClientRuntime RUNTIME = new SamaritanClientRuntime();

    public static SamaritanClientRuntime getRuntime() {
        return RUNTIME;
    }

    @Override
    public void onInitializeClient() {
        KeyMapping openLoginScreen = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.samaritan.open_login",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("samaritan", "general"))
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openLoginScreen.consumeClick()) {
                client.gui.setScreen(new LoginScreen(client.gui.screen(), RUNTIME));
            }
            RUNTIME.onClientTick(client);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> RUNTIME.onLeaveServer(client));
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("samaritan", "out_of_range_indicators"),
                (graphics, deltaTracker) -> RUNTIME.renderOorHudIndicators(graphics)
        );

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(buildRootCommand("samaritan"));
            dispatcher.register(buildRootCommand("s"));
        });

    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildRootCommand(String rootLiteral) {
        return ClientCommands.literal(rootLiteral)
                        .then(ClientCommands.literal("login")
                                .executes(ctx -> {
                                    Minecraft client = Minecraft.getInstance();
                                    RUNTIME.reconnectWithCurrentToken(client);
                                    ctx.getSource().sendFeedback(Component.literal("Samaritan token reconnect requested."));
                                    return 1;
                                })
                                .then(ClientCommands.argument("username", StringArgumentType.string())
                                        .then(ClientCommands.argument("password", StringArgumentType.string())
                                        .executes(ctx -> {
                                            Minecraft client = Minecraft.getInstance();
                                            String username = StringArgumentType.getString(ctx, "username");
                                            String password = StringArgumentType.getString(ctx, "password");

                                            RUNTIME.connectAsync(username, password, client);
                                            ctx.getSource().sendFeedback(Component.literal("Samaritan login started..."));
                                            return 1;
                                        }))))
                        .then(ClientCommands.literal("logout")
                                .executes(ctx -> {
                                    Minecraft client = Minecraft.getInstance();
                                    RUNTIME.disconnect(client);
                                    ctx.getSource().sendFeedback(Component.literal("Samaritan logout complete."));
                                    return 1;
                                }))
                        .then(ClientCommands.literal("server")
                                .then(ClientCommands.argument("host", StringArgumentType.string())
                                        .then(ClientCommands.argument("port", IntegerArgumentType.integer(1, 65535))
                                                .executes(ctx -> {
                                                    String host = StringArgumentType.getString(ctx, "host");
                                                    int port = IntegerArgumentType.getInteger(ctx, "port");
                                                    boolean useTls = RUNTIME.isTlsEnabled();
                                                    RUNTIME.setServerEndpoint(host, port, useTls);
                                                    ctx.getSource().sendFeedback(Component.literal("Samaritan server set to " + protocolLabel(useTls) + "://" + host + ":" + port));
                                                    return 1;
                                                })
                                                .then(ClientCommands.argument("protocol", StringArgumentType.word())
                                                        .executes(ctx -> {
                                                            String host = StringArgumentType.getString(ctx, "host");
                                                            int port = IntegerArgumentType.getInteger(ctx, "port");
                                                            String protocol = StringArgumentType.getString(ctx, "protocol");
                                                            Boolean useTls = parseProtocol(protocol);
                                                            if (useTls == null) {
                                                                ctx.getSource().sendError(Component.literal("Protocol must be http or https"));
                                                                return 0;
                                                            }

                                                            RUNTIME.setServerEndpoint(host, port, useTls);
                                                            ctx.getSource().sendFeedback(Component.literal("Samaritan server set to " + protocolLabel(useTls) + "://" + host + ":" + port));
                                                            return 1;
                                                        })))))
                        .then(ClientCommands.literal("status")
                                .executes(ctx -> {
                                    ctx.getSource().sendFeedback(Component.literal(RUNTIME.getDetailedStatus()));
                                    return 1;
                                }))
                        .then(ClientCommands.literal("token")
                                .executes(ctx -> {
                                    long sec = RUNTIME.getTokenTimeRemainingMs() / 1000;
                                    ctx.getSource().sendFeedback(Component.literal("Token time remaining: " + sec + "s"));
                                    return 1;
                                }))
                        .then(ClientCommands.literal("players")
                                .executes(ctx -> {
                                    List<String> users = RUNTIME.getOnlineUsernames();
                                    if (users.isEmpty()) {
                                        ctx.getSource().sendFeedback(Component.literal("No online Samaritan users in your feed."));
                                    } else {
                                        ctx.getSource().sendFeedback(Component.literal("Online users (" + users.size() + "): " + String.join(", ", users)));
                                    }
                                    return 1;
                                }))
                        .then(ClientCommands.literal("pos")
                                .then(ClientCommands.argument("user", StringArgumentType.word())
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
                                                ctx.getSource().sendFeedback(Component.literal("No online position for user: " + username));
                                                return 0;
                                            }
                                            var pos = remote.get();
                                            ctx.getSource().sendFeedback(Component.literal(String.format(
                                                    "%s -> x=%.0f y=%.0f z=%.0f | dim=%s | server=%s | ign=%s | totems=%d",
                                                    pos.username(), pos.x(), pos.y(), pos.z(),
                                                    pos.dimension(), pos.serverIp(), pos.ign(), pos.totemCount()
                                            )));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("chat")
                                .then(ClientCommands.argument("message", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            Minecraft client = Minecraft.getInstance();
                                            String message = StringArgumentType.getString(ctx, "message");
                                            return RUNTIME.sendChat(client, message) ? 1 : 0;
                                        })))
                        .then(ClientCommands.literal("ping")
                                .executes(ctx -> {
                                    Minecraft client = Minecraft.getInstance();
                                    return RUNTIME.sendPing(client) ? 1 : 0;
                                }))
                        .then(ClientCommands.literal("passwd")
                                .then(ClientCommands.argument("currentPassword", StringArgumentType.string())
                                        .then(ClientCommands.argument("newPassword", StringArgumentType.string())
                                                .executes(ctx -> {
                                                    Minecraft client = Minecraft.getInstance();
                                                    String currentPassword = StringArgumentType.getString(ctx, "currentPassword");
                                                    String newPassword = StringArgumentType.getString(ctx, "newPassword");
                                                    return RUNTIME.changeOwnPassword(client, currentPassword, newPassword) ? 1 : 0;
                                                }))))
                        .then(ClientCommands.literal("help")
                                .executes(SamaritanFabricClient::sendHelp))
                        .executes(SamaritanFabricClient::sendHelp);
    }

    private static int sendHelp(CommandContext<FabricClientCommandSource> ctx) {
        ctx.getSource().sendFeedback(Component.literal("Samaritan commands:"));
        ctx.getSource().sendFeedback(Component.literal("/samaritan login [username] [password]"));
        ctx.getSource().sendFeedback(Component.literal("/samaritan logout"));
        ctx.getSource().sendFeedback(Component.literal("/samaritan server <host> <port> [http|https]"));
        ctx.getSource().sendFeedback(Component.literal("/samaritan status"));
        ctx.getSource().sendFeedback(Component.literal("/samaritan token"));
        ctx.getSource().sendFeedback(Component.literal("/samaritan players"));
        ctx.getSource().sendFeedback(Component.literal("/samaritan pos <user>"));
        ctx.getSource().sendFeedback(Component.literal("/samaritan chat <message>"));
        ctx.getSource().sendFeedback(Component.literal("/samaritan ping"));
        ctx.getSource().sendFeedback(Component.literal("/samaritan passwd <currentPassword> <newPassword>"));
        return 1;
    }

    private static Boolean parseProtocol(String protocol) {
        if (protocol == null) {
            return null;
        }
        return switch (protocol.toLowerCase(Locale.ROOT)) {
            case "http" -> false;
            case "https" -> true;
            default -> null;
        };
    }

    private static String protocolLabel(boolean useTls) {
        return useTls ? "https" : "http";
    }
}
