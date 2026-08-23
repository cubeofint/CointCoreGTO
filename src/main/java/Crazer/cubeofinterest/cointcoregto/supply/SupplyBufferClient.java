package Crazer.cubeofinterest.cointcoregto.supply;

import net.minecraft.client.gui.screens.MenuScreens;

public final class SupplyBufferClient {
    private SupplyBufferClient() {
    }

    public static void registerScreens() {
        MenuScreens.register(
                SupplyBufferRegistry.SUPPLY_BUFFER_MENU.get(),
                SupplyBufferScreen::new
        );
    }
}
