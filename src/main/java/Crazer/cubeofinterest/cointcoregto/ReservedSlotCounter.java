package Crazer.cubeofinterest.cointcoregto;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.util.Tristate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class ReservedSlotCounter {

    public static final String RESERVED_PERMISSION =
            "cointcoregto.reserved_slot";

    private ReservedSlotCounter() {
    }

    public static int countReservedPlayers(MinecraftServer server) {
        LuckPerms luckPerms;

        try {
            luckPerms = LuckPermsProvider.get();
        } catch (IllegalStateException exception) {
            return countOperators(server);
        }

        int count = 0;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (hasReservedAccess(luckPerms, player)) {
                count++;
            }
        }

        return count;
    }

    public static boolean hasReservedAccess(
            LuckPerms luckPerms,
            ServerPlayer player
    ) {
        if (player.hasPermissions(3)) {
            return true;
        }

        User user = luckPerms
                .getUserManager()
                .getUser(player.getUUID());

        if (user == null) {
            return false;
        }

        Tristate result = user
                .getCachedData()
                .getPermissionData()
                .checkPermission(RESERVED_PERMISSION);

        return result.asBoolean();
    }

    private static int countOperators(MinecraftServer server) {
        int count = 0;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(3)) {
                count++;
            }
        }

        return count;
    }
}