package Crazer.cubeofinterest.cointcoregto.battlepass.client;

import Crazer.cubeofinterest.cointcoregto.battlepass.network.BattlePassStatePacket;
import net.minecraft.client.Minecraft;

public final class BattlePassClientHandler {

    private BattlePassClientHandler() {
    }

    public static void handle(BattlePassStatePacket state) {
        Minecraft minecraft = Minecraft.getInstance();

        minecraft.execute(() -> {
            // StatePacket already contains the server-side enabled flag.
            // Keep the local availability state consistent even if this packet
            // arrived because of /battlepass or an old/stale open request.
            BattlePassClientEvents.setServerBattlePassEnabled(state.enabled());

            if (!state.enabled()) {
                if (minecraft.screen instanceof BattlePassScreen) {
                    minecraft.setScreen(null);
                }
                return;
            }

            if (minecraft.screen instanceof BattlePassScreen screen) {
                screen.updateState(state);
            } else {
                minecraft.setScreen(new BattlePassScreen(state));
            }
        });
    }
}
