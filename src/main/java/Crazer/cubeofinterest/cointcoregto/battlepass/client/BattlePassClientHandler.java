package Crazer.cubeofinterest.cointcoregto.battlepass.client;

import Crazer.cubeofinterest.cointcoregto.battlepass.network.BattlePassStatePacket;
import net.minecraft.client.Minecraft;

public final class BattlePassClientHandler {
    private BattlePassClientHandler() {
    }

    public static void handle(BattlePassStatePacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.screen instanceof BattlePassScreen screen) {
                screen.updateState(packet);
            } else {
                minecraft.setScreen(new BattlePassScreen(packet));
            }
        });
    }
}
