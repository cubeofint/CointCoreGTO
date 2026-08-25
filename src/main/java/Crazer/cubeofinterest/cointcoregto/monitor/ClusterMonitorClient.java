package Crazer.cubeofinterest.cointcoregto.monitor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;

public final class ClusterMonitorClient {
    private ClusterMonitorClient() {
    }

    public static void registerScreens() {
        MenuScreens.register(
                ClusterMonitorRegistry.CLUSTER_MONITOR_MENU.get(),
                ClusterMonitorScreen::new
        );
    }

    public static void handleSnapshot(ClusterMonitorSnapshot snapshot) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof ClusterMonitorScreen screen) {
            screen.applySnapshot(snapshot);
        }
    }
}
