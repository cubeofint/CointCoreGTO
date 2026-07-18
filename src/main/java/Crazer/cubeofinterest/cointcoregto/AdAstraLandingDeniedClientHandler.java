package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.client.Minecraft;

public final class AdAstraLandingDeniedClientHandler {

    private AdAstraLandingDeniedClientHandler() {
    }

    public static void handle() {
        Minecraft minecraft = Minecraft.getInstance();

        minecraft.execute(
                AdAstraLandingDenyClient::show
        );
    }
}