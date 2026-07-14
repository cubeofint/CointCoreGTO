package Crazer.cubeofinterest.cointcoregto;

import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class ReservedSlots {
    private static boolean displaySlotsLogged = false;

    private static final UUID RESERVED_STATUS_MARKER_UUID =
            UUID.fromString("00000000-0000-0000-0000-00000000c017");


    private static final String RESERVED_STATUS_MARKER_PREFIX = "CRS_";

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

        if (publicSlots > totalSlots) {
            publicSlots = totalSlots;
        }

        int totalOnline = server.getPlayerList().getPlayerCount();

        if (totalOnline > totalSlots) {
            player.connection.disconnect(Component.literal(getFullMessage()));
            return;
        }

        if (hasReservedSlotPermission(player)) {
            return;
        }

        int regularOnline = countRegularPlayers(server);

        if (regularOnline > publicSlots) {
            player.connection.disconnect(Component.literal(getNoPermissionMessage()));
        }
    }

    public static int getPublicSlotsForDisplay(int originalMaxPlayers) {
        int publicSlots = getPublicSlots();

        if (!displaySlotsLogged) {
            displaySlotsLogged = true;

            System.out.println("[CointCoreGTO] Server list slots: originalMaxPlayers="
                    + originalMaxPlayers
                    + ", publicSlots="
                    + publicSlots);
        }

        if (publicSlots <= 0) {
            return originalMaxPlayers;
        }

        if (originalMaxPlayers <= 0) {
            return publicSlots;
        }

        return Math.min(publicSlots, originalMaxPlayers);
    }

    public static int getRegularOnlineForDisplay(MinecraftServer server, int fallbackOnline) {
        if (server == null) {
            return fallbackOnline;
        }

        try {
            return countRegularPlayers(server);
        } catch (Throwable ignored) {
            return fallbackOnline;
        }
    }

    public static int getReservedOnlineForDisplay(MinecraftServer server) {
        if (server == null) {
            return 0;
        }

        try {
            return countReservedPlayers(server);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    public static int getReservedSlotsForDisplay() {
        int publicSlots = getPublicSlots();
        int totalSlots = getTotalSlots();

        if (publicSlots <= 0 || totalSlots <= 0) {
            return 0;
        }

        return Math.max(0, totalSlots - publicSlots);
    }

    public static List<GameProfile> addReservedStatusMarker(List<GameProfile> originalSample, MinecraftServer server) {
        ArrayList<GameProfile> result = new ArrayList<>();

        if (server != null) {
            int regularOnline = getRegularOnlineForDisplay(server, 0);
            int reservedOnline = getReservedOnlineForDisplay(server);
            int publicSlots = getPublicSlotsForDisplay(getTotalSlots());
            int reservedSlots = getReservedSlotsForDisplay();

            String markerName = RESERVED_STATUS_MARKER_PREFIX
                    + toBase36(regularOnline)
                    + "_"
                    + toBase36(reservedOnline)
                    + "_"
                    + toBase36(publicSlots)
                    + "_"
                    + toBase36(reservedSlots);


            result.add(new GameProfile(RESERVED_STATUS_MARKER_UUID, markerName));
        }

        if (originalSample != null) {
            for (GameProfile profile : originalSample) {
                if (profile == null) {
                    continue;
                }

                if (isReservedStatusMarkerName(profile.getName())) {
                    continue;
                }

                result.add(profile);
            }
        }

        return result;
    }

    public static boolean isReservedStatusMarkerName(String name) {
        if (name == null) {
            return false;
        }

        String clean = name.replaceAll("§.", "").trim();

        return clean.startsWith("CRS_") || clean.startsWith("R_");
    }
    public static Component appendReservedInfoToMotd(Component originalMotd, MinecraftServer server) {
        Component safeOriginal = originalMotd == null ? Component.empty() : originalMotd;

        if (server == null) {
            return safeOriginal;
        }

        String originalText = safeOriginal.getString();

        if (originalText != null && originalText.contains("Обычные:") && originalText.contains("Резерв:")) {
            return safeOriginal;
        }

        int regularOnline = getRegularOnlineForDisplay(server, 0);
        int reservedOnline = getReservedOnlineForDisplay(server);
        int publicSlots = getPublicSlotsForDisplay(getTotalSlots());
        int reservedSlots = getReservedSlotsForDisplay();

        MutableComponent result = safeOriginal.copy();

        result.append(Component.literal("\n"));
        result.append(
                Component.literal(
                        "Обычные: "
                                + regularOnline
                                + "/"
                                + publicSlots
                                + " | Резерв: "
                                + reservedOnline
                                + "/"
                                + reservedSlots
                ).withStyle(ChatFormatting.GRAY)
        );

        return result;
    }

    private static String toBase36(int value) {
        return Integer.toString(Math.max(0, value), 36);
    }

    private static int countRegularPlayers(MinecraftServer server) {
        if (server == null) {
            return 0;
        }

        int count = 0;

        for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
            if (onlinePlayer == null) {
                continue;
            }

            if (!hasReservedSlotPermission(onlinePlayer)) {
                count++;
            }
        }

        return count;
    }

    private static int countReservedPlayers(MinecraftServer server) {
        if (server == null) {
            return 0;
        }

        int count = 0;

        for (ServerPlayer onlinePlayer : server.getPlayerList().getPlayers()) {
            if (onlinePlayer == null) {
                continue;
            }

            if (hasReservedSlotPermission(onlinePlayer)) {
                count++;
            }
        }

        return count;
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