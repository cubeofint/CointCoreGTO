package Crazer.cubeofinterest.cubechat;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MuteManager {
    private static final Map<UUID, MuteData> MUTED = new ConcurrentHashMap<>();

    private MuteManager() {
    }

    public static void mute(ServerPlayer player, long durationMillis, String reason) {
        long until = System.currentTimeMillis() + durationMillis;
        MUTED.put(player.getUUID(), new MuteData(player.getGameProfile().getName(), until, reason == null ? "" : reason));
    }

    public static void unmute(ServerPlayer player) {
        MUTED.remove(player.getUUID());
    }

    public static boolean isMuted(ServerPlayer player) {
        MuteData data = MUTED.get(player.getUUID());

        if (data == null) {
            return false;
        }

        if (System.currentTimeMillis() >= data.untilMillis()) {
            MUTED.remove(player.getUUID());
            return false;
        }

        return true;
    }

    public static void sendMutedMessage(ServerPlayer player) {
        MuteData data = MUTED.get(player.getUUID());

        if (data == null) {
            return;
        }

        long leftMillis = data.untilMillis() - System.currentTimeMillis();

        player.displayClientMessage(
                Component.literal("§cТы замучен. Осталось: §e" + formatTime(leftMillis)),
                true
        );

//        if (!data.reason().isBlank()) {
//            player.sendSystemMessage(Component.literal("§cПричина мута: §f" + data.reason()));
//        }
    }

    public static String getReason(ServerPlayer player) {
        MuteData data = MUTED.get(player.getUUID());
        return data == null ? "" : data.reason();
    }

    private static String formatTime(long millis) {
        long seconds = Math.max(1, millis / 1000L);

        if (seconds < 60) {
            return seconds + " сек.";
        }

        long minutes = seconds / 60L;
        if (minutes < 60) {
            return minutes + " мин.";
        }

        long hours = minutes / 60L;
        if (hours < 24) {
            return hours + " ч.";
        }

        long days = hours / 24L;
        return days + " дн.";
    }

    public record MuteData(String name, long untilMillis, String reason) {
    }
}