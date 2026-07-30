package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerXpEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClusterTransferGuard {
    private static final ClusterTransferGuard INSTANCE =
            new ClusterTransferGuard();

    private static final AtomicBoolean REGISTERED =
            new AtomicBoolean();

    private static final ConcurrentMap<UUID, TransferLock> LOCKS =
            new ConcurrentHashMap<>();

    private ClusterTransferGuard() {
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            MinecraftForge.EVENT_BUS.register(INSTANCE);
        }
    }

    public static boolean lock(
            ServerPlayer player,
            int timeoutSeconds,
            String reason
    ) {
        if (player == null) {
            return false;
        }

        TransferLock newLock = new TransferLock(
                Instant.now(),
                Math.max(1, timeoutSeconds),
                reason == null || reason.isBlank()
                        ? "Проверка кластерного перехода"
                        : reason
        );

        TransferLock existing = LOCKS.putIfAbsent(
                player.getUUID(),
                newLock
        );

        if (existing != null) {
            return false;
        }

        player.stopUsingItem();
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastFullState();

        return true;
    }

    public static void updateReason(
            UUID playerUuid,
            String reason
    ) {
        if (playerUuid == null
                || reason == null
                || reason.isBlank()) {
            return;
        }

        LOCKS.computeIfPresent(
                playerUuid,
                (ignored, current) -> new TransferLock(
                        current.startedAt(),
                        current.timeoutSeconds(),
                        reason
                )
        );
    }

    public static boolean isLocked(
            ServerPlayer player
    ) {
        return player != null
                && LOCKS.containsKey(player.getUUID());
    }

    public static boolean isLocked(
            UUID playerUuid
    ) {
        return playerUuid != null
                && LOCKS.containsKey(playerUuid);
    }

    public static void unlock(
            ServerPlayer player
    ) {
        if (player == null) {
            return;
        }

        unlock(player.getUUID());
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastFullState();
    }

    public static void unlock(
            UUID playerUuid
    ) {
        if (playerUuid != null) {
            LOCKS.remove(playerUuid);
        }
    }

    public static void clearAll() {
        LOCKS.clear();
    }

    @SubscribeEvent
    public void onPlayerTick(
            TickEvent.PlayerTickEvent event
    ) {
        if (event.phase != TickEvent.Phase.END
                || !(event.player instanceof ServerPlayer player)) {
            return;
        }

        TransferLock transferLock = LOCKS.get(
                player.getUUID()
        );

        if (transferLock == null) {
            return;
        }

        player.stopUsingItem();

        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }

        player.setDeltaMovement(0.0D, 0.0D, 0.0D);
        player.hurtMarked = true;

        if (player.tickCount % 20 == 0) {
            player.displayClientMessage(
                    Component.literal(
                            "§e" + transferLock.reason()
                                    + "§7. Инвентарь временно заблокирован."
                    ),
                    true
            );
        }

        long elapsedSeconds = Duration.between(
                transferLock.startedAt(),
                Instant.now()
        ).getSeconds();

        if (elapsedSeconds < transferLock.timeoutSeconds()) {
            return;
        }

        player.connection.disconnect(
                Component.literal(
                        "Кластерный переход не завершился за "
                                + transferLock.timeoutSeconds()
                                + " секунд. Вы отключены для защиты данных игрока. "
                                + "Повторите вход через несколько секунд."
                )
        );

        LOCKS.remove(player.getUUID(), transferLock);
    }

    @SubscribeEvent
    public void onPlayerInteract(
            PlayerInteractEvent event
    ) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !isLocked(player)) {
            return;
        }

        if (event.isCancelable()) {
            event.setCanceled(true);
        }

        event.setCancellationResult(InteractionResult.FAIL);
    }

    @SubscribeEvent
    public void onItemToss(
            ItemTossEvent event
    ) {
        if (event.getPlayer() instanceof ServerPlayer player
                && isLocked(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onItemPickup(
            EntityItemPickupEvent event
    ) {
        if (event.getEntity() instanceof ServerPlayer player
                && isLocked(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onExperiencePickup(
            PlayerXpEvent.PickupXp event
    ) {
        if (event.getEntity() instanceof ServerPlayer player
                && isLocked(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onLivingAttack(
            LivingAttackEvent event
    ) {
        if (event.getEntity() instanceof ServerPlayer player
                && isLocked(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(
            PlayerEvent.PlayerLoggedOutEvent event
    ) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LOCKS.remove(player.getUUID());
        }
    }

    private record TransferLock(
            Instant startedAt,
            int timeoutSeconds,
            String reason
    ) {
    }
}
