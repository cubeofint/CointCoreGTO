package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class DimensionQuestAccessHandler {

    private static final Map<UUID, Long> LAST_RETURN_TIME =
            new HashMap<>();

    private static final long RETURN_COOLDOWN_MS = 5_000L;

    private DimensionQuestAccessHandler() {
    }

    @SubscribeEvent
    public static void onTravelToDimension(
            EntityTravelToDimensionEvent event
    ) {
        if (!DimensionQuestLockConfig.ENABLED.get()) {
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (hasBypassPermission(player)) {
            return;
        }

        String requiredQuestId = getRequiredQuestId(
                event.getDimension().location()
        );

        if (requiredQuestId == null) {
            return;
        }

        if (
                FTBQuestAccess.isQuestCompleted(
                        player,
                        requiredQuestId
                )
        ) {
            return;
        }

        event.setCanceled(true);

        player.displayClientMessage(
                message(
                        DimensionQuestLockConfig.DENY_MESSAGE.get()
                ),
                true
        );
    }

    public static boolean canLandOnDimension(
            ServerPlayer player,
            ResourceLocation dimensionId
    ) {
        if (!DimensionQuestLockConfig.ENABLED.get()) {
            return true;
        }

        if (hasBypassPermission(player)) {
            return true;
        }

        String requiredQuestId = getRequiredQuestId(dimensionId);

        if (requiredQuestId == null) {
            return true;
        }

        return FTBQuestAccess.isQuestCompleted(
                player,
                requiredQuestId
        );
    }

    public static void sendLandingDeniedMessage(
            ServerPlayer player
    ) {
        player.displayClientMessage(
                message(DimensionQuestLockConfig.DENY_MESSAGE.get()),
                true
        );
    }

    @SubscribeEvent
    public static void onPlayerTick(
            TickEvent.PlayerTickEvent event
    ) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        if (!(event.player instanceof ServerPlayer player)) {
            return;
        }

        if (!DimensionQuestLockConfig.ENABLED.get()) {
            return;
        }

        int interval = DimensionQuestLockConfig
                .CHECK_INTERVAL_TICKS
                .get();

        if (player.tickCount % interval != 0) {
            return;
        }

        if (hasBypassPermission(player)) {
            return;
        }

        String requiredQuestId = getRequiredQuestId(
                player.level().dimension().location()
        );

        if (requiredQuestId == null) {
            return;
        }

        if (
                FTBQuestAccess.isQuestCompleted(
                        player,
                        requiredQuestId
                )
        ) {
            return;
        }

        returnToOverworld(player);
    }

    private static String getRequiredQuestId(
            ResourceLocation dimensionId
    ) {
        List<? extends String> configuredLocks =
                DimensionQuestLockConfig.LOCKS.get();

        for (String configuredLock : configuredLocks) {
            int separator = configuredLock.indexOf('=');

            if (separator <= 0) {
                continue;
            }

            String configuredDimension = configuredLock
                    .substring(0, separator)
                    .trim();

            String configuredQuest = configuredLock
                    .substring(separator + 1)
                    .trim();

            ResourceLocation parsedDimension =
                    ResourceLocation.tryParse(configuredDimension);

            if (
                    parsedDimension != null
                            && parsedDimension.equals(dimensionId)
            ) {
                return configuredQuest;
            }
        }

        return null;
    }

    private static boolean hasBypassPermission(
            ServerPlayer player
    ) {
        int permissionLevel = DimensionQuestLockConfig
                .BYPASS_PERMISSION_LEVEL
                .get();

        return permissionLevel <= 0
                || player.hasPermissions(permissionLevel);
    }

    private static void returnToOverworld(
            ServerPlayer player
    ) {
        long now = System.currentTimeMillis();

        long lastReturn = LAST_RETURN_TIME.getOrDefault(
                player.getUUID(),
                0L
        );

        if (now - lastReturn < RETURN_COOLDOWN_MS) {
            return;
        }

        LAST_RETURN_TIME.put(player.getUUID(), now);

        ServerLevel overworld = player.server.overworld();
        BlockPos spawn = overworld.getSharedSpawnPos();

        int safeY = overworld.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                spawn.getX(),
                spawn.getZ()
        );

        player.teleportTo(
                overworld,
                spawn.getX() + 0.5D,
                safeY,
                spawn.getZ() + 0.5D,
                player.getYRot(),
                player.getXRot()
        );

        player.displayClientMessage(
                message(
                        DimensionQuestLockConfig.RETURN_MESSAGE.get()
                ),
                false
        );
    }

    private static Component message(String text) {
        return Component.literal(
                text.replace(
                        '&',
                        ChatFormatting.PREFIX_CODE
                )
        );
    }
}