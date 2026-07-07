package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class CointRadioClientPacketHandler {
    private CointRadioClientPacketHandler() {
    }

    public static void play(String url, String stationId) {
        CointRadioPlayer.play(url, message -> {
            Minecraft minecraft = Minecraft.getInstance();

            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(message, false);
            }
        });

        sendClientMessage(CointRadioConfig.getOnMessage(stationId), true);
    }

    public static void stop() {
        CointRadioPlayer.stop();
        sendClientMessage(CointRadioConfig.getOffMessage(), true);
    }

    public static void toggle(String url, String stationId) {
        if (CointRadioPlayer.isPlaying()) {
            stop();
            return;
        }

        play(url, stationId);
    }

    private static void sendClientMessage(String text, boolean actionBar) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(text), actionBar);
        }
    }

    public static void switchStation(String url, String stationId) {
        boolean wasPlaying = CointRadioPlayer.isPlaying();

        if (!wasPlaying) {
            sendClientMessage("§e[CointMusic] Станция: §f" + stationId, true);
            return;
        }

        CointRadioPlayer.play(url, message -> {
            Minecraft minecraft = Minecraft.getInstance();

            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(message, false);
            }
        });

        sendClientMessage(CointRadioConfig.getOnMessage(stationId), true);
    }
}