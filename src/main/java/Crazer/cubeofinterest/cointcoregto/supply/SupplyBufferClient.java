package Crazer.cubeofinterest.cointcoregto.supply;

import net.minecraft.client.Minecraft;
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

    public static void handleState(SupplyBufferStatePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || !(minecraft.player.containerMenu instanceof SupplyBufferMenu menu)
                || !menu.getBlockPos().equals(packet.pos())) {
            return;
        }
        menu.applyClientState(
                packet.itemAmounts(),
                packet.itemTargets(),
                packet.fluidAmounts(),
                packet.fluidTargets()
        );
    }
}
