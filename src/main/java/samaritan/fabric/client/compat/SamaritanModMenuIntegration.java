package samaritan.fabric.client.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import samaritan.fabric.client.SamaritanFabricClient;
import samaritan.fabric.client.ui.LoginScreen;

public class SamaritanModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new LoginScreen(parent, SamaritanFabricClient.getRuntime());
    }
}
