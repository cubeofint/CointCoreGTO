package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

@OnlyIn(Dist.CLIENT)
public final class CointRadioClientPacketHandler {
    private static String currentRadioId = "";

    private CointRadioClientPacketHandler() {
    }

    public static void play(String url, String stationId, String radioId) {
        currentRadioId = radioId == null ? "" : radioId;

        CointRadioPlayer.play(url, null);

        sendClientMessage(CointRadioConfig.getOnMessage(stationId), true);
    }

    public static void stop(String radioId) {
        String incomingId = radioId == null ? "" : radioId;

        if (!incomingId.isBlank() && !currentRadioId.isBlank() && !incomingId.equals(currentRadioId)) {
            return;
        }

        CointRadioPlayer.stop();
        currentRadioId = "";

        sendClientMessage(CointRadioConfig.getOffMessage(), true);
    }

    public static void toggle(String url, String stationId, String radioId) {
        if (CointRadioPlayer.isPlaying()) {
            stop(radioId);
            return;
        }

        play(url, stationId, radioId);
    }

    public static void switchStation(String url, String stationId, String radioId) {
        boolean wasPlaying = CointRadioPlayer.isPlaying();

        if (!wasPlaying) {
            sendClientMessage("§e[CointMusic] Станция: §f" + stationId, true);
            return;
        }

        play(url, stationId, radioId);
    }

    public static void openScreen(
            BlockPos pos,
            List<String> stations,
            String currentStation,
            boolean active,
            int radius
    ) {
        Minecraft minecraft = Minecraft.getInstance();

        minecraft.setScreen(new CointRadioScreen(pos, stations, currentStation, active, radius));
    }

    public static void resetCurrentRadio() {
        currentRadioId = "";
    }

    private static void sendClientMessage(String text, boolean actionBar) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(text), actionBar);
        }
    }
}