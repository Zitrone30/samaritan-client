package samaritan.fabric.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;
import samaritan.fabric.client.SamaritanClientRuntime;

public class LoginScreen extends Screen {
    private final Screen parent;
    private final SamaritanClientRuntime runtime;

    private EditBox hostField;
    private EditBox portField;
    private Button securityToggleButton;
    private EditBox usernameField;
    private EditBox passwordField;
    private EditBox minArrowRangeField;
    private Button horizontalDistanceToggleButton;
    private Button onlyHighwayToggleButton;
    private String errorMessage = "";

    public LoginScreen(Screen parent, SamaritanClientRuntime runtime) {
        super(Component.translatable("screen.samaritan.login.title"));
        this.parent = parent;
        this.runtime = runtime;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = this.height / 2 - 132;
        int fieldWidth = 220;
        int fieldHeight = 20;
        int rowGap = 34;
        int buttonStartY = startY + rowGap * 8 + 2;

        hostField = new EditBox(this.font, centerX - fieldWidth / 2, startY, fieldWidth, fieldHeight,
                Component.translatable("screen.samaritan.server_host"));
        hostField.setValue(runtime.getServerHost());
        addRenderableWidget(hostField);

        portField = new EditBox(this.font, centerX - fieldWidth / 2, startY + rowGap, fieldWidth, fieldHeight,
                Component.translatable("screen.samaritan.server_port"));
        portField.setValue(String.valueOf(runtime.getServerPort()));
        addRenderableWidget(portField);

        securityToggleButton = addRenderableWidget(Button.builder(getSecurityToggleText(), button -> onToggleSecurity())
                .bounds(centerX - fieldWidth / 2, startY + rowGap * 2, fieldWidth, 20)
                .build());

        usernameField = new EditBox(this.font, centerX - fieldWidth / 2, startY + rowGap * 3, fieldWidth, fieldHeight,
                Component.translatable("screen.samaritan.username"));
        addRenderableWidget(usernameField);

        passwordField = new EditBox(this.font, centerX - fieldWidth / 2, startY + rowGap * 4, fieldWidth, fieldHeight,
                Component.translatable("screen.samaritan.password"));
        passwordField.addFormatter((text, firstCharacterIndex) -> FormattedCharSequence.forward("*".repeat(text.length()), Style.EMPTY));
        addRenderableWidget(passwordField);

        minArrowRangeField = new EditBox(this.font, centerX - fieldWidth / 2, startY + rowGap * 5, fieldWidth, fieldHeight,
                Component.translatable("screen.samaritan.min_arrow_range"));
        minArrowRangeField.setValue(String.valueOf(runtime.getMinArrowDistanceBlocks()));
        addRenderableWidget(minArrowRangeField);

        horizontalDistanceToggleButton = addRenderableWidget(Button.builder(getHorizontalDistanceToggleText(), button -> onToggleHorizontalDistance())
                .bounds(centerX - fieldWidth / 2, startY + rowGap * 6, fieldWidth, 20)
                .build());

        onlyHighwayToggleButton = addRenderableWidget(Button.builder(getOnlyHighwayToggleText(), button -> onToggleOnlyHighway())
                .bounds(centerX - fieldWidth / 2, startY + rowGap * 7, fieldWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("screen.samaritan.only_highway.tooltip")))
                .build());

        addRenderableWidget(Button.builder(Component.translatable("screen.samaritan.login"), button -> onLogin())
                .bounds(centerX - fieldWidth / 2, buttonStartY, 106, 20)
                .build());

        addRenderableWidget(Button.builder(Component.translatable("screen.samaritan.disconnect"), button -> onDisconnect())
                .bounds(centerX + 2, buttonStartY, 106, 20)
                .build());

        addRenderableWidget(Button.builder(Component.translatable("screen.samaritan.apply_settings"), button -> onApplySettings())
                .bounds(centerX - fieldWidth / 2, buttonStartY + 24, fieldWidth, 20)
                .build());

        addRenderableWidget(Button.builder(Component.translatable("screen.samaritan.close"), button -> onClose())
                .bounds(centerX - fieldWidth / 2, buttonStartY + 48, fieldWidth, 20)
                .build());

        setInitialFocus(usernameField);
    }

    private void onLogin() {
        String host = hostField.getValue().trim();
        String portText = portField.getValue().trim();
        String username = usernameField.getValue().trim();
        String password = passwordField.getValue();

        if (host.isEmpty() || username.isEmpty() || password.isEmpty()) {
            errorMessage = "Host, username and password are required";
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            errorMessage = "Port must be numeric";
            return;
        }

        if (port < 1 || port > 65535) {
            errorMessage = "Port must be between 1 and 65535";
            return;
        }

        if (!applyRenderSettingsFromFields()) {
            return;
        }

        runtime.setServerEndpoint(host, port, runtime.isTlsEnabled());
        if (minecraft != null) {
            runtime.connectAsync(username, password, minecraft);
        }
        onClose();
    }

    private void onDisconnect() {
        if (minecraft != null) {
            runtime.disconnect(minecraft);
        }
        onClose();
    }

    private void onApplySettings() {
        if (applyRenderSettingsFromFields()) {
            errorMessage = "Saved arrow settings";
        }
    }

    private void onToggleSecurity() {
        String host = hostField != null ? hostField.getValue().trim() : runtime.getServerHost();
        if (host.isEmpty()) {
            host = runtime.getServerHost();
        }

        int port = runtime.getServerPort();
        if (portField != null) {
            try {
                port = Integer.parseInt(portField.getValue().trim());
            } catch (NumberFormatException ignored) {
            }
        }

        runtime.setServerEndpoint(host, port, !runtime.isTlsEnabled());
        hostField.setValue(host);
        portField.setValue(String.valueOf(port));
        if (securityToggleButton != null) {
            securityToggleButton.setMessage(getSecurityToggleText());
        }
        errorMessage = "Saved connection settings";
    }

    private void onToggleHorizontalDistance() {
        runtime.setHorizontalDistanceOnly(!runtime.isHorizontalDistanceOnly());
        if (horizontalDistanceToggleButton != null) {
            horizontalDistanceToggleButton.setMessage(getHorizontalDistanceToggleText());
        }
        errorMessage = "Saved arrow settings";
    }

    private void onToggleOnlyHighway() {
        runtime.setOnlyHighway(!runtime.isOnlyHighway());
        if (onlyHighwayToggleButton != null) {
            onlyHighwayToggleButton.setMessage(getOnlyHighwayToggleText());
        }
        errorMessage = "Saved highway settings";
    }

    private Component getSecurityToggleText() {
        return Component.translatable(
                runtime.isTlsEnabled()
                        ? "screen.samaritan.connection_security.https"
                        : "screen.samaritan.connection_security.http"
        );
    }

    private Component getHorizontalDistanceToggleText() {
        return Component.translatable(
                runtime.isHorizontalDistanceOnly()
                        ? "screen.samaritan.horizontal_distance_only.on"
                        : "screen.samaritan.horizontal_distance_only.off"
        );
    }

    private Component getOnlyHighwayToggleText() {
        return Component.translatable(
                runtime.isOnlyHighway()
                        ? "screen.samaritan.only_highway.on"
                        : "screen.samaritan.only_highway.off"
        );
    }

    private boolean applyRenderSettingsFromFields() {
        int minArrowRange;
        try {
            minArrowRange = Integer.parseInt(minArrowRangeField.getValue().trim());
        } catch (NumberFormatException e) {
            errorMessage = "Min arrow range must be numeric";
            return false;
        }

        if (minArrowRange < 1) {
            errorMessage = "Min arrow range must be at least 1";
            return false;
        }

        runtime.setMinArrowDistanceBlocks(minArrowRange);
        minArrowRangeField.setValue(String.valueOf(minArrowRange));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent keyInput) {
        if (keyInput.key() == GLFW.GLFW_KEY_ENTER || keyInput.key() == GLFW.GLFW_KEY_KP_ENTER) {
            onLogin();
            return true;
        }
        return super.keyPressed(keyInput);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int startY = this.height / 2 - 132;
        int rowGap = 34;
        int labelOffsetY = -12;
        int messageY = startY + rowGap * 8 + 74;

        context.centeredText(this.font, this.title, centerX, startY - 20, 0xFFFFFFFF);
        context.text(this.font, Component.translatable("screen.samaritan.server_host"), centerX - 110, startY + labelOffsetY, 0xFFE0E0E0);
        context.text(this.font, Component.translatable("screen.samaritan.server_port"), centerX - 110, startY + rowGap + labelOffsetY, 0xFFE0E0E0);
        context.text(this.font, Component.translatable("screen.samaritan.connection_security"), centerX - 110, startY + rowGap * 2 + labelOffsetY, 0xFFE0E0E0);
        context.text(this.font, Component.translatable("screen.samaritan.username"), centerX - 110, startY + rowGap * 3 + labelOffsetY, 0xFFE0E0E0);
        context.text(this.font, Component.translatable("screen.samaritan.password"), centerX - 110, startY + rowGap * 4 + labelOffsetY, 0xFFE0E0E0);
        context.text(this.font, Component.translatable("screen.samaritan.min_arrow_range"), centerX - 110, startY + rowGap * 5 + labelOffsetY, 0xFFE0E0E0);
        context.text(this.font, Component.translatable("screen.samaritan.horizontal_distance_only"), centerX - 110, startY + rowGap * 6 + labelOffsetY, 0xFFE0E0E0);
        context.text(this.font, Component.translatable("screen.samaritan.only_highway"), centerX - 110, startY + rowGap * 7 + labelOffsetY, 0xFFE0E0E0);

        if (!errorMessage.isEmpty()) {
            int color = errorMessage.startsWith("Saved") ? 0xFF55FF55 : 0xFFFF5555;
            context.centeredText(this.font, Component.literal(errorMessage), centerX, messageY, color);
        }
    }
}
