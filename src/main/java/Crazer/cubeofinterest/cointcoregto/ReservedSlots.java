package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

@Mod.EventBusSubscriber(modid = CointCoreGTO.MODID)
public final class ReservedSlots {
    private ReservedSlots() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        int publicSlots = CointCoreGTO.RESERVED_PUBLIC_SLOTS.get();
        int totalSlots = CointCoreGTO.RESERVED_TOTAL_SLOTS.get();
        String permission = CointCoreGTO.RESERVED_PERMISSION.get();

        int online = server.getPlayerList().getPlayerCount();

        if (online > totalSlots) {
            player.connection.disconnect(Component.literal(CointCoreGTO.RESERVED_FULL_MESSAGE.get()));
            return;
        }

        if (online <= publicSlots) {
            return;
        }

        if (!hasPermission(player, permission)) {
            player.connection.disconnect(Component.literal(CointCoreGTO.RESERVED_NO_PERMISSION_MESSAGE.get()));
        }
    }

    private static boolean hasPermission(ServerPlayer player, String permission) {
        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");

            Object luckPerms = providerClass.getMethod("get").invoke(null);
            Object userManager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);
            Object user = userManager.getClass()
                    .getMethod("getUser", UUID.class)
                    .invoke(userManager, player.getUUID());

            if (user != null) {
                Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
                Object permissionData = cachedData.getClass().getMethod("getPermissionData").invoke(cachedData);
                Object result = permissionData.getClass()
                        .getMethod("checkPermission", String.class)
                        .invoke(permissionData, permission);
                Object booleanResult = result.getClass().getMethod("asBoolean").invoke(result);

                return (boolean) booleanResult;
            }
        } catch (Throwable ignored) {
            // LuckPerms отсутствует или недоступен.
        }

        // OP тоже может заходить в резервные слоты.
        return player.hasPermissions(2);
    }
}