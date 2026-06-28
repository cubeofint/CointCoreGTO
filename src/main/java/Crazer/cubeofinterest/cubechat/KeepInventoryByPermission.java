package Crazer.cubeofinterest.cubechat;

import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = CubeChat.MODID)
public final class KeepInventoryByPermission {
    private static final String PERMISSION = "cubechat.keepinv";

    private static final Map<UUID, ListTag> SAVED_INVENTORIES = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SAVED_LEVELS = new ConcurrentHashMap<>();
    private static final Map<UUID, Float> SAVED_PROGRESS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> SAVED_TOTAL_XP = new ConcurrentHashMap<>();

    private KeepInventoryByPermission() {
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (!hasKeepInventory(player)) {
            return;
        }

        UUID uuid = player.getUUID();

        ListTag inventoryTag = new ListTag();
        player.getInventory().save(inventoryTag);

        SAVED_INVENTORIES.put(uuid, inventoryTag);
        SAVED_LEVELS.put(uuid, player.experienceLevel);
        SAVED_PROGRESS.put(uuid, player.experienceProgress);
        SAVED_TOTAL_XP.put(uuid, player.totalExperience);
    }

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (!hasKeepInventory(player)) {
            return;
        }

        event.getDrops().clear();
    }

    @SubscribeEvent
    public static void onExperienceDrop(LivingExperienceDropEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (!hasKeepInventory(player)) {
            return;
        }

        event.setDroppedExperience(0);
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        if (!event.isWasDeath()) {
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer newPlayer)) {
            return;
        }

        UUID uuid = newPlayer.getUUID();

        ListTag inventoryTag = SAVED_INVENTORIES.remove(uuid);
        if (inventoryTag != null) {
            Inventory inventory = newPlayer.getInventory();

            inventory.clearContent();
            inventory.load(inventoryTag);
            inventory.setChanged();

            newPlayer.inventoryMenu.broadcastChanges();
            newPlayer.containerMenu.broadcastChanges();
        }

        Integer level = SAVED_LEVELS.remove(uuid);
        Float progress = SAVED_PROGRESS.remove(uuid);
        Integer totalXp = SAVED_TOTAL_XP.remove(uuid);

        if (level != null && progress != null && totalXp != null) {
            newPlayer.experienceLevel = level;
            newPlayer.experienceProgress = progress;
            newPlayer.totalExperience = totalXp;
        }
    }

    private static boolean hasKeepInventory(ServerPlayer player) {
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
                        .invoke(permissionData, PERMISSION);
                Object booleanResult = result.getClass().getMethod("asBoolean").invoke(result);

                return (boolean) booleanResult;
            }
        } catch (Throwable ignored) {
            // LuckPerms нет в IDE или проверка недоступна.
        }

        // Fallback для теста в IDE. Если на реальном сервере не нужно, чтобы OP тоже получал сохранение без LuckPerms-права, замени внизу:
        return player.hasPermissions(2);
        // на return false;
    }
}