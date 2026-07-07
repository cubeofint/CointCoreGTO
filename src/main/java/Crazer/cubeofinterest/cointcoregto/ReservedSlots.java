package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class ReservedSlots {
    private ReservedSlots() {
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        MinecraftServer server = player.getServer();

        if (server == null) {
            return;
        }

        int publicSlots = getPublicSlots();
        int totalSlots = getTotalSlots();

        if (publicSlots <= 0 || totalSlots <= 0) {
            return;
        }

        int online = server.getPlayerList().getPlayerCount();

        // Игрок уже вошёл, поэтому 51-й игрок даст online == 51.
        if (online <= publicSlots) {
            return;
        }

        if (online > totalSlots) {
            player.connection.disconnect(Component.literal(getFullMessage()));
            return;
        }

        if (!hasReservedSlotPermission(player)) {
            player.connection.disconnect(Component.literal(getNoPermissionMessage()));
        }
    }

    public static int getPublicSlotsForDisplay(int originalMaxPlayers) {
        int publicSlots = getPublicSlots();

        if (publicSlots <= 0) {
            return originalMaxPlayers;
        }

        return publicSlots;
    }

    private static boolean hasReservedSlotPermission(ServerPlayer player) {
        if (player == null) {
            return false;
        }

        if (player.hasPermissions(2)) {
            return true;
        }

        return CointCoreGTO.hasPermissionNode(player, getPermission());
    }

    private static int getPublicSlots() {
        try {
            return CointCoreGTO.RESERVED_PUBLIC_SLOTS.get();
        } catch (Throwable ignored) {
            return 50;
        }
    }

    private static int getTotalSlots() {
        try {
            return CointCoreGTO.RESERVED_TOTAL_SLOTS.get();
        } catch (Throwable ignored) {
            return 75;
        }
    }

    private static String getPermission() {
        try {
            return CointCoreGTO.RESERVED_PERMISSION.get();
        } catch (Throwable ignored) {
            return "cubechatjoinfull";
        }
    }

    private static String getFullMessage() {
        try {
            return CointCoreGTO.RESERVED_FULL_MESSAGE.get();
        } catch (Throwable ignored) {
            return "Сервер заполнен.";
        }
    }

    private static String getNoPermissionMessage() {
        try {
            return CointCoreGTO.RESERVED_NO_PERMISSION_MESSAGE.get();
        } catch (Throwable ignored) {
            return "Сервер заполнен. Резервные слоты доступны только администрации и донатерам.";
        }
    }
}