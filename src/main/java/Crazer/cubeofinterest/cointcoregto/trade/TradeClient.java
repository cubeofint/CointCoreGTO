package Crazer.cubeofinterest.cointcoregto.trade;

import net.minecraft.client.gui.screens.MenuScreens;

public final class TradeClient {
    private TradeClient() {
    }

    public static void registerScreens() {
        MenuScreens.register(TradeRegistry.TRADE_MENU.get(), TradeScreen::new);
    }
}
