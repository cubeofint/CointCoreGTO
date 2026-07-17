package Crazer.cubeofinterest.cointcoregto.exchanger;

import net.minecraft.client.gui.screens.MenuScreens;

public final class CointExchangerClient {
    private CointExchangerClient() {
    }

    public static void registerScreens() {
        MenuScreens.register(
                CointExchangerRegistry.EXCHANGER_MENU.get(),
                ExchangerScreen::new
        );
    }
}