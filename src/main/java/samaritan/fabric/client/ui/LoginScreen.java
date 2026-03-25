package samaritan.fabric.client.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import samaritan.fabric.client.SamaritanClientRuntime;

public class LoginScreen extends Screen {
    private final SamaritanClientRuntime runtime;

    private TextFieldWidget hostField;
    private TextFieldWidget portField;
    private TextFieldWidget usernameField;
    private TextFieldWidget passwordField;
    private TextFieldWidget colorField;
    private TextFieldWidget maxDistanceField;
    private String errorMessage = "";

    public LoginScreen(SamaritanClientRuntime runtime) {
        super(Text.translatable("screen.samaritan.login.title"));
        this.runtime = runtime;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = this.height / 2 - 116;
        int fieldWidth = 220;
        int fieldHeight = 20;
        int rowGap = 34;
        int buttonStartY = startY + rowGap * 6 + 2;

        hostField = new TextFieldWidget(this.textRenderer, centerX - fieldWidth / 2, startY, fieldWidth, fieldHeight,
                Text.translatable("screen.samaritan.server_host"));
        hostField.setText(runtime.getServerHost());
        addDrawableChild(hostField);

        portField = new TextFieldWidget(this.textRenderer, centerX - fieldWidth / 2, startY + rowGap, fieldWidth, fieldHeight,
                Text.translatable("screen.samaritan.server_port"));
        portField.setText(String.valueOf(runtime.getServerPort()));
        addDrawableChild(portField);

        usernameField = new TextFieldWidget(this.textRenderer, centerX - fieldWidth / 2, startY + rowGap * 2, fieldWidth, fieldHeight,
                Text.translatable("screen.samaritan.username"));
        addDrawableChild(usernameField);

        passwordField = new TextFieldWidget(this.textRenderer, centerX - fieldWidth / 2, startY + rowGap * 3, fieldWidth, fieldHeight,
                Text.translatable("screen.samaritan.password"));
        addDrawableChild(passwordField);

        colorField = new TextFieldWidget(this.textRenderer, centerX - fieldWidth / 2, startY + rowGap * 4, fieldWidth, fieldHeight,
                Text.translatable("screen.samaritan.esp_color"));
        colorField.setText(String.format("#%06X", runtime.getEspColorRgb()));
        addDrawableChild(colorField);

        maxDistanceField = new TextFieldWidget(this.textRenderer, centerX - fieldWidth / 2, startY + rowGap * 5, fieldWidth, fieldHeight,
                Text.translatable("screen.samaritan.max_distance"));
        maxDistanceField.setText(String.valueOf(runtime.getMaxRenderDistanceBlocks()));
        addDrawableChild(maxDistanceField);

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

        if (!applyEspSettingsFromFields()) {
            return;
        }

        runtime.setServerEndpoint(host, port);
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
        if (applyEspSettingsFromFields()) {
            errorMessage = "Saved ESP settings";
        }
    }

    private boolean applyEspSettingsFromFields() {
        String colorText = colorField.getText().trim();
        if (colorText.startsWith("#")) {
            colorText = colorText.substring(1);
        }
        if (!colorText.matches("[0-9a-fA-F]{6}")) {
            errorMessage = "ESP color must be 6-digit hex (#RRGGBB)";
            return false;
        }

        int colorRgb;
        try {
            colorRgb = Integer.parseInt(colorText, 16);
        } catch (NumberFormatException e) {
            errorMessage = "Invalid ESP color";
            return false;
        }

        int maxDistance;
        try {
            maxDistance = Integer.parseInt(maxDistanceField.getText().trim());
        } catch (NumberFormatException e) {
            errorMessage = "Max distance must be numeric";
            return false;
        }

        if (maxDistance < 1) {
            errorMessage = "Max distance must be at least 1";
            return false;
        }

        runtime.setEspSettings(colorRgb, maxDistance);
        colorField.setText(String.format("#%06X", colorRgb));
        maxDistanceField.setText(String.valueOf(maxDistance));
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
        int startY = this.height / 2 - 116;
        int rowGap = 34;
        int labelOffsetY = -12;
        int messageY = startY + rowGap * 6 + 74;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centerX, startY - 20, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.samaritan.server_host"), centerX - 110, startY + labelOffsetY, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.samaritan.server_port"), centerX - 110, startY + rowGap + labelOffsetY, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.samaritan.username"), centerX - 110, startY + rowGap * 2 + labelOffsetY, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.samaritan.password"), centerX - 110, startY + rowGap * 3 + labelOffsetY, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.samaritan.esp_color"), centerX - 110, startY + rowGap * 4 + labelOffsetY, 0xAAAAAA);
        context.drawTextWithShadow(this.textRenderer, Text.translatable("screen.samaritan.max_distance"), centerX - 110, startY + rowGap * 5 + labelOffsetY, 0xAAAAAA);

        if (!errorMessage.isEmpty()) {
            int color = errorMessage.startsWith("Saved") ? 0x55FF55 : 0xFF5555;
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(errorMessage), centerX, messageY, color);
        }
    }
}
