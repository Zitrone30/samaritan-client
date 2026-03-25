package samaritan.fabric.client.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import samaritan.fabric.client.SamaritanClientRuntime;

public class LoginScreen extends Screen {
    private final SamaritanClientRuntime runtime;

    private TextFieldWidget hostField;
    private TextFieldWidget portField;
    private ButtonWidget securityToggleButton;
    private TextFieldWidget usernameField;
    private TextFieldWidget passwordField;
    private TextFieldWidget minArrowRangeField;
    private ButtonWidget horizontalDistanceToggleButton;
    private ButtonWidget onlyHighwayToggleButton;
    private String errorMessage = "";

    public LoginScreen(SamaritanClientRuntime runtime) {
        super(Text.translatable("screen.samaritan.login.title"));
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

        hostField = new TextFieldWidget(this.textRenderer, centerX - fieldWidth / 2, startY, fieldWidth, fieldHeight,
                Text.translatable("screen.samaritan.server_host"));
        hostField.setText(runtime.getServerHost());
        addDrawableChild(hostField);

        portField = new TextFieldWidget(this.textRenderer, centerX - fieldWidth / 2, startY + rowGap, fieldWidth, fieldHeight,
                Text.translatable("screen.samaritan.server_port"));
        portField.setText(String.valueOf(runtime.getServerPort()));
        addDrawableChild(portField);

        securityToggleButton = addDrawableChild(ButtonWidget.builder(getSecurityToggleText(), button -> onToggleSecurity())
                .dimensions(centerX - fieldWidth / 2, startY + rowGap * 2, fieldWidth, 20)
                .build());

        usernameField = new TextFieldWidget(this.textRenderer, centerX - fieldWidth / 2, startY + rowGap * 3, fieldWidth, fieldHeight,
                Text.translatable("screen.samaritan.username"));
        addDrawableChild(usernameField);

        passwordField = new TextFieldWidget(this.textRenderer, centerX - fieldWidth / 2, startY + rowGap * 4, fieldWidth, fieldHeight,
                Text.translatable("screen.samaritan.password"));
        passwordField.setRenderTextProvider((text, firstCharacterIndex) -> OrderedText.styledForwardsVisitedString("*".repeat(text.length()), Style.EMPTY));
        addDrawableChild(passwordField);

        minArrowRangeField = new TextFieldWidget(this.textRenderer, centerX - fieldWidth / 2, startY + rowGap * 5, fieldWidth, fieldHeight,
                Text.translatable("screen.samaritan.min_arrow_range"));
        minArrowRangeField.setText(String.valueOf(runtime.getMinArrowDistanceBlocks()));
        addDrawableChild(minArrowRangeField);

        horizontalDistanceToggleButton = addDrawableChild(ButtonWidget.builder(getHorizontalDistanceToggleText(), button -> onToggleHorizontalDistance())
                .dimensions(centerX - fieldWidth / 2, startY + rowGap * 6, fieldWidth, 20)
                .build());

        onlyHighwayToggleButton = addDrawableChild(ButtonWidget.builder(getOnlyHighwayToggleText(), button -> onToggleOnlyHighway())
                .dimensions(centerX - fieldWidth / 2, startY + rowGap * 7, fieldWidth, 20)
                .tooltip(Tooltip.of(Text.translatable("screen.samaritan.only_highway.tooltip")))
                .build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.samaritan.login"), button -> onLogin())
                .dimensions(centerX - fieldWidth / 2, buttonStartY, 106, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.samaritan.disconnect"), button -> onDisconnect())
                .dimensions(centerX + 2, buttonStartY, 106, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.samaritan.apply_settings"), button -> onApplySettings())
                .dimensions(centerX - fieldWidth / 2, buttonStartY + 24, fieldWidth, 20)
                .build());

        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.samaritan.close"), button -> close())
                .dimensions(centerX - fieldWidth / 2, buttonStartY + 48, fieldWidth, 20)
                .build());

        setInitialFocus(usernameField);
    }

    private void onLogin() {
        String host = hostField.getText().trim();
        String portText = portField.getText().trim();
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

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
        if (client != null) {
            runtime.connectAsync(username, password, client);
        }
        close();
    }

    private void onDisconnect() {
        if (client != null) {
            runtime.disconnect(client);
        }
        close();
    }

    private void onApplySettings() {
        if (applyRenderSettingsFromFields()) {
            errorMessage = "Saved arrow settings";
        }
    }

    private void onToggleSecurity() {
        String host = hostField != null ? hostField.getText().trim() : runtime.getServerHost();
        if (host.isEmpty()) {
            host = runtime.getServerHost();
        }

        int port = runtime.getServerPort();
        if (portField != null) {
            try {
                port = Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException ignored) {
            }
        }

        runtime.setServerEndpoint(host, port, !runtime.isTlsEnabled());
        hostField.setText(host);
        portField.setText(String.valueOf(port));
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

    private Text getSecurityToggleText() {
        return Text.translatable(
                runtime.isTlsEnabled()
                        ? "screen.samaritan.connection_security.https"
                        : "screen.samaritan.connection_security.http"
        );
    }

    private Text getHorizontalDistanceToggleText() {
        return Text.translatable(
                runtime.isHorizontalDistanceOnly()
                        ? "screen.samaritan.horizontal_distance_only.on"
                        : "screen.samaritan.horizontal_distance_only.off"
        );
    }

    private Text getOnlyHighwayToggleText() {
        return Text.translatable(
                runtime.isOnlyHighway()
                        ? "screen.samaritan.only_highway.on"
                        : "screen.samaritan.only_highway.off"
        );
    }

    private boolean applyRenderSettingsFromFields() {
        int minArrowRange;
        try {
            minArrowRange = Integer.parseInt(minArrowRangeField.getText().trim());
        } catch (NumberFormatException e) {
            errorMessage = "Min arrow range must be numeric";
            return false;
        }

        if (minArrowRange < 1) {
            errorMessage = "Min arrow range must be at least 1";
            return false;
        }

        runtime.setMinArrowDistanceBlocks(minArrowRange);
        minArrowRangeField.setText(String.valueOf(minArrowRange));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            onLogin();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(null);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int startY = this.height / 2 - 132;
        int rowGap = 34;
        int labelOffsetY = -12;
        int messageY = startY + rowGap * 8 + 74;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, startY - 20, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.samaritan.server_host"), centerX - 110, startY + labelOffsetY, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.samaritan.server_port"), centerX - 110, startY + rowGap + labelOffsetY, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.samaritan.connection_security"), centerX - 110, startY + rowGap * 2 + labelOffsetY, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.samaritan.username"), centerX - 110, startY + rowGap * 3 + labelOffsetY, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.samaritan.password"), centerX - 110, startY + rowGap * 4 + labelOffsetY, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.samaritan.min_arrow_range"), centerX - 110, startY + rowGap * 5 + labelOffsetY, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.samaritan.horizontal_distance_only"), centerX - 110, startY + rowGap * 6 + labelOffsetY, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.samaritan.only_highway"), centerX - 110, startY + rowGap * 7 + labelOffsetY, 0xAAAAAA);

        if (!errorMessage.isEmpty()) {
            int color = errorMessage.startsWith("Saved") ? 0x55FF55 : 0xFF5555;
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(errorMessage), centerX, messageY, color);
        }
    }
}
