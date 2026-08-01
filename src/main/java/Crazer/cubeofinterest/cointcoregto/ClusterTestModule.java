package Crazer.cubeofinterest.cointcoregto;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class ClusterTestModule {
    private static final Logger LOGGER =
            LogManager.getLogger("CointCoreGTO:Cluster");

    private static final ClusterTestModule INSTANCE =
            new ClusterTestModule();

    private static final AtomicBoolean REGISTERED =
            new AtomicBoolean();

    private static final AtomicBoolean HEARTBEAT_IN_FLIGHT =
            new AtomicBoolean();

    private static final AtomicBoolean DIMENSION_TICK_GUARD_ACTIVE =
            new AtomicBoolean();

    private static final AtomicBoolean SNAPSHOT_OPERATION_IN_FLIGHT =
            new AtomicBoolean();
    private static final AtomicBoolean FAILBACK_OPERATION_IN_FLIGHT =
            new AtomicBoolean();
    private static final AtomicBoolean DRAIN_OPERATION_IN_FLIGHT =
            new AtomicBoolean();
    private static final AtomicBoolean AUTOMATIC_OPERATION_RECOVERY_SCAN_IN_FLIGHT =
            new AtomicBoolean();

    private static final long AUTOMATIC_OPERATION_RECOVERY_WAIT_LOG_INTERVAL_MILLIS =
            300_000L;

    private static volatile Map<String, String>
            DIMENSION_OWNER_CACHE = Map.of();
    private static volatile long
            DIMENSION_OWNER_CACHE_REFRESHED_AT_MILLIS;

    private static final Set<String>
            DIMENSION_TICK_SUPPRESSION_LOGGED =
            ConcurrentHashMap.newKeySet();

    private static volatile Map<String, Integer>
            DIMENSION_PLAYER_COUNT_SNAPSHOT = Map.of();

    private static volatile Set<String>
            DIMENSION_MIGRATION_FROZEN = Set.of();
    private static volatile Set<String>
            DIMENSION_MIGRATION_BLOCKED = Set.of();
    private static volatile Set<String>
            DIMENSION_SNAPSHOT_FROZEN = Set.of();

    private static final int DIMENSION_LIST_PAGE_SIZE = 12;
    private static volatile PendingApplyConfirmation PENDING_APPLY_CONFIRMATION;
    private static volatile String LAST_PENDING_APPLY_NOTIFICATION_FINGERPRINT;
    private static volatile long LAST_PENDING_APPLY_NOTIFICATION_AT_MILLIS;

    





    private static final ConcurrentMap<UUID, DimensionRouteSuppression>
            DIMENSION_ROUTE_SUPPRESSIONS = new ConcurrentHashMap<>();

    private static final long DIMENSION_ROUTE_SUPPRESSION_NANOS =
            10_000_000_000L;

    private static final ExecutorService MIGRATION_EXECUTOR =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(
                        task,
                        "CointCoreGTO-Dimension-Migration"
                );

                thread.setDaemon(true);
                thread.setContextClassLoader(
                        ClusterTestModule.class.getClassLoader()
                );

                return thread;
            });

    private static final ExecutorService DATABASE_EXECUTOR =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(
                        task,
                        "CointCoreGTO-Cluster-DB"
                );

                thread.setDaemon(true);
                thread.setContextClassLoader(
                        ClusterTestModule.class.getClassLoader()
                );

                return thread;
            });

    private volatile ClusterConfig config;
    private volatile ClusterDatabase.TestResult lastResult;
    private volatile String lastError;
    private volatile MinecraftServer activeServer;
    private volatile boolean heartbeatFailureLogged;
    private volatile long nextAutomaticSnapshotAtMillis;
    private volatile String lastAutomaticSnapshotSummary;
    private volatile long nextAutomaticOperationRecoveryCheckAtMillis;
    private volatile long lastAutomaticOperationRecoveryScanAtMillis;
    private volatile String lastAutomaticOperationRecoveryScanSummary;
    private volatile String lastAutomaticOperationRecoverySummary;
    private volatile String lastAutomaticOperationRecoveryWaitKey;
    private volatile long lastAutomaticOperationRecoveryWaitLoggedAtMillis;
    private int heartbeatTickCounter;

    private ClusterTestModule() {
    }

    public static void register() {
        ClusterTransferGuard.register();

        if (REGISTERED.compareAndSet(false, true)) {
            MinecraftForge.EVENT_BUS.register(INSTANCE);
        }
    }

    public static void markDimensionTickGuardActive() {
        DIMENSION_TICK_GUARD_ACTIVE.set(true);
    }

    public static boolean shouldSkipDimensionTick(
            ServerLevel level
    ) {
        return INSTANCE.isDimensionTickSuppressed(level);
    }

    private boolean isDimensionTickSuppressed(
            ServerLevel level
    ) {
        ClusterConfig currentConfig = config;

        if (level == null
                || currentConfig == null
                || !currentConfig.enabled()) {
            return false;
        }

        String dimensionId =
                level.dimension().location().toString();

        if (currentConfig.dimensionTickIsolation()
                && !isDimensionOwnerCacheFresh(currentConfig)) {
            if (DIMENSION_TICK_SUPPRESSION_LOGGED.add(dimensionId)) {
                LOGGER.warn(
                        "Dimension tick isolation fail-closed: freezing {} on node {} because owner cache is {}",
                        dimensionId,
                        currentConfig.nodeId(),
                        dimensionOwnerCacheState(currentConfig)
                );
            }
            return true;
        }

        if (DIMENSION_MIGRATION_FROZEN.contains(dimensionId)
                || DIMENSION_SNAPSHOT_FROZEN.contains(dimensionId)) {
            if (DIMENSION_TICK_SUPPRESSION_LOGGED.add(dimensionId)) {
                LOGGER.info(
                        "Dimension migration freezing {} on source node {}",
                        dimensionId,
                        currentConfig.nodeId()
                );
            }
            return true;
        }

        if (!currentConfig.dimensionTickIsolation()) {
            DIMENSION_TICK_SUPPRESSION_LOGGED.remove(dimensionId);
            return false;
        }

        String ownerNode =
                DIMENSION_OWNER_CACHE.get(dimensionId);

        if (ownerNode == null || ownerNode.isBlank()) {
            if (DIMENSION_TICK_SUPPRESSION_LOGGED.add(dimensionId)) {
                LOGGER.warn(
                        "Dimension tick isolation fail-closed: freezing {} on node {} because owner is unknown",
                        dimensionId,
                        currentConfig.nodeId()
                );
            }
            return true;
        }

        boolean suppressed =
                !ownerNode.equalsIgnoreCase(
                        currentConfig.nodeId()
                );

        if (suppressed) {
            if (DIMENSION_TICK_SUPPRESSION_LOGGED.add(dimensionId)) {
                LOGGER.info(
                        "Dimension tick isolation enabled: freezing {} on node {} because owner is {}",
                        dimensionId,
                        currentConfig.nodeId(),
                        ownerNode
                );
            }
        } else {
            DIMENSION_TICK_SUPPRESSION_LOGGED.remove(dimensionId);
        }

        return suppressed;
    }

    public static boolean routeFtbEssentialsTeleport(
            ServerPlayer player,
            ResourceKey<Level> dimension,
            BlockPos pos,
            Float yRot,
            Float xRot
    ) {
        return INSTANCE.tryRouteFtbEssentialsTeleport(
                player,
                dimension,
                pos,
                yRot,
                xRot
        );
    }

    private boolean tryRouteFtbEssentialsTeleport(
            ServerPlayer player,
            ResourceKey<Level> dimension,
            BlockPos pos,
            Float yRot,
            Float xRot
    ) {
        ClusterConfig currentConfig = config;

        if (currentConfig == null
                || !currentConfig.enabled()
                || player == null
                || dimension == null
                || pos == null) {
            return false;
        }

        String dimensionId =
                dimension.location().toString();

        if (DIMENSION_MIGRATION_BLOCKED.contains(dimensionId)
                || DIMENSION_SNAPSHOT_FROZEN.contains(dimensionId)) {
            player.sendSystemMessage(
                    Component.literal(
                            "§cИзмерение временно недоступно: выполняется безопасная migration."
                    )
            );
            return true;
        }

        String cachedOwner =
                DIMENSION_OWNER_CACHE.get(dimensionId);

        if (cachedOwner == null
                && !currentConfig.failClosedRouting()) {
            return false;
        }

        MinecraftServer server = player.getServer();

        if (server == null) {
            return false;
        }

        UUID playerUuid = player.getUUID();
        String playerName =
                player.getGameProfile().getName();

        if (!ClusterTransferGuard.lock(
                player,
                currentConfig.transferLockTimeoutSeconds(),
                "Подготовка межсерверного телепорта"
        )) {
            player.sendSystemMessage(
                    Component.literal(
                            "§cДругой кластерный переход уже выполняется."
                    )
            );
            return true;
        }

        double targetX = pos.getX() + 0.5D;
        double targetY = pos.getY() + 0.1D;
        double targetZ = pos.getZ() + 0.5D;

        float targetYaw = yRot == null
                ? player.getYRot()
                : yRot;

        float targetPitch = xRot == null
                ? player.getXRot()
                : xRot;

        ClusterPlayerDataCodec.Snapshot playerData;

        try {
            playerData = capturePlayerDataForTransfer(
                    player,
                    currentConfig
            );
        } catch (Exception exception) {
            LOGGER.error(
                    "Unable to capture player data before FTB Essentials route for {}",
                    playerUuid,
                    exception
            );

            ClusterTransferGuard.unlock(player);
            player.sendSystemMessage(
                    Component.literal(
                            "§cПеренос отменён: не удалось сохранить данные игрока: "
                                    + exception.getMessage()
                    )
            );

            return true;
        }

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig =
                        ClusterConfig.load();

                config = latestConfig;

                if (!latestConfig.enabled()) {
                    scheduleRouteFailure(
                            server,
                            playerUuid,
                            "Кластерная маршрутизация отключена."
                    );

                    return;
                }

                String latestOwner =
                        ClusterDatabase.findDimensionOwner(
                                latestConfig,
                                dimensionId
                        );

                if (latestOwner == null) {
                    removeCachedDimensionOwner(
                            dimensionId
                    );

                    scheduleRouteFailure(
                            server,
                            playerUuid,
                            "Для dimension "
                                    + dimensionId
                                    + " владелец больше не назначен."
                    );

                    return;
                }

                updateCachedDimensionOwner(
                        dimensionId,
                        latestOwner
                );

                if (latestOwner.equalsIgnoreCase(
                        latestConfig.nodeId()
                )) {
                    server.execute(
                            () -> applyFtbTeleportLocally(
                                    server,
                                    latestConfig,
                                    playerUuid,
                                    dimensionId,
                                    targetX,
                                    targetY,
                                    targetZ,
                                    targetYaw,
                                    targetPitch
                            )
                    );

                    return;
                }

                createTransferAndScheduleRedirect(
                        server,
                        latestConfig,
                        playerUuid,
                        playerName,
                        latestOwner,
                        dimensionId,
                        targetX,
                        targetY,
                        targetZ,
                        targetYaw,
                        targetPitch,
                        playerData
                );
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to route FTB Essentials teleport for player {} to {}",
                        playerUuid,
                        dimensionId,
                        exception
                );

                scheduleTransferError(
                        server,
                        playerUuid,
                        exception
                );
            }
        });

        return true;
    }

    @SubscribeEvent
    public void onServerAboutToStart(
            ServerAboutToStartEvent event
    ) {
        try {
            ClusterConfig startupConfig = ClusterConfig.load();
            config = startupConfig;

            if (!startupConfig.enabled()) {
                return;
            }

            applyPendingDimensionFailoverAtStartup(
                    event.getServer(),
                    startupConfig
            );
            refreshDimensionMigrationFreeze(startupConfig);
            applyPendingDimensionMigrationAtStartup(
                    event.getServer(),
                    startupConfig
            );
            applyPendingDimensionRollbackAtStartup(
                    event.getServer(),
                    startupConfig
            );
            applyPendingDimensionFinalizationAtStartup(
                    event.getServer(),
                    startupConfig
            );
            cleanupFinalizedDimensionMigrationBackups(
                    event.getServer(),
                    startupConfig
            );
            refreshDimensionMigrationFreeze(startupConfig);
            refreshDimensionOwnerCache(startupConfig);
        } catch (Exception exception) {
            lastError = exception.getClass().getSimpleName()
                    + ": "
                    + exception.getMessage();
            LOGGER.error(
                    "Unable to process pending dimension migration before world load",
                    exception
            );
        }
    }

    @SubscribeEvent
    public void onServerStarted(
            ServerStartedEvent event
    ) {
        MinecraftServer server = event.getServer();
        ClusterTransferGuard.clearAll();
        SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
        activeServer = server;
        heartbeatTickCounter = 0;
        DIMENSION_PLAYER_COUNT_SNAPSHOT =
                captureDimensionPlayerCounts(server);

        try {
            config = ClusterConfig.load();
        } catch (Exception exception) {
            lastError = exception.getMessage();

            LOGGER.error(
                    "Unable to load cluster config",
                    exception
            );

            return;
        }

        if (!config.enabled()) {
            LOGGER.info(
                    "Cluster is disabled. Edit {} and set enabled=true",
                    ClusterConfig.path()
            );

            return;
        }

        long now = System.currentTimeMillis();
        nextAutomaticSnapshotAtMillis = now
                + config.dimensionSnapshotIntervalMinutes() * 60_000L;
        nextAutomaticOperationRecoveryCheckAtMillis = now
                + config.automaticOperationRecoveryIntervalSeconds() * 1_000L;
        lastAutomaticOperationRecoveryScanAtMillis = 0L;
        lastAutomaticOperationRecoveryScanSummary = null;
        lastAutomaticOperationRecoverySummary = null;
        clearAutomaticOperationRecoveryWait();

        DATABASE_EXECUTOR.execute(() -> {
            runTest(server, false);
            checkAutomaticNodeOperationRecoveryAtStartup(server);
            inspectPendingApplyRestart(server, config);
        });
    }

    @SubscribeEvent
    public void onServerTick(
            TickEvent.ServerTickEvent event
    ) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        MinecraftServer server = activeServer;
        ClusterConfig currentConfig = config;

        if (server == null
                || currentConfig == null
                || !currentConfig.enabled()) {
            return;
        }

        DIMENSION_PLAYER_COUNT_SNAPSHOT =
                captureDimensionPlayerCounts(server);

        startAutomaticDimensionSnapshotsIfDue(server, currentConfig);
        startAutomaticNodeOperationRecoveryWatchdogIfDue(server, currentConfig);

        heartbeatTickCounter++;

        if (heartbeatTickCounter
                < currentConfig.heartbeatIntervalTicks()) {
            return;
        }

        heartbeatTickCounter = 0;

        if (!HEARTBEAT_IN_FLIGHT.compareAndSet(
                false,
                true
        )) {
            return;
        }

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.heartbeat(
                        currentConfig,
                        server,
                        DIMENSION_PLAYER_COUNT_SNAPSHOT
                );

                performFailover(
                        currentConfig,
                        false
                );

                inspectPendingApplyRestart(server, currentConfig);

                refreshDimensionOwnerCache(
                        currentConfig
                );
                refreshDimensionMigrationFreeze(
                        currentConfig
                );

                if (heartbeatFailureLogged) {
                    LOGGER.info(
                            "Cluster heartbeat recovered for node {}",
                            currentConfig.nodeId()
                    );
                }

                heartbeatFailureLogged = false;
            } catch (Exception exception) {
                if (!heartbeatFailureLogged) {
                    LOGGER.error(
                            "Cluster heartbeat failed for node {}",
                            currentConfig.nodeId(),
                            exception
                    );
                }

                heartbeatFailureLogged = true;
                lastError =
                        exception.getClass()
                                .getSimpleName()
                                + ": "
                                + exception.getMessage();
            } finally {
                HEARTBEAT_IN_FLIGHT.set(false);
            }
        });
    }

    @SubscribeEvent
    public void onServerStopping(
            ServerStoppingEvent event
    ) {
        ClusterConfig stoppingConfig = config;

        if (stoppingConfig != null
                && stoppingConfig.enabled()) {
            DATABASE_EXECUTOR.execute(() -> {
                try {
                    ClusterDatabase.markNodeOffline(
                            stoppingConfig
                    );
                } catch (Exception exception) {
                    LOGGER.warn(
                            "Unable to mark cluster node {} as stopped",
                            stoppingConfig.nodeId(),
                            exception
                    );
                }
            });
        }

        if (activeServer == event.getServer()) {
            activeServer = null;
        }

        heartbeatTickCounter = 0;
        DIMENSION_OWNER_CACHE = Map.of();
        DIMENSION_OWNER_CACHE_REFRESHED_AT_MILLIS = 0L;
        DIMENSION_PLAYER_COUNT_SNAPSHOT = Map.of();
        DIMENSION_MIGRATION_FROZEN = Set.of();
        DIMENSION_MIGRATION_BLOCKED = Set.of();
        DIMENSION_SNAPSHOT_FROZEN = Set.of();
        DIMENSION_ROUTE_SUPPRESSIONS.clear();
        DIMENSION_TICK_SUPPRESSION_LOGGED.clear();
        DIMENSION_TICK_GUARD_ACTIVE.set(false);
        SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
        FAILBACK_OPERATION_IN_FLIGHT.set(false);
        DRAIN_OPERATION_IN_FLIGHT.set(false);
        AUTOMATIC_OPERATION_RECOVERY_SCAN_IN_FLIGHT.set(false);
        nextAutomaticSnapshotAtMillis = 0L;
        lastAutomaticSnapshotSummary = null;
        nextAutomaticOperationRecoveryCheckAtMillis = 0L;
        lastAutomaticOperationRecoveryScanAtMillis = 0L;
        lastAutomaticOperationRecoveryScanSummary = null;
        lastAutomaticOperationRecoverySummary = null;
        clearAutomaticOperationRecoveryWait();
        PENDING_APPLY_CONFIRMATION = null;
        LAST_PENDING_APPLY_NOTIFICATION_FINGERPRINT = null;
        LAST_PENDING_APPLY_NOTIFICATION_AT_MILLIS = 0L;
        ClusterTransferGuard.clearAll();
    }

    @SubscribeEvent
    public void onRegisterCommands(
            RegisterCommandsEvent event
    ) {
        CommandDispatcher<CommandSourceStack> dispatcher =
                event.getDispatcher();

        dispatcher.register(
                Commands.literal("gtocluster")
                        .requires(
                                source ->
                                        source.hasPermission(2)
                        )

                        .then(
                                Commands.literal("dbtest")
                                        .executes(context -> {
                                            CommandSourceStack source =
                                                    context.getSource();

                                            MinecraftServer server =
                                                    source.getServer();

                                            source.sendSuccess(
                                                    () -> Component.literal(
                                                            "§eПроверяю подключение к MySQL..."
                                                    ),
                                                    false
                                            );

                                            DATABASE_EXECUTOR.execute(
                                                    () -> runTest(
                                                            server,
                                                            true
                                                    )
                                            );

                                            return 1;
                                        })
                        )

                        .then(
                                Commands.literal("status")
                                        .executes(context -> {
                                            sendStatus(
                                                    context.getSource()
                                            );

                                            return 1;
                                        })
                        )

                        .then(
                                Commands.literal("health")
                                        .executes(context ->
                                                showClusterHealth(
                                                        context.getSource()
                                                )
                                        )
                        )

                        .then(
                                Commands.literal("tickstatus")
                                        .executes(context ->
                                                showDimensionTickStatus(
                                                        context.getSource()
                                                )
                                        )
                        )

                        .then(
                                Commands.literal("chat")
                                        .then(
                                                Commands.literal("test")
                                                        .executes(context ->
                                                                startNetworkChatDeliveryTest(
                                                                        context.getSource()
                                                                )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("status")
                                                        .executes(context ->
                                                                showNetworkChatDeliveryTest(
                                                                        context.getSource(),
                                                                        null
                                                                )
                                                        )
                                                        .then(
                                                                Commands.argument(
                                                                                "testId",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(context ->
                                                                                showNetworkChatDeliveryTest(
                                                                                        context.getSource(),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "testId"
                                                                                        )
                                                                                )
                                                                        )
                                                        )
                                        )
                        )

                        .then(
                                Commands.literal("applyrestart")
                                        .executes(context ->
                                                showPendingApplyRestartStatus(
                                                        context.getSource()
                                                )
                                        )
                                        .then(
                                                Commands.literal("status")
                                                        .executes(context ->
                                                                showPendingApplyRestartStatus(
                                                                        context.getSource()
                                                                )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("confirm")
                                                        .then(
                                                                Commands.argument(
                                                                                "code",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(context ->
                                                                                confirmPendingApplyRestart(
                                                                                        context.getSource(),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "code"
                                                                                        )
                                                                                )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("cancel")
                                                        .executes(context ->
                                                                cancelPendingApplyRestart(
                                                                        context.getSource()
                                                                )
                                                        )
                                        )
                        )

                        .then(
                                Commands.literal("nodes")
                                        .executes(context ->
                                                showNodes(
                                                        context.getSource()
                                                )
                                        )
                        )

                        .then(
                                Commands.literal("drain")
                                        .then(
                                                Commands.literal("preview")
                                                        .then(
                                                                Commands.argument(
                                                                                "targetNode",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(context ->
                                                                                previewNodeDrain(
                                                                                        context.getSource(),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "targetNode"
                                                                                        )
                                                                                )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("evacuate")
                                                        .then(
                                                                Commands.argument(
                                                                                "targetNode",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .then(
                                                                                Commands.argument(
                                                                                                "dimension",
                                                                                                ResourceLocationArgument.id()
                                                                                        )
                                                                                        .then(
                                                                                                Commands.argument(
                                                                                                                "x",
                                                                                                                DoubleArgumentType.doubleArg()
                                                                                                        )
                                                                                                        .then(
                                                                                                                Commands.argument(
                                                                                                                                "y",
                                                                                                                                DoubleArgumentType.doubleArg()
                                                                                                                        )
                                                                                                                        .then(
                                                                                                                                Commands.argument(
                                                                                                                                                "z",
                                                                                                                                                DoubleArgumentType.doubleArg()
                                                                                                                                        )
                                                                                                                                        .executes(context ->
                                                                                                                                                evacuateNodeDrainPlayers(
                                                                                                                                                        context.getSource(),
                                                                                                                                                        StringArgumentType.getString(
                                                                                                                                                                context,
                                                                                                                                                                "targetNode"
                                                                                                                                                        ),
                                                                                                                                                        ResourceLocationArgument.getId(
                                                                                                                                                                context,
                                                                                                                                                                "dimension"
                                                                                                                                                        ).toString(),
                                                                                                                                                        DoubleArgumentType.getDouble(
                                                                                                                                                                context,
                                                                                                                                                                "x"
                                                                                                                                                        ),
                                                                                                                                                        DoubleArgumentType.getDouble(
                                                                                                                                                                context,
                                                                                                                                                                "y"
                                                                                                                                                        ),
                                                                                                                                                        DoubleArgumentType.getDouble(
                                                                                                                                                                context,
                                                                                                                                                                "z"
                                                                                                                                                        )
                                                                                                                                                )
                                                                                                                                        )
                                                                                                                        )
                                                                                                        )
                                                                                        )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("start")
                                                        .then(
                                                                Commands.argument(
                                                                                "targetNode",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(context ->
                                                                                prepareNodeDrain(
                                                                                        context.getSource(),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "targetNode"
                                                                                        )
                                                                                )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("status")
                                                        .executes(context ->
                                                                showNodeDrains(
                                                                        context.getSource()
                                                                )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("cancel")
                                                        .then(
                                                                Commands.argument(
                                                                                "drainId",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(context ->
                                                                                cancelNodeDrain(
                                                                                        context.getSource(),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "drainId"
                                                                                        )
                                                                                )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("retry")
                                                        .then(
                                                                Commands.argument(
                                                                                "drainId",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(context ->
                                                                                retryNodeDrain(
                                                                                        context.getSource(),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "drainId"
                                                                                        )
                                                                                )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("resume")
                                                        .then(
                                                                Commands.argument(
                                                                                "drainId",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(context ->
                                                                                resumeNodeDrain(
                                                                                        context.getSource(),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "drainId"
                                                                                        )
                                                                                )
                                                                        )
                                                        )
                                        )
                        )

                        .then(
                                Commands.literal("rebalance")
                                        .then(
                                                Commands.literal("preview")
                                                        .then(
                                                                Commands.argument(
                                                                                "targetNode",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(context ->
                                                                                previewSafeRebalance(
                                                                                        context.getSource(),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "targetNode"
                                                                                        )
                                                                                )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("prepare")
                                                        .then(
                                                                Commands.argument(
                                                                                "targetNode",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(context ->
                                                                                prepareSafeRebalance(
                                                                                        context.getSource(),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "targetNode"
                                                                                        )
                                                                                )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("status")
                                                        .executes(context ->
                                                                showSafeRebalances(
                                                                        context.getSource()
                                                                )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("cancel")
                                                        .then(
                                                                Commands.argument(
                                                                                "operationId",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(context ->
                                                                                cancelSafeRebalance(
                                                                                        context.getSource(),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "operationId"
                                                                                        )
                                                                                )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("retry")
                                                        .then(
                                                                Commands.argument(
                                                                                "operationId",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(context ->
                                                                                retrySafeRebalance(
                                                                                        context.getSource(),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "operationId"
                                                                                        )
                                                                                )
                                                                        )
                                                        )
                                        )
                        )

                        .then(
                                Commands.literal("failover")
                                        .executes(context ->
                                                showDimensionFailovers(
                                                        context.getSource()
                                                )
                                        )
                                        .then(
                                                Commands.literal("preview")
                                                        .then(
                                                                Commands.argument(
                                                                                "sourceNode",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(context ->
                                                                                previewDimensionFailover(
                                                                                        context.getSource(),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "sourceNode"
                                                                                        )
                                                                                )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("execute")
                                                        .then(
                                                                Commands.argument(
                                                                                "sourceNode",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(context ->
                                                                                executeDimensionFailover(
                                                                                        context.getSource(),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "sourceNode"
                                                                                        )
                                                                                )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("status")
                                                        .executes(context ->
                                                                showDimensionFailovers(
                                                                        context.getSource()
                                                                )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("watch")
                                                        .executes(context ->
                                                                showAutomaticFailoverWatch(
                                                                        context.getSource()
                                                                )
                                                        )
                                        )
                        )

                        .then(
                                Commands.literal("failback")
                                        .then(
                                                Commands.literal("preview")
                                                        .then(
                                                                Commands.argument(
                                                                                "recoveredNode",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(context ->
                                                                                previewDimensionFailback(
                                                                                        context.getSource(),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "recoveredNode"
                                                                                        )
                                                                                )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("prepare")
                                                        .then(
                                                                Commands.argument(
                                                                                "recoveredNode",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(context ->
                                                                                prepareDimensionFailback(
                                                                                        context.getSource(),
                                                                                        StringArgumentType.getString(
                                                                                                context,
                                                                                                "recoveredNode"
                                                                                        )
                                                                                )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("status")
                                                        .executes(context ->
                                                                showDimensionFailbacks(
                                                                        context.getSource()
                                                                )
                                                        )
                                        )
                        )

                        .then(
                                Commands.literal("snapshots")
                                        .then(
                                                Commands.literal("create")
                                                        .then(
                                                                Commands.argument(
                                                                                "dimension",
                                                                                ResourceLocationArgument.id()
                                                                        )
                                                                        .executes(context ->
                                                                                createDimensionSnapshot(
                                                                                        context.getSource(),
                                                                                        ResourceLocationArgument.getId(
                                                                                                context,
                                                                                                "dimension"
                                                                                        ).toString()
                                                                                )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("createall")
                                                        .executes(context ->
                                                                createAllDimensionSnapshots(
                                                                        context.getSource()
                                                                )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("cleanup")
                                                        .executes(context ->
                                                                cleanupDimensionSnapshots(
                                                                        context.getSource()
                                                                )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("schedule")
                                                        .executes(context ->
                                                                showDimensionSnapshotSchedule(
                                                                        context.getSource()
                                                                )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("status")
                                                        .executes(context ->
                                                                showDimensionSnapshots(
                                                                        context.getSource()
                                                                )
                                                        )
                                        )
                        )

                        .then(
                                Commands.literal("transfer")
                                        .then(
                                                Commands.argument(
                                                                "targetNode",
                                                                StringArgumentType.word()
                                                        )
                                                        .executes(
                                                                context ->
                                                                        queueTransfer(
                                                                                context.getSource(),
                                                                                StringArgumentType.getString(
                                                                                        context,
                                                                                        "targetNode"
                                                                                )
                                                                        )
                                                        )
                                        )
                        )

                        .then(
                                Commands.literal("transferpos")
                                        .then(
                                                Commands.argument(
                                                                "targetNode",
                                                                StringArgumentType.word()
                                                        )
                                                        .then(
                                                                Commands.argument(
                                                                                "dimension",
                                                                                ResourceLocationArgument.id()
                                                                        )
                                                                        .then(
                                                                                Commands.argument(
                                                                                                "x",
                                                                                                DoubleArgumentType.doubleArg()
                                                                                        )
                                                                                        .then(
                                                                                                Commands.argument(
                                                                                                                "y",
                                                                                                                DoubleArgumentType.doubleArg()
                                                                                                        )
                                                                                                        .then(
                                                                                                                Commands.argument(
                                                                                                                                "z",
                                                                                                                                DoubleArgumentType.doubleArg()
                                                                                                                        )
                                                                                                                        .executes(
                                                                                                                                context ->
                                                                                                                                        queueTransferToPosition(
                                                                                                                                                context.getSource(),
                                                                                                                                                StringArgumentType.getString(
                                                                                                                                                        context,
                                                                                                                                                        "targetNode"
                                                                                                                                                ),
                                                                                                                                                ResourceLocationArgument.getId(
                                                                                                                                                        context,
                                                                                                                                                        "dimension"
                                                                                                                                                ).toString(),
                                                                                                                                                DoubleArgumentType.getDouble(
                                                                                                                                                        context,
                                                                                                                                                        "x"
                                                                                                                                                ),
                                                                                                                                                DoubleArgumentType.getDouble(
                                                                                                                                                        context,
                                                                                                                                                        "y"
                                                                                                                                                ),
                                                                                                                                                DoubleArgumentType.getDouble(
                                                                                                                                                        context,
                                                                                                                                                        "z"
                                                                                                                                                )
                                                                                                                                        )
                                                                                                                        )
                                                                                                        )
                                                                                        )
                                                                        )
                                                        )
                                        )
                        )

                        .then(
                                Commands.literal("dimension")
                                        .then(
                                                Commands.literal("assign")
                                                        .then(
                                                                Commands.argument(
                                                                                "dimension",
                                                                                ResourceLocationArgument.id()
                                                                        )
                                                                        .then(
                                                                                Commands.argument(
                                                                                                "node",
                                                                                                StringArgumentType.word()
                                                                                        )
                                                                                        .executes(
                                                                                                context ->
                                                                                                        assignDimensionOwner(
                                                                                                                context.getSource(),
                                                                                                                ResourceLocationArgument.getId(
                                                                                                                        context,
                                                                                                                        "dimension"
                                                                                                                ).toString(),
                                                                                                                StringArgumentType.getString(
                                                                                                                        context,
                                                                                                                        "node"
                                                                                                                )
                                                                                                        )
                                                                                        )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("owner")
                                                        .then(
                                                                Commands.argument(
                                                                                "dimension",
                                                                                ResourceLocationArgument.id()
                                                                        )
                                                                        .executes(
                                                                                context ->
                                                                                        showDimensionOwner(
                                                                                                context.getSource(),
                                                                                                ResourceLocationArgument.getId(
                                                                                                        context,
                                                                                                        "dimension"
                                                                                                ).toString()
                                                                                        )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("autoassign")
                                                        .then(
                                                                Commands.argument(
                                                                                "dimension",
                                                                                ResourceLocationArgument.id()
                                                                        )
                                                                        .executes(
                                                                                context ->
                                                                                        autoAssignDimensionOwner(
                                                                                                context.getSource(),
                                                                                                ResourceLocationArgument.getId(
                                                                                                        context,
                                                                                                        "dimension"
                                                                                                ).toString()
                                                                                        )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("pin")
                                                        .then(
                                                                Commands.argument(
                                                                                "dimension",
                                                                                ResourceLocationArgument.id()
                                                                        )
                                                                        .then(
                                                                                Commands.argument(
                                                                                                "node",
                                                                                                StringArgumentType.word()
                                                                                        )
                                                                                        .executes(
                                                                                                context ->
                                                                                                        pinDimensionOwner(
                                                                                                                context.getSource(),
                                                                                                                ResourceLocationArgument.getId(
                                                                                                                        context,
                                                                                                                        "dimension"
                                                                                                                ).toString(),
                                                                                                                StringArgumentType.getString(
                                                                                                                        context,
                                                                                                                        "node"
                                                                                                                )
                                                                                                        )
                                                                                        )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("unpin")
                                                        .then(
                                                                Commands.argument(
                                                                                "dimension",
                                                                                ResourceLocationArgument.id()
                                                                        )
                                                                        .executes(
                                                                                context ->
                                                                                        unpinDimensionOwner(
                                                                                                context.getSource(),
                                                                                                ResourceLocationArgument.getId(
                                                                                                        context,
                                                                                                        "dimension"
                                                                                                ).toString()
                                                                                        )
                                                                        )
                                                        )
                                        )
                        )

                        .then(
                                Commands.literal("dimensions")
                                        .then(
                                                Commands.literal("list")
                                                        .executes(
                                                                context ->
                                                                        showDimensions(
                                                                                context.getSource(),
                                                                                1
                                                                        )
                                                        )
                                                        .then(
                                                                Commands.argument(
                                                                                "page",
                                                                                IntegerArgumentType.integer(1)
                                                                        )
                                                                        .executes(
                                                                                context ->
                                                                                        showDimensions(
                                                                                                context.getSource(),
                                                                                                IntegerArgumentType.getInteger(
                                                                                                        context,
                                                                                                        "page"
                                                                                                )
                                                                                        )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("forget")
                                                        .then(
                                                                Commands.argument(
                                                                                "dimension",
                                                                                ResourceLocationArgument.id()
                                                                        )
                                                                        .executes(
                                                                                context ->
                                                                                        forgetDimensionAssignment(
                                                                                                context.getSource(),
                                                                                                ResourceLocationArgument.getId(
                                                                                                        context,
                                                                                                        "dimension"
                                                                                                ).toString(),
                                                                                                false
                                                                                        )
                                                                        )
                                                                        .then(
                                                                                Commands.literal("confirm")
                                                                                        .executes(
                                                                                                context ->
                                                                                                        forgetDimensionAssignment(
                                                                                                                context.getSource(),
                                                                                                                ResourceLocationArgument.getId(
                                                                                                                        context,
                                                                                                                        "dimension"
                                                                                                                ).toString(),
                                                                                                                true
                                                                                                        )
                                                                                        )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("preview")
                                                        .executes(
                                                                context ->
                                                                        runDimensionPlan(
                                                                                context.getSource(),
                                                                                true,
                                                                                false
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("autoassign")
                                                        .executes(
                                                                context ->
                                                                        runDimensionPlan(
                                                                                context.getSource(),
                                                                                false,
                                                                                true
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("rebalance")
                                                        .executes(
                                                                context ->
                                                                        runDimensionPlan(
                                                                                context.getSource(),
                                                                                true,
                                                                                true
                                                                        )
                                                        )
                                        )
                        )

                        .then(
                                Commands.literal("migration")
                                        .then(
                                                Commands.literal("prepare")
                                                        .then(
                                                                Commands.argument(
                                                                                "dimension",
                                                                                ResourceLocationArgument.id()
                                                                        )
                                                                        .then(
                                                                                Commands.argument(
                                                                                                "targetNode",
                                                                                                StringArgumentType.word()
                                                                                        )
                                                                                        .executes(
                                                                                                context ->
                                                                                                        prepareDimensionMigration(
                                                                                                                context.getSource(),
                                                                                                                ResourceLocationArgument.getId(
                                                                                                                        context,
                                                                                                                        "dimension"
                                                                                                                ).toString(),
                                                                                                                StringArgumentType.getString(
                                                                                                                        context,
                                                                                                                        "targetNode"
                                                                                                                )
                                                                                                        )
                                                                                        )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("status")
                                                        .executes(
                                                                context ->
                                                                        showDimensionMigrations(
                                                                                context.getSource()
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("history")
                                                        .executes(
                                                                context ->
                                                                        showDimensionMigrationHistory(
                                                                                context.getSource()
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("pending")
                                                        .executes(
                                                                context ->
                                                                        showPendingDimensionMigrations(
                                                                                context.getSource()
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("verify")
                                                        .then(
                                                                Commands.argument(
                                                                                "migrationId",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(
                                                                                context ->
                                                                                        verifyDimensionMigration(
                                                                                                context.getSource(),
                                                                                                StringArgumentType.getString(
                                                                                                        context,
                                                                                                        "migrationId"
                                                                                                )
                                                                                        )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("finalize")
                                                        .then(
                                                                Commands.argument(
                                                                                "migrationId",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(
                                                                                context ->
                                                                                        finalizeDimensionMigration(
                                                                                                context.getSource(),
                                                                                                StringArgumentType.getString(
                                                                                                        context,
                                                                                                        "migrationId"
                                                                                                )
                                                                                        )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("rollback")
                                                        .then(
                                                                Commands.argument(
                                                                                "migrationId",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(
                                                                                context ->
                                                                                        rollbackDimensionMigration(
                                                                                                context.getSource(),
                                                                                                StringArgumentType.getString(
                                                                                                        context,
                                                                                                        "migrationId"
                                                                                                )
                                                                                        )
                                                                        )
                                                        )
                                        )
                                        .then(
                                                Commands.literal("cancel")
                                                        .then(
                                                                Commands.argument(
                                                                                "migrationId",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .executes(
                                                                                context ->
                                                                                        cancelDimensionMigration(
                                                                                                context.getSource(),
                                                                                                StringArgumentType.getString(
                                                                                                        context,
                                                                                                        "migrationId"
                                                                                                )
                                                                                        )
                                                                        )
                                                        )
                                        )
                        )

                        .then(
                                Commands.literal("transferdimension")
                                        .then(
                                                Commands.argument(
                                                                "dimension",
                                                                ResourceLocationArgument.id()
                                                        )
                                                        .then(
                                                                Commands.argument(
                                                                                "x",
                                                                                DoubleArgumentType.doubleArg()
                                                                        )
                                                                        .then(
                                                                                Commands.argument(
                                                                                                "y",
                                                                                                DoubleArgumentType.doubleArg()
                                                                                        )
                                                                                        .then(
                                                                                                Commands.argument(
                                                                                                                "z",
                                                                                                                DoubleArgumentType.doubleArg()
                                                                                                        )
                                                                                                        .executes(
                                                                                                                context ->
                                                                                                                        queueTransferToAssignedDimension(
                                                                                                                                context.getSource(),
                                                                                                                                ResourceLocationArgument.getId(
                                                                                                                                        context,
                                                                                                                                        "dimension"
                                                                                                                                ).toString(),
                                                                                                                                DoubleArgumentType.getDouble(
                                                                                                                                        context,
                                                                                                                                        "x"
                                                                                                                                ),
                                                                                                                                DoubleArgumentType.getDouble(
                                                                                                                                        context,
                                                                                                                                        "y"
                                                                                                                                ),
                                                                                                                                DoubleArgumentType.getDouble(
                                                                                                                                        context,
                                                                                                                                        "z"
                                                                                                                                )
                                                                                                                        )
                                                                                                        )
                                                                                        )
                                                                        )
                                                        )
                                        )
                        )
        );
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(
            PlayerEvent.PlayerLoggedInEvent event
    ) {
        if (!(event.getEntity()
                instanceof ServerPlayer player)) {
            return;
        }

        ClusterConfig currentConfig = config;

        if (currentConfig == null
                || !currentConfig.enabled()) {
            return;
        }

        MinecraftServer server = player.getServer();

        if (server == null) {
            return;
        }

        UUID playerUuid = player.getUUID();
        String loginDimensionId =
                player.level()
                        .dimension()
                        .location()
                        .toString();

        if (!ClusterTransferGuard.lock(
                player,
                currentConfig.transferLockTimeoutSeconds(),
                "Проверка кластерной сессии"
        )) {
            return;
        }

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig =
                        ClusterConfig.load();

                config = latestConfig;

                if (!latestConfig.enabled()) {
                    scheduleRouteFailure(
                            server,
                            playerUuid,
                            "Кластерная система отключена."
                    );
                    return;
                }

                ClusterDatabase.PendingTransfer transfer =
                        ClusterDatabase.claimPendingTransfer(
                                latestConfig,
                                playerUuid
                        );

                if (transfer != null) {
                    LOGGER.info(
                            "Claimed transfer {} for player {}: {} -> {}",
                            transfer.transferId(),
                            transfer.playerUuid(),
                            transfer.sourceNode(),
                            transfer.targetNode()
                    );

                    ClusterTransferGuard.updateReason(
                            playerUuid,
                            "Применение данных межсерверного перехода"
                    );

                    server.execute(
                            () -> applyTransfer(
                                    server,
                                    latestConfig,
                                    transfer
                            )
                    );

                    return;
                }

                ClusterDatabase.PlayerSessionAcquireResult sessionResult =
                        ClusterDatabase.acquirePlayerSession(
                                latestConfig,
                                playerUuid
                        );

                if (!sessionResult.acquired()) {
                    String recoveryRedirectAddress = null;

                    if (sessionResult.targetNode() != null
                            && !sessionResult.targetNode()
                            .equalsIgnoreCase(latestConfig.nodeId())) {
                        try {
                            recoveryRedirectAddress =
                                    ClusterDatabase.findOnlineRedirectAddress(
                                            latestConfig,
                                            sessionResult.targetNode()
                                    );
                        } catch (Exception redirectLookupException) {
                            LOGGER.warn(
                                    "Unable to resolve recovery redirect for player {} to node {}",
                                    playerUuid,
                                    sessionResult.targetNode(),
                                    redirectLookupException
                            );
                        }
                    }

                    scheduleSessionRejected(
                            server,
                            playerUuid,
                            sessionResult,
                            recoveryRedirectAddress
                    );
                    return;
                }

                ClusterDatabase.RecoveryBackup recoveryBackup =
                        ClusterDatabase.findRecoveryBackup(
                                latestConfig,
                                playerUuid
                        );

                String dimensionOwner =
                        ClusterDatabase.findDimensionOwner(
                                latestConfig,
                                loginDimensionId
                        );

                if (dimensionOwner == null) {
                    removeCachedDimensionOwner(
                            loginDimensionId
                    );
                } else {
                    updateCachedDimensionOwner(
                            loginDimensionId,
                            dimensionOwner
                    );
                }

                String finalDimensionOwner = dimensionOwner;
                server.execute(
                        () -> finishLoginSessionCheck(
                                server,
                                latestConfig,
                                playerUuid,
                                loginDimensionId,
                                finalDimensionOwner,
                                sessionResult,
                                recoveryBackup
                        )
                );
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to process cluster login for player {}",
                        playerUuid,
                        exception
                );

                scheduleTransferError(
                        server,
                        playerUuid,
                        exception
                );
            }
        });
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(
            PlayerEvent.PlayerLoggedOutEvent event
    ) {
        if (!(event.getEntity()
                instanceof ServerPlayer player)) {
            return;
        }

        ClusterTransferGuard.unlock(player.getUUID());
        DIMENSION_ROUTE_SUPPRESSIONS.remove(player.getUUID());

        ClusterConfig currentConfig = config;

        if (currentConfig == null
                || !currentConfig.enabled()) {
            return;
        }

        UUID playerUuid = player.getUUID();

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.releasePlayerSession(
                        currentConfig,
                        playerUuid
                );
            } catch (Exception exception) {
                LOGGER.warn(
                        "Unable to release cluster player session for {} on node {}",
                        playerUuid,
                        currentConfig.nodeId(),
                        exception
                );
            }
        });
    }

    private void finishLoginSessionCheck(
            MinecraftServer server,
            ClusterConfig currentConfig,
            UUID playerUuid,
            String loginDimensionId,
            String dimensionOwner,
            ClusterDatabase.PlayerSessionAcquireResult sessionResult,
            ClusterDatabase.RecoveryBackup recoveryBackup
    ) {
        ServerPlayer player = server.getPlayerList()
                .getPlayer(playerUuid);

        if (player == null) {
            releaseSessionAfterLoginAbort(
                    currentConfig,
                    playerUuid
            );
            return;
        }

        if (sessionResult.recovered()) {
            player.sendSystemMessage(
                    Component.literal(
                            "§eКластерная сессия восстановлена после узла §f"
                                    + sessionResult.previousOwnerNode()
                                    + "§e (предыдущее состояние: §f"
                                    + sessionResult.state()
                                    + "§e)."
                    )
            );
        }

        if (recoveryBackup != null) {
            applyRecoveryBackup(
                    server,
                    currentConfig,
                    playerUuid,
                    loginDimensionId,
                    dimensionOwner,
                    recoveryBackup
            );
            return;
        }

        continueLoginSessionCheck(
                server,
                currentConfig,
                playerUuid,
                loginDimensionId,
                dimensionOwner
        );
    }

    private void applyRecoveryBackup(
            MinecraftServer server,
            ClusterConfig currentConfig,
            UUID playerUuid,
            String loginDimensionId,
            String dimensionOwner,
            ClusterDatabase.RecoveryBackup recoveryBackup
    ) {
        ServerPlayer player = server.getPlayerList()
                .getPlayer(playerUuid);

        if (player == null) {
            releaseSessionAfterLoginAbort(
                    currentConfig,
                    playerUuid
            );
            return;
        }

        ClusterTransferGuard.updateReason(
                playerUuid,
                "Восстановление резервной копии игрока"
        );

        ClusterPlayerDataCodec.ApplyResult applyResult;

        try {
            if (recoveryBackup.playerData() == null
                    || recoveryBackup.playerData().length == 0) {
                throw new IllegalStateException(
                        "Резервная копия не содержит player-data"
                );
            }

            if (recoveryBackup.playerDataSize()
                    != recoveryBackup.playerData().length) {
                throw new IllegalStateException(
                        "Размер резервной копии не совпадает: declared="
                                + recoveryBackup.playerDataSize()
                                + ", actual="
                                + recoveryBackup.playerData().length
                );
            }

            applyResult = ClusterPlayerDataCodec.apply(
                    player,
                    recoveryBackup.transferId(),
                    recoveryBackup.playerDataCodec(),
                    recoveryBackup.playerData(),
                    recoveryBackup.playerDataSha256(),
                    currentConfig.maxPlayerDataBytes()
            );

            server.getPlayerList().saveAll();
        } catch (Exception exception) {
            LOGGER.error(
                    "Unable to restore player backup {} for {}",
                    recoveryBackup.backupId(),
                    playerUuid,
                    exception
            );

            player.connection.disconnect(
                    Component.literal(
                            "Не удалось восстановить резервную копию данных игрока: "
                                    + exception.getMessage()
                                    + "\nОбратитесь к администратору; backup не удалён."
                    )
            );
            return;
        }

        ClusterTransferGuard.updateReason(
                playerUuid,
                "Фиксация восстановленной резервной копии"
        );

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.markRecoveryBackupRestored(
                        currentConfig,
                        recoveryBackup.backupId(),
                        playerUuid
                );

                server.execute(() -> {
                    ServerPlayer onlinePlayer = server.getPlayerList()
                            .getPlayer(playerUuid);

                    if (onlinePlayer == null) {
                        releaseSessionAfterLoginAbort(
                                currentConfig,
                                playerUuid
                        );
                        return;
                    }

                    onlinePlayer.sendSystemMessage(
                            Component.literal(
                                    "§eВосстановлена резервная копия transfer §f"
                                            + recoveryBackup.transferId()
                                            + "§e с узла §f"
                                            + recoveryBackup.sourceNode()
                                            + formatPlayerDataApplyMessage(
                                            applyResult
                                    )
                            )
                    );

                    continueLoginSessionCheck(
                            server,
                            currentConfig,
                            playerUuid,
                            loginDimensionId,
                            dimensionOwner
                    );
                });
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to mark player backup {} as restored",
                        recoveryBackup.backupId(),
                        exception
                );

                server.execute(() -> {
                    ServerPlayer onlinePlayer = server.getPlayerList()
                            .getPlayer(playerUuid);

                    if (onlinePlayer != null) {
                        onlinePlayer.connection.disconnect(
                                Component.literal(
                                        "Резервная копия применена, но не была "
                                                + "зафиксирована в базе. Повторите вход "
                                                + "через несколько секунд."
                                )
                        );
                    } else {
                        ClusterTransferGuard.unlock(playerUuid);
                    }
                });
            }
        });
    }

    private void continueLoginSessionCheck(
            MinecraftServer server,
            ClusterConfig currentConfig,
            UUID playerUuid,
            String loginDimensionId,
            String dimensionOwner
    ) {
        ServerPlayer player = server.getPlayerList()
                .getPlayer(playerUuid);

        if (player == null) {
            releaseSessionAfterLoginAbort(
                    currentConfig,
                    playerUuid
            );
            return;
        }

        if (DIMENSION_MIGRATION_BLOCKED.contains(loginDimensionId)
                || DIMENSION_SNAPSHOT_FROZEN.contains(loginDimensionId)) {
            ClusterTransferGuard.unlock(player);
            player.connection.disconnect(
                    Component.literal(
                            "Измерение "
                                    + loginDimensionId
                                    + " временно недоступно: выполняется безопасная migration."
                    )
            );
            return;
        }

        if (dimensionOwner == null
                || dimensionOwner.equalsIgnoreCase(
                currentConfig.nodeId()
        )) {
            ClusterTransferGuard.unlock(player);
            return;
        }

        queueWrongNodeLoginTransfer(
                server,
                currentConfig,
                player,
                loginDimensionId,
                dimensionOwner
        );
    }

    private void queueWrongNodeLoginTransfer(
            MinecraftServer server,
            ClusterConfig currentConfig,
            ServerPlayer player,
            String dimensionId,
            String targetNode
    ) {
        UUID playerUuid = player.getUUID();
        String playerName = player.getGameProfile().getName();

        ClusterTransferGuard.updateReason(
                playerUuid,
                "Автоматический переход на владельца измерения"
        );

        ClusterPlayerDataCodec.Snapshot playerData;

        try {
            playerData = capturePlayerDataForTransfer(
                    player,
                    currentConfig
            );
        } catch (Exception exception) {
            LOGGER.error(
                    "Unable to capture player data for wrong-node login handoff {}",
                    playerUuid,
                    exception
            );
            ClusterTransferGuard.unlock(player);
            player.connection.disconnect(
                    Component.literal(
                            "Не удалось подготовить автоматический переход на узел "
                                    + targetNode
                                    + ": "
                                    + exception.getMessage()
                    )
            );
            return;
        }

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYRot();
        float pitch = player.getXRot();

        player.sendSystemMessage(
                Component.literal(
                        "§eИзмерение §f"
                                + dimensionId
                                + "§e принадлежит узлу §f"
                                + targetNode
                                + "§e. Выполняю автоматический кластерный переход."
                )
        );

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;

                String latestOwner = ClusterDatabase.findDimensionOwner(
                        latestConfig,
                        dimensionId
                );

                if (latestOwner == null) {
                    scheduleRouteFailure(
                            server,
                            playerUuid,
                            "Для dimension "
                                    + dimensionId
                                    + " владелец не назначен."
                    );
                    return;
                }

                updateCachedDimensionOwner(
                        dimensionId,
                        latestOwner
                );

                if (latestOwner.equalsIgnoreCase(latestConfig.nodeId())) {
                    server.execute(() -> {
                        ServerPlayer onlinePlayer = server.getPlayerList()
                                .getPlayer(playerUuid);
                        if (onlinePlayer != null) {
                            ClusterTransferGuard.unlock(onlinePlayer);
                        } else {
                            ClusterTransferGuard.unlock(playerUuid);
                        }
                    });
                    return;
                }

                createTransferAndScheduleRedirect(
                        server,
                        latestConfig,
                        playerUuid,
                        playerName,
                        latestOwner,
                        dimensionId,
                        x,
                        y,
                        z,
                        yaw,
                        pitch,
                        playerData
                );
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to create wrong-node login transfer for player {}",
                        playerUuid,
                        exception
                );
                scheduleTransferError(
                        server,
                        playerUuid,
                        exception
                );
            }
        });
    }

    private void releaseSessionAfterLoginAbort(
            ClusterConfig currentConfig,
            UUID playerUuid
    ) {
        ClusterTransferGuard.unlock(playerUuid);
        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.releasePlayerSession(
                        currentConfig,
                        playerUuid
                );
            } catch (Exception exception) {
                LOGGER.warn(
                        "Unable to release session after player {} left during login processing",
                        playerUuid,
                        exception
                );
            }
        });
    }

    private void scheduleSessionRejected(
            MinecraftServer server,
            UUID playerUuid,
            ClusterDatabase.PlayerSessionAcquireResult result,
            String recoveryRedirectAddress
    ) {
        server.execute(() -> {
            ServerPlayer player = server.getPlayerList()
                    .getPlayer(playerUuid);

            if (player == null) {
                ClusterTransferGuard.unlock(playerUuid);
                return;
            }

            String transferDetails = result.transferId() == null
                    ? ""
                    : "\nTransfer: " + result.transferId()
                    + (result.targetNode() == null
                    ? ""
                    : " -> " + result.targetNode());

            if (recoveryRedirectAddress != null
                    && !recoveryRedirectAddress.isBlank()) {
                String playerName = player.getGameProfile().getName();
                String redirectCommand = "redirect "
                        + playerName
                        + " "
                        + recoveryRedirectAddress;

                player.sendSystemMessage(
                        Component.literal(
                                "§eНайдена незавершённая кластерная сессия на узле §f"
                                        + result.targetNode()
                                        + "§e. Повторно отправляю redirect для завершения transfer."
                                        + transferDetails
                                        + "\n§7Не отключайтесь: модифицированное подключение "
                                        + "может занять некоторое время."
                        )
                );

                if (server.getCommands()
                        .getDispatcher()
                        .getRoot()
                        .getChild("redirect") == null) {
                    LOGGER.error(
                            "Recovery redirect command is not registered: {}",
                            redirectCommand
                    );
                } else {
                    ClusterTransferGuard.updateReason(
                            playerUuid,
                            "Восстановление незавершённого межсерверного перехода"
                    );

                    try {
                        int redirectResult = server.getCommands()
                                .performPrefixedCommand(
                                        server.createCommandSourceStack(),
                                        redirectCommand
                                );

                        








                        if (redirectResult <= 0) {
                            LOGGER.warn(
                                    "Recovery redirect command returned {} but was dispatched; keeping player {} connected while transfer {} continues to node {}: {}",
                                    redirectResult,
                                    playerUuid,
                                    result.transferId(),
                                    result.targetNode(),
                                    redirectCommand
                            );
                        } else {
                            LOGGER.info(
                                    "Redirected player {} to recover active transfer {} on node {} with result {}",
                                    playerUuid,
                                    result.transferId(),
                                    result.targetNode(),
                                    redirectResult
                            );
                        }

                        return;
                    } catch (Exception exception) {
                        LOGGER.error(
                                "Recovery redirect command threw an exception for player {}: {}",
                                playerUuid,
                                redirectCommand,
                                exception
                        );
                    }
                }
            }

            player.connection.disconnect(
                    Component.literal(
                            "Данные этого игрока уже используются узлом "
                                    + result.ownerNode()
                                    + " (state="
                                    + result.state()
                                    + ")."
                                    + transferDetails
                                    + "\nПовторите вход после завершения перехода "
                                    + "или истечения lease."
                    )
            );
        });
    }

    @SubscribeEvent
    public void onPlayerChangedDimension(
            PlayerEvent.PlayerChangedDimensionEvent event
    ) {
        if (!(event.getEntity()
                instanceof ServerPlayer player)) {
            return;
        }

        ClusterConfig currentConfig = config;

        if (currentConfig == null
                || !currentConfig.enabled()) {
            return;
        }

        String dimensionId =
                event.getTo()
                        .location()
                        .toString();

        UUID playerUuid = player.getUUID();

        if (DIMENSION_MIGRATION_BLOCKED.contains(dimensionId)
                || DIMENSION_SNAPSHOT_FROZEN.contains(dimensionId)) {
            player.connection.disconnect(
                    Component.literal(
                            "Измерение "
                                    + dimensionId
                                    + " временно недоступно: выполняется безопасная migration."
                    )
            );
            return;
        }

        if (ClusterTransferGuard.isLocked(player)
                || isDimensionRouteSuppressed(
                playerUuid,
                dimensionId
        )) {
            LOGGER.debug(
                    "Suppressed dimension route for player {} to {} while applying an incoming transfer",
                    playerUuid,
                    dimensionId
            );
            return;
        }

        String cachedOwner =
                DIMENSION_OWNER_CACHE.get(dimensionId);

        if (cachedOwner == null
                && !currentConfig.failClosedRouting()) {
            return;
        }

        MinecraftServer server = player.getServer();

        if (server == null) {
            return;
        }

        String playerName =
                player.getGameProfile().getName();
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYRot();
        float pitch = player.getXRot();

        if (!ClusterTransferGuard.lock(
                player,
                currentConfig.transferLockTimeoutSeconds(),
                "Проверка владельца измерения"
        )) {
            return;
        }

        ClusterPlayerDataCodec.Snapshot playerData;

        try {
            playerData = capturePlayerDataForTransfer(
                    player,
                    currentConfig
            );
        } catch (Exception exception) {
            LOGGER.error(
                    "Unable to capture player data after dimension change for {}",
                    playerUuid,
                    exception
            );

            ClusterTransferGuard.unlock(player);
            player.sendSystemMessage(
                    Component.literal(
                            "§cКластерный переход отменён: не удалось сохранить данные игрока: "
                                    + exception.getMessage()
                    )
            );

            return;
        }

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig =
                        ClusterConfig.load();

                config = latestConfig;

                if (!latestConfig.enabled()) {
                    scheduleRouteFailure(
                            server,
                            playerUuid,
                            "Кластерная система отключена."
                    );
                    return;
                }

                String owner =
                        ClusterDatabase.findDimensionOwner(
                                latestConfig,
                                dimensionId
                        );

                if (owner == null) {
                    removeCachedDimensionOwner(
                            dimensionId
                    );
                    server.execute(() -> {
                        ServerPlayer onlinePlayer = server
                                .getPlayerList()
                                .getPlayer(playerUuid);
                        if (onlinePlayer != null) {
                            ClusterTransferGuard.unlock(onlinePlayer);
                        } else {
                            ClusterTransferGuard.unlock(playerUuid);
                        }
                    });

                    return;
                }

                updateCachedDimensionOwner(
                        dimensionId,
                        owner
                );

                if (owner.equalsIgnoreCase(
                        latestConfig.nodeId()
                )) {
                    server.execute(() -> {
                        ServerPlayer onlinePlayer = server
                                .getPlayerList()
                                .getPlayer(playerUuid);
                        if (onlinePlayer != null) {
                            ClusterTransferGuard.unlock(onlinePlayer);
                        } else {
                            ClusterTransferGuard.unlock(playerUuid);
                        }
                    });
                    return;
                }

                LOGGER.warn(
                        "Player {} entered dimension {} on node {}, but owner is {}. Redirecting.",
                        playerUuid,
                        dimensionId,
                        latestConfig.nodeId(),
                        owner
                );

                createTransferAndScheduleRedirect(
                        server,
                        latestConfig,
                        playerUuid,
                        playerName,
                        owner,
                        dimensionId,
                        x,
                        y,
                        z,
                        yaw,
                        pitch,
                        playerData
                );
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to route player {} after dimension change to {}",
                        playerUuid,
                        dimensionId,
                        exception
                );

                scheduleTransferError(
                        server,
                        playerUuid,
                        exception
                );
            }
        });
    }

    private int queueTransfer(
            CommandSourceStack source,
            String targetNode
    ) {
        ServerPlayer player = getCommandPlayer(source);

        if (player == null) {
            return 0;
        }

        String dimensionId =
                player.level()
                        .dimension()
                        .location()
                        .toString();

        return queueTransferInternal(
                source,
                player,
                targetNode,
                dimensionId,
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYRot(),
                player.getXRot()
        );
    }

    private int queueTransferToPosition(
            CommandSourceStack source,
            String targetNode,
            String dimensionId,
            double x,
            double y,
            double z
    ) {
        ServerPlayer player = getCommandPlayer(source);

        if (player == null) {
            return 0;
        }

        ResourceLocation parsedDimension =
                ResourceLocation.tryParse(dimensionId);

        if (parsedDimension == null) {
            source.sendFailure(
                    Component.literal(
                            "§cНекорректный dimension id: §f"
                                    + dimensionId
                    )
            );

            return 0;
        }

        return queueTransferInternal(
                source,
                player,
                targetNode,
                parsedDimension.toString(),
                x,
                y,
                z,
                player.getYRot(),
                player.getXRot()
        );
    }

    private ServerPlayer getCommandPlayer(
            CommandSourceStack source
    ) {
        try {
            return source.getPlayerOrException();
        } catch (Exception exception) {
            source.sendFailure(
                    Component.literal(
                            "§cЭту команду нужно выполнять игроком."
                    )
            );

            return null;
        }
    }

    private static Map<String, Integer> captureDimensionPlayerCounts(
            MinecraftServer server
    ) {
        Map<String, Integer> playerCounts = new HashMap<>();

        for (ServerLevel level : server.getAllLevels()) {
            playerCounts.put(
                    level.dimension().location().toString(),
                    level.players().size()
            );
        }

        return Map.copyOf(playerCounts);
    }

    private static List<String> registeredDimensionIds(
            MinecraftServer server
    ) {
        Set<String> dimensions = new TreeSet<>();

        server.registryAccess()
                .registryOrThrow(Registries.DIMENSION)
                .keySet()
                .forEach(
                        location -> dimensions.add(
                                location.toString()
                        )
                );

        for (ServerLevel level : server.getAllLevels()) {
            dimensions.add(
                    level.dimension().location().toString()
            );
        }

        return List.copyOf(dimensions);
    }

    private int showDimensions(
            CommandSourceStack source,
            int requestedPage
    ) {
        ClusterConfig currentConfig = config;

        if (currentConfig == null
                || !currentConfig.enabled()) {
            source.sendFailure(
                    Component.literal(
                            "§cКластер выключен или конфиг ещё не загружен."
                    )
            );
            return 0;
        }

        MinecraftServer server = source.getServer();
        List<String> registeredDimensions =
                registeredDimensionIds(server);
        Set<String> registeredSet =
                Set.copyOf(registeredDimensions);
        Map<String, Integer> localActivity =
                captureDimensionPlayerCounts(server);

        source.sendSuccess(
                () -> Component.literal(
                        "§eПолучаю список измерений кластера..."
                ),
                false
        );

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;

                ClusterDatabase.updateDimensionActivity(
                        latestConfig,
                        localActivity
                );

                List<ClusterDatabase.DimensionAssignmentInfo> dimensions =
                        ClusterDatabase.listDimensionAssignments(
                                latestConfig,
                                registeredDimensions
                        );

                server.execute(() -> {
                    int totalPages = Math.max(
                            1,
                            (dimensions.size()
                                    + DIMENSION_LIST_PAGE_SIZE
                                    - 1)
                                    / DIMENSION_LIST_PAGE_SIZE
                    );
                    int page = Math.min(
                            Math.max(1, requestedPage),
                            totalPages
                    );

                    if (requestedPage > totalPages) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "§eСтраница §f"
                                                + requestedPage
                                                + "§e не существует. Показываю последнюю страницу §f"
                                                + totalPages
                                                + "§e."
                                ),
                                false
                        );
                    }

                    int start = (page - 1)
                            * DIMENSION_LIST_PAGE_SIZE;
                    int end = Math.min(
                            dimensions.size(),
                            start + DIMENSION_LIST_PAGE_SIZE
                    );

                    long unknown = dimensions.stream()
                            .filter(info -> info.nodeId() == null)
                            .count();
                    long pinned = dimensions.stream()
                            .filter(
                                    ClusterDatabase.DimensionAssignmentInfo::pinned
                            )
                            .count();
                    long active = dimensions.stream()
                            .filter(info -> info.activePlayers() > 0)
                            .count();

                    source.sendSuccess(
                            () -> Component.literal(
                                    "§6Измерения кластера §7(страница §f"
                                            + page
                                            + "§7/§f"
                                            + totalPages
                                            + "§7, всего §f"
                                            + dimensions.size()
                                            + "§7, без владельца §f"
                                            + unknown
                                            + "§7, закреплено §f"
                                            + pinned
                                            + "§7, с игроками §f"
                                            + active
                                            + "§7):"
                            ),
                            false
                    );

                    for (int index = start; index < end; index++) {
                        ClusterDatabase.DimensionAssignmentInfo info =
                                dimensions.get(index);

                        String owner = info.nodeId() == null
                                ? "§cunknown"
                                : "§f" + info.nodeId();
                        String pinState = info.pinned()
                                ? " §6[PINNED]"
                                : "";
                        String players = info.activePlayers() > 0
                                ? " §a[players="
                                + info.activePlayers()
                                + " on "
                                + info.activeNodes()
                                + "]"
                                : "";
                        String registryState = registeredSet.contains(
                                info.dimensionId()
                        )
                                ? ""
                                : " §8[not registered locally]";

                        source.sendSuccess(
                                () -> Component.literal(
                                        "§f"
                                                + info.dimensionId()
                                                + "§7 -> "
                                                + owner
                                                + pinState
                                                + players
                                                + registryState
                                ),
                                false
                        );
                    }

                    if (page < totalPages) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "§7Следующая страница: §f/gtocluster dimensions list "
                                                + (page + 1)
                                ),
                                false
                        );
                    }
                });
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to list cluster dimensions",
                        exception
                );

                server.execute(
                        () -> source.sendFailure(
                                Component.literal(
                                        "§cНе удалось получить список измерений: "
                                                + exception
                                                .getClass()
                                                .getSimpleName()
                                                + ": "
                                                + exception.getMessage()
                                )
                        )
                );
            }
        });

        return 1;
    }

    private int forgetDimensionAssignment(
            CommandSourceStack source,
            String dimensionId,
            boolean confirm
    ) {
        ClusterConfig currentConfig = config;

        if (currentConfig == null
                || !currentConfig.enabled()) {
            source.sendFailure(
                    Component.literal(
                            "§cКластер выключен или конфиг ещё не загружен."
                    )
            );
            return 0;
        }

        MinecraftServer server = source.getServer();
        Set<String> registeredSet = Set.copyOf(
                registeredDimensionIds(server)
        );

        if (registeredSet.contains(dimensionId)) {
            source.sendFailure(
                    Component.literal(
                            "§cУдаление запрещено: измерение зарегистрировано локально: §f"
                                    + dimensionId
                    )
            );
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal(
                        confirm
                                ? "§eУдаляю назначение измерения из кластера: §f"
                                + dimensionId
                                : "§eПроверяю назначение измерения: §f"
                                + dimensionId
                ),
                false
        );

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;

                if (confirm) {
                    ClusterDatabase.DimensionForgetResult result =
                            ClusterDatabase.forgetDimensionAssignment(
                                    latestConfig,
                                    dimensionId
                            );
                    refreshDimensionOwnerCache(latestConfig);

                    server.execute(() -> source.sendSuccess(
                            () -> Component.literal(
                                    "§aНазначение удалено: §f"
                                            + result.dimensionId()
                                            + " §7| previous owner: §f"
                                            + result.previousNodeId()
                                            + " §7| activity rows: §f"
                                            + result.deletedActivityRows()
                            ),
                            true
                    ));
                    return;
                }

                ClusterDatabase.DimensionForgetPreview preview =
                        ClusterDatabase.previewDimensionForget(
                                latestConfig,
                                dimensionId
                        );

                server.execute(() -> {
                    if (preview.nodeId() == null) {
                        source.sendFailure(
                                Component.literal(
                                        "§cНазначение не найдено: §f"
                                                + dimensionId
                                )
                        );
                        return;
                    }

                    source.sendSuccess(
                            () -> Component.literal(
                                    "§6Кандидат на удаление: §f"
                                            + preview.dimensionId()
                                            + " §7-> §f"
                                            + preview.nodeId()
                                            + " §7| pinned: §f"
                                            + preview.pinned()
                                            + " §7| players: §f"
                                            + preview.activePlayers()
                                            + " §7| active nodes: §f"
                                            + preview.activeNodes()
                            ),
                            false
                    );

                    if (!preview.removable()) {
                        source.sendFailure(
                                Component.literal(
                                        "§cУдаление заблокировано: "
                                                + preview.reason()
                                )
                        );
                        return;
                    }

                    source.sendSuccess(
                            () -> Component.literal(
                                    "§eДля подтверждения: §f/gtocluster dimensions forget "
                                            + dimensionId
                                            + " confirm"
                            ),
                            false
                    );
                });
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to forget cluster dimension assignment {}",
                        dimensionId,
                        exception
                );

                server.execute(() -> source.sendFailure(
                        Component.literal(
                                "§cНе удалось удалить назначение: "
                                        + exceptionSummary(exception)
                        )
                ));
            }
        });

        return 1;
    }

    private int runDimensionPlan(
            CommandSourceStack source,
            boolean rebalance,
            boolean apply
    ) {
        ClusterConfig currentConfig = config;

        if (currentConfig == null
                || !currentConfig.enabled()) {
            source.sendFailure(
                    Component.literal(
                            "§cКластер выключен или конфиг ещё не загружен."
                    )
            );
            return 0;
        }

        MinecraftServer server = source.getServer();
        List<String> registeredDimensions =
                registeredDimensionIds(server);
        Map<String, Integer> localActivity =
                captureDimensionPlayerCounts(server);

        String operation;
        if (!apply) {
            operation = "предварительный план балансировки";
        } else if (rebalance) {
            operation = "балансировку измерений";
        } else {
            operation = "назначение измерений без владельца";
        }

        source.sendSuccess(
                () -> Component.literal(
                        "§eЗапускаю " + operation + "..."
                ),
                false
        );

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;

                ClusterDatabase.updateDimensionActivity(
                        latestConfig,
                        localActivity
                );

                ClusterDatabase.DimensionPlanResult result =
                        ClusterDatabase.planDimensionAssignments(
                                latestConfig,
                                registeredDimensions,
                                rebalance,
                                apply
                        );

                if (apply) {
                    refreshDimensionOwnerCache(latestConfig);
                }

                server.execute(
                        () -> sendDimensionPlanResult(
                                source,
                                result
                        )
                );
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to run dimension assignment plan",
                        exception
                );

                server.execute(
                        () -> source.sendFailure(
                                Component.literal(
                                        "§cНе удалось выполнить план измерений: "
                                                + exception
                                                .getClass()
                                                .getSimpleName()
                                                + ": "
                                                + exception.getMessage()
                                )
                        )
                );
            }
        });

        return 1;
    }

    private void sendDimensionPlanResult(
            CommandSourceStack source,
            ClusterDatabase.DimensionPlanResult result
    ) {
        int assigned = 0;
        int moved = 0;
        int pinned = 0;
        int migrating = 0;
        int active = 0;
        int conflicts = 0;

        for (ClusterDatabase.DimensionPlanEntry entry
                : result.entries()) {
            switch (entry.action()) {
                case ASSIGN -> assigned++;
                case MOVE -> moved++;
                case SKIP_PINNED -> pinned++;
                case SKIP_MIGRATING -> migrating++;
                case SKIP_ACTIVE -> active++;
                case CONFLICT_ACTIVE -> conflicts++;
                default -> {
                }
            }
        }

        String mode = result.applied()
                ? "§aПлан применён"
                : "§eПредварительный план";
        final int assignedCount = assigned;
        final int movedCount = moved;
        final int pinnedCount = pinned;
        final int migratingCount = migrating;
        final int activeCount = active;
        final int conflictCount = conflicts;

        source.sendSuccess(
                () -> Component.literal(
                        mode
                                + "§7 | назначить: §f"
                                + assignedCount
                                + "§7 | переместить: §f"
                                + movedCount
                                + "§7 | pinned: §f"
                                + pinnedCount
                                + "§7 | migration: §f"
                                + migratingCount
                                + "§7 | активные пропущены: §f"
                                + activeCount
                                + "§7 | конфликты: §f"
                                + conflictCount
                ),
                false
        );

        int shown = 0;
        for (ClusterDatabase.DimensionPlanEntry entry
                : result.entries()) {
            if (entry.action()
                    == ClusterDatabase.DimensionPlanAction.KEEP) {
                continue;
            }

            if (shown >= 30) {
                break;
            }

            String line = switch (entry.action()) {
                case ASSIGN -> "§aASSIGN §f"
                        + entry.dimensionId()
                        + " §7-> §f"
                        + entry.targetNodeId();
                case MOVE -> "§eMOVE §f"
                        + entry.dimensionId()
                        + " §7"
                        + entry.previousNodeId()
                        + " -> §f"
                        + entry.targetNodeId();
                case SKIP_PINNED -> "§6PINNED §f"
                        + entry.dimensionId()
                        + " §7-> §f"
                        + entry.targetNodeId();
                case SKIP_MIGRATING -> "§dMIGRATION §f"
                        + entry.dimensionId()
                        + " §7-> §f"
                        + entry.targetNodeId();
                case SKIP_ACTIVE -> "§bACTIVE §f"
                        + entry.dimensionId()
                        + " §7players="
                        + entry.activePlayers()
                        + " nodes="
                        + entry.activeNodes();
                case CONFLICT_ACTIVE -> "§cCONFLICT §f"
                        + entry.dimensionId()
                        + " §7players="
                        + entry.activePlayers()
                        + " nodes="
                        + entry.activeNodes();
                default -> "";
            };

            if (!line.isEmpty()) {
                String finalLine = line;
                source.sendSuccess(
                        () -> Component.literal(finalLine),
                        false
                );
                shown++;
            }
        }

        final int shownCount = shown;
        if (result.entries().stream()
                .filter(entry -> entry.action()
                        != ClusterDatabase.DimensionPlanAction.KEEP)
                .count() > shownCount) {
            source.sendSuccess(
                    () -> Component.literal(
                            "§7Показаны первые §f"
                                    + shownCount
                                    + "§7 изменения/блокировки."
                    ),
                    false
            );
        }

        source.sendSuccess(
                () -> Component.literal(
                        "§6Планируемая нагрузка узлов:"
                ),
                false
        );

        for (ClusterDatabase.PlanningNodeStatus node
                : result.nodes()) {
            source.sendSuccess(
                    () -> Component.literal(
                            "§f"
                                    + node.nodeId()
                                    + "§7 | dimensions: §f"
                                    + node.plannedDimensionCount()
                                    + "§7 | players: §f"
                                    + node.playerCount()
                    ),
                    false
            );
        }
    }

    private int pinDimensionOwner(
            CommandSourceStack source,
            String dimensionId,
            String nodeId
    ) {
        ResourceLocation parsedDimension =
                ResourceLocation.tryParse(dimensionId);

        if (parsedDimension == null) {
            source.sendFailure(
                    Component.literal(
                            "§cНекорректный dimension id: §f"
                                    + dimensionId
                    )
            );
            return 0;
        }

        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(
                    Component.literal(
                            "§cКластер выключен или конфиг ещё не загружен."
                    )
            );
            return 0;
        }

        MinecraftServer server = source.getServer();
        String normalizedDimension = parsedDimension.toString();
        Map<String, Integer> localActivity =
                captureDimensionPlayerCounts(server);

        source.sendSuccess(
                () -> Component.literal(
                        "§eЗакрепляю dimension §f"
                                + normalizedDimension
                                + "§e за узлом §f"
                                + nodeId
                                + "§e..."
                ),
                false
        );

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;

                ClusterDatabase.updateDimensionActivity(
                        latestConfig,
                        localActivity
                );

                ClusterDatabase.DimensionPinResult result =
                        ClusterDatabase.pinDimension(
                                latestConfig,
                                normalizedDimension,
                                nodeId
                        );

                updateCachedDimensionOwner(
                        result.dimensionId(),
                        result.nodeId()
                );

                server.execute(
                        () -> source.sendSuccess(
                                () -> Component.literal(
                                        "§aDimension §f"
                                                + result.dimensionId()
                                                + "§a закреплена за узлом §f"
                                                + result.nodeId()
                                ),
                                false
                        )
                );
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to pin dimension {} to node {}",
                        normalizedDimension,
                        nodeId,
                        exception
                );

                server.execute(
                        () -> source.sendFailure(
                                Component.literal(
                                        "§cНе удалось закрепить dimension: "
                                                + exception.getMessage()
                                )
                        )
                );
            }
        });

        return 1;
    }

    private int unpinDimensionOwner(
            CommandSourceStack source,
            String dimensionId
    ) {
        ResourceLocation parsedDimension =
                ResourceLocation.tryParse(dimensionId);

        if (parsedDimension == null) {
            source.sendFailure(
                    Component.literal(
                            "§cНекорректный dimension id: §f"
                                    + dimensionId
                    )
            );
            return 0;
        }

        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(
                    Component.literal(
                            "§cКластер выключен или конфиг ещё не загружен."
                    )
            );
            return 0;
        }

        MinecraftServer server = source.getServer();
        String normalizedDimension = parsedDimension.toString();

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;

                ClusterDatabase.DimensionPinResult result =
                        ClusterDatabase.unpinDimension(
                                latestConfig,
                                normalizedDimension
                        );

                server.execute(
                        () -> source.sendSuccess(
                                () -> Component.literal(
                                        "§aЗакрепление снято: §f"
                                                + result.dimensionId()
                                                + "§a остаётся на узле §f"
                                                + result.nodeId()
                                ),
                                false
                        )
                );
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to unpin dimension {}",
                        normalizedDimension,
                        exception
                );

                server.execute(
                        () -> source.sendFailure(
                                Component.literal(
                                        "§cНе удалось снять закрепление: "
                                                + exception.getMessage()
                                )
                        )
                );
            }
        });

        return 1;
    }

    private int prepareDimensionMigration(
            CommandSourceStack source,
            String dimensionId,
            String targetNode
    ) {
        ClusterConfig currentConfig = config;

        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(
                    Component.literal(
                            "§cКластер выключен или конфиг ещё не загружен."
                    )
            );
            return 0;
        }

        Path stagingPath = currentConfig.dimensionMigrationStagingPath();
        if (stagingPath == null) {
            source.sendFailure(
                    Component.literal(
                            "§cВ конфиге не указан dimension_migration_staging_path."
                    )
            );
            return 0;
        }

        ResourceLocation parsedDimension = ResourceLocation.tryParse(dimensionId);
        if (parsedDimension == null) {
            source.sendFailure(
                    Component.literal(
                            "§cНекорректный dimension id: §f" + dimensionId
                    )
            );
            return 0;
        }

        MinecraftServer server = source.getServer();
        String normalizedDimension = parsedDimension.toString();
        ResourceKey<Level> dimensionKey = ResourceKey.create(
                Registries.DIMENSION,
                parsedDimension
        );
        ServerLevel level = server.getLevel(dimensionKey);

        if (level == null) {
            source.sendFailure(
                    Component.literal(
                            "§cИзмерение не загружено на source node: §f"
                                    + normalizedDimension
                    )
            );
            return 0;
        }

        if (!level.players().isEmpty()) {
            source.sendFailure(
                    Component.literal(
                            "§cВ измерении находятся игроки: §f"
                                    + level.players().size()
                    )
            );
            return 0;
        }

        try {
            ClusterDimensionMigration.resolveDimensionPath(
                    server,
                    normalizedDimension
            );
        } catch (Exception exception) {
            source.sendFailure(
                    Component.literal(
                            "§cMigration недоступна: " + exception.getMessage()
                    )
            );
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal(
                        "§eСоздаю migration для §f"
                                + normalizedDimension
                                + "§e: §f"
                                + currentConfig.nodeId()
                                + " §7-> §f"
                                + targetNode
                ),
                false
        );

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;

                ClusterDatabase.DimensionMigration migration =
                        ClusterDatabase.requestDimensionMigration(
                                latestConfig,
                                normalizedDimension,
                                targetNode
                        );

                server.execute(() -> startDimensionMigrationArchive(
                        source,
                        server,
                        latestConfig,
                        migration
                ));
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to create dimension migration for {}",
                        normalizedDimension,
                        exception
                );
                server.execute(
                        () -> source.sendFailure(
                                Component.literal(
                                        "§cНе удалось создать migration: "
                                                + exception.getMessage()
                                )
                        )
                );
            }
        });

        return 1;
    }

    private void startDimensionMigrationArchive(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            ClusterDatabase.DimensionMigration migration
    ) {
        ResourceLocation parsedDimension =
                ResourceLocation.tryParse(migration.dimensionId());
        if (parsedDimension == null) {
            failDimensionMigration(
                    source,
                    server,
                    currentConfig,
                    migration,
                    "Некорректный dimension id"
            );
            return;
        }

        ServerLevel level = server.getLevel(
                ResourceKey.create(
                        Registries.DIMENSION,
                        parsedDimension
                )
        );

        if (level == null || !level.players().isEmpty()) {
            failDimensionMigration(
                    source,
                    server,
                    currentConfig,
                    migration,
                    level == null
                            ? "Измерение не загружено"
                            : "В измерении появились игроки"
            );
            return;
        }

        addMigrationFreeze(migration.dimensionId());

        boolean saved;
        try {
            saved = server.saveEverything(true, true, true);
        } catch (Exception exception) {
            failDimensionMigration(
                    source,
                    server,
                    currentConfig,
                    migration,
                    "Ошибка сохранения мира: " + exception.getMessage()
            );
            return;
        }

        if (!saved) {
            failDimensionMigration(
                    source,
                    server,
                    currentConfig,
                    migration,
                    "MinecraftServer не подтвердил сохранение мира"
            );
            return;
        }

        source.sendSuccess(
                () -> Component.literal(
                        "§eИзмерение заморожено и сохранено. Создаю архив..."
                ),
                false
        );

        MIGRATION_EXECUTOR.execute(() -> {
            ClusterDimensionMigration.PreparedArchive archive = null;
            try {
                archive = ClusterDimensionMigration.createArchive(
                        server,
                        migration.dimensionId(),
                        currentConfig.dimensionMigrationStagingPath(),
                        migration.migrationId()
                );

                ClusterDatabase.DimensionMigration ready =
                        ClusterDatabase.markDimensionMigrationReady(
                                currentConfig,
                                migration.migrationId(),
                                archive.archiveName(),
                                archive.archiveSha256(),
                                archive.contentSha256(),
                                archive.archiveSize()
                        );

                refreshDimensionMigrationFreeze(currentConfig);
                ClusterDimensionMigration.PreparedArchive finalArchive = archive;
                server.execute(() -> {
                    source.sendSuccess(
                            () -> Component.literal(
                                    "§aMigration READY: §f"
                                            + ready.migrationId()
                                            + "§a, dimension: §f"
                                            + ready.dimensionId()
                                            + "§a, target: §f"
                                            + ready.targetNode()
                                            + "§a, files: §f"
                                            + finalArchive.fileCount()
                                            + "§a, archive: §f"
                                            + finalArchive.archiveSize()
                                            + " bytes"
                            ),
                            false
                    );
                    source.sendSuccess(
                            () -> Component.literal(
                                    "§eТеперь полностью перезапусти target node §f"
                                            + ready.targetNode()
                                            + "§e. Владелец изменится только после проверки архива."
                            ),
                            false
                    );
                });
            } catch (Exception exception) {
                if (archive != null) {
                    try {
                        ClusterDimensionMigration.deleteArchive(
                                currentConfig.dimensionMigrationStagingPath(),
                                archive.archiveName()
                        );
                    } catch (Exception ignored) {
                    }
                }
                failDimensionMigration(
                        source,
                        server,
                        currentConfig,
                        migration,
                        exception.getClass().getSimpleName()
                                + ": "
                                + exception.getMessage()
                );
            }
        });
    }

    private void failDimensionMigration(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            ClusterDatabase.DimensionMigration migration,
            String error
    ) {
        MIGRATION_EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.failDimensionMigration(
                        currentConfig,
                        migration.migrationId(),
                        error
                );
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to mark dimension migration {} as failed",
                        migration.migrationId(),
                        exception
                );
            }

            removeMigrationFreeze(migration.dimensionId());
            server.execute(
                    () -> source.sendFailure(
                            Component.literal(
                                    "§cMigration "
                                            + migration.migrationId()
                                            + " завершилась ошибкой: "
                                            + error
                            )
                    )
            );
        });
    }

    private int showDimensionMigrations(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(
                    Component.literal(
                            "§cКластер выключен или конфиг ещё не загружен."
                    )
            );
            return 0;
        }

        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                List<ClusterDatabase.DimensionMigration> migrations =
                        ClusterDatabase.listDimensionMigrations(
                                currentConfig,
                                20
                        );

                server.execute(() -> {
                    if (migrations.isEmpty()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "§7Dimension migrations отсутствуют."
                                ),
                                false
                        );
                        return;
                    }

                    source.sendSuccess(
                            () -> Component.literal(
                                    "§6Последние dimension migrations:"
                            ),
                            false
                    );

                    for (ClusterDatabase.DimensionMigration migration
                            : migrations) {
                        String color = switch (migration.status()) {
                            case "APPLIED", "COMPLETED", "VERIFIED", "FINALIZED", "ROLLED_BACK" -> "§a";
                            case "FAILED", "CANCELLED" -> "§c";
                            case "READY", "FINALIZE_READY", "ROLLBACK_READY" -> "§e";
                            case "APPLYING", "ROLLBACK_PREPARING", "ROLLBACK_APPLYING" -> "§b";
                            default -> "§6";
                        };

                        String error = migration.errorText() == null
                                || migration.errorText().isBlank()
                                ? ""
                                : " §7| error: §c" + migration.errorText();

                        source.sendSuccess(
                                () -> Component.literal(
                                        color
                                                + migration.status()
                                                + " §f"
                                                + migration.migrationId()
                                                + " §7| §f"
                                                + migration.dimensionId()
                                                + " §7| "
                                                + migration.sourceNode()
                                                + " -> "
                                                + migration.targetNode()
                                                + " §7| "
                                                + migration.archiveSize()
                                                + " bytes"
                                                + error
                                ),
                                false
                        );
                    }
                });
            } catch (Exception exception) {
                server.execute(
                        () -> source.sendFailure(
                                Component.literal(
                                        "§cНе удалось получить migrations: "
                                                + exception.getMessage()
                                )
                        )
                );
            }
        });
        return 1;
    }


    private int showPendingDimensionMigrations(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(
                    Component.literal(
                            "§cКластер выключен или конфиг ещё не загружен."
                    )
            );
            return 0;
        }

        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                List<ClusterDatabase.DimensionMigration> migrations =
                        ClusterDatabase.listPendingDimensionMigrations(
                                currentConfig
                        );

                server.execute(() -> {
                    int staleWarningMinutes =
                            currentConfig.dimensionMigrationStaleWarningMinutes();
                    if (migrations.isEmpty()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "§aНезавершённых dimension migrations нет. §7Порог предупреждения: §f"
                                                + staleWarningMinutes
                                                + " мин."
                                ),
                                false
                        );
                        return;
                    }

                    Map<String, Integer> counts = new LinkedHashMap<>();
                    int staleCount = 0;
                    for (ClusterDatabase.DimensionMigration migration : migrations) {
                        counts.merge(migration.status(), 1, Integer::sum);
                        if (isPendingMigrationStale(
                                migration,
                                staleWarningMinutes
                        )) {
                            staleCount++;
                        }
                    }
                    int finalStaleCount = staleCount;

                    source.sendSuccess(
                            () -> Component.literal(
                                    "§6Незавершённые dimension migrations: §f"
                                            + pendingMigrationCounts(counts)
                                            + " §7| всего: §f"
                                            + migrations.size()
                                            + " §7| старше "
                                            + staleWarningMinutes
                                            + " мин: §f"
                                            + finalStaleCount
                            ),
                            false
                    );

                    for (ClusterDatabase.DimensionMigration migration : migrations) {
                        boolean stale = isPendingMigrationStale(
                                migration,
                                staleWarningMinutes
                        );
                        String color = stale
                                ? "§c"
                                : switch (migration.status()) {
                                    case "APPLIED", "VERIFIED" -> "§a";
                                    case "READY", "FINALIZE_READY", "ROLLBACK_READY" -> "§e";
                                    case "APPLYING", "ROLLBACK_PREPARING", "ROLLBACK_APPLYING" -> "§b";
                                    default -> "§6";
                                };
                        String updatedAt = migration.updatedAt() == null
                                ? "unknown"
                                : migration.updatedAt().toString();
                        String age = pendingMigrationAge(migration);
                        String nextAction = pendingMigrationNextAction(migration);

                        source.sendSuccess(
                                () -> Component.literal(
                                        color
                                                + (stale ? "STALE §7| " : "")
                                                + color
                                                + migration.status()
                                                + " §f"
                                                + migration.migrationId()
                                                + " §7| §f"
                                                + migration.dimensionId()
                                                + " §7| "
                                                + migration.sourceNode()
                                                + " -> "
                                                + migration.targetNode()
                                                + " §7| age: §f"
                                                + age
                                                + " §7| updated: §f"
                                                + updatedAt
                                                + " §7| "
                                                + nextAction
                                ),
                                false
                        );
                    }
                });
            } catch (Exception exception) {
                server.execute(
                        () -> source.sendFailure(
                                Component.literal(
                                        "§cНе удалось получить незавершённые migrations: "
                                                + exception.getMessage()
                                )
                        )
                );
            }
        });
        return 1;
    }

    private static boolean isPendingMigrationStale(
            ClusterDatabase.DimensionMigration migration,
            int staleWarningMinutes
    ) {
        if (migration.updatedAt() == null) {
            return true;
        }
        long thresholdSeconds = staleWarningMinutes * 60L;
        return pendingMigrationAgeSeconds(migration) >= thresholdSeconds;
    }

    private static long pendingMigrationAgeSeconds(
            ClusterDatabase.DimensionMigration migration
    ) {
        if (migration.updatedAt() == null) {
            return Long.MAX_VALUE;
        }
        return Math.max(
                0L,
                (System.currentTimeMillis()
                        - migration.updatedAt().toEpochMilli()) / 1_000L
        );
    }

    private static String pendingMigrationAge(
            ClusterDatabase.DimensionMigration migration
    ) {
        long ageSeconds = pendingMigrationAgeSeconds(migration);
        if (ageSeconds == Long.MAX_VALUE) {
            return "unknown";
        }
        long days = ageSeconds / 86_400L;
        long hours = ageSeconds % 86_400L / 3_600L;
        long minutes = ageSeconds % 3_600L / 60L;
        long seconds = ageSeconds % 60L;
        if (days > 0L) {
            return days + "д " + hours + "ч";
        }
        if (hours > 0L) {
            return hours + "ч " + minutes + "м";
        }
        if (minutes > 0L) {
            return minutes + "м " + seconds + "с";
        }
        return seconds + "с";
    }

    private static String pendingMigrationCounts(
            Map<String, Integer> counts
    ) {
        List<String> parts = new ArrayList<>();
        String[] statuses = {
                "PREPARING",
                "READY",
                "APPLYING",
                "APPLIED",
                "VERIFIED",
                "FINALIZE_READY",
                "ROLLBACK_PREPARING",
                "ROLLBACK_READY",
                "ROLLBACK_APPLYING"
        };
        for (String status : statuses) {
            int count = counts.getOrDefault(status, 0);
            if (count > 0) {
                parts.add(status + "=" + count);
            }
        }
        return String.join(", ", parts);
    }

    private static String pendingMigrationNextAction(
            ClusterDatabase.DimensionMigration migration
    ) {
        return switch (migration.status()) {
            case "PREPARING" -> "§6подготовка на source " + migration.sourceNode();
            case "READY" -> "§eперезапусти target " + migration.targetNode();
            case "APPLYING" -> "§bприменение на target " + migration.targetNode();
            case "APPLIED" -> "§everify на target " + migration.targetNode();
            case "VERIFIED" -> "§efinalize на source " + migration.sourceNode();
            case "FINALIZE_READY" -> "§eперезапусти source " + migration.sourceNode();
            case "ROLLBACK_PREPARING" -> "§6подготовка rollback";
            case "ROLLBACK_READY" -> "§eперезапусти source " + migration.sourceNode();
            case "ROLLBACK_APPLYING" -> "§brollback на source " + migration.sourceNode();
            default -> "§7проверь состояние вручную";
        };
    }

    private int verifyDimensionMigration(
            CommandSourceStack source,
            String migrationId
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.DimensionMigration migration =
                        ClusterDatabase.validateDimensionMigrationVerification(
                                currentConfig,
                                migrationId
                        );
                server.execute(() -> startDimensionMigrationVerification(
                        source,
                        server,
                        currentConfig,
                        migration
                ));
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cVerify недоступен: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private void startDimensionMigrationVerification(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            ClusterDatabase.DimensionMigration migration
    ) {
        ResourceLocation id = ResourceLocation.tryParse(migration.dimensionId());
        ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null || !level.players().isEmpty()) {
            source.sendFailure(Component.literal(
                    level == null
                            ? "§cИзмерение не загружено на target node."
                            : "§cВ измерении находятся игроки."
            ));
            return;
        }

        addMigrationFreeze(migration.dimensionId());
        try {
            if (!server.saveEverything(true, true, true)) {
                throw new IllegalStateException("MinecraftServer не подтвердил сохранение мира");
            }
        } catch (Exception exception) {
            try {
                refreshDimensionMigrationFreeze(currentConfig);
            } catch (Exception ignored) {
            }
            source.sendFailure(Component.literal("§cVerify завершился ошибкой: " + exception.getMessage()));
            return;
        }

        source.sendSuccess(() -> Component.literal("§eПроверяю SHA-256 измерения..."), false);
        MIGRATION_EXECUTOR.execute(() -> {
            try {
                ClusterDimensionMigration.VerificationResult verification =
                        ClusterDimensionMigration.verifyDimension(
                                server,
                                migration.dimensionId(),
                                migration.contentSha256()
                        );
                ClusterDatabase.DimensionMigration verified =
                        ClusterDatabase.markDimensionMigrationVerified(
                                currentConfig,
                                migration.migrationId()
                        );
                refreshDimensionMigrationFreeze(currentConfig);
                server.execute(() -> source.sendSuccess(
                        () -> Component.literal(
                                "§aMigration VERIFIED: §f"
                                        + verified.migrationId()
                                        + " §7| SHA-256: §f"
                                        + verification.actualSha256()
                                        + (verification.matchesArchive()
                                        ? " §7| archive match: §aда"
                                        : " §7| archive match: §eнет, измерение изменилось после переноса")
                        ),
                        false
                ));
            } catch (Exception exception) {
                try {
                    refreshDimensionMigrationFreeze(currentConfig);
                } catch (Exception ignored) {
                }
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cVerify завершился ошибкой: " + exception.getMessage()
                )));
            }
        });
    }

    private int finalizeDimensionMigration(
            CommandSourceStack source,
            String migrationId
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.DimensionMigration migration =
                        ClusterDatabase.requestDimensionMigrationFinalization(
                                currentConfig,
                                migrationId
                        );
                refreshDimensionMigrationFreeze(currentConfig);
                server.execute(() -> source.sendSuccess(
                        () -> Component.literal(
                                "§aFinalize подготовлен: §f"
                                        + migration.migrationId()
                                        + "§a. Полностью перезапусти source node §f"
                                        + migration.sourceNode()
                        ),
                        false
                ));
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось подготовить finalize: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private int rollbackDimensionMigration(
            CommandSourceStack source,
            String migrationId
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        if (currentConfig.dimensionMigrationStagingPath() == null) {
            source.sendFailure(Component.literal("§cВ конфиге не указан dimension_migration_staging_path."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.DimensionMigration migration =
                        ClusterDatabase.requestDimensionRollback(
                                currentConfig,
                                migrationId
                        );
                server.execute(() -> startDimensionRollbackArchive(
                        source,
                        server,
                        currentConfig,
                        migration
                ));
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cRollback недоступен: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private void startDimensionRollbackArchive(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            ClusterDatabase.DimensionMigration migration
    ) {
        ResourceLocation id = ResourceLocation.tryParse(migration.dimensionId());
        ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null || !level.players().isEmpty()) {
            failDimensionRollback(
                    source,
                    server,
                    currentConfig,
                    migration,
                    level == null ? "Измерение не загружено" : "В измерении находятся игроки"
            );
            return;
        }

        addMigrationFreeze(migration.dimensionId());
        try {
            if (!server.saveEverything(true, true, true)) {
                throw new IllegalStateException("MinecraftServer не подтвердил сохранение мира");
            }
        } catch (Exception exception) {
            failDimensionRollback(source, server, currentConfig, migration, exception.getMessage());
            return;
        }

        source.sendSuccess(() -> Component.literal("§eИзмерение заморожено. Создаю rollback-архив..."), false);
        MIGRATION_EXECUTOR.execute(() -> {
            ClusterDimensionMigration.PreparedArchive archive = null;
            try {
                archive = ClusterDimensionMigration.createArchive(
                        server,
                        migration.dimensionId(),
                        currentConfig.dimensionMigrationStagingPath(),
                        migration.migrationId() + "-rollback"
                );
                ClusterDatabase.DimensionMigration ready =
                        ClusterDatabase.markDimensionRollbackReady(
                                currentConfig,
                                migration.migrationId(),
                                archive.archiveName(),
                                archive.archiveSha256(),
                                archive.contentSha256(),
                                archive.archiveSize()
                        );
                refreshDimensionMigrationFreeze(currentConfig);
                ClusterDimensionMigration.PreparedArchive finalArchive = archive;
                server.execute(() -> source.sendSuccess(
                        () -> Component.literal(
                                "§aRollback READY: §f"
                                        + ready.migrationId()
                                        + " §7| archive: §f"
                                        + finalArchive.archiveSize()
                                        + " bytes§a. Полностью перезапусти source node §f"
                                        + ready.sourceNode()
                        ),
                        false
                ));
            } catch (Exception exception) {
                if (archive != null) {
                    try {
                        ClusterDimensionMigration.deleteArchive(
                                currentConfig.dimensionMigrationStagingPath(),
                                archive.archiveName()
                        );
                    } catch (Exception ignored) {
                    }
                }
                failDimensionRollback(source, server, currentConfig, migration, exception.getMessage());
            }
        });
    }

    private void failDimensionRollback(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            ClusterDatabase.DimensionMigration migration,
            String error
    ) {
        MIGRATION_EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.failDimensionRollback(
                        currentConfig,
                        migration.migrationId(),
                        error
                );
                refreshDimensionMigrationFreeze(currentConfig);
            } catch (Exception exception) {
                LOGGER.error("Unable to fail dimension rollback {}", migration.migrationId(), exception);
            }
            server.execute(() -> source.sendFailure(Component.literal(
                    "§cRollback завершился ошибкой: " + error
            )));
        });
    }

    private int showDimensionMigrationHistory(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                List<ClusterDatabase.DimensionMigration> migrations =
                        ClusterDatabase.listDimensionMigrations(currentConfig, 50);
                server.execute(() -> {
                    source.sendSuccess(() -> Component.literal("§6История dimension migrations:"), false);
                    for (ClusterDatabase.DimensionMigration migration : migrations) {
                        String time = migration.updatedAt() == null ? "unknown" : migration.updatedAt().toString();
                        source.sendSuccess(() -> Component.literal(
                                "§f"
                                        + migration.migrationId()
                                        + " §7| §f"
                                        + migration.dimensionId()
                                        + " §7| §f"
                                        + migration.sourceNode()
                                        + " -> "
                                        + migration.targetNode()
                                        + " §7| §e"
                                        + migration.status()
                                        + " §7| §f"
                                        + time
                        ), false);
                    }
                });
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось получить историю: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }
    private int cancelDimensionMigration(
            CommandSourceStack source,
            String migrationId
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(
                    Component.literal(
                            "§cКластер выключен или конфиг ещё не загружен."
                    )
            );
            return 0;
        }

        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.DimensionMigration migration =
                        ClusterDatabase.cancelDimensionMigration(
                                currentConfig,
                                migrationId
                        );

                try {
                    ClusterDimensionMigration.deleteArchive(
                            currentConfig.dimensionMigrationStagingPath(),
                            migration.archiveName()
                    );
                } catch (Exception exception) {
                    LOGGER.warn(
                            "Unable to delete cancelled migration archive {}",
                            migration.archiveName(),
                            exception
                    );
                }

                removeMigrationFreeze(migration.dimensionId());
                server.execute(
                        () -> source.sendSuccess(
                                () -> Component.literal(
                                        "§aMigration отменена: §f"
                                                + migration.migrationId()
                                ),
                                false
                        )
                );
            } catch (Exception exception) {
                server.execute(
                        () -> source.sendFailure(
                                Component.literal(
                                        "§cНе удалось отменить migration: "
                                                + exception.getMessage()
                                )
                        )
                );
            }
        });
        return 1;
    }

    private int assignDimensionOwner(
            CommandSourceStack source,
            String dimensionId,
            String nodeId
    ) {
        ResourceLocation parsedDimension =
                ResourceLocation.tryParse(dimensionId);

        if (parsedDimension == null) {
            source.sendFailure(
                    Component.literal(
                            "§cНекорректный dimension id: §f"
                                    + dimensionId
                    )
            );

            return 0;
        }

        ClusterConfig currentConfig = config;

        if (currentConfig == null
                || !currentConfig.enabled()) {
            source.sendFailure(
                    Component.literal(
                            "§cКластер выключен или конфиг ещё не загружен."
                    )
            );

            return 0;
        }

        MinecraftServer server = source.getServer();
        String normalizedDimension =
                parsedDimension.toString();

        source.sendSuccess(
                () -> Component.literal(
                        "§eНазначаю dimension §f"
                                + normalizedDimension
                                + "§e узлу §f"
                                + nodeId
                                + "§e..."
                ),
                false
        );

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig =
                        ClusterConfig.load();

                config = latestConfig;

                ClusterDatabase.DimensionAssignment assignment =
                        ClusterDatabase.assignDimension(
                                latestConfig,
                                normalizedDimension,
                                nodeId
                        );

                updateCachedDimensionOwner(
                        assignment.dimensionId(),
                        assignment.nodeId()
                );

                server.execute(() -> {
                    String previousNode =
                            assignment.previousNodeId();

                    if (previousNode == null) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "§aDimension §f"
                                                + assignment.dimensionId()
                                                + "§a назначена узлу §f"
                                                + assignment.nodeId()
                                ),
                                false
                        );
                    } else if (previousNode.equalsIgnoreCase(
                            assignment.nodeId()
                    )) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "§aDimension §f"
                                                + assignment.dimensionId()
                                                + "§a уже принадлежит узлу §f"
                                                + assignment.nodeId()
                                ),
                                false
                        );
                    } else {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "§aDimension §f"
                                                + assignment.dimensionId()
                                                + "§a переназначена: §f"
                                                + previousNode
                                                + " §7-> §f"
                                                + assignment.nodeId()
                                ),
                                false
                        );
                    }
                });

                LOGGER.info(
                        "Dimension assignment updated: dimension={}, previousNode={}, node={}",
                        assignment.dimensionId(),
                        assignment.previousNodeId(),
                        assignment.nodeId()
                );
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to assign dimension {} to node {}",
                        normalizedDimension,
                        nodeId,
                        exception
                );

                server.execute(
                        () -> source.sendFailure(
                                Component.literal(
                                        "§cНе удалось назначить dimension: "
                                                + exception
                                                .getClass()
                                                .getSimpleName()
                                                + ": "
                                                + exception.getMessage()
                                )
                        )
                );
            }
        });

        return 1;
    }

    private int autoAssignDimensionOwner(
            CommandSourceStack source,
            String dimensionId
    ) {
        ResourceLocation parsedDimension =
                ResourceLocation.tryParse(dimensionId);

        if (parsedDimension == null) {
            source.sendFailure(
                    Component.literal(
                            "§cНекорректный dimension id: §f"
                                    + dimensionId
                    )
            );

            return 0;
        }

        ClusterConfig currentConfig = config;

        if (currentConfig == null
                || !currentConfig.enabled()) {
            source.sendFailure(
                    Component.literal(
                            "§cКластер выключен или конфиг ещё не загружен."
                    )
            );

            return 0;
        }

        MinecraftServer server = source.getServer();
        String normalizedDimension =
                parsedDimension.toString();

        source.sendSuccess(
                () -> Component.literal(
                        "§eАвтоматически выбираю узел для dimension §f"
                                + normalizedDimension
                                + "§e..."
                ),
                false
        );

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig =
                        ClusterConfig.load();

                config = latestConfig;

                ClusterDatabase.AutomaticDimensionAssignment assignment =
                        ClusterDatabase.assignDimensionAutomatically(
                                latestConfig,
                                normalizedDimension
                        );

                updateCachedDimensionOwner(
                        assignment.dimensionId(),
                        assignment.nodeId()
                );

                server.execute(() -> {
                    if (!assignment.created()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "§aDimension §f"
                                                + assignment.dimensionId()
                                                + "§a уже принадлежит узлу §f"
                                                + assignment.nodeId()
                                ),
                                false
                        );

                        return;
                    }

                    source.sendSuccess(
                            () -> Component.literal(
                                    "§aDimension §f"
                                            + assignment.dimensionId()
                                            + "§a автоматически назначена узлу §f"
                                            + assignment.nodeId()
                                            + "§a. До назначения: dimensions §f"
                                            + assignment.assignmentCountBefore()
                                            + "§a, игроков §f"
                                            + assignment.playerCountBefore()
                            ),
                            false
                    );
                });

                LOGGER.info(
                        "Automatic dimension assignment: dimension={}, node={}, created={}, previousDimensions={}, players={}",
                        assignment.dimensionId(),
                        assignment.nodeId(),
                        assignment.created(),
                        assignment.assignmentCountBefore(),
                        assignment.playerCountBefore()
                );
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to auto-assign dimension {}",
                        normalizedDimension,
                        exception
                );

                server.execute(
                        () -> source.sendFailure(
                                Component.literal(
                                        "§cНе удалось автоматически назначить dimension: "
                                                + exception
                                                .getClass()
                                                .getSimpleName()
                                                + ": "
                                                + exception.getMessage()
                                )
                        )
                );
            }
        });

        return 1;
    }

    private int showDimensionOwner(
            CommandSourceStack source,
            String dimensionId
    ) {
        ResourceLocation parsedDimension =
                ResourceLocation.tryParse(dimensionId);

        if (parsedDimension == null) {
            source.sendFailure(
                    Component.literal(
                            "§cНекорректный dimension id: §f"
                                    + dimensionId
                    )
            );

            return 0;
        }

        ClusterConfig currentConfig = config;

        if (currentConfig == null
                || !currentConfig.enabled()) {
            source.sendFailure(
                    Component.literal(
                            "§cКластер выключен или конфиг ещё не загружен."
                    )
            );

            return 0;
        }

        MinecraftServer server = source.getServer();
        String normalizedDimension =
                parsedDimension.toString();

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig =
                        ClusterConfig.load();

                config = latestConfig;

                ClusterDatabase.DimensionAssignmentInfo info =
                        ClusterDatabase.findDimensionAssignmentInfo(
                                latestConfig,
                                normalizedDimension
                        );

                server.execute(() -> {
                    if (info == null) {
                        source.sendFailure(
                                Component.literal(
                                        "§cДля dimension §f"
                                                + normalizedDimension
                                                + "§c владелец не назначен."
                                )
                        );

                        return;
                    }

                    String pinState = info.pinned()
                            ? "§6 [PINNED]"
                            : "§7 [unpinned]";
                    String activeState = info.activePlayers() > 0
                            ? "§a | players: §f"
                            + info.activePlayers()
                            + "§a on §f"
                            + info.activeNodes()
                            : "";

                    source.sendSuccess(
                            () -> Component.literal(
                                    "§aDimension §f"
                                            + normalizedDimension
                                            + "§a принадлежит узлу §f"
                                            + info.nodeId()
                                            + " "
                                            + pinState
                                            + activeState
                            ),
                            false
                    );
                });
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to read owner of dimension {}",
                        normalizedDimension,
                        exception
                );

                server.execute(
                        () -> source.sendFailure(
                                Component.literal(
                                        "§cНе удалось получить владельца dimension: "
                                                + exception
                                                .getClass()
                                                .getSimpleName()
                                                + ": "
                                                + exception.getMessage()
                                )
                        )
                );
            }
        });

        return 1;
    }

    private int queueTransferToAssignedDimension(
            CommandSourceStack source,
            String dimensionId,
            double x,
            double y,
            double z
    ) {
        ServerPlayer player = getCommandPlayer(source);

        if (player == null) {
            return 0;
        }

        ResourceLocation parsedDimension =
                ResourceLocation.tryParse(dimensionId);

        if (parsedDimension == null) {
            source.sendFailure(
                    Component.literal(
                            "§cНекорректный dimension id: §f"
                                    + dimensionId
                    )
            );

            return 0;
        }

        ClusterConfig currentConfig = config;

        if (currentConfig == null
                || !currentConfig.enabled()) {
            source.sendFailure(
                    Component.literal(
                            "§cКластер выключен или конфиг ещё не загружен."
                    )
            );

            return 0;
        }

        MinecraftServer server = source.getServer();
        UUID playerUuid = player.getUUID();
        String playerName =
                player.getGameProfile().getName();

        String normalizedDimension =
                parsedDimension.toString();

        float yaw = player.getYRot();
        float pitch = player.getXRot();

        if (!ClusterTransferGuard.lock(
                player,
                currentConfig.transferLockTimeoutSeconds(),
                "Проверка владельца измерения"
        )) {
            source.sendFailure(
                    Component.literal(
                            "§cДругой кластерный переход уже выполняется."
                    )
            );
            return 0;
        }

        ClusterPlayerDataCodec.Snapshot playerData;

        try {
            playerData = capturePlayerDataForTransfer(
                    player,
                    currentConfig
            );
        } catch (Exception exception) {
            ClusterTransferGuard.unlock(player);
            source.sendFailure(
                    Component.literal(
                            "§cНе удалось сохранить данные игрока перед transfer: "
                                    + exception.getMessage()
                    )
            );

            return 0;
        }

        source.sendSuccess(
                () -> Component.literal(
                        "§eИщу владельца dimension §f"
                                + normalizedDimension
                                + "§e..."
                ),
                false
        );

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig =
                        ClusterConfig.load();

                config = latestConfig;

                String targetNode =
                        ClusterDatabase.findDimensionOwner(
                                latestConfig,
                                normalizedDimension
                        );

                if (targetNode == null) {
                    scheduleRouteFailure(
                            server,
                            playerUuid,
                            "Для dimension "
                                    + normalizedDimension
                                    + " владелец не назначен."
                    );
                    return;
                }

                if (targetNode.equalsIgnoreCase(
                        latestConfig.nodeId()
                )) {
                    server.execute(
                            () -> teleportLocally(
                                    server,
                                    latestConfig,
                                    playerUuid,
                                    normalizedDimension,
                                    x,
                                    y,
                                    z,
                                    yaw,
                                    pitch
                            )
                    );

                    return;
                }

                createTransferAndScheduleRedirect(
                        server,
                        latestConfig,
                        playerUuid,
                        playerName,
                        targetNode,
                        normalizedDimension,
                        x,
                        y,
                        z,
                        yaw,
                        pitch,
                        playerData
                );
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to route player {} to dimension {}",
                        playerUuid,
                        normalizedDimension,
                        exception
                );

                scheduleTransferError(
                        server,
                        playerUuid,
                        exception
                );
            }
        });

        return 1;
    }

    private int queueTransferInternal(
            CommandSourceStack source,
            ServerPlayer player,
            String targetNode,
            String dimensionId,
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {
        ClusterConfig currentConfig = config;

        if (currentConfig == null
                || !currentConfig.enabled()) {
            source.sendFailure(
                    Component.literal(
                            "§cКластер выключен или конфиг ещё не загружен."
                    )
            );

            return 0;
        }

        MinecraftServer server = source.getServer();
        UUID playerUuid = player.getUUID();

        String playerName =
                player.getGameProfile().getName();

        if (!ClusterTransferGuard.lock(
                player,
                currentConfig.transferLockTimeoutSeconds(),
                "Подготовка межсерверного перехода"
        )) {
            source.sendFailure(
                    Component.literal(
                            "§cДругой кластерный переход уже выполняется."
                    )
            );
            return 0;
        }

        ClusterPlayerDataCodec.Snapshot playerData;

        try {
            playerData = capturePlayerDataForTransfer(
                    player,
                    currentConfig
            );
        } catch (Exception exception) {
            ClusterTransferGuard.unlock(player);
            source.sendFailure(
                    Component.literal(
                            "§cНе удалось сохранить данные игрока перед transfer: "
                                    + exception.getMessage()
                    )
            );

            return 0;
        }

        source.sendSuccess(
                () -> Component.literal(
                        "§eСоздаю transfer на узел §f"
                                + targetNode
                                + "\n§7Назначение: §f"
                                + dimensionId
                                + " "
                                + formatCoordinate(x)
                                + " "
                                + formatCoordinate(y)
                                + " "
                                + formatCoordinate(z)
                ),
                false
        );

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig =
                        ClusterConfig.load();

                config = latestConfig;

                createTransferAndScheduleRedirect(
                        server,
                        latestConfig,
                        playerUuid,
                        playerName,
                        targetNode,
                        dimensionId,
                        x,
                        y,
                        z,
                        yaw,
                        pitch,
                        playerData
                );
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to create transfer for player {}",
                        playerUuid,
                        exception
                );

                scheduleTransferError(
                        server,
                        playerUuid,
                        exception
                );
            }
        });

        return 1;
    }

    private ClusterPlayerDataCodec.Snapshot
    capturePlayerDataForTransfer(
            ServerPlayer player,
            ClusterConfig currentConfig
    ) throws Exception {
        if (!currentConfig.syncPlayerData()) {
            return null;
        }

        return ClusterPlayerDataCodec.capture(
                player,
                currentConfig.maxPlayerDataBytes(),
                currentConfig.syncForgeCapabilities()
        );
    }

    private void createTransferAndScheduleRedirect(
            MinecraftServer server,
            ClusterConfig currentConfig,
            UUID playerUuid,
            String playerName,
            String targetNode,
            String dimensionId,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            ClusterPlayerDataCodec.Snapshot playerData
    ) throws Exception {
        if (!currentConfig.enabled()) {
            throw new IllegalStateException(
                    "Кластерная система отключена"
            );
        }

        String assignedOwner =
                ClusterDatabase.findDimensionOwner(
                        currentConfig,
                        dimensionId
                );

        if (assignedOwner != null
                && !assignedOwner.equalsIgnoreCase(targetNode)) {
            throw new IllegalStateException(
                    "Dimension "
                            + dimensionId
                            + " принадлежит узлу "
                            + assignedOwner
                            + ", поэтому transfer на "
                            + targetNode
                            + " запрещён"
            );
        }

        ClusterDatabase.CreatedTransfer transfer =
                ClusterDatabase.createTransfer(
                        currentConfig,
                        playerUuid,
                        targetNode,
                        dimensionId,
                        x,
                        y,
                        z,
                        yaw,
                        pitch,
                        playerData
                );

        LOGGER.info(
                "Created transfer {} for player {}: {} -> {}, destination={} {} {} {}, redirect={}, playerData={} bytes",
                transfer.transferId(),
                transfer.playerUuid(),
                transfer.sourceNode(),
                transfer.targetNode(),
                transfer.dimensionId(),
                transfer.x(),
                transfer.y(),
                transfer.z(),
                transfer.redirectAddress(),
                transfer.playerDataSize()
        );

        server.execute(
                () -> redirectPlayer(
                        server,
                        currentConfig,
                        playerUuid,
                        playerName,
                        transfer
                )
        );
    }

    private void redirectPlayer(
            MinecraftServer server,
            ClusterConfig currentConfig,
            UUID playerUuid,
            String playerName,
            ClusterDatabase.CreatedTransfer transfer
    ) {
        ServerPlayer onlinePlayer =
                server.getPlayerList()
                        .getPlayer(playerUuid);

        if (onlinePlayer == null) {
            LOGGER.warn(
                    "Player {} left before redirect command was executed",
                    playerUuid
            );

            return;
        }

        String redirectCommand =
                "redirect "
                        + playerName
                        + " "
                        + transfer.redirectAddress();

        onlinePlayer.sendSystemMessage(
                Component.literal(
                        "§aTransfer READY: §f"
                                + transfer.sourceNode()
                                + " §7-> §f"
                                + transfer.targetNode()
                                + "\n§7Назначение: §f"
                                + transfer.dimensionId()
                                + " "
                                + formatCoordinate(transfer.x())
                                + " "
                                + formatCoordinate(transfer.y())
                                + " "
                                + formatCoordinate(transfer.z())
                                + (transfer.playerDataSize() > 0
                                ? "\n§7Данные игрока: §a"
                                + transfer.playerDataSize()
                                + " байт"
                                : "\n§7Данные игрока: §8snapshot отсутствует")
                                + "\n§eАвтоматически перенаправляю на §f"
                                + transfer.redirectAddress()
                )
        );

        if (server.getCommands()
                .getDispatcher()
                .getRoot()
                .getChild("redirect") == null) {
            LOGGER.error(
                    "Server Redirect command is not registered: {}",
                    redirectCommand
            );
            cancelTransferAfterRedirectDispatchFailure(
                    server,
                    currentConfig,
                    playerUuid,
                    transfer,
                    "Команда redirect не зарегистрирована на backend-сервере."
            );
            return;
        }

        final int redirectResult;

        try {
            redirectResult = server.getCommands()
                    .performPrefixedCommand(
                            server.createCommandSourceStack(),
                            redirectCommand
                    );
        } catch (Exception exception) {
            LOGGER.error(
                    "Server Redirect command threw an exception: {}",
                    redirectCommand,
                    exception
            );
            cancelTransferAfterRedirectDispatchFailure(
                    server,
                    currentConfig,
                    playerUuid,
                    transfer,
                    "Команда redirect завершилась исключением: "
                            + exception.getMessage()
            );
            return;
        }

        












        if (redirectResult <= 0) {
            LOGGER.warn(
                    "Server Redirect command returned {} but was dispatched; keeping transfer {} READY because this redirect implementation may report zero on success: {}",
                    redirectResult,
                    transfer.transferId(),
                    redirectCommand
            );
        } else {
            LOGGER.info(
                    "Automatic redirect command executed for player {} with result {}: {}",
                    playerUuid,
                    redirectResult,
                    redirectCommand
            );
        }
    }

    private void cancelTransferAfterRedirectDispatchFailure(
            MinecraftServer server,
            ClusterConfig currentConfig,
            UUID playerUuid,
            ClusterDatabase.CreatedTransfer transfer,
            String failureMessage
    ) {
        ClusterTransferGuard.updateReason(
                playerUuid,
                "Отмена неудачного межсерверного перехода"
        );

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.cancelReadyTransfer(
                        currentConfig,
                        transfer.transferId(),
                        playerUuid
                );

                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList()
                            .getPlayer(playerUuid);

                    if (player == null) {
                        ClusterTransferGuard.unlock(playerUuid);
                        return;
                    }

                    ClusterTransferGuard.unlock(player);
                    player.sendSystemMessage(
                            Component.literal(
                                    "§cАвтоматический redirect не выполнился: §f"
                                            + failureMessage
                                            + "\n§aTransfer безопасно отменён; "
                                            + "сессия возвращена текущему узлу."
                            )
                    );
                });
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to cancel READY transfer {} after redirect dispatch failure",
                        transfer.transferId(),
                        exception
                );

                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList()
                            .getPlayer(playerUuid);

                    if (player != null) {
                        player.connection.disconnect(
                                Component.literal(
                                        "Не удалось выполнить redirect и безопасно "
                                                + "отменить transfer. Повторите вход "
                                                + "через несколько секунд."
                                )
                        );
                    } else {
                        ClusterTransferGuard.unlock(playerUuid);
                    }
                });
            }
        });
    }

    private void teleportLocally(
            MinecraftServer server,
            ClusterConfig currentConfig,
            UUID playerUuid,
            String dimensionId,
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {
        ServerPlayer player =
                server.getPlayerList()
                        .getPlayer(playerUuid);

        if (player == null) {
            ClusterTransferGuard.unlock(playerUuid);
            return;
        }

        try {
            ResourceLocation dimensionLocation =
                    ResourceLocation.tryParse(dimensionId);

            if (dimensionLocation == null) {
                player.sendSystemMessage(
                        Component.literal(
                                "§cНекорректный dimension id: §f"
                                        + dimensionId
                        )
                );
                return;
            }

            ResourceKey<Level> dimensionKey =
                    ResourceKey.create(
                            Registries.DIMENSION,
                            dimensionLocation
                    );

            ServerLevel targetLevel =
                    server.getLevel(dimensionKey);

            if (targetLevel == null) {
                player.sendSystemMessage(
                        Component.literal(
                                "§cDimension §f"
                                        + dimensionId
                                        + "§c назначена текущему узлу §f"
                                        + currentConfig.nodeId()
                                        + "§c, но измерение на нём не загружено."
                        )
                );
                return;
            }

            suppressDimensionRoute(
                    playerUuid,
                    dimensionId
            );

            player.teleportTo(
                    targetLevel,
                    x,
                    y,
                    z,
                    yaw,
                    pitch
            );

            player.sendSystemMessage(
                    Component.literal(
                            "§aЛокальный переход выполнен: §f"
                                    + currentConfig.nodeId()
                                    + "§a, dimension: §f"
                                    + dimensionId
                    )
            );

            LOGGER.info(
                    "Applied local dimension route for player {}: node={}, dimension={}, destination={} {} {}",
                    playerUuid,
                    currentConfig.nodeId(),
                    dimensionId,
                    x,
                    y,
                    z
            );
        } catch (Exception exception) {
            LOGGER.error(
                    "Unable to teleport player {} locally to dimension {}",
                    playerUuid,
                    dimensionId,
                    exception
            );

            player.sendSystemMessage(
                    Component.literal(
                            "§cОшибка локальной телепортации: "
                                    + exception.getMessage()
                    )
            );
        } finally {
            ClusterTransferGuard.unlock(player);
        }
    }

    private void applyFtbTeleportLocally(
            MinecraftServer server,
            ClusterConfig currentConfig,
            UUID playerUuid,
            String dimensionId,
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {
        ServerPlayer player =
                server.getPlayerList()
                        .getPlayer(playerUuid);

        if (player == null) {
            ClusterTransferGuard.unlock(playerUuid);
            return;
        }

        try {
            ResourceLocation dimensionLocation =
                    ResourceLocation.tryParse(dimensionId);

            if (dimensionLocation == null) {
                player.sendSystemMessage(
                        Component.literal(
                                "§cНекорректный dimension id: §f"
                                        + dimensionId
                        )
                );
                return;
            }

            ResourceKey<Level> dimensionKey =
                    ResourceKey.create(
                            Registries.DIMENSION,
                            dimensionLocation
                    );

            ServerLevel targetLevel =
                    server.getLevel(dimensionKey);

            if (targetLevel == null) {
                player.sendSystemMessage(
                        Component.literal(
                                "§cDimension "
                                        + dimensionId
                                        + " назначена текущему узлу "
                                        + currentConfig.nodeId()
                                        + ", но не загружена на нём."
                        )
                );
                return;
            }

            int experienceLevel = player.experienceLevel;

            suppressDimensionRoute(
                    playerUuid,
                    dimensionId
            );

            player.teleportTo(
                    targetLevel,
                    x,
                    y,
                    z,
                    yaw,
                    pitch
            );

            player.experienceLevel = experienceLevel;
        } catch (Exception exception) {
            LOGGER.error(
                    "Unable to finish local FTB Essentials teleport for player {} to {}",
                    playerUuid,
                    dimensionId,
                    exception
            );

            player.sendSystemMessage(
                    Component.literal(
                            "§cОшибка локальной телепортации: "
                                    + exception.getMessage()
                    )
            );
        } finally {
            ClusterTransferGuard.unlock(player);
        }
    }

    private void refreshDimensionOwnerCache(
            ClusterConfig currentConfig
    ) throws java.sql.SQLException {
        Map<String, String> owners =
                ClusterDatabase.listDimensionOwners(
                        currentConfig
                );
        DIMENSION_OWNER_CACHE = owners;
        DIMENSION_OWNER_CACHE_REFRESHED_AT_MILLIS =
                System.currentTimeMillis();
    }

    private static boolean isDimensionOwnerCacheFresh(
            ClusterConfig currentConfig
    ) {
        long refreshedAt = DIMENSION_OWNER_CACHE_REFRESHED_AT_MILLIS;
        if (refreshedAt <= 0L) {
            return false;
        }

        long maximumAgeMillis =
                currentConfig.dimensionOwnerCacheMaxAgeSeconds()
                        * 1_000L;
        long ageMillis = Math.max(
                0L,
                System.currentTimeMillis() - refreshedAt
        );
        return ageMillis <= maximumAgeMillis;
    }

    private static long dimensionOwnerCacheAgeSeconds() {
        long refreshedAt = DIMENSION_OWNER_CACHE_REFRESHED_AT_MILLIS;
        if (refreshedAt <= 0L) {
            return -1L;
        }

        return Math.max(
                0L,
                (System.currentTimeMillis() - refreshedAt) / 1_000L
        );
    }

    private static String dimensionOwnerCacheState(
            ClusterConfig currentConfig
    ) {
        long ageSeconds = dimensionOwnerCacheAgeSeconds();
        if (ageSeconds < 0L) {
            return "never loaded";
        }

        String freshness = isDimensionOwnerCacheFresh(currentConfig)
                ? "fresh"
                : "stale";
        return freshness
                + ", age="
                + ageSeconds
                + "s, max="
                + currentConfig.dimensionOwnerCacheMaxAgeSeconds()
                + "s";
    }

    private static void updateCachedDimensionOwner(
            String dimensionId,
            String nodeId
    ) {
        Map<String, String> updated =
                new HashMap<>(
                        DIMENSION_OWNER_CACHE
                );

        updated.put(
                dimensionId,
                nodeId
        );

        DIMENSION_OWNER_CACHE =
                Map.copyOf(updated);
    }

    private static void removeCachedDimensionOwner(
            String dimensionId
    ) {
        if (!DIMENSION_OWNER_CACHE.containsKey(
                dimensionId
        )) {
            return;
        }

        Map<String, String> updated =
                new HashMap<>(
                        DIMENSION_OWNER_CACHE
                );

        updated.remove(dimensionId);

        DIMENSION_OWNER_CACHE =
                Map.copyOf(updated);
    }

    private void scheduleRouteFailure(
            MinecraftServer server,
            UUID playerUuid,
            String message
    ) {
        server.execute(() -> {
            ServerPlayer onlinePlayer =
                    server.getPlayerList()
                            .getPlayer(playerUuid);

            if (onlinePlayer != null) {
                ClusterTransferGuard.unlock(onlinePlayer);
                onlinePlayer.sendSystemMessage(
                        Component.literal(
                                "§c" + message
                        )
                );
            } else {
                ClusterTransferGuard.unlock(playerUuid);
            }
        });
    }

    private void scheduleTransferError(
            MinecraftServer server,
            UUID playerUuid,
            Exception exception
    ) {
        server.execute(() -> {
            ServerPlayer onlinePlayer =
                    server.getPlayerList()
                            .getPlayer(playerUuid);

            if (onlinePlayer == null) {
                ClusterTransferGuard.unlock(playerUuid);
                return;
            }

            ClusterTransferGuard.unlock(onlinePlayer);
            onlinePlayer.sendSystemMessage(
                    Component.literal(
                            "§cНе удалось создать или выполнить маршрут: "
                                    + exception
                                    .getClass()
                                    .getSimpleName()
                                    + ": "
                                    + exception.getMessage()
                    )
            );
        });
    }

    private static String formatCoordinate(
            double coordinate
    ) {
        return String.format(
                java.util.Locale.ROOT,
                "%.2f",
                coordinate
        );
    }

    private void applyTransfer(
            MinecraftServer server,
            ClusterConfig currentConfig,
            ClusterDatabase.PendingTransfer transfer
    ) {
        ServerPlayer player =
                server.getPlayerList()
                        .getPlayer(
                                transfer.playerUuid()
                        );

        if (player == null) {
            failTransfer(
                    server,
                    currentConfig,
                    transfer,
                    "Игрок вышел до применения transfer"
            );
            return;
        }

        ResourceLocation dimensionLocation =
                ResourceLocation.tryParse(
                        transfer.dimensionId()
                );

        if (dimensionLocation == null) {
            failTransfer(
                    server,
                    currentConfig,
                    transfer,
                    "Некорректный dimension id: "
                            + transfer.dimensionId()
            );
            return;
        }

        ResourceKey<Level> dimensionKey =
                ResourceKey.create(
                        Registries.DIMENSION,
                        dimensionLocation
                );

        ServerLevel targetLevel =
                server.getLevel(dimensionKey);

        if (targetLevel == null) {
            failTransfer(
                    server,
                    currentConfig,
                    transfer,
                    "Измерение отсутствует на узле: "
                            + transfer.dimensionId()
            );
            return;
        }

        try {
            ClusterPlayerDataCodec.ApplyResult playerDataResult =
                    applyPlayerDataSnapshot(
                            player,
                            currentConfig,
                            transfer
                    );

            suppressDimensionRoute(
                    transfer.playerUuid(),
                    transfer.dimensionId()
            );

            player.teleportTo(
                    targetLevel,
                    transfer.x(),
                    transfer.y(),
                    transfer.z(),
                    transfer.yaw(),
                    transfer.pitch()
            );

            
            
            server.getPlayerList().saveAll();

            LOGGER.info(
                    "Applied transfer {} for player {} on node {}, playerDataPresent={}, playerDataApplied={}, playerDataAlreadyApplied={}, playerDataSize={}",
                    transfer.transferId(),
                    transfer.playerUuid(),
                    currentConfig.nodeId(),
                    playerDataResult.snapshotPresent(),
                    playerDataResult.applied(),
                    playerDataResult.alreadyApplied(),
                    playerDataResult.compressedSize()
            );

            ClusterTransferGuard.updateReason(
                    transfer.playerUuid(),
                    "Фиксация кластерной сессии"
            );

            DATABASE_EXECUTOR.execute(() -> {
                try {
                    ClusterDatabase.markConsumed(
                            currentConfig,
                            transfer.transferId(),
                            transfer.playerUuid()
                    );

                    server.execute(() -> {
                        ServerPlayer onlinePlayer =
                                server.getPlayerList()
                                        .getPlayer(transfer.playerUuid());

                        if (onlinePlayer == null) {
                            ClusterTransferGuard.unlock(
                                    transfer.playerUuid()
                            );
                            return;
                        }

                        ClusterTransferGuard.unlock(onlinePlayer);
                        onlinePlayer.sendSystemMessage(
                                Component.literal(
                                        "§aTransfer выполнен: §f"
                                                + transfer.sourceNode()
                                                + " §7-> §f"
                                                + transfer.targetNode()
                                                + "§a, dimension: §f"
                                                + transfer.dimensionId()
                                                + formatPlayerDataApplyMessage(
                                                playerDataResult
                                        )
                                )
                        );
                    });
                } catch (Exception exception) {
                    LOGGER.error(
                            "Unable to mark transfer {} as CONSUMED",
                            transfer.transferId(),
                            exception
                    );

                    server.execute(() -> {
                        ServerPlayer onlinePlayer =
                                server.getPlayerList()
                                        .getPlayer(transfer.playerUuid());

                        if (onlinePlayer != null) {
                            onlinePlayer.connection.disconnect(
                                    Component.literal(
                                            "Данные transfer применены, но кластерная "
                                                    + "сессия не была зафиксирована. "
                                                    + "Вы отключены для защиты от дюпа. "
                                                    + "Повторите вход через несколько секунд."
                                    )
                            );
                        } else {
                            ClusterTransferGuard.unlock(
                                    transfer.playerUuid()
                            );
                        }
                    });
                }
            });
        } catch (Exception exception) {
            failTransfer(
                    server,
                    currentConfig,
                    transfer,
                    exception.getClass().getSimpleName()
                            + ": "
                            + exception.getMessage()
            );
        }
    }

    private ClusterPlayerDataCodec.ApplyResult
    applyPlayerDataSnapshot(
            ServerPlayer player,
            ClusterConfig currentConfig,
            ClusterDatabase.PendingTransfer transfer
    ) throws Exception {
        byte[] playerData = transfer.playerData();
        boolean snapshotPresent =
                playerData != null
                        && playerData.length > 0;

        if (!snapshotPresent) {
            if (transfer.playerDataSize() != 0
                    || transfer.playerDataCodec() != 0
                    || transfer.playerDataSha256() != null) {
                throw new IllegalStateException(
                        "Player-data snapshot metadata is inconsistent"
                );
            }

            return new ClusterPlayerDataCodec.ApplyResult(
                    false,
                    false,
                    false,
                    0,
                    null
            );
        }

        if (!currentConfig.syncPlayerData()) {
            throw new IllegalStateException(
                    "На целевом узле sync_player_data=false, snapshot не применён"
            );
        }

        if (transfer.playerDataSize() != playerData.length) {
            throw new IllegalStateException(
                    "Player-data size mismatch: declared "
                            + transfer.playerDataSize()
                            + ", actual "
                            + playerData.length
            );
        }

        return ClusterPlayerDataCodec.apply(
                player,
                transfer.transferId(),
                transfer.playerDataCodec(),
                playerData,
                transfer.playerDataSha256(),
                currentConfig.maxPlayerDataBytes()
        );
    }

    private static String formatPlayerDataApplyMessage(
            ClusterPlayerDataCodec.ApplyResult result
    ) {
        if (!result.snapshotPresent()) {
            return "\n§7Данные игрока: §8snapshot отсутствует";
        }

        if (result.alreadyApplied()) {
            return "\n§7Данные игрока: §eуже были применены §7("
                    + result.compressedSize()
                    + " байт)";
        }

        return "\n§7Данные игрока: §aсинхронизированы §7("
                + result.compressedSize()
                + " байт)";
    }

    private void failTransfer(
            MinecraftServer server,
            ClusterConfig currentConfig,
            ClusterDatabase.PendingTransfer transfer,
            String reason
    ) {
        LOGGER.error(
                "Transfer {} failed for player {}: {}",
                transfer.transferId(),
                transfer.playerUuid(),
                reason
        );

        ClusterTransferGuard.updateReason(
                transfer.playerUuid(),
                "Отмена повреждённого transfer"
        );

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.markFailed(
                        currentConfig,
                        transfer.transferId(),
                        transfer.playerUuid()
                );
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to mark transfer {} as FAILED",
                        transfer.transferId(),
                        exception
                );
            } finally {
                server.execute(() -> {
                    ServerPlayer player = server.getPlayerList()
                            .getPlayer(transfer.playerUuid());

                    if (player == null) {
                        ClusterTransferGuard.unlock(
                                transfer.playerUuid()
                        );
                        return;
                    }

                    player.connection.disconnect(
                            Component.literal(
                                    "Не удалось безопасно применить transfer: "
                                            + reason
                                            + "\nИгрок отключён для защиты данных."
                            )
                    );
                });
            }
        });
    }

    private int createDimensionSnapshot(
            CommandSourceStack source,
            String dimensionId
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        if (currentConfig.dimensionMigrationStagingPath() == null) {
            source.sendFailure(Component.literal("§cВ конфиге не указан dimension_migration_staging_path."));
            return 0;
        }
        if (!SNAPSHOT_OPERATION_IN_FLIGHT.compareAndSet(false, true)) {
            source.sendFailure(Component.literal("§cУже выполняется операция со snapshots."));
            return 0;
        }
        ResourceLocation parsed = ResourceLocation.tryParse(dimensionId);
        if (parsed == null) {
            SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
            source.sendFailure(Component.literal("§cНекорректный dimension id: §f" + dimensionId));
            return 0;
        }
        MinecraftServer server = source.getServer();
        String normalized = parsed.toString();
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, parsed));
        if (level == null) {
            SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
            source.sendFailure(Component.literal("§cИзмерение не загружено на этом узле."));
            return 0;
        }
        if (!level.players().isEmpty()) {
            SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
            source.sendFailure(Component.literal("§cВ измерении находятся игроки: §f" + level.players().size()));
            return 0;
        }
        try {
            ClusterDimensionMigration.resolveDimensionPath(server, normalized);
        } catch (Exception exception) {
            SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
            source.sendFailure(Component.literal("§cSnapshot недоступен: " + exception.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§eПодготавливаю snapshot §f" + normalized), false);
        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;
                ClusterDatabase.DimensionSnapshot snapshot =
                        ClusterDatabase.requestDimensionSnapshot(latestConfig, normalized);
                server.execute(() -> startDimensionSnapshotArchive(
                        source,
                        server,
                        latestConfig,
                        snapshot,
                        success -> SNAPSHOT_OPERATION_IN_FLIGHT.set(false),
                        false,
                        true
                ));
            } catch (Exception exception) {
                SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось создать snapshot: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private int createAllDimensionSnapshots(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        if (currentConfig.dimensionMigrationStagingPath() == null) {
            source.sendFailure(Component.literal("§cВ конфиге не указан dimension_migration_staging_path."));
            return 0;
        }
        if (!SNAPSHOT_OPERATION_IN_FLIGHT.compareAndSet(false, true)) {
            source.sendFailure(Component.literal("§cУже выполняется операция со snapshots."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("§eЗапускаю snapshots всех доступных измерений этого узла..."), false);
        startDimensionSnapshotBatch(source, source.getServer(), currentConfig, false);
        return 1;
    }

    private int cleanupDimensionSnapshots(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        if (currentConfig.dimensionMigrationStagingPath() == null) {
            source.sendFailure(Component.literal("§cВ конфиге не указан dimension_migration_staging_path."));
            return 0;
        }
        if (!SNAPSHOT_OPERATION_IN_FLIGHT.compareAndSet(false, true)) {
            source.sendFailure(Component.literal("§cУже выполняется операция со snapshots."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        MIGRATION_EXECUTOR.execute(() -> {
            try {
                SnapshotCleanupResult result = cleanupDimensionSnapshotsInternal(currentConfig);
                server.execute(() -> source.sendSuccess(() -> Component.literal(
                        "§aОчистка snapshots завершена: §f"
                                + result.deleted()
                                + "§a архивов, освобождено §f"
                                + result.bytes()
                                + "§a bytes"
                ), false));
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cОчистка snapshots завершилась ошибкой: " + exception.getMessage()
                )));
            } finally {
                SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
            }
        });
        return 1;
    }

    private int showDimensionSnapshotSchedule(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        long seconds = Math.max(
                0L,
                (nextAutomaticSnapshotAtMillis - System.currentTimeMillis() + 999L) / 1000L
        );
        source.sendSuccess(() -> Component.literal(
                "§6Automatic snapshots: §f"
                        + currentConfig.automaticDimensionSnapshots()
                        + "§7 | interval: §f"
                        + currentConfig.dimensionSnapshotIntervalMinutes()
                        + " min§7 | retention: §f"
                        + currentConfig.dimensionSnapshotRetentionDays()
                        + " days§7 | max per dimension: §f"
                        + currentConfig.dimensionSnapshotMaxPerDimension()
                        + "§7 | failover max age: §f"
                        + currentConfig.dimensionSnapshotMaxAgeMinutes()
                        + " min§7 | next: §f"
                        + seconds
                        + "s§7 | busy: §f"
                        + SNAPSHOT_OPERATION_IN_FLIGHT.get()
        ), false);
        if (lastAutomaticSnapshotSummary != null) {
            source.sendSuccess(() -> Component.literal(
                    "§7Последний автоматический запуск: §f" + lastAutomaticSnapshotSummary
            ), false);
        }
        return 1;
    }

    private void startAutomaticDimensionSnapshotsIfDue(
            MinecraftServer server,
            ClusterConfig currentConfig
    ) {
        if (!currentConfig.automaticDimensionSnapshots()
                || currentConfig.dimensionMigrationStagingPath() == null
                || System.currentTimeMillis() < nextAutomaticSnapshotAtMillis) {
            return;
        }
        nextAutomaticSnapshotAtMillis = System.currentTimeMillis()
                + currentConfig.dimensionSnapshotIntervalMinutes() * 60_000L;
        if (!SNAPSHOT_OPERATION_IN_FLIGHT.compareAndSet(false, true)) {
            lastAutomaticSnapshotSummary = "пропущен: другая snapshot-операция уже выполнялась";
            return;
        }
        startDimensionSnapshotBatch(null, server, currentConfig, true);
    }

    private void startDimensionSnapshotBatch(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            boolean automatic
    ) {
        List<String> registered = registeredDimensionIds(server);
        DATABASE_EXECUTOR.execute(() -> {
            try {
                List<ClusterDatabase.DimensionAssignmentInfo> assignments =
                        ClusterDatabase.listDimensionAssignments(currentConfig, registered);
                List<String> dimensions = new ArrayList<>();
                long dueAfter = currentConfig.dimensionSnapshotIntervalMinutes() * 60_000L;
                long now = System.currentTimeMillis();
                for (ClusterDatabase.DimensionAssignmentInfo assignment : assignments) {
                    if (assignment.nodeId() == null
                            || !assignment.nodeId().equalsIgnoreCase(currentConfig.nodeId())
                            || assignment.activePlayers() > 0
                            || Level.OVERWORLD.location().toString().equals(assignment.dimensionId())) {
                        continue;
                    }
                    if (automatic) {
                        ClusterDatabase.DimensionSnapshot latest =
                                ClusterDatabase.findLatestReadyDimensionSnapshot(
                                        currentConfig,
                                        assignment.dimensionId(),
                                        currentConfig.nodeId()
                                );
                        if (latest != null
                                && latest.readyAt() != null
                                && now - latest.readyAt().toEpochMilli() < dueAfter) {
                            continue;
                        }
                    }
                    dimensions.add(assignment.dimensionId());
                }
                server.execute(() -> {
                    List<String> available = new ArrayList<>();
                    for (String dimensionId : dimensions) {
                        ResourceLocation parsed = ResourceLocation.tryParse(dimensionId);
                        ServerLevel level = parsed == null
                                ? null
                                : server.getLevel(ResourceKey.create(Registries.DIMENSION, parsed));
                        if (level == null || !level.players().isEmpty()) {
                            continue;
                        }
                        try {
                            ClusterDimensionMigration.resolveDimensionPath(server, dimensionId);
                            available.add(dimensionId);
                        } catch (Exception ignored) {
                        }
                    }
                    SnapshotBatchState state = new SnapshotBatchState(available, automatic);
                    state.skipped = Math.max(0, dimensions.size() - available.size());
                    if (available.isEmpty()) {
                        finishDimensionSnapshotBatch(source, server, currentConfig, state);
                        return;
                    }
                    for (String dimensionId : available) {
                        addSnapshotFreeze(dimensionId);
                    }
                    try {
                        if (!server.saveEverything(true, true, true)) {
                            throw new IllegalStateException("MinecraftServer не подтвердил сохранение мира");
                        }
                    } catch (Exception exception) {
                        for (String dimensionId : available) {
                            removeSnapshotFreeze(dimensionId);
                        }
                        SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
                        lastAutomaticSnapshotSummary = "ошибка сохранения: " + exception.getMessage();
                        if (source != null) {
                            source.sendFailure(Component.literal(
                                    "§cНе удалось сохранить мир перед createall: " + exception.getMessage()
                            ));
                        } else {
                            LOGGER.error("Unable to save worlds before automatic dimension snapshots", exception);
                        }
                        return;
                    }
                    continueDimensionSnapshotBatch(source, server, currentConfig, state);
                });
            } catch (Exception exception) {
                SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
                lastAutomaticSnapshotSummary = "ошибка: " + exception.getMessage();
                if (source != null) {
                    server.execute(() -> source.sendFailure(Component.literal(
                            "§cНе удалось подготовить createall: " + exception.getMessage()
                    )));
                } else {
                    LOGGER.error("Unable to prepare automatic dimension snapshots", exception);
                }
            }
        });
    }

    private void continueDimensionSnapshotBatch(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            SnapshotBatchState state
    ) {
        if (state.index >= state.dimensions.size()) {
            finishDimensionSnapshotBatch(source, server, currentConfig, state);
            return;
        }
        String dimensionId = state.dimensions.get(state.index++);
        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.DimensionSnapshot snapshot =
                        ClusterDatabase.requestDimensionSnapshot(currentConfig, dimensionId);
                server.execute(() -> startDimensionSnapshotArchive(
                        null,
                        server,
                        currentConfig,
                        snapshot,
                        success -> {
                            if (success) {
                                state.created++;
                            } else {
                                state.failed++;
                            }
                            continueDimensionSnapshotBatch(source, server, currentConfig, state);
                        },
                        true,
                        false
                ));
            } catch (Exception exception) {
                state.skipped++;
                removeSnapshotFreeze(dimensionId);
                server.execute(() -> continueDimensionSnapshotBatch(
                        source,
                        server,
                        currentConfig,
                        state
                ));
            }
        });
    }

    private void finishDimensionSnapshotBatch(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            SnapshotBatchState state
    ) {
        for (String dimensionId : state.dimensions) {
            removeSnapshotFreeze(dimensionId);
        }
        MIGRATION_EXECUTOR.execute(() -> {
            SnapshotCleanupResult cleanup = new SnapshotCleanupResult(0, 0L);
            String cleanupError = null;
            try {
                cleanup = cleanupDimensionSnapshotsInternal(currentConfig);
            } catch (Exception exception) {
                cleanupError = exception.getMessage();
            }
            SnapshotCleanupResult finalCleanup = cleanup;
            String finalCleanupError = cleanupError;
            server.execute(() -> {
                SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
                String summary = "created="
                        + state.created
                        + ", skipped="
                        + state.skipped
                        + ", failed="
                        + state.failed
                        + ", deleted="
                        + finalCleanup.deleted();
                if (state.automatic) {
                    lastAutomaticSnapshotSummary = summary;
                    LOGGER.info("Automatic dimension snapshots finished: {}", summary);
                }
                if (source != null) {
                    source.sendSuccess(() -> Component.literal(
                            "§aCreateall завершён: §f"
                                    + state.created
                                    + "§a создано, §f"
                                    + state.skipped
                                    + "§a пропущено, §f"
                                    + state.failed
                                    + "§a ошибок, удалено старых: §f"
                                    + finalCleanup.deleted()
                    ), false);
                    if (finalCleanupError != null) {
                        source.sendFailure(Component.literal(
                                "§cОшибка очистки старых snapshots: " + finalCleanupError
                        ));
                    }
                }
            });
        });
    }

    private SnapshotCleanupResult cleanupDimensionSnapshotsInternal(
            ClusterConfig currentConfig
    ) throws Exception {
        List<ClusterDatabase.DimensionSnapshot> candidates =
                ClusterDatabase.listDimensionSnapshotCleanupCandidates(
                        currentConfig,
                        currentConfig.dimensionSnapshotRetentionDays(),
                        currentConfig.dimensionSnapshotMaxPerDimension()
                );
        int deleted = 0;
        long bytes = 0L;
        for (ClusterDatabase.DimensionSnapshot snapshot : candidates) {
            ClusterDimensionMigration.deleteArchive(
                    currentConfig.dimensionMigrationStagingPath(),
                    snapshot.archiveName()
            );
            ClusterDatabase.markDimensionSnapshotDeleted(
                    currentConfig,
                    snapshot.snapshotId()
            );
            deleted++;
            bytes += Math.max(0L, snapshot.archiveSize());
        }
        return new SnapshotCleanupResult(deleted, bytes);
    }

    private void startDimensionSnapshotArchive(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            ClusterDatabase.DimensionSnapshot snapshot,
            Consumer<Boolean> completion,
            boolean worldAlreadySaved,
            boolean announce
    ) {
        ResourceLocation parsed = ResourceLocation.tryParse(snapshot.dimensionId());
        ServerLevel level = parsed == null
                ? null
                : server.getLevel(ResourceKey.create(Registries.DIMENSION, parsed));
        if (level == null || !level.players().isEmpty()) {
            failDimensionSnapshot(
                    source,
                    server,
                    currentConfig,
                    snapshot,
                    level == null ? "Измерение не загружено" : "В измерении появились игроки",
                    completion
            );
            return;
        }
        addSnapshotFreeze(snapshot.dimensionId());
        if (!worldAlreadySaved) {
            try {
                if (!server.saveEverything(true, true, true)) {
                    throw new IllegalStateException("MinecraftServer не подтвердил сохранение мира");
                }
            } catch (Exception exception) {
                failDimensionSnapshot(
                        source,
                        server,
                        currentConfig,
                        snapshot,
                        exception.getMessage(),
                        completion
                );
                return;
            }
        }
        if (announce && source != null) {
            source.sendSuccess(() -> Component.literal("§eИзмерение заморожено. Создаю snapshot-архив..."), false);
        }
        MIGRATION_EXECUTOR.execute(() -> {
            ClusterDimensionMigration.PreparedArchive archive = null;
            try {
                archive = ClusterDimensionMigration.createArchive(
                        server,
                        snapshot.dimensionId(),
                        currentConfig.dimensionMigrationStagingPath(),
                        "snapshot-" + snapshot.snapshotId()
                );
                ClusterDatabase.DimensionSnapshot ready =
                        ClusterDatabase.markDimensionSnapshotReady(
                                currentConfig,
                                snapshot.snapshotId(),
                                archive.archiveName(),
                                archive.archiveSha256(),
                                archive.contentSha256(),
                                archive.archiveSize()
                        );
                removeSnapshotFreeze(snapshot.dimensionId());
                ClusterDimensionMigration.PreparedArchive finalArchive = archive;
                server.execute(() -> {
                    if (announce && source != null) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "§aSnapshot READY: §f"
                                                + ready.snapshotId()
                                                + " §7| §f"
                                                + ready.dimensionId()
                                                + " §7| archive: §f"
                                                + finalArchive.archiveSize()
                                                + " bytes"
                                ),
                                false
                        );
                    }
                    completion.accept(true);
                });
            } catch (Exception exception) {
                if (archive != null) {
                    try {
                        ClusterDimensionMigration.deleteArchive(
                                currentConfig.dimensionMigrationStagingPath(),
                                archive.archiveName()
                        );
                    } catch (Exception ignored) {
                    }
                }
                failDimensionSnapshot(
                        source,
                        server,
                        currentConfig,
                        snapshot,
                        exception.getClass().getSimpleName() + ": " + exception.getMessage(),
                        completion
                );
            }
        });
    }

    private void failDimensionSnapshot(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            ClusterDatabase.DimensionSnapshot snapshot,
            String error,
            Consumer<Boolean> completion
    ) {
        MIGRATION_EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.failDimensionSnapshot(
                        currentConfig,
                        snapshot.snapshotId(),
                        error
                );
            } catch (Exception exception) {
                LOGGER.error("Unable to fail dimension snapshot {}", snapshot.snapshotId(), exception);
            }
            removeSnapshotFreeze(snapshot.dimensionId());
            server.execute(() -> {
                if (source != null) {
                    source.sendFailure(Component.literal(
                            "§cSnapshot завершился ошибкой: " + error
                    ));
                }
                completion.accept(false);
            });
        });
    }

    private int previewNodeDrain(
            CommandSourceStack source,
            String targetNode
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        Map<String, Integer> localActivity = captureDimensionPlayerCounts(server);
        source.sendSuccess(() -> Component.literal(
                "§eСтрою план drain: §f" + currentConfig.nodeId() + " §7-> §f" + targetNode
        ), false);
        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;
                ClusterDatabase.heartbeat(
                        latestConfig,
                        server,
                        localActivity
                );
                ClusterDatabase.NodeDrainPreview preview =
                        ClusterDatabase.previewNodeDrain(latestConfig, targetNode);
                server.execute(() -> {
                    int ready = 0;
                    int blocked = 0;
                    for (ClusterDatabase.NodeDrainPreviewEntry entry : preview.entries()) {
                        String localReason = nodeDrainLocalReason(server, entry);
                        if (entry.executable() && localReason == null) {
                            ready++;
                        } else {
                            blocked++;
                        }
                    }
                    int finalReady = ready;
                    int finalBlocked = blocked;
                    int localPlayers = server.getPlayerList().getPlayerCount();
                    source.sendSuccess(() -> Component.literal(
                            "§6Drain preview: §aready=" + finalReady
                                    + "§7, blocked=" + finalBlocked
                                    + "§7, players=" + localPlayers
                                    + "§7, target="
                                    + (preview.targetReady() ? "§aONLINE" : "§cBLOCKED")
                    ), false);
                    if (localPlayers > 0) {
                        source.sendSuccess(() -> Component.literal(
                                "§eПеред start эвакуируй игроков командой drain evacuate или вручную."
                        ), false);
                    }
                    for (ClusterDatabase.NodeDrainPreviewEntry entry : preview.entries()) {
                        String localReason = nodeDrainLocalReason(server, entry);
                        boolean executable = entry.executable() && localReason == null;
                        String reason = localReason != null ? localReason : entry.reason();
                        String state = executable ? "§aREADY" : "§cBLOCKED";
                        String suffix = reason == null ? "" : " §7| §f" + reason;
                        source.sendSuccess(() -> Component.literal(
                                state
                                        + " §f" + entry.dimensionId()
                                        + " §7| §f" + entry.sourceNode()
                                        + " §7-> §f" + entry.targetNode()
                                        + suffix
                        ), false);
                    }
                });
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось построить drain preview: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private static String nodeDrainLocalReason(
            MinecraftServer server,
            ClusterDatabase.NodeDrainPreviewEntry entry
    ) {
        if (!entry.executable()) {
            return null;
        }
        ResourceLocation parsed = ResourceLocation.tryParse(entry.dimensionId());
        ServerLevel level = parsed == null
                ? null
                : server.getLevel(ResourceKey.create(Registries.DIMENSION, parsed));
        if (level == null) {
            return "измерение не загружено";
        }
        if (!level.players().isEmpty()) {
            return "в измерении находятся игроки";
        }
        try {
            ClusterDimensionMigration.resolveDimensionPath(server, entry.dimensionId());
        } catch (Exception exception) {
            return exception.getMessage();
        }
        return null;
    }

    private int evacuateNodeDrainPlayers(
            CommandSourceStack source,
            String targetNode,
            String dimensionId,
            double x,
            double y,
            double z
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;
                ClusterDatabase.NodeDrainPreview preview =
                        ClusterDatabase.previewNodeDrain(latestConfig, targetNode);
                if (!preview.targetReady()) {
                    throw new IllegalStateException(
                            "Target node OFFLINE или находится в drain-режиме: " + targetNode
                    );
                }
                String owner = ClusterDatabase.findDimensionOwner(
                        latestConfig,
                        dimensionId
                );
                if (owner == null || !owner.equalsIgnoreCase(targetNode)) {
                    throw new IllegalStateException(
                            "Dimension " + dimensionId + " не принадлежит target node " + targetNode
                    );
                }
                server.execute(() -> {
                    List<ServerPlayer> players = new ArrayList<>(
                            server.getPlayerList().getPlayers()
                    );
                    if (players.isEmpty()) {
                        source.sendSuccess(() -> Component.literal(
                                "§aНа текущем узле нет игроков для эвакуации."
                        ), false);
                        return;
                    }
                    int queued = 0;
                    for (ServerPlayer player : players) {
                        int result = queueTransferInternal(
                                source,
                                player,
                                targetNode,
                                dimensionId,
                                x,
                                y,
                                z,
                                player.getYRot(),
                                player.getXRot()
                        );
                        if (result > 0) {
                            queued++;
                        }
                    }
                    int finalQueued = queued;
                    source.sendSuccess(() -> Component.literal(
                            "§aЭвакуация поставлена в очередь: §f" + finalQueued
                                    + "§a из §f" + players.size()
                                    + "§a игроков."
                    ), false);
                });
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось начать эвакуацию: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private int prepareNodeDrain(
            CommandSourceStack source,
            String targetNode
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        if (currentConfig.dimensionMigrationStagingPath() == null) {
            source.sendFailure(Component.literal("§cВ конфиге не указан dimension_migration_staging_path."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (!server.getPlayerList().getPlayers().isEmpty()) {
            source.sendFailure(Component.literal(
                    "§cDrain start запрещён: на текущем узле ещё находятся игроки."
            ));
            return 0;
        }
        if (!DRAIN_OPERATION_IN_FLIGHT.compareAndSet(false, true)) {
            source.sendFailure(Component.literal("§cУже выполняется drain."));
            return 0;
        }
        if (!SNAPSHOT_OPERATION_IN_FLIGHT.compareAndSet(false, true)) {
            DRAIN_OPERATION_IN_FLIGHT.set(false);
            source.sendFailure(Component.literal("§cСначала дождись завершения snapshot-операции."));
            return 0;
        }
        Map<String, Integer> localActivity = captureDimensionPlayerCounts(server);
        Set<String> locallyAvailableDimensions = collectNodeDrainLocalCandidates(server);
        if (locallyAvailableDimensions.isEmpty()) {
            DRAIN_OPERATION_IN_FLIGHT.set(false);
            SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
            source.sendFailure(Component.literal(
                    "§cНет локально загруженных измерений, доступных для drain."
            ));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "§eПодготавливаю drain: §f" + currentConfig.nodeId() + " §7-> §f" + targetNode
        ), false);
        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;
                ClusterDatabase.heartbeat(
                        latestConfig,
                        server,
                        localActivity
                );
                ClusterDatabase.NodeDrainPreparationResult result =
                        ClusterDatabase.prepareNodeDrain(
                                latestConfig,
                                targetNode,
                                locallyAvailableDimensions
                        );
                server.execute(() -> startNodeDrainBatch(
                        source,
                        server,
                        latestConfig,
                        result
                ));
            } catch (Exception exception) {
                DRAIN_OPERATION_IN_FLIGHT.set(false);
                SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось подготовить drain: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private static Set<String> collectNodeDrainLocalCandidates(
            MinecraftServer server
    ) {
        Set<String> result = new TreeSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            if (!level.players().isEmpty()) {
                continue;
            }
            String dimensionId = level.dimension().location().toString();
            try {
                ClusterDimensionMigration.resolveDimensionPath(server, dimensionId);
                result.add(dimensionId);
            } catch (Exception ignored) {
            }
        }
        return Set.copyOf(result);
    }

    private void startNodeDrainBatch(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            ClusterDatabase.NodeDrainPreparationResult preparation
    ) {
        startNodeDrainBatch(
                source,
                server,
                currentConfig,
                preparation.drain(),
                preparation.items(),
                preparation.skipped(),
                false,
                0,
                0,
                null,
                false
        );
    }

    private void startNodeOperationRecoveryBatch(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            ClusterDatabase.NodeOperationRecoveryResult recovery,
            String leaseName,
            boolean automatic
    ) {
        startNodeDrainBatch(
                source,
                server,
                currentConfig,
                recovery.operation(),
                recovery.items(),
                recovery.skipped(),
                true,
                recovery.alreadyReady(),
                recovery.alreadyApplied(),
                leaseName,
                automatic
        );
    }

    private void startNodeDrainBatch(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            ClusterDatabase.NodeDrain drain,
            List<ClusterDatabase.DimensionDrainItem> candidateItems,
            int skipped,
            boolean recovery,
            int alreadyReady,
            int alreadyApplied,
            String leaseName,
            boolean automatic
    ) {
        List<ClusterDatabase.DimensionDrainItem> available = new ArrayList<>();
        Map<ClusterDatabase.DimensionDrainItem, String> rejected = new LinkedHashMap<>();
        for (ClusterDatabase.DimensionDrainItem item : candidateItems) {
            ResourceLocation parsed = ResourceLocation.tryParse(item.dimensionId());
            ServerLevel level = parsed == null
                    ? null
                    : server.getLevel(ResourceKey.create(Registries.DIMENSION, parsed));
            String reason = null;
            if (level == null) {
                reason = "Измерение не загружено";
            } else if (!level.players().isEmpty()) {
                reason = "В измерении находятся игроки";
            } else {
                try {
                    ClusterDimensionMigration.resolveDimensionPath(server, item.dimensionId());
                } catch (Exception exception) {
                    reason = exception.getMessage();
                }
            }
            if (reason == null) {
                available.add(item);
            } else {
                rejected.put(item, reason);
            }
        }
        if (rejected.isEmpty()) {
            continueStartNodeDrainBatch(
                    source,
                    server,
                    currentConfig,
                    drain,
                    available,
                    skipped,
                    recovery,
                    alreadyReady,
                    alreadyApplied,
                    leaseName,
                    automatic,
                    0
            );
            return;
        }
        MIGRATION_EXECUTOR.execute(() -> {
            for (Map.Entry<ClusterDatabase.DimensionDrainItem, String> entry : rejected.entrySet()) {
                try {
                    if (recovery) {
                        ClusterDatabase.failNodeDrainItem(
                                currentConfig,
                                entry.getKey().drainItemId(),
                                "Recovery заблокирован: " + entry.getValue()
                        );
                    } else {
                        ClusterDatabase.skipNodeDrainItem(
                                currentConfig,
                                entry.getKey().drainItemId(),
                                "Пропущено: " + entry.getValue()
                        );
                    }
                } catch (Exception exception) {
                    LOGGER.error(
                            "Unable to reject node operation item {}",
                            entry.getKey().drainItemId(),
                            exception
                    );
                }
            }
            server.execute(() -> continueStartNodeDrainBatch(
                    source,
                    server,
                    currentConfig,
                    drain,
                    available,
                    skipped + (recovery ? 0 : rejected.size()),
                    recovery,
                    alreadyReady,
                    alreadyApplied,
                    leaseName,
                    automatic,
                    recovery ? rejected.size() : 0
            ));
        });
    }

    private void continueStartNodeDrainBatch(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            ClusterDatabase.NodeDrain drain,
            List<ClusterDatabase.DimensionDrainItem> available,
            int skipped,
            boolean recovery,
            int alreadyReady,
            int alreadyApplied,
            String leaseName,
            boolean automatic,
            int initialFailed
    ) {
        NodeDrainBatchState state = new NodeDrainBatchState(
                drain,
                available,
                skipped,
                recovery,
                alreadyReady,
                alreadyApplied,
                leaseName,
                automatic
        );
        state.failed = initialFailed;
        if (available.isEmpty()) {
            finishNodeDrainBatch(source, currentConfig, state);
            return;
        }
        for (ClusterDatabase.DimensionDrainItem item : available) {
            addMigrationFreeze(item.dimensionId());
        }
        try {
            if (!server.saveEverything(true, true, true)) {
                throw new IllegalStateException("MinecraftServer не подтвердил сохранение мира");
            }
        } catch (Exception exception) {
            MIGRATION_EXECUTOR.execute(() -> {
                for (ClusterDatabase.DimensionDrainItem item : available) {
                    try {
                        ClusterDatabase.failNodeDrainItem(
                                currentConfig,
                                item.drainItemId(),
                                exception.getMessage()
                        );
                    } catch (Exception databaseException) {
                        LOGGER.error("Unable to fail node drain item {}", item.drainItemId(), databaseException);
                    }
                }
                server.execute(() -> {
                    for (ClusterDatabase.DimensionDrainItem item : available) {
                        removeMigrationFreeze(item.dimensionId());
                    }
                    state.failed += available.size();
                    finishNodeDrainBatch(source, currentConfig, state);
                });
            });
            return;
        }
        continueNodeDrainBatch(source, server, currentConfig, state);
    }

    private void continueNodeDrainBatch(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            NodeDrainBatchState state
    ) {
        if (state.index >= state.items.size()) {
            finishNodeDrainBatch(source, currentConfig, state);
            return;
        }
        ClusterDatabase.DimensionDrainItem item = state.items.get(state.index++);
        MIGRATION_EXECUTOR.execute(() -> {
            ClusterDimensionMigration.PreparedArchive archive = null;
            try {
                archive = ClusterDimensionMigration.createArchive(
                        server,
                        item.dimensionId(),
                        currentConfig.dimensionMigrationStagingPath(),
                        item.migrationId()
                );
                ClusterDatabase.markNodeDrainArchiveReady(
                        currentConfig,
                        item.drainItemId(),
                        item.migrationId(),
                        archive.archiveName(),
                        archive.archiveSha256(),
                        archive.contentSha256(),
                        archive.archiveSize()
                );
                state.ready++;
            } catch (Exception exception) {
                Boolean failed = null;
                try {
                    failed = ClusterDatabase.failNodeDrainPreparationIfPending(
                            currentConfig,
                            item.drainItemId(),
                            exception.getClass().getSimpleName() + ": " + exception.getMessage()
                    );
                } catch (Exception databaseException) {
                    exception.addSuppressed(databaseException);
                    LOGGER.error(
                            "Unable to reconcile failed node drain preparation {}. "
                                    + "Archive is kept because READY commit state is unknown.",
                            item.drainItemId(),
                            databaseException
                    );
                }
                if (Boolean.TRUE.equals(failed)) {
                    state.failed++;
                    if (archive != null) {
                        try {
                            ClusterDimensionMigration.deleteArchive(
                                    currentConfig.dimensionMigrationStagingPath(),
                                    archive.archiveName()
                            );
                        } catch (Exception ignored) {
                        }
                    }
                    removeMigrationFreeze(item.dimensionId());
                } else if (Boolean.FALSE.equals(failed)) {
                    state.ready++;
                    LOGGER.info(
                            "Node operation item {} advanced while publishing READY; treating it as successfully published",
                            item.drainItemId()
                    );
                } else {
                    state.failed++;
                }
            }
            server.execute(() -> continueNodeDrainBatch(
                    source,
                    server,
                    currentConfig,
                    state
            ));
        });
    }

    private void finishNodeDrainBatch(
            CommandSourceStack source,
            ClusterConfig currentConfig,
            NodeDrainBatchState state
    ) {
        try {
            refreshDimensionMigrationFreeze(currentConfig);
        } catch (Exception exception) {
            LOGGER.error("Unable to refresh dimension migration freeze after batch operation", exception);
        }
        if (state.leaseName != null) {
            releaseOperationLeaseQuietly(currentConfig, state.leaseName);
        }
        DRAIN_OPERATION_IN_FLIGHT.set(false);
        SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
        boolean rebalance = "REBALANCE".equals(state.drain.operationType());
        String operationName = rebalance ? "Безопасная балансировка" : "Drain";
        if (state.automatic) {
            lastAutomaticOperationRecoverySummary =
                    state.drain.operationType() + " " + state.drain.drainId()
                            + ": new READY=" + state.ready
                            + ", already READY=" + state.alreadyReady
                            + ", already APPLIED=" + state.alreadyApplied
                            + ", failed=" + state.failed
                            + ", skipped=" + state.skipped;
            if (state.failed == 0) {
                LOGGER.info(
                        "Automatic recovery completed for {} operation {}: newReady={}, alreadyReady={}, alreadyApplied={}, skipped={}",
                        state.drain.operationType(),
                        state.drain.drainId(),
                        state.ready,
                        state.alreadyReady,
                        state.alreadyApplied,
                        state.skipped
                );
            } else {
                LOGGER.error(
                        "Automatic recovery completed with errors for {} operation {}: newReady={}, alreadyReady={}, alreadyApplied={}, failed={}, skipped={}",
                        state.drain.operationType(),
                        state.drain.drainId(),
                        state.ready,
                        state.alreadyReady,
                        state.alreadyApplied,
                        state.failed,
                        state.skipped
                );
            }
        }
        if (state.recovery) {
            int totalReady = state.alreadyReady + state.ready;
            source.sendSuccess(() -> Component.literal(
                    "§aRecovery " + operationName + " завершён: §f" + state.ready
                            + "§a новых READY, §f" + state.alreadyReady
                            + "§a уже были READY, §f" + state.alreadyApplied
                            + "§a уже были APPLIED, §f" + state.failed
                            + "§a ошибок, §f" + state.skipped
                            + "§a пропущено."
            ), false);
            if (totalReady > 0) {
                source.sendSuccess(() -> Component.literal(
                        "§eПосле проверки status полностью перезапусти target node §f"
                                + state.drain.targetNode()
                                + "§e."
                ), false);
            }
        } else {
            source.sendSuccess(() -> Component.literal(
                    "§a" + operationName + " подготовлен: §f" + state.ready
                            + "§a READY, §f" + state.failed
                            + "§a ошибок, §f" + state.skipped
                            + "§a пропущено. Перезапусти target node §f"
                            + state.drain.targetNode()
                            + "§a."
            ), false);
        }
        String idLabel = rebalance ? "Rebalance ID" : "Drain ID";
        source.sendSuccess(() -> Component.literal(
                "§7" + idLabel + ": §f" + state.drain.drainId()
        ), false);
        if (state.ready == 0 && state.alreadyReady == 0 && state.alreadyApplied == 0) {
            String action = rebalance
                    ? "Проверь rebalance status, затем выполни rebalance retry или cancel."
                    : "Проверь drain status, затем выполни drain retry, resume или cancel.";
            source.sendSuccess(() -> Component.literal(
                    "§eНи одного архива READY нет. " + action
            ), false);
        }
    }

    private int showNodeDrains(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                List<ClusterDatabase.NodeDrain> drains =
                        ClusterDatabase.listNodeDrains(currentConfig, 10);
                ClusterDatabase.NodeDrainReadiness readiness =
                        ClusterDatabase.readNodeDrainReadiness(
                                currentConfig,
                                currentConfig.nodeId()
                        );
                List<ClusterDatabase.DimensionDrainItem> latestItems = drains.isEmpty()
                        ? List.of()
                        : ClusterDatabase.listNodeDrainItems(
                                currentConfig,
                                drains.get(0).drainId()
                        );
                server.execute(() -> {
                    source.sendSuccess(() -> Component.literal("§6Последние node drains:"), false);
                    if (drains.isEmpty()) {
                        source.sendSuccess(() -> Component.literal("§7Записей нет."), false);
                    }
                    for (ClusterDatabase.NodeDrain drain : drains) {
                        String color = switch (drain.status()) {
                            case "DRAINED" -> "§a";
                            case "READY" -> "§e";
                            case "APPLYING", "PREPARING" -> "§b";
                            case "PARTIAL", "FAILED" -> "§c";
                            case "CANCELLED", "RESUMED" -> "§7";
                            default -> "§6";
                        };
                        source.sendSuccess(() -> Component.literal(
                                color + drain.status()
                                        + " §f" + drain.drainId()
                                        + " §7| §f" + drain.sourceNode()
                                        + " §7-> §f" + drain.targetNode()
                                        + " §7| total=" + drain.totalItems()
                                        + ", ready=" + drain.readyItems()
                                        + ", applying=" + drain.applyingItems()
                                        + ", applied=" + drain.appliedItems()
                                        + ", skipped=" + drain.cancelledItems()
                                        + ", failed=" + drain.failedItems()
                        ), false);
                    }
                    if (!latestItems.isEmpty()) {
                        source.sendSuccess(() -> Component.literal(
                                "§6Элементы последнего drain:"
                        ), false);
                        for (ClusterDatabase.DimensionDrainItem item : latestItems) {
                            String reason = item.errorText() == null || item.errorText().isBlank()
                                    ? ""
                                    : " §7| §f" + item.errorText();
                            source.sendSuccess(() -> Component.literal(
                                    "§f" + item.dimensionId()
                                            + " §7| §e" + item.status()
                                            + " §7| migration: §f" + item.migrationId()
                                            + reason
                            ), false);
                        }
                    }
                    boolean localPlayersEmpty = server.getPlayerList().getPlayers().isEmpty();
                    boolean safe = localPlayersEmpty && readiness.safeToStop();
                    String state = safe ? "§aSAFE TO STOP" : "§eNOT FULLY DRAINED";
                    source.sendSuccess(() -> Component.literal(
                            "§6Готовность узла: " + state
                                    + "§7 | players=" + server.getPlayerList().getPlayerCount()
                                    + ", assignments=" + readiness.assignmentCount()
                                    + ", pinned=" + readiness.pinnedCount()
                                    + ", unsupported=" + readiness.unsupportedCount()
                                    + ", migratable=" + readiness.migratableCount()
                    ), false);
                    if (readiness.assignmentCount() > 0) {
                        source.sendSuccess(() -> Component.literal(
                                "§eОстановка узла сделает оставшиеся назначенные измерения недоступными."
                        ), false);
                    }
                });
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось получить drain status: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private int cancelNodeDrain(
            CommandSourceStack source,
            String drainId
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.NodeDrainCancellationResult result =
                        ClusterDatabase.cancelNodeDrain(currentConfig, drainId);
                for (ClusterDatabase.DimensionMigration migration : result.migrations()) {
                    try {
                        ClusterDimensionMigration.deleteArchive(
                                currentConfig.dimensionMigrationStagingPath(),
                                migration.archiveName()
                        );
                    } catch (Exception exception) {
                        LOGGER.warn(
                                "Unable to delete cancelled drain archive {}",
                                migration.archiveName(),
                                exception
                        );
                    }
                    removeMigrationFreeze(migration.dimensionId());
                }
                try {
                    refreshDimensionMigrationFreeze(currentConfig);
                } catch (Exception exception) {
                    LOGGER.warn("Unable to refresh migration freeze after drain cancel", exception);
                }
                server.execute(() -> source.sendSuccess(() -> Component.literal(
                        "§aDrain отменён: §f" + result.drain().drainId()
                ), false));
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось отменить drain: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private int resumeNodeDrain(
            CommandSourceStack source,
            String drainId
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.NodeDrain drain =
                        ClusterDatabase.resumeNodeDrain(currentConfig, drainId);
                server.execute(() -> source.sendSuccess(() -> Component.literal(
                        "§aDrain переведён в RESUMED. Узел снова участвует в autoassign/rebalance: §f"
                                + drain.drainId()
                ), false));
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось выполнить drain resume: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private int retryNodeDrain(
            CommandSourceStack source,
            String drainId
    ) {
        return retryNodeOperation(source, drainId, "DRAIN");
    }

    private int retrySafeRebalance(
            CommandSourceStack source,
            String operationId
    ) {
        return retryNodeOperation(source, operationId, "REBALANCE");
    }

    private void checkAutomaticNodeOperationRecoveryAtStartup(
            MinecraftServer server
    ) {
        ClusterConfig currentConfig = config;
        inspectAutomaticNodeOperationRecovery(
                server,
                currentConfig,
                "startup"
        );
    }

    private void startAutomaticNodeOperationRecoveryWatchdogIfDue(
            MinecraftServer server,
            ClusterConfig currentConfig
    ) {
        if (currentConfig == null
                || !currentConfig.enabled()
                || !currentConfig.automaticOperationRecovery()) {
            return;
        }
        if (DRAIN_OPERATION_IN_FLIGHT.get()
                || SNAPSHOT_OPERATION_IN_FLIGHT.get()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextAutomaticOperationRecoveryCheckAtMillis) {
            return;
        }
        nextAutomaticOperationRecoveryCheckAtMillis = now
                + currentConfig.automaticOperationRecoveryIntervalSeconds() * 1_000L;

        if (!AUTOMATIC_OPERATION_RECOVERY_SCAN_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;
                nextAutomaticOperationRecoveryCheckAtMillis = System.currentTimeMillis()
                        + latestConfig.automaticOperationRecoveryIntervalSeconds() * 1_000L;
                inspectAutomaticNodeOperationRecovery(
                        server,
                        latestConfig,
                        "watchdog"
                );
            } catch (Exception exception) {
                lastAutomaticOperationRecoveryScanAtMillis = System.currentTimeMillis();
                lastAutomaticOperationRecoveryScanSummary =
                        "watchdog: ошибка загрузки конфига: "
                                + exception.getClass().getSimpleName()
                                + ": "
                                + exception.getMessage();
                LOGGER.error(
                        "Unable to run automatic node operation recovery watchdog",
                        exception
                );
            } finally {
                AUTOMATIC_OPERATION_RECOVERY_SCAN_IN_FLIGHT.set(false);
            }
        });
    }

    private void inspectAutomaticNodeOperationRecovery(
            MinecraftServer server,
            ClusterConfig currentConfig,
            String trigger
    ) {
        lastAutomaticOperationRecoveryScanAtMillis = System.currentTimeMillis();

        if (currentConfig == null || !currentConfig.enabled()) {
            lastAutomaticOperationRecoveryScanSummary = trigger + ": кластер выключен";
            return;
        }
        if (!currentConfig.automaticOperationRecovery()) {
            lastAutomaticOperationRecoveryScanSummary = trigger + ": отключено в конфиге";
            if ("startup".equals(trigger)) {
                lastAutomaticOperationRecoverySummary = "отключено в конфиге";
            }
            return;
        }
        if (currentConfig.dimensionMigrationStagingPath() == null) {
            lastAutomaticOperationRecoveryScanSummary =
                    trigger + ": staging path не настроен";
            lastAutomaticOperationRecoverySummary =
                    "пропущено: dimension_migration_staging_path не настроен";
            LOGGER.warn(
                    "Automatic node operation recovery {} skipped on {}: staging path is not configured",
                    trigger,
                    currentConfig.nodeId()
            );
            return;
        }

        try {
            List<ClusterDatabase.NodeDrain> candidates =
                    ClusterDatabase.listStartupNodeOperationRecoveryCandidates(
                            currentConfig,
                            3
                    );
            if (candidates.isEmpty()) {
                clearAutomaticOperationRecoveryWait();
                lastAutomaticOperationRecoveryScanSummary = trigger + ": кандидатов нет";
                if ("startup".equals(trigger)
                        && lastAutomaticOperationRecoverySummary == null) {
                    lastAutomaticOperationRecoverySummary =
                            "незавершённых PREPARING-операций не найдено";
                }
                if ("startup".equals(trigger)) {
                    LOGGER.info(
                            "No interrupted drain/rebalance operation requires startup recovery on node {}",
                            currentConfig.nodeId()
                    );
                }
                return;
            }
            if (candidates.size() != 1) {
                lastAutomaticOperationRecoveryScanSummary =
                        trigger + ": несколько PREPARING-операций ("
                                + candidates.size() + ")";
                lastAutomaticOperationRecoverySummary =
                        "заблокировано: найдено несколько PREPARING-операций ("
                                + candidates.size() + ")";
                LOGGER.error(
                        "Automatic node operation recovery {} refused on {}: {} PREPARING operations found",
                        trigger,
                        currentConfig.nodeId(),
                        candidates.size()
                );
                server.execute(() -> broadcastToOperators(
                        server,
                        "§cAutomatic operation recovery остановлен: найдено несколько PREPARING-операций. "
                                + "Проверь drain/rebalance status и выполни ручной retry или cancel."
                ));
                return;
            }

            ClusterDatabase.NodeDrain candidate = candidates.get(0);
            lastAutomaticOperationRecoveryScanSummary =
                    trigger + ": найдена " + candidate.operationType()
                            + " " + candidate.drainId();
            lastAutomaticOperationRecoverySummary =
                    "найдена " + candidate.operationType() + " " + candidate.drainId();
            if ("startup".equals(trigger)) {
                LOGGER.warn(
                        "Interrupted {} operation {} detected by startup on source node {}; scheduling automatic recovery",
                        candidate.operationType(),
                        candidate.drainId(),
                        currentConfig.nodeId()
                );
            } else {
                LOGGER.debug(
                        "Interrupted {} operation {} detected by watchdog on source node {}; scheduling automatic recovery",
                        candidate.operationType(),
                        candidate.drainId(),
                        currentConfig.nodeId()
                );
            }
            server.execute(() -> beginNodeOperationRecovery(
                    server.createCommandSourceStack(),
                    server,
                    candidate.drainId(),
                    candidate.operationType(),
                    true
            ));
        } catch (Exception exception) {
            lastAutomaticOperationRecoveryScanSummary =
                    trigger + ": ошибка проверки: "
                            + exception.getClass().getSimpleName()
                            + ": "
                            + exception.getMessage();
            lastAutomaticOperationRecoverySummary =
                    "ошибка проверки: "
                            + exception.getClass().getSimpleName()
                            + ": "
                            + exception.getMessage();
            LOGGER.error(
                    "Unable to inspect interrupted node operations via {} on {}",
                    trigger,
                    currentConfig.nodeId(),
                    exception
            );
        }
    }

    private int retryNodeOperation(
            CommandSourceStack source,
            String operationId,
            String expectedOperationType
    ) {
        return beginNodeOperationRecovery(
                source,
                source.getServer(),
                operationId,
                expectedOperationType,
                false
        );
    }

    private int beginNodeOperationRecovery(
            CommandSourceStack source,
            MinecraftServer server,
            String operationId,
            String expectedOperationType,
            boolean automatic
    ) {
        ClusterConfig currentConfig = config;
        String normalizedType = expectedOperationType == null
                ? ""
                : expectedOperationType.trim().toUpperCase(java.util.Locale.ROOT);
        boolean drain = "DRAIN".equals(normalizedType);
        String displayName = drain ? "drain" : "безопасной балансировки";

        if (currentConfig == null || !currentConfig.enabled()) {
            String message = "Кластер выключен или конфиг ещё не загружен.";
            if (automatic) {
                lastAutomaticOperationRecoverySummary = "пропущено: " + message;
                LOGGER.warn("Automatic operation recovery skipped: {}", message);
            }
            source.sendFailure(Component.literal("§c" + message));
            return 0;
        }
        if (currentConfig.dimensionMigrationStagingPath() == null) {
            String message = "В конфиге не указан dimension_migration_staging_path.";
            if (automatic) {
                lastAutomaticOperationRecoverySummary = "пропущено: " + message;
                LOGGER.warn("Automatic operation recovery skipped: {}", message);
            }
            source.sendFailure(Component.literal("§c" + message));
            return 0;
        }
        if (!drain && !"REBALANCE".equals(normalizedType)) {
            String message = "Неизвестный тип operation: " + expectedOperationType;
            if (automatic) {
                lastAutomaticOperationRecoverySummary = "пропущено: " + message;
                LOGGER.error("Automatic operation recovery skipped: {}", message);
            }
            source.sendFailure(Component.literal("§c" + message));
            return 0;
        }
        if (drain && !server.getPlayerList().getPlayers().isEmpty()) {
            String message = "На source node ещё находятся игроки: "
                    + currentConfig.nodeId();
            if (automatic) {
                recordAutomaticOperationRecoveryWait(
                        server,
                        normalizedType,
                        operationId,
                        "WAITING_SOURCE_PLAYERS",
                        message
                );
            } else {
                source.sendFailure(Component.literal(
                        "§cDrain retry запрещён: на текущем узле ещё находятся игроки."
                ));
            }
            return 0;
        }
        if (!DRAIN_OPERATION_IN_FLIGHT.compareAndSet(false, true)) {
            String message = "Уже выполняется drain, rebalance или recovery.";
            if (automatic) {
                lastAutomaticOperationRecoverySummary =
                        "WAITING_LOCAL_OPERATION: " + message;
                lastAutomaticOperationRecoveryScanSummary =
                        "ожидание WAITING_LOCAL_OPERATION: " + message;
                LOGGER.debug(
                        "Automatic operation recovery {} waits for another local node operation",
                        operationId
                );
            } else {
                source.sendFailure(Component.literal("§c" + message));
            }
            return 0;
        }
        if (!SNAPSHOT_OPERATION_IN_FLIGHT.compareAndSet(false, true)) {
            DRAIN_OPERATION_IN_FLIGHT.set(false);
            String message = "Сначала дождись завершения snapshot-операции.";
            if (automatic) {
                lastAutomaticOperationRecoverySummary =
                        "WAITING_SNAPSHOT: " + message;
                lastAutomaticOperationRecoveryScanSummary =
                        "ожидание WAITING_SNAPSHOT: " + message;
                LOGGER.debug(
                        "Automatic operation recovery {} waits for the local snapshot operation",
                        operationId
                );
            } else {
                source.sendFailure(Component.literal("§c" + message));
            }
            return 0;
        }

        Map<String, Integer> localActivity = captureDimensionPlayerCounts(server);
        Set<String> locallyAvailableDimensions = collectNodeDrainLocalCandidates(server);
        String leaseName = "operation-retry:" + operationId;
        if (automatic) {
            lastAutomaticOperationRecoverySummary =
                    "проверка: " + normalizedType + " " + operationId;
        } else {
            source.sendSuccess(() -> Component.literal(
                    "§eЗапускаю recovery " + displayName + ": §f" + operationId
            ), false);
        }

        DATABASE_EXECUTOR.execute(() -> {
            ClusterConfig latestConfig = currentConfig;
            boolean leaseAcquired = false;
            try {
                latestConfig = ClusterConfig.load();
                config = latestConfig;
                if (automatic && !latestConfig.automaticOperationRecovery()) {
                    throw new IllegalStateException(
                            "automatic_operation_recovery был выключен до запуска recovery"
                    );
                }
                ClusterDatabase.heartbeat(latestConfig, server, localActivity);
                leaseAcquired = ClusterDatabase.tryAcquireOperationLease(
                        latestConfig,
                        leaseName,
                        Math.max(300, latestConfig.automaticFailoverLeaseSeconds())
                );
                if (!leaseAcquired) {
                    throw new ClusterDatabase.NodeOperationRecoveryDeferredException(
                            "WAITING_LEASE",
                            "Recovery уже выполняется другим координатором"
                    );
                }
                ClusterDatabase.NodeOperationRecoveryResult result =
                        ClusterDatabase.prepareNodeOperationRecovery(
                                latestConfig,
                                operationId,
                                normalizedType,
                                locallyAvailableDimensions
                        );
                if (automatic) {
                    clearAutomaticOperationRecoveryWait();
                    lastAutomaticOperationRecoverySummary =
                            "выполняется: " + normalizedType + " " + operationId;
                    LOGGER.info(
                            "Automatic recovery can proceed for {} operation {} on node {}",
                            normalizedType,
                            operationId,
                            latestConfig.nodeId()
                    );
                    server.execute(() -> broadcastToOperators(
                            server,
                            "§eПрерванная " + normalizedType
                                    + " operation §f" + operationId
                                    + "§e готова к продолжению. Запускаю recovery."
                    ));
                }
                if (result.items().isEmpty()) {
                    releaseOperationLeaseQuietly(latestConfig, leaseName);
                    DRAIN_OPERATION_IN_FLIGHT.set(false);
                    SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
                    int alreadyReady = result.alreadyReady();
                    int alreadyApplied = result.alreadyApplied();
                    int skipped = result.skipped();
                    if (automatic) {
                        lastAutomaticOperationRecoverySummary =
                                normalizedType + " " + operationId
                                        + ": архивы не требуются, already READY="
                                        + alreadyReady + ", already APPLIED="
                                        + alreadyApplied + ", skipped=" + skipped;
                        LOGGER.info(
                                "Automatic recovery {} {} required no new archives: alreadyReady={}, alreadyApplied={}, skipped={}",
                                normalizedType,
                                operationId,
                                alreadyReady,
                                alreadyApplied,
                                skipped
                        );
                    }
                    server.execute(() -> source.sendSuccess(() -> Component.literal(
                            "§aRecovery не требует создания архивов: §f"
                                    + alreadyReady + "§a элементов уже READY, §f"
                                    + alreadyApplied + "§a уже APPLIED, §f"
                                    + skipped + "§a пропущено. Проверь status."
                    ), false));
                    return;
                }
                ClusterConfig recoveryConfig = latestConfig;
                server.execute(() -> startNodeOperationRecoveryBatch(
                        source,
                        server,
                        recoveryConfig,
                        result,
                        leaseName,
                        automatic
                ));
            } catch (Exception exception) {
                if (leaseAcquired) {
                    releaseOperationLeaseQuietly(latestConfig, leaseName);
                }
                DRAIN_OPERATION_IN_FLIGHT.set(false);
                SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
                if (automatic
                        && exception instanceof ClusterDatabase.NodeOperationRecoveryDeferredException deferred) {
                    recordAutomaticOperationRecoveryWait(
                            server,
                            normalizedType,
                            operationId,
                            deferred.reasonCode(),
                            deferred.getMessage()
                    );
                    return;
                }
                if (automatic) {
                    lastAutomaticOperationRecoverySummary =
                            "ошибка " + normalizedType + " " + operationId
                                    + ": " + exception.getMessage();
                    lastAutomaticOperationRecoveryScanSummary =
                            "ошибка " + normalizedType + " " + operationId
                                    + ": " + exception.getMessage();
                    LOGGER.error(
                            "Automatic recovery failed for {} operation {}",
                            normalizedType,
                            operationId,
                            exception
                    );
                }
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось выполнить recovery " + displayName + ": "
                                + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private void recordAutomaticOperationRecoveryWait(
            MinecraftServer server,
            String operationType,
            String operationId,
            String reasonCode,
            String message
    ) {
        String normalizedReason = reasonCode == null || reasonCode.isBlank()
                ? "WAITING"
                : reasonCode.trim().toUpperCase(java.util.Locale.ROOT);
        String safeMessage = message == null || message.isBlank()
                ? "условие для recovery ещё не выполнено"
                : message;
        String waitKey = operationType + ":" + operationId + ":"
                + normalizedReason + ":" + safeMessage;
        long now = System.currentTimeMillis();
        boolean logNow;
        synchronized (this) {
            logNow = !waitKey.equals(lastAutomaticOperationRecoveryWaitKey)
                    || now - lastAutomaticOperationRecoveryWaitLoggedAtMillis
                    >= AUTOMATIC_OPERATION_RECOVERY_WAIT_LOG_INTERVAL_MILLIS;
            lastAutomaticOperationRecoveryWaitKey = waitKey;
            if (logNow) {
                lastAutomaticOperationRecoveryWaitLoggedAtMillis = now;
            }
        }

        lastAutomaticOperationRecoverySummary =
                normalizedReason + " " + operationType + " " + operationId
                        + ": " + safeMessage;
        lastAutomaticOperationRecoveryScanSummary =
                "ожидание " + normalizedReason + ": " + safeMessage;

        if (!logNow) {
            return;
        }
        LOGGER.info(
                "Automatic recovery waits: reason={}, operationType={}, operationId={}, message={}",
                normalizedReason,
                operationType,
                operationId,
                safeMessage
        );
        server.execute(() -> broadcastToOperators(
                server,
                "§eAutomatic recovery ожидает §f" + normalizedReason
                        + "§e для " + operationType + " §f" + operationId
                        + "§e: " + safeMessage
        ));
    }

    private synchronized void clearAutomaticOperationRecoveryWait() {
        lastAutomaticOperationRecoveryWaitKey = null;
        lastAutomaticOperationRecoveryWaitLoggedAtMillis = 0L;
    }

    private static void releaseOperationLeaseQuietly(
            ClusterConfig currentConfig,
            String leaseName
    ) {
        try {
            ClusterDatabase.releaseOperationLease(currentConfig, leaseName);
        } catch (Exception exception) {
            LOGGER.warn("Unable to release operation lease {}", leaseName, exception);
        }
    }

    private int previewSafeRebalance(
            CommandSourceStack source,
            String targetNode
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        if (targetNode.equalsIgnoreCase(currentConfig.nodeId())) {
            source.sendFailure(Component.literal("§cTarget node совпадает с текущим узлом."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        List<String> registeredDimensions = registeredDimensionIds(server);
        Map<String, Integer> localActivity = captureDimensionPlayerCounts(server);
        Set<String> locallyAvailable = collectNodeDrainLocalCandidates(server);
        source.sendSuccess(() -> Component.literal(
                "§eСтрою безопасный план балансировки: §f"
                        + currentConfig.nodeId() + " §7-> §f" + targetNode
        ), false);
        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;
                ClusterDatabase.heartbeat(latestConfig, server, localActivity);
                ClusterDatabase.DimensionPlanResult plan =
                        ClusterDatabase.planDimensionAssignments(
                                latestConfig,
                                registeredDimensions,
                                true,
                                false
                        );
                boolean targetAvailable = plan.nodes().stream().anyMatch(
                        node -> node.nodeId().equalsIgnoreCase(targetNode)
                );
                if (!targetAvailable) {
                    throw new IllegalStateException(
                            "Target node OFFLINE, находится в drain-режиме или не участвует в планировании: "
                                    + targetNode
                    );
                }
                List<ClusterDatabase.DimensionPlanEntry> selected = plan.entries().stream()
                        .filter(entry -> entry.action() == ClusterDatabase.DimensionPlanAction.MOVE)
                        .filter(entry -> currentConfig.nodeId().equalsIgnoreCase(entry.previousNodeId()))
                        .filter(entry -> targetNode.equalsIgnoreCase(entry.targetNodeId()))
                        .toList();
                int ready = 0;
                int blocked = 0;
                List<String> lines = new ArrayList<>();
                for (ClusterDatabase.DimensionPlanEntry entry : selected) {
                    if (locallyAvailable.contains(entry.dimensionId())) {
                        ready++;
                        lines.add("§aREADY §f" + entry.dimensionId()
                                + " §7| §f" + entry.previousNodeId()
                                + " §7-> §f" + entry.targetNodeId());
                    } else {
                        blocked++;
                        lines.add("§cBLOCKED §f" + entry.dimensionId()
                                + " §7| измерение не загружено или папка недоступна");
                    }
                }
                int finalReady = ready;
                int finalBlocked = blocked;
                server.execute(() -> {
                    source.sendSuccess(() -> Component.literal(
                            "§6Безопасный rebalance preview: §aready=" + finalReady
                                    + "§7, §cblocked=" + finalBlocked
                                    + "§7, planned moves=" + selected.size()
                    ), false);
                    if (selected.isEmpty()) {
                        source.sendSuccess(() -> Component.literal(
                                "§aТекущий план уже сбалансирован для направления на §f" + targetNode + "§a."
                        ), false);
                    } else {
                        for (String line : lines) {
                            source.sendSuccess(() -> Component.literal(line), false);
                        }
                    }
                    source.sendSuccess(() -> Component.literal(
                            "§6Планируемая нагрузка после операции:"
                    ), false);
                    for (ClusterDatabase.PlanningNodeStatus node : plan.nodes()) {
                        source.sendSuccess(() -> Component.literal(
                                "§f" + node.nodeId()
                                        + " §7| dimensions: §f" + node.plannedDimensionCount()
                                        + " §7| players: §f" + node.playerCount()
                        ), false);
                    }
                });
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось построить безопасный план балансировки: "
                                + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private int prepareSafeRebalance(
            CommandSourceStack source,
            String targetNode
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        if (currentConfig.dimensionMigrationStagingPath() == null) {
            source.sendFailure(Component.literal("§cВ конфиге не указан dimension_migration_staging_path."));
            return 0;
        }
        if (targetNode.equalsIgnoreCase(currentConfig.nodeId())) {
            source.sendFailure(Component.literal("§cTarget node совпадает с текущим узлом."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        if (!DRAIN_OPERATION_IN_FLIGHT.compareAndSet(false, true)) {
            source.sendFailure(Component.literal("§cУже выполняется drain или безопасная балансировка."));
            return 0;
        }
        if (!SNAPSHOT_OPERATION_IN_FLIGHT.compareAndSet(false, true)) {
            DRAIN_OPERATION_IN_FLIGHT.set(false);
            source.sendFailure(Component.literal("§cСначала дождись завершения snapshot-операции."));
            return 0;
        }
        List<String> registeredDimensions = registeredDimensionIds(server);
        Map<String, Integer> localActivity = captureDimensionPlayerCounts(server);
        Set<String> locallyAvailable = collectNodeDrainLocalCandidates(server);
        source.sendSuccess(() -> Component.literal(
                "§eПодготавливаю безопасную балансировку: §f"
                        + currentConfig.nodeId() + " §7-> §f" + targetNode
        ), false);
        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;
                ClusterDatabase.heartbeat(latestConfig, server, localActivity);
                ClusterDatabase.DimensionPlanResult plan =
                        ClusterDatabase.planDimensionAssignments(
                                latestConfig,
                                registeredDimensions,
                                true,
                                false
                        );
                boolean targetAvailable = plan.nodes().stream().anyMatch(
                        node -> node.nodeId().equalsIgnoreCase(targetNode)
                );
                if (!targetAvailable) {
                    throw new IllegalStateException(
                            "Target node OFFLINE, находится в drain-режиме или не участвует в планировании: "
                                    + targetNode
                    );
                }
                List<String> selected = plan.entries().stream()
                        .filter(entry -> entry.action() == ClusterDatabase.DimensionPlanAction.MOVE)
                        .filter(entry -> latestConfig.nodeId().equalsIgnoreCase(entry.previousNodeId()))
                        .filter(entry -> targetNode.equalsIgnoreCase(entry.targetNodeId()))
                        .map(ClusterDatabase.DimensionPlanEntry::dimensionId)
                        .toList();
                if (selected.isEmpty()) {
                    throw new IllegalStateException(
                            "План не содержит перемещений с "
                                    + latestConfig.nodeId() + " на " + targetNode
                    );
                }
                ClusterDatabase.NodeDrainPreparationResult result =
                        ClusterDatabase.prepareSafeRebalance(
                                latestConfig,
                                targetNode,
                                selected,
                                locallyAvailable
                        );
                server.execute(() -> startNodeDrainBatch(
                        source,
                        server,
                        latestConfig,
                        result
                ));
            } catch (Exception exception) {
                DRAIN_OPERATION_IN_FLIGHT.set(false);
                SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось подготовить безопасную балансировку: "
                                + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private int showSafeRebalances(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                List<ClusterDatabase.NodeDrain> operations =
                        ClusterDatabase.listSafeRebalances(currentConfig, 10);
                List<ClusterDatabase.DimensionDrainItem> latestItems = operations.isEmpty()
                        ? List.of()
                        : ClusterDatabase.listNodeDrainItems(
                                currentConfig,
                                operations.get(0).drainId()
                        );
                server.execute(() -> {
                    source.sendSuccess(() -> Component.literal(
                            "§6Последние безопасные балансировки:"
                    ), false);
                    if (operations.isEmpty()) {
                        source.sendSuccess(() -> Component.literal("§7Записей нет."), false);
                    }
                    for (ClusterDatabase.NodeDrain operation : operations) {
                        String color = switch (operation.status()) {
                            case "COMPLETED" -> "§a";
                            case "READY" -> "§e";
                            case "APPLYING", "PREPARING" -> "§b";
                            case "PARTIAL", "FAILED" -> "§c";
                            case "CANCELLED" -> "§7";
                            default -> "§6";
                        };
                        source.sendSuccess(() -> Component.literal(
                                color + operation.status()
                                        + " §f" + operation.drainId()
                                        + " §7| §f" + operation.sourceNode()
                                        + " §7-> §f" + operation.targetNode()
                                        + " §7| total=" + operation.totalItems()
                                        + ", ready=" + operation.readyItems()
                                        + ", applying=" + operation.applyingItems()
                                        + ", applied=" + operation.appliedItems()
                                        + ", skipped=" + operation.cancelledItems()
                                        + ", failed=" + operation.failedItems()
                        ), false);
                    }
                    if (!latestItems.isEmpty()) {
                        source.sendSuccess(() -> Component.literal(
                                "§6Элементы последней балансировки:"
                        ), false);
                        for (ClusterDatabase.DimensionDrainItem item : latestItems) {
                            String reason = item.errorText() == null || item.errorText().isBlank()
                                    ? ""
                                    : " §7| §f" + item.errorText();
                            source.sendSuccess(() -> Component.literal(
                                    "§f" + item.dimensionId()
                                            + " §7| §e" + item.status()
                                            + " §7| migration: §f" + item.migrationId()
                                            + reason
                            ), false);
                        }
                    }
                });
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось получить status безопасной балансировки: "
                                + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private int cancelSafeRebalance(
            CommandSourceStack source,
            String operationId
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.NodeDrain operation =
                        ClusterDatabase.findNodeDrain(currentConfig, operationId);
                if (operation == null || !"REBALANCE".equals(operation.operationType())) {
                    throw new IllegalStateException(
                            "Безопасная балансировка не найдена: " + operationId
                    );
                }
                ClusterDatabase.NodeDrainCancellationResult result =
                        ClusterDatabase.cancelNodeDrain(currentConfig, operationId);
                for (ClusterDatabase.DimensionMigration migration : result.migrations()) {
                    try {
                        ClusterDimensionMigration.deleteArchive(
                                currentConfig.dimensionMigrationStagingPath(),
                                migration.archiveName()
                        );
                    } catch (Exception exception) {
                        LOGGER.warn(
                                "Unable to delete cancelled rebalance archive {}",
                                migration.archiveName(),
                                exception
                        );
                    }
                    removeMigrationFreeze(migration.dimensionId());
                }
                try {
                    refreshDimensionMigrationFreeze(currentConfig);
                } catch (Exception exception) {
                    LOGGER.warn("Unable to refresh migration freeze after rebalance cancel", exception);
                }
                server.execute(() -> source.sendSuccess(() -> Component.literal(
                        "§aБезопасная балансировка отменена: §f" + result.drain().drainId()
                ), false));
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось отменить безопасную балансировку: "
                                + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private int previewDimensionFailback(
            CommandSourceStack source,
            String recoveredNode
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal(
                "§eПроверяю failback на восстановленный узел §f" + recoveredNode
        ), false);
        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;
                List<ClusterDatabase.FailbackPreviewEntry> entries =
                        ClusterDatabase.previewDimensionFailback(latestConfig, recoveredNode);
                server.execute(() -> {
                    int ready = 0;
                    for (ClusterDatabase.FailbackPreviewEntry entry : entries) {
                        if (entry.executable()) {
                            ready++;
                        }
                    }
                    int finalReady = ready;
                    source.sendSuccess(() -> Component.literal(
                            "§6Failback preview: §aready="
                                    + finalReady
                                    + "§7, blocked="
                                    + (entries.size() - finalReady)
                    ), false);
                    if (entries.isEmpty()) {
                        source.sendSuccess(() -> Component.literal(
                                "§7Подходящих APPLIED failover для возврата нет."
                        ), false);
                        return;
                    }
                    for (ClusterDatabase.FailbackPreviewEntry entry : entries) {
                        String state = entry.executable() ? "§aREADY" : "§cBLOCKED";
                        String reason = entry.reason() == null ? "" : " §7| §f" + entry.reason();
                        source.sendSuccess(() -> Component.literal(
                                state
                                        + " §f"
                                        + entry.dimensionId()
                                        + " §7| §f"
                                        + entry.sourceNode()
                                        + " §7-> §f"
                                        + entry.targetNode()
                                        + reason
                        ), false);
                    }
                });
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось построить failback preview: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private int prepareDimensionFailback(
            CommandSourceStack source,
            String recoveredNode
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        if (currentConfig.dimensionMigrationStagingPath() == null) {
            source.sendFailure(Component.literal("§cВ конфиге не указан dimension_migration_staging_path."));
            return 0;
        }
        if (!FAILBACK_OPERATION_IN_FLIGHT.compareAndSet(false, true)) {
            source.sendFailure(Component.literal("§cУже выполняется failback."));
            return 0;
        }
        if (!SNAPSHOT_OPERATION_IN_FLIGHT.compareAndSet(false, true)) {
            FAILBACK_OPERATION_IN_FLIGHT.set(false);
            source.sendFailure(Component.literal("§cСначала дождись завершения snapshot-операции."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal(
                "§eПодготавливаю управляемый failback на узел §f" + recoveredNode
        ), false);
        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;
                ClusterDatabase.FailbackPreparationResult result =
                        ClusterDatabase.prepareDimensionFailback(latestConfig, recoveredNode);
                server.execute(() -> {
                    if (result.failbacks().isEmpty()) {
                        FAILBACK_OPERATION_IN_FLIGHT.set(false);
                        SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
                        source.sendSuccess(() -> Component.literal(
                                "§aFailback не подготовлен: подходящих APPLIED failover нет."
                        ), false);
                        return;
                    }
                    startDimensionFailbackBatch(
                            source,
                            server,
                            latestConfig,
                            result
                    );
                });
            } catch (Exception exception) {
                FAILBACK_OPERATION_IN_FLIGHT.set(false);
                SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось подготовить failback: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private void startDimensionFailbackBatch(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            ClusterDatabase.FailbackPreparationResult preparation
    ) {
        List<ClusterDatabase.DimensionFailback> available = new ArrayList<>();
        Map<ClusterDatabase.DimensionFailback, String> rejected = new LinkedHashMap<>();
        for (ClusterDatabase.DimensionFailback failback : preparation.failbacks()) {
            ResourceLocation parsed = ResourceLocation.tryParse(failback.dimensionId());
            ServerLevel level = parsed == null
                    ? null
                    : server.getLevel(ResourceKey.create(Registries.DIMENSION, parsed));
            String reason = null;
            if (level == null) {
                reason = "Измерение не загружено";
            } else if (!level.players().isEmpty()) {
                reason = "В измерении находятся игроки";
            } else {
                try {
                    ClusterDimensionMigration.resolveDimensionPath(server, failback.dimensionId());
                } catch (Exception exception) {
                    reason = exception.getMessage();
                }
            }
            if (reason == null) {
                available.add(failback);
            } else {
                rejected.put(failback, reason);
            }
        }
        if (rejected.isEmpty()) {
            continueStartDimensionFailbackBatch(
                    source,
                    server,
                    currentConfig,
                    available,
                    preparation.skipped()
            );
            return;
        }
        MIGRATION_EXECUTOR.execute(() -> {
            for (Map.Entry<ClusterDatabase.DimensionFailback, String> entry : rejected.entrySet()) {
                try {
                    ClusterDatabase.failDimensionFailback(
                            currentConfig,
                            entry.getKey().failbackId(),
                            entry.getValue()
                    );
                } catch (Exception exception) {
                    LOGGER.error("Unable to fail dimension failback {}", entry.getKey().failbackId(), exception);
                }
            }
            server.execute(() -> continueStartDimensionFailbackBatch(
                    source,
                    server,
                    currentConfig,
                    available,
                    preparation.skipped() + rejected.size()
            ));
        });
    }

    private void continueStartDimensionFailbackBatch(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            List<ClusterDatabase.DimensionFailback> available,
            int skipped
    ) {
        DimensionFailbackBatchState state = new DimensionFailbackBatchState(
                available,
                skipped
        );
        if (available.isEmpty()) {
            finishDimensionFailbackBatch(source, server, currentConfig, state);
            return;
        }
        for (ClusterDatabase.DimensionFailback failback : available) {
            addMigrationFreeze(failback.dimensionId());
        }
        try {
            if (!server.saveEverything(true, true, true)) {
                throw new IllegalStateException("MinecraftServer не подтвердил сохранение мира");
            }
        } catch (Exception exception) {
            MIGRATION_EXECUTOR.execute(() -> {
                for (ClusterDatabase.DimensionFailback failback : available) {
                    try {
                        ClusterDatabase.failDimensionFailback(
                                currentConfig,
                                failback.failbackId(),
                                exception.getMessage()
                        );
                    } catch (Exception databaseException) {
                        LOGGER.error("Unable to fail dimension failback {}", failback.failbackId(), databaseException);
                    }
                }
                server.execute(() -> {
                    for (ClusterDatabase.DimensionFailback failback : available) {
                        removeMigrationFreeze(failback.dimensionId());
                    }
                    state.failed += available.size();
                    finishDimensionFailbackBatch(source, server, currentConfig, state);
                });
            });
            return;
        }
        continueDimensionFailbackBatch(source, server, currentConfig, state);
    }

    private void continueDimensionFailbackBatch(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            DimensionFailbackBatchState state
    ) {
        if (state.index >= state.failbacks.size()) {
            finishDimensionFailbackBatch(source, server, currentConfig, state);
            return;
        }
        ClusterDatabase.DimensionFailback failback = state.failbacks.get(state.index++);
        MIGRATION_EXECUTOR.execute(() -> {
            ClusterDimensionMigration.PreparedArchive archive = null;
            try {
                archive = ClusterDimensionMigration.createArchive(
                        server,
                        failback.dimensionId(),
                        currentConfig.dimensionMigrationStagingPath(),
                        failback.migrationId()
                );
                ClusterDatabase.markDimensionMigrationReady(
                        currentConfig,
                        failback.migrationId(),
                        archive.archiveName(),
                        archive.archiveSha256(),
                        archive.contentSha256(),
                        archive.archiveSize()
                );
                ClusterDatabase.markDimensionFailbackReady(
                        currentConfig,
                        failback.failbackId()
                );
                state.ready++;
            } catch (Exception exception) {
                state.failed++;
                if (archive != null) {
                    try {
                        ClusterDimensionMigration.deleteArchive(
                                currentConfig.dimensionMigrationStagingPath(),
                                archive.archiveName()
                        );
                    } catch (Exception ignored) {
                    }
                }
                try {
                    ClusterDatabase.failDimensionFailback(
                            currentConfig,
                            failback.failbackId(),
                            exception.getClass().getSimpleName() + ": " + exception.getMessage()
                    );
                } catch (Exception databaseException) {
                    LOGGER.error("Unable to fail dimension failback {}", failback.failbackId(), databaseException);
                }
                removeMigrationFreeze(failback.dimensionId());
            }
            server.execute(() -> continueDimensionFailbackBatch(
                    source,
                    server,
                    currentConfig,
                    state
            ));
        });
    }

    private void finishDimensionFailbackBatch(
            CommandSourceStack source,
            MinecraftServer server,
            ClusterConfig currentConfig,
            DimensionFailbackBatchState state
    ) {
        try {
            refreshDimensionMigrationFreeze(currentConfig);
        } catch (Exception exception) {
            LOGGER.error("Unable to refresh dimension migration freeze after failback", exception);
        }
        FAILBACK_OPERATION_IN_FLIGHT.set(false);
        SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
        source.sendSuccess(() -> Component.literal(
                "§aFailback подготовлен: §f"
                        + state.ready
                        + "§a READY, §f"
                        + state.failed
                        + "§a ошибок, §f"
                        + state.skipped
                        + "§a пропущено. Перезапусти target node."
        ), false);
        for (ClusterDatabase.DimensionFailback failback : state.failbacks) {
            source.sendSuccess(() -> Component.literal(
                    "§f"
                            + failback.dimensionId()
                            + " §7| §f"
                            + failback.sourceNode()
                            + " §7-> §f"
                            + failback.targetNode()
                            + " §7| migration: §f"
                            + failback.migrationId()
            ), false);
        }
    }

    private int showDimensionFailbacks(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                List<ClusterDatabase.DimensionFailback> failbacks =
                        ClusterDatabase.listDimensionFailbacks(currentConfig, 50);
                server.execute(() -> {
                    source.sendSuccess(() -> Component.literal("§6Последние dimension failbacks:"), false);
                    if (failbacks.isEmpty()) {
                        source.sendSuccess(() -> Component.literal("§7Записей нет."), false);
                        return;
                    }
                    for (ClusterDatabase.DimensionFailback failback : failbacks) {
                        String color = switch (failback.status()) {
                            case "APPLIED" -> "§a";
                            case "FAILED" -> "§c";
                            case "APPLYING" -> "§b";
                            default -> "§e";
                        };
                        String reason = failback.errorText() == null || failback.errorText().isBlank()
                                ? ""
                                : " §7| §f" + failback.errorText();
                        source.sendSuccess(() -> Component.literal(
                                "§f"
                                        + failback.failbackId()
                                        + " §7| §f"
                                        + failback.dimensionId()
                                        + " §7| §f"
                                        + failback.sourceNode()
                                        + " §7-> §f"
                                        + failback.targetNode()
                                        + " §7| "
                                        + color
                                        + failback.status()
                                        + " §7| migration: §f"
                                        + failback.migrationId()
                                        + reason
                        ), false);
                    }
                });
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось получить failbacks: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private int showDimensionSnapshots(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                List<ClusterDatabase.DimensionSnapshot> snapshots =
                        ClusterDatabase.listDimensionSnapshots(currentConfig, 50);
                server.execute(() -> {
                    source.sendSuccess(() -> Component.literal("§6Последние dimension snapshots:"), false);
                    if (snapshots.isEmpty()) {
                        source.sendSuccess(() -> Component.literal("§7Записей нет."), false);
                        return;
                    }
                    for (ClusterDatabase.DimensionSnapshot snapshot : snapshots) {
                        source.sendSuccess(() -> Component.literal(
                                "§f"
                                        + snapshot.snapshotId()
                                        + " §7| §f"
                                        + snapshot.dimensionId()
                                        + " §7| §f"
                                        + snapshot.sourceNode()
                                        + " §7| §e"
                                        + snapshot.status()
                                        + " §7| §f"
                                        + snapshot.archiveSize()
                                        + " bytes"
                                        + (snapshot.readyAt() == null
                                        ? ""
                                        : " §7| age: §f"
                                        + Math.max(0L, (System.currentTimeMillis() - snapshot.readyAt().toEpochMilli()) / 60_000L)
                                        + " min"
                                        + ((System.currentTimeMillis() - snapshot.readyAt().toEpochMilli())
                                        <= currentConfig.dimensionSnapshotMaxAgeMinutes() * 60_000L
                                        ? " §aFRESH"
                                        : " §cSTALE"))
                        ), false);
                    }
                });
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось получить snapshots: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private void inspectPendingApplyRestart(
            MinecraftServer server,
            ClusterConfig currentConfig
    ) {
        if (currentConfig == null
                || !currentConfig.enabled()
                || !currentConfig.pendingApplyRestartEnabled()) {
            LAST_PENDING_APPLY_NOTIFICATION_FINGERPRINT = null;
            LAST_PENDING_APPLY_NOTIFICATION_AT_MILLIS = 0L;
            clearPendingApplyConfirmation();
            return;
        }

        try {
            List<ClusterDatabase.PendingApplyOperation> operations =
                    ClusterDatabase.listPendingApplyOperationsForNode(currentConfig);
            if (operations.isEmpty()) {
                LAST_PENDING_APPLY_NOTIFICATION_FINGERPRINT = null;
                LAST_PENDING_APPLY_NOTIFICATION_AT_MILLIS = 0L;
                clearPendingApplyConfirmation();
                return;
            }

            String fingerprint = pendingApplyFingerprint(
                    currentConfig.nodeId(),
                    operations
            );
            long now = System.currentTimeMillis();
            long intervalMillis = currentConfig
                    .pendingApplyRestartNotificationIntervalSeconds() * 1_000L;
            if (fingerprint.equals(LAST_PENDING_APPLY_NOTIFICATION_FINGERPRINT)
                    && now - LAST_PENDING_APPLY_NOTIFICATION_AT_MILLIS < intervalMillis) {
                return;
            }

            LAST_PENDING_APPLY_NOTIFICATION_FINGERPRINT = fingerprint;
            LAST_PENDING_APPLY_NOTIFICATION_AT_MILLIS = now;
            String summary = pendingApplySummary(operations);
            LOGGER.warn(
                    "Pending cluster apply detected on node {}: {}. Confirmation is required with /gtocluster applyrestart status",
                    currentConfig.nodeId(),
                    summary
            );
            server.execute(() -> broadcastToOperators(
                    server,
                    "§6Кластер ожидает применения на этом узле: §f"
                            + summary
                            + "§6. Для контроля выполни §f/gtocluster applyrestart status"
            ));
        } catch (Exception exception) {
            LOGGER.warn(
                    "Unable to inspect pending cluster apply restart on node {}: {}",
                    currentConfig.nodeId(),
                    exception.getMessage()
            );
        }
    }

    private int showPendingApplyRestartStatus(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal(
                "§eПроверяю pending apply для узла §f" + currentConfig.nodeId()
        ), false);

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;
                List<ClusterDatabase.PendingApplyOperation> operations =
                        ClusterDatabase.listPendingApplyOperationsForNode(latestConfig);
                server.execute(() -> {
                    source.sendSuccess(() -> Component.literal(
                            "§6Контролируемый apply restart: §f"
                                    + latestConfig.pendingApplyRestartEnabled()
                                    + "§7 | delay: §f"
                                    + latestConfig.pendingApplyRestartDelaySeconds()
                                    + "s§7 | confirmation timeout: §f"
                                    + latestConfig.pendingApplyRestartConfirmationTimeoutSeconds()
                                    + "s"
                    ), false);
                    source.sendSuccess(() -> Component.literal(
                            "§7Текущее расписание рестарта: §f"
                                    + CointCoreGTO.getClusterRestartControlStatus()
                    ), false);

                    if (!latestConfig.pendingApplyRestartEnabled()) {
                        clearPendingApplyConfirmation();
                        source.sendFailure(Component.literal(
                                "§cКонтролируемый pending apply restart выключен в cluster config."
                        ));
                        return;
                    }

                    if (operations.isEmpty()) {
                        clearPendingApplyConfirmation();
                        source.sendSuccess(() -> Component.literal(
                                "§aНа текущем узле нет операций, ожидающих применения при рестарте."
                        ), false);
                        return;
                    }

                    String fingerprint = pendingApplyFingerprint(
                            latestConfig.nodeId(),
                            operations
                    );
                    PendingApplyConfirmation confirmation = issuePendingApplyConfirmation(
                            fingerprint,
                            latestConfig.pendingApplyRestartConfirmationTimeoutSeconds()
                    );
                    source.sendSuccess(() -> Component.literal(
                            "§6Ожидают применения: §f" + pendingApplySummary(operations)
                    ), false);
                    int shown = Math.min(operations.size(), 20);
                    for (int index = 0; index < shown; index++) {
                        ClusterDatabase.PendingApplyOperation operation = operations.get(index);
                        source.sendSuccess(() -> Component.literal(
                                "§f" + operation.operationType()
                                        + " §7| §f" + operation.dimensionId()
                                        + " §7| §f" + operation.status()
                                        + " §7| §f" + operation.operationId()
                        ), false);
                    }
                    if (operations.size() > shown) {
                        source.sendSuccess(() -> Component.literal(
                                "§7И ещё §f" + (operations.size() - shown)
                        ), false);
                    }
                    long secondsLeft = Math.max(
                            0L,
                            (confirmation.expiresAtMillis() - System.currentTimeMillis() + 999L) / 1_000L
                    );
                    source.sendSuccess(() -> Component.literal(
                            "§eДля одного немедленного безопасного рестарта: §f/gtocluster applyrestart confirm "
                                    + confirmation.code()
                                    + " §7(код действует " + secondsLeft + "s)"
                    ), false);
                    source.sendSuccess(() -> Component.literal(
                            "§7Без подтверждения операции применятся при следующем обычном плановом рестарте. "
                                    + "Автоматических повторных рестартов кластер не запускает."
                    ), false);
                });
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось проверить pending apply: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private int confirmPendingApplyRestart(
            CommandSourceStack source,
            String code
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal(
                "§eПроверяю код и неизменность pending apply..."
        ), false);

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;
                if (!latestConfig.pendingApplyRestartEnabled()) {
                    server.execute(() -> source.sendFailure(Component.literal(
                            "§cКонтролируемый pending apply restart выключен в cluster config."
                    )));
                    return;
                }

                List<ClusterDatabase.PendingApplyOperation> operations =
                        ClusterDatabase.listPendingApplyOperationsForNode(latestConfig);
                if (operations.isEmpty()) {
                    clearPendingApplyConfirmation();
                    server.execute(() -> source.sendFailure(Component.literal(
                            "§cPending apply уже отсутствует; рестарт не требуется."
                    )));
                    return;
                }

                String fingerprint = pendingApplyFingerprint(
                        latestConfig.nodeId(),
                        operations
                );
                String validationError = validatePendingApplyConfirmation(
                        code,
                        fingerprint
                );
                if (validationError != null) {
                    server.execute(() -> source.sendFailure(Component.literal(
                            "§c" + validationError
                                    + " Выполни /gtocluster applyrestart status ещё раз."
                    )));
                    return;
                }

                String summary = pendingApplySummary(operations);
                server.execute(() -> {
                    String repeatedValidationError = validatePendingApplyConfirmation(
                            code,
                            fingerprint
                    );
                    if (repeatedValidationError != null) {
                        source.sendFailure(Component.literal(
                                "§c" + repeatedValidationError
                                        + " Выполни /gtocluster applyrestart status ещё раз."
                        ));
                        return;
                    }

                    CointCoreGTO.ClusterRestartResult result =
                            CointCoreGTO.requestClusterRestart(
                                    latestConfig.pendingApplyRestartDelaySeconds(),
                                    code,
                                    "cluster pending apply: " + summary
                            );
                    if (!result.accepted()) {
                        source.sendFailure(Component.literal("§c" + result.message()));
                        return;
                    }

                    clearPendingApplyConfirmation();
                    source.sendSuccess(() -> Component.literal(
                            "§aПодтверждение принято. " + result.message()
                    ), false);
                    broadcastToOperators(
                            server,
                            "§cПодтверждён безопасный кластерный рестарт узла §f"
                                    + latestConfig.nodeId()
                                    + "§c: §f" + summary
                    );
                });
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось подтвердить pending apply restart: "
                                + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private int cancelPendingApplyRestart(
            CommandSourceStack source
    ) {
        CointCoreGTO.ClusterRestartResult result =
                CointCoreGTO.cancelClusterRestart();
        if (!result.accepted()) {
            source.sendFailure(Component.literal("§c" + result.message()));
            return 0;
        }
        clearPendingApplyConfirmation();
        source.sendSuccess(() -> Component.literal("§a" + result.message()), true);
        return 1;
    }

    private static String pendingApplySummary(
            List<ClusterDatabase.PendingApplyOperation> operations
    ) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ClusterDatabase.PendingApplyOperation operation : operations) {
            counts.merge(operation.operationType(), 1, Integer::sum);
        }
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!result.isEmpty()) {
                result.append(", ");
            }
            result.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return result.toString();
    }

    private static String pendingApplyFingerprint(
            String nodeId,
            List<ClusterDatabase.PendingApplyOperation> operations
    ) {
        StringBuilder source = new StringBuilder(nodeId);
        for (ClusterDatabase.PendingApplyOperation operation : operations) {
            source.append('|')
                    .append(operation.operationType())
                    .append(':')
                    .append(operation.operationId())
                    .append(':')
                    .append(operation.status())
                    .append(':')
                    .append(operation.dimensionId());
        }
        return UUID.nameUUIDFromBytes(
                source.toString().getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    private static synchronized PendingApplyConfirmation issuePendingApplyConfirmation(
            String fingerprint,
            int timeoutSeconds
    ) {
        long now = System.currentTimeMillis();
        PendingApplyConfirmation current = PENDING_APPLY_CONFIRMATION;
        if (current != null
                && current.expiresAtMillis() > now
                && current.fingerprint().equals(fingerprint)) {
            return current;
        }

        String code = UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 8)
                .toUpperCase(java.util.Locale.ROOT);
        PendingApplyConfirmation created = new PendingApplyConfirmation(
                code,
                fingerprint,
                now + Math.max(30, timeoutSeconds) * 1_000L
        );
        PENDING_APPLY_CONFIRMATION = created;
        return created;
    }

    private static synchronized String validatePendingApplyConfirmation(
            String code,
            String fingerprint
    ) {
        PendingApplyConfirmation confirmation = PENDING_APPLY_CONFIRMATION;
        if (confirmation == null) {
            return "Нет активного подтверждения.";
        }
        if (confirmation.expiresAtMillis() <= System.currentTimeMillis()) {
            PENDING_APPLY_CONFIRMATION = null;
            return "Код подтверждения истёк.";
        }
        if (!confirmation.code().equalsIgnoreCase(code)) {
            return "Неверный код подтверждения.";
        }
        if (!confirmation.fingerprint().equals(fingerprint)) {
            PENDING_APPLY_CONFIRMATION = null;
            return "Набор pending apply изменился.";
        }
        return null;
    }

    private static synchronized void clearPendingApplyConfirmation() {
        PENDING_APPLY_CONFIRMATION = null;
    }

    private int showAutomaticFailoverWatch(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                List<ClusterDatabase.AutomaticFailoverCandidate> candidates =
                        ClusterDatabase.listAutomaticFailoverCandidates(currentConfig);
                server.execute(() -> {
                    source.sendSuccess(() -> Component.literal(
                            "§6Automatic failover: §f"
                                    + currentConfig.automaticFailover()
                                    + "§7 | confirmation: §f"
                                    + Math.max(
                                            currentConfig.nodeTimeoutSeconds(),
                                            currentConfig.automaticFailoverConfirmationSeconds()
                                    )
                                    + "s§7 | lease: §f"
                                    + currentConfig.automaticFailoverLeaseSeconds()
                                    + "s§7 | clean stops: §f"
                                    + currentConfig.automaticFailoverIncludeCleanStops()
                    ), false);
                    if (candidates.isEmpty()) {
                        source.sendSuccess(() -> Component.literal("§7Нет других узлов-владельцев измерений."), false);
                        return;
                    }
                    for (ClusterDatabase.AutomaticFailoverCandidate candidate : candidates) {
                        String state;
                        if (candidate.applyingFailoverCount() > 0) {
                            state = "§cAPPLYING";
                        } else if (candidate.readyFailoverCount() > 0) {
                            state = "§6WAITING_CONFIRMATION";
                        } else if (candidate.eligible()) {
                            state = "§cREADY";
                        } else if (candidate.secondsRemaining() > 0L) {
                            state = "§eWAIT " + candidate.secondsRemaining() + "s";
                        } else {
                            state = "§7SKIP";
                        }
                        String pending = candidate.readyFailoverCount() > 0
                                || candidate.applyingFailoverCount() > 0
                                ? "§7 | pending: §f"
                                + (candidate.readyFailoverCount()
                                + candidate.applyingFailoverCount())
                                + "§7 | target: §f"
                                + candidate.pendingTargetNodes()
                                : "";
                        source.sendSuccess(() -> Component.literal(
                                state
                                        + " §f"
                                        + candidate.nodeId()
                                        + "§7 | heartbeat: §f"
                                        + candidate.heartbeatAgeSeconds()
                                        + "s§7 | assignments: §f"
                                        + candidate.dimensionCount()
                                        + "§7 | clean stop: §f"
                                        + candidate.cleanStop()
                                        + pending
                                        + "§7 | "
                                        + candidate.reason()
                        ), false);
                    }
                });
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось получить automatic failover watch: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private int previewDimensionFailover(
            CommandSourceStack source,
            String sourceNode
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("§eСтрою безопасный failover-план для §f" + sourceNode), false);
        DATABASE_EXECUTOR.execute(() -> {
            try {
                List<ClusterDatabase.FailoverPreviewEntry> entries =
                        ClusterDatabase.previewDimensionFailover(currentConfig, sourceNode);
                server.execute(() -> {
                    int ready = 0;
                    for (ClusterDatabase.FailoverPreviewEntry entry : entries) {
                        if (entry.executable()) {
                            ready++;
                        }
                    }
                    int finalReady = ready;
                    source.sendSuccess(() -> Component.literal(
                            "§6Failover preview: §aготово "
                                    + finalReady
                                    + "§7, пропущено "
                                    + (entries.size() - finalReady)
                    ), false);
                    for (ClusterDatabase.FailoverPreviewEntry entry : entries) {
                        source.sendSuccess(() -> Component.literal(
                                (entry.executable() ? "§aREADY " : "§cSKIP ")
                                        + "§f"
                                        + entry.dimensionId()
                                        + (entry.executable()
                                        ? " §7-> §f" + entry.targetNode() + " §7| snapshot §f" + entry.snapshotId()
                                        : " §7| " + entry.reason())
                        ), false);
                    }
                });
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось построить failover-план: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private int executeDimensionFailover(
            CommandSourceStack source,
            String sourceNode
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        if (currentConfig.dimensionMigrationStagingPath() == null) {
            source.sendFailure(Component.literal("§cВ конфиге не указан dimension_migration_staging_path."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                List<ClusterDatabase.DimensionFailover> failovers =
                        ClusterDatabase.prepareDimensionFailover(currentConfig, sourceNode);
                server.execute(() -> {
                    if (failovers.isEmpty()) {
                        source.sendFailure(Component.literal(
                                "§cНе создано ни одного failover: проверь preview и READY snapshots."
                        ));
                        return;
                    }
                    source.sendSuccess(() -> Component.literal(
                            "§aFailover READY: §f" + failovers.size() + "§a. Target-узлы ожидают перезапуска для применения."
                    ), false);
                    for (ClusterDatabase.DimensionFailover failover : failovers) {
                        source.sendSuccess(() -> Component.literal(
                                "§f"
                                        + failover.dimensionId()
                                        + " §7| §c"
                                        + failover.sourceNode()
                                        + " §7-> §a"
                                        + failover.targetNode()
                                        + " §7| §f"
                                        + failover.failoverId()
                        ), false);
                    }
                });
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cFailover не подготовлен: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private int showDimensionFailovers(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер выключен или конфиг ещё не загружен."));
            return 0;
        }
        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                List<ClusterDatabase.DimensionFailover> failovers =
                        ClusterDatabase.listDimensionFailovers(currentConfig, 50);
                server.execute(() -> {
                    source.sendSuccess(() -> Component.literal("§6Последние dimension failovers:"), false);
                    if (failovers.isEmpty()) {
                        source.sendSuccess(() -> Component.literal("§7Записей нет."), false);
                        return;
                    }
                    for (ClusterDatabase.DimensionFailover failover : failovers) {
                        String statusColor = switch (failover.status()) {
                            case "APPLIED" -> "§a";
                            case "FAILED" -> "§c";
                            case "ABORTED" -> "§6";
                            case "APPLYING" -> "§b";
                            default -> "§e";
                        };
                        String reason = failover.errorText() == null
                                || failover.errorText().isBlank()
                                ? ""
                                : " §7| §f" + failover.errorText();
                        source.sendSuccess(() -> Component.literal(
                                "§f"
                                        + failover.failoverId()
                                        + " §7| §f"
                                        + failover.dimensionId()
                                        + " §7| §f"
                                        + failover.sourceNode()
                                        + " -> "
                                        + failover.targetNode()
                                        + " §7| "
                                        + statusColor
                                        + failover.status()
                                        + reason
                        ), false);
                    }
                });
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось получить failovers: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private void applyPendingDimensionFailoverAtStartup(
            MinecraftServer server,
            ClusterConfig currentConfig
    ) throws Exception {
        Path stagingPath = currentConfig.dimensionMigrationStagingPath();
        while (true) {
            ClusterDatabase.DimensionFailover pending =
                    ClusterDatabase.findPendingDimensionFailoverForTarget(currentConfig);
            if (pending == null) {
                return;
            }
            if (stagingPath == null) {
                ClusterDatabase.failDimensionFailover(
                        currentConfig,
                        pending.failoverId(),
                        "dimension_migration_staging_path не настроен"
                );
                throw new IllegalStateException("dimension_migration_staging_path не настроен");
            }
            ClusterDatabase.DimensionFailover applying =
                    ClusterDatabase.markDimensionFailoverApplying(
                            currentConfig,
                            pending.failoverId()
                    );
            try {
                ClusterDatabase.DimensionSnapshot snapshot =
                        ClusterDatabase.findDimensionSnapshot(
                                currentConfig,
                                applying.snapshotId()
                        );
                if (snapshot == null || !"READY".equals(snapshot.status())) {
                    throw new IllegalStateException("READY snapshot не найден: " + applying.snapshotId());
                }
                if (!snapshot.dimensionId().equals(applying.dimensionId())
                        || !snapshot.sourceNode().equalsIgnoreCase(applying.sourceNode())) {
                    throw new IllegalStateException("Snapshot не соответствует failover");
                }
                ClusterDimensionMigration.applySnapshotArchive(
                        server,
                        snapshot,
                        stagingPath,
                        "failover-" + applying.failoverId()
                );
                ClusterDatabase.DimensionFailover applied =
                        ClusterDatabase.completeDimensionFailover(
                                currentConfig,
                                applying.failoverId()
                        );
                LOGGER.warn(
                        "Applied snapshot failover {}: {} {} -> {}",
                        applied.failoverId(),
                        applied.dimensionId(),
                        applied.sourceNode(),
                        applied.targetNode()
                );
            } catch (Exception exception) {
                ClusterDatabase.failDimensionFailover(
                        currentConfig,
                        applying.failoverId(),
                        exception.getClass().getSimpleName() + ": " + exception.getMessage()
                );
                throw exception;
            }
        }
    }

    private static void addSnapshotFreeze(
            String dimensionId
    ) {
        synchronized (ClusterTestModule.class) {
            Set<String> dimensions = new TreeSet<>(DIMENSION_SNAPSHOT_FROZEN);
            dimensions.add(dimensionId);
            DIMENSION_SNAPSHOT_FROZEN = Set.copyOf(dimensions);
        }
    }

    private static void removeSnapshotFreeze(
            String dimensionId
    ) {
        synchronized (ClusterTestModule.class) {
            Set<String> dimensions = new TreeSet<>(DIMENSION_SNAPSHOT_FROZEN);
            dimensions.remove(dimensionId);
            DIMENSION_SNAPSHOT_FROZEN = Set.copyOf(dimensions);
            DIMENSION_TICK_SUPPRESSION_LOGGED.remove(dimensionId);
        }
    }

    private List<ClusterDatabase.DimensionFailover>
    performFailover(
            ClusterConfig currentConfig,
            boolean force
    ) throws java.sql.SQLException {
        if (!force && !currentConfig.automaticFailover()) {
            return List.of();
        }
        if (currentConfig.dimensionMigrationStagingPath() == null) {
            return List.of();
        }
        boolean leaseAcquired = force || ClusterDatabase.tryAcquireOperationLease(
                currentConfig,
                "automatic_dimension_failover",
                currentConfig.automaticFailoverLeaseSeconds()
        );
        if (!leaseAcquired) {
            return List.of();
        }
        try {
            List<String> sourceNodes = new ArrayList<>();
            if (force) {
                sourceNodes.addAll(
                        ClusterDatabase.listOfflineDimensionOwnerNodes(currentConfig)
                );
            } else {
                for (ClusterDatabase.AutomaticFailoverCandidate candidate
                        : ClusterDatabase.listAutomaticFailoverCandidates(currentConfig)) {
                    if (candidate.eligible()) {
                        sourceNodes.add(candidate.nodeId());
                    }
                }
            }
            List<ClusterDatabase.DimensionFailover> prepared = new ArrayList<>();
            for (String offlineNode : sourceNodes) {
                List<ClusterDatabase.DimensionFailover> failovers =
                        ClusterDatabase.prepareDimensionFailover(
                                currentConfig,
                                offlineNode
                        );
                prepared.addAll(failovers);
                if (!failovers.isEmpty()) {
                    String targetNodes = failovers.stream()
                            .map(ClusterDatabase.DimensionFailover::targetNode)
                            .distinct()
                            .sorted()
                            .reduce((left, right) -> left + ", " + right)
                            .orElse("unknown");
                    LOGGER.warn(
                            "Prepared {} snapshot failovers for offline node {}. Waiting for target restart: {}.",
                            failovers.size(),
                            offlineNode,
                            targetNodes
                    );
                }
            }
            return List.copyOf(prepared);
        } finally {
            if (!force) {
                ClusterDatabase.releaseOperationLease(
                        currentConfig,
                        "automatic_dimension_failover"
                );
            }
        }
    }

    private int runFailoverCommand(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;

        if (currentConfig == null
                || !currentConfig.enabled()) {
            source.sendFailure(
                    Component.literal(
                            "§cКластер выключен или конфиг ещё не загружен."
                    )
            );

            return 0;
        }

        MinecraftServer server = source.getServer();

        source.sendSuccess(
                () -> Component.literal(
                        "§eПроверяю владельцев измерений и OFFLINE-узлы..."
                ),
                false
        );

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig =
                        ClusterConfig.load();

                config = latestConfig;

                List<ClusterDatabase.DimensionFailover> failovers =
                        performFailover(
                                latestConfig,
                                true
                        );

                refreshDimensionOwnerCache(
                        latestConfig
                );

                server.execute(() -> {
                    if (failovers.isEmpty()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "§aFailover не подготовлен: нет подходящих OFFLINE-владельцев или свежих snapshots."
                                ),
                                false
                        );
                        return;
                    }
                    source.sendSuccess(
                            () -> Component.literal(
                                    "§aПодготовлено безопасных failover: §f"
                                            + failovers.size()
                                            + "§a. Перезапусти target node."
                            ),
                            false
                    );
                    for (ClusterDatabase.DimensionFailover failover : failovers) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "§f"
                                                + failover.dimensionId()
                                                + " §7| §c"
                                                + failover.sourceNode()
                                                + " §7-> §a"
                                                + failover.targetNode()
                                                + " §7| §f"
                                                + failover.failoverId()
                                ),
                                false
                        );
                    }
                });
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to perform cluster failover",
                        exception
                );

                server.execute(
                        () -> source.sendFailure(
                                Component.literal(
                                        "§cFailover завершился ошибкой: "
                                                + exception
                                                .getClass()
                                                .getSimpleName()
                                                + ": "
                                                + exception.getMessage()
                                )
                        )
                );
            }
        });

        return 1;
    }

    private void applyPendingDimensionMigrationAtStartup(
            MinecraftServer server,
            ClusterConfig currentConfig
    ) throws Exception {
        while (true) {
            ClusterDatabase.DimensionMigration pending =
                    ClusterDatabase.findPendingDimensionMigrationForTarget(
                            currentConfig
                    );

            if (pending == null) {
                return;
            }

            Path stagingPath = currentConfig.dimensionMigrationStagingPath();
            if (stagingPath == null) {
                throw new IllegalStateException(
                        "Найдена migration "
                                + pending.migrationId()
                                + ", но dimension_migration_staging_path не настроен"
                );
            }

            if (pending.archiveName() == null
                    || pending.archiveSha256() == null
                    || pending.contentSha256() == null
                    || pending.archiveSize() <= 0) {
                ClusterDatabase.failDimensionMigration(
                        currentConfig,
                        pending.migrationId(),
                        "Запись migration не содержит корректных данных архива"
                );
                throw new IllegalStateException(
                        "Migration "
                                + pending.migrationId()
                                + " не содержит корректных данных архива"
                );
            }

            ClusterDatabase.DimensionMigration applying =
                    ClusterDatabase.markDimensionMigrationApplying(
                            currentConfig,
                            pending.migrationId()
                    );

            ClusterDimensionMigration.AppliedArchive applied;
            try {
                applied = ClusterDimensionMigration.applyArchive(
                        server,
                        applying,
                        stagingPath
                );
            } catch (Exception exception) {
                try {
                    ClusterDatabase.failDimensionMigration(
                            currentConfig,
                            applying.migrationId(),
                            exception.getClass().getSimpleName()
                                    + ": "
                                    + exception.getMessage()
                    );
                } catch (Exception databaseException) {
                    exception.addSuppressed(databaseException);
                }
                throw exception;
            }

            ClusterDatabase.DimensionMigration completed =
                    ClusterDatabase.completeDimensionMigration(
                            currentConfig,
                            applying.migrationId()
                    );

            LOGGER.info(
                    "Dimension migration applied before world load: migration={}, dimension={}, source={}, target={}, targetPath={}, backupPath={}, alreadyApplied={}",
                    completed.migrationId(),
                    completed.dimensionId(),
                    completed.sourceNode(),
                    completed.targetNode(),
                    applied.targetPath(),
                    applied.backupPath(),
                    applied.alreadyApplied()
            );

            try {
                ClusterDimensionMigration.deleteArchive(
                        stagingPath,
                        completed.archiveName()
                );
            } catch (Exception exception) {
                LOGGER.warn(
                        "Unable to delete applied migration archive {}",
                        completed.archiveName(),
                        exception
                );
            }
        }
    }


    private void applyPendingDimensionRollbackAtStartup(
            MinecraftServer server,
            ClusterConfig currentConfig
    ) throws Exception {
        while (true) {
            ClusterDatabase.DimensionMigration pending =
                    ClusterDatabase.findPendingDimensionRollbackForSource(
                            currentConfig
                    );
            if (pending == null) {
                return;
            }

            Path stagingPath = currentConfig.dimensionMigrationStagingPath();
            if (stagingPath == null) {
                throw new IllegalStateException(
                        "Найден rollback "
                                + pending.migrationId()
                                + ", но dimension_migration_staging_path не настроен"
                );
            }
            if (pending.rollbackArchiveName() == null
                    || pending.rollbackArchiveSha256() == null
                    || pending.rollbackContentSha256() == null
                    || pending.rollbackArchiveSize() <= 0) {
                throw new IllegalStateException(
                        "Rollback " + pending.migrationId() + " не содержит корректных данных архива"
                );
            }

            ClusterDatabase.DimensionMigration applying =
                    ClusterDatabase.markDimensionRollbackApplying(
                            currentConfig,
                            pending.migrationId()
                    );
            ClusterDimensionMigration.AppliedArchive applied =
                    ClusterDimensionMigration.applyRollbackArchive(
                            server,
                            applying,
                            stagingPath
                    );
            ClusterDatabase.DimensionMigration completed =
                    ClusterDatabase.completeDimensionRollback(
                            currentConfig,
                            applying.migrationId()
                    );

            LOGGER.info(
                    "Dimension rollback applied before world load: migration={}, dimension={}, source={}, target={}, targetPath={}, backupPath={}, alreadyApplied={}",
                    completed.migrationId(),
                    completed.dimensionId(),
                    completed.sourceNode(),
                    completed.targetNode(),
                    applied.targetPath(),
                    applied.backupPath(),
                    applied.alreadyApplied()
            );

            try {
                ClusterDimensionMigration.deleteArchive(
                        stagingPath,
                        completed.rollbackArchiveName()
                );
            } catch (Exception exception) {
                LOGGER.warn(
                        "Unable to delete rollback archive {}",
                        completed.rollbackArchiveName(),
                        exception
                );
            }
        }
    }

    private void applyPendingDimensionFinalizationAtStartup(
            MinecraftServer server,
            ClusterConfig currentConfig
    ) throws Exception {
        while (true) {
            ClusterDatabase.DimensionMigration pending =
                    ClusterDatabase.findPendingDimensionFinalizationForSource(
                            currentConfig
                    );
            if (pending == null) {
                return;
            }

            Path backup = ClusterDimensionMigration.finalizeSourceCopy(
                    server,
                    pending
            );
            ClusterDatabase.DimensionMigration completed =
                    ClusterDatabase.completeDimensionMigrationFinalization(
                            currentConfig,
                            pending.migrationId()
                    );
            LOGGER.info(
                    "Dimension migration finalized before world load: migration={}, dimension={}, source={}, target={}, backup={}",
                    completed.migrationId(),
                    completed.dimensionId(),
                    completed.sourceNode(),
                    completed.targetNode(),
                    backup
            );
        }
    }

    private void cleanupFinalizedDimensionMigrationBackups(
            MinecraftServer server,
            ClusterConfig currentConfig
    ) throws Exception {
        List<ClusterDatabase.DimensionMigration> expired =
                ClusterDatabase.listExpiredFinalizedMigrationBackups(
                        currentConfig,
                        currentConfig.dimensionMigrationBackupRetentionDays()
                );
        for (ClusterDatabase.DimensionMigration migration : expired) {
            ClusterDimensionMigration.deleteFinalizedBackup(
                    server,
                    migration.migrationId()
            );
            ClusterDatabase.markFinalizedMigrationBackupDeleted(
                    currentConfig,
                    migration.migrationId()
            );
            LOGGER.info(
                    "Deleted expired finalized dimension backup: migration={}, dimension={}",
                    migration.migrationId(),
                    migration.dimensionId()
            );
        }
    }
    private void refreshDimensionMigrationFreeze(
            ClusterConfig currentConfig
    ) throws Exception {
        DIMENSION_MIGRATION_FROZEN =
                ClusterDatabase.listFrozenMigrationDimensions(
                        currentConfig
                );
        DIMENSION_MIGRATION_BLOCKED =
                ClusterDatabase.listActiveMigrationDimensions(
                        currentConfig
                );
    }

    private static void addMigrationFreeze(
            String dimensionId
    ) {
        synchronized (ClusterTestModule.class) {
            Set<String> dimensions =
                    new TreeSet<>(DIMENSION_MIGRATION_FROZEN);
            dimensions.add(dimensionId);
            DIMENSION_MIGRATION_FROZEN = Set.copyOf(dimensions);
            Set<String> blocked =
                    new TreeSet<>(DIMENSION_MIGRATION_BLOCKED);
            blocked.add(dimensionId);
            DIMENSION_MIGRATION_BLOCKED = Set.copyOf(blocked);
        }
    }

    private static void removeMigrationFreeze(
            String dimensionId
    ) {
        synchronized (ClusterTestModule.class) {
            Set<String> dimensions =
                    new TreeSet<>(DIMENSION_MIGRATION_FROZEN);
            dimensions.remove(dimensionId);
            DIMENSION_MIGRATION_FROZEN = Set.copyOf(dimensions);
            Set<String> blocked =
                    new TreeSet<>(DIMENSION_MIGRATION_BLOCKED);
            blocked.remove(dimensionId);
            DIMENSION_MIGRATION_BLOCKED = Set.copyOf(blocked);
            DIMENSION_TICK_SUPPRESSION_LOGGED.remove(dimensionId);
        }
    }

    private void runTest(
            MinecraftServer server,
            boolean reportToOperators
    ) {
        try {
            ClusterConfig currentConfig =
                    ClusterConfig.load();

            config = currentConfig;

            if (!currentConfig.enabled()) {
                lastError =
                        "Кластер отключён в "
                                + ClusterConfig.path();

                if (reportToOperators) {
                    server.execute(
                            () -> broadcastToOperators(
                                    server,
                                    "§c" + lastError
                            )
                    );
                }

                return;
            }

            ClusterDatabase.TestResult result =
                    ClusterDatabase.test(
                            currentConfig,
                            server,
                            DIMENSION_PLAYER_COUNT_SNAPSHOT
                    );

            performFailover(
                    currentConfig,
                    false
            );

            refreshDimensionOwnerCache(
                    currentConfig
            );
            refreshDimensionMigrationFreeze(
                    currentConfig
            );

            lastResult = result;
            lastError = null;

            LOGGER.info(
                    "Cluster DB test OK: node={}, database={} {}, catalog={}, registeredNodes={}",
                    result.nodeId(),
                    result.databaseName(),
                    result.databaseVersion(),
                    result.catalog(),
                    result.registeredNodes()
            );

            if (reportToOperators) {
                server.execute(
                        () -> broadcastToOperators(
                                server,
                                "§aMySQL подключён. Узел: §f"
                                        + result.nodeId()
                                        + "§a, база: §f"
                                        + result.catalog()
                                        + "§a, зарегистрировано узлов: §f"
                                        + result.registeredNodes()
                        )
                );
            }
        } catch (Exception exception) {
            lastError =
                    exception.getClass().getSimpleName()
                            + ": "
                            + exception.getMessage();

            LOGGER.error(
                    "Cluster DB test failed",
                    exception
            );

            if (reportToOperators) {
                server.execute(
                        () -> broadcastToOperators(
                                server,
                                "§cОшибка MySQL: "
                                        + lastError
                        )
                );
            }
        }
    }

    private int startNetworkChatDeliveryTest(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер не включён."));
            return 0;
        }
        if (!currentConfig.networkChatEnabled()) {
            source.sendFailure(Component.literal("§cМежсерверный чат выключен."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        source.sendSuccess(
                () -> Component.literal("§eЗапускаю проверку доставки межсерверного чата..."),
                false
        );

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;
                List<ClusterDatabase.ClusterNodeStatus> nodes =
                        ClusterDatabase.listNodes(latestConfig);
                Set<String> expectedNodes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (ClusterDatabase.ClusterNodeStatus node : nodes) {
                    if (node.online()) {
                        expectedNodes.add(node.nodeId());
                    }
                }
                expectedNodes.add(latestConfig.nodeId());

                String testId = UUID.randomUUID().toString();
                ClusterDatabase.publishChatMessage(
                        latestConfig,
                        new ClusterDatabase.NetworkChatPublish(
                                testId,
                                latestConfig.nodeId(),
                                latestConfig.networkRole(),
                                "TEST",
                                "DIAGNOSTIC",
                                null,
                                "gtocluster",
                                null,
                                String.join(",", expectedNodes),
                                null,
                                null,
                                false
                        )
                );
                ClusterDatabase.recordChatTestReceipt(
                        latestConfig,
                        testId,
                        latestConfig.nodeId()
                );

                server.execute(() -> {
                    source.sendSuccess(
                            () -> Component.literal(
                                    "§aChat test создан: §f" + testId
                                            + " §7| ожидаются узлы: §f"
                                            + String.join(", ", expectedNodes)
                            ),
                            false
                    );
                    source.sendSuccess(
                            () -> Component.literal(
                                    "§7Через 1-2 секунды: §f/gtocluster chat status " + testId
                            ),
                            false
                    );
                });
            } catch (Exception exception) {
                LOGGER.error("Unable to start network chat delivery test", exception);
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось запустить chat test: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private int showNetworkChatDeliveryTest(
            CommandSourceStack source,
            String testId
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null || !currentConfig.enabled()) {
            source.sendFailure(Component.literal("§cКластер не включён."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;
                ClusterDatabase.ChatDeliveryTest test =
                        testId == null || testId.isBlank()
                                ? ClusterDatabase.findLatestChatDeliveryTest(latestConfig)
                                : ClusterDatabase.findChatDeliveryTest(latestConfig, testId);
                if (test == null) {
                    server.execute(() -> source.sendFailure(Component.literal(
                            "§cChat test не найден."
                    )));
                    return;
                }

                Map<String, ClusterDatabase.ChatTestReceipt> receipts = new LinkedHashMap<>();
                for (ClusterDatabase.ChatTestReceipt receipt : test.receipts()) {
                    receipts.put(receipt.nodeId().toLowerCase(java.util.Locale.ROOT), receipt);
                }

                List<String> lines = new ArrayList<>();
                int received = 0;
                int duplicateNodes = 0;
                for (String nodeId : test.expectedNodes()) {
                    ClusterDatabase.ChatTestReceipt receipt =
                            receipts.get(nodeId.toLowerCase(java.util.Locale.ROOT));
                    if (receipt == null) {
                        lines.add("§e[WAIT] §f" + nodeId + " §7не подтвердил получение");
                        continue;
                    }
                    received++;
                    long latencyMillis = Math.max(
                            0L,
                            receipt.firstReceivedAt().toEpochMilli() - test.createdAt().toEpochMilli()
                    );
                    if (receipt.receiveCount() > 1) {
                        duplicateNodes++;
                        lines.add(
                                "§6[WARN] §f" + nodeId
                                        + " §7получено, latency="
                                        + formatChatLatency(latencyMillis)
                                        + ", count=§e" + receipt.receiveCount()
                        );
                    } else {
                        lines.add(
                                "§a[OK] §f" + nodeId
                                        + " §7получено, latency="
                                        + formatChatLatency(latencyMillis)
                                        + ", count=1"
                        );
                    }
                }

                long ageMillis = Math.max(0L, System.currentTimeMillis() - test.createdAt().toEpochMilli());
                boolean success = received == test.expectedNodes().size() && duplicateNodes == 0;
                String summary = (success ? "§aSUCCESS" : "§eWAITING")
                        + " §7| test=" + test.testId()
                        + " | origin=" + test.originNode()
                        + " | received=" + received + "/" + test.expectedNodes().size()
                        + " | duplicates=" + duplicateNodes
                        + " | age=" + formatChatLatency(ageMillis);

                server.execute(() -> {
                    source.sendSuccess(() -> Component.literal(summary), false);
                    for (String line : lines) {
                        source.sendSuccess(() -> Component.literal(line), false);
                    }
                });
            } catch (Exception exception) {
                LOGGER.error("Unable to read network chat delivery test", exception);
                server.execute(() -> source.sendFailure(Component.literal(
                        "§cНе удалось прочитать chat test: " + exception.getMessage()
                )));
            }
        });
        return 1;
    }

    private static String formatChatLatency(long millis) {
        long safeMillis = Math.max(0L, millis);
        if (safeMillis < 1_000L) {
            return safeMillis + "ms";
        }
        long seconds = safeMillis / 1_000L;
        long remainder = safeMillis % 1_000L;
        return seconds + "." + String.format(java.util.Locale.ROOT, "%03d", remainder) + "s";
    }

    private int showClusterHealth(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;
        if (currentConfig == null) {
            source.sendFailure(Component.literal("§cКонфиг кластера ещё не загружен."));
            return 0;
        }

        MinecraftServer server = source.getServer();
        List<String> registeredDimensions = registeredDimensionIds(server);
        Map<String, Integer> localActivity = captureDimensionPlayerCounts(server);
        int loadedDimensions = 0;
        for (ServerLevel ignored : server.getAllLevels()) {
            loadedDimensions++;
        }
        int finalLoadedDimensions = loadedDimensions;

        source.sendSuccess(() -> Component.literal(
                "§eЗапускаю полную диагностику кластера..."
        ), false);

        DATABASE_EXECUTOR.execute(() -> {
            List<HealthMessage> messages = new ArrayList<>();
            try {
                ClusterConfig latestConfig = ClusterConfig.load();
                config = latestConfig;

                if (latestConfig.enabled()) {
                    addHealthMessage(messages, HealthSeverity.OK,
                            "Кластер включён, node=" + latestConfig.nodeId()
                                    + ", redirect=" + latestConfig.redirectAddress());
                } else {
                    addHealthMessage(messages, HealthSeverity.CRITICAL,
                            "Кластер отключён в " + ClusterConfig.path());
                }

                if (latestConfig.dimensionTickIsolation()
                        && DIMENSION_TICK_GUARD_ACTIVE.get()) {
                    if (isDimensionOwnerCacheFresh(latestConfig)) {
                        addHealthMessage(messages, HealthSeverity.OK,
                                "Изоляция тиков включена, mixin активен, owner cache "
                                        + dimensionOwnerCacheState(latestConfig));
                    } else {
                        addHealthMessage(messages, HealthSeverity.CRITICAL,
                                "Изоляция тиков fail-closed: owner cache "
                                        + dimensionOwnerCacheState(latestConfig));
                    }
                } else if (!latestConfig.dimensionTickIsolation()) {
                    addHealthMessage(messages, HealthSeverity.WARNING,
                            "Изоляция тиков отключена");
                } else {
                    addHealthMessage(messages, HealthSeverity.CRITICAL,
                            "Изоляция тиков включена, но mixin не активен");
                }

                if (!latestConfig.failClosedRouting()) {
                    addHealthMessage(messages, HealthSeverity.WARNING,
                            "fail_closed_routing выключен");
                }
                if (!latestConfig.syncPlayerData()) {
                    addHealthMessage(messages, HealthSeverity.WARNING,
                            "Синхронизация данных игрока выключена");
                } else if (!latestConfig.syncForgeCapabilities()) {
                    addHealthMessage(messages, HealthSeverity.WARNING,
                            "Синхронизация Forge capabilities выключена");
                }
                if (latestConfig.networkChatEnabled()) {
                    addHealthMessage(messages, HealthSeverity.OK,
                            "Межсерверный чат включён, role="
                                    + latestConfig.networkRole()
                                    + ", prefix="
                                    + latestConfig.networkChatPrefix()
                                    + ", dimension overrides="
                                    + latestConfig.networkChatDimensionOverrides().size()
                                    + ", transport retention="
                                    + latestConfig.networkChatRetentionMinutes()
                                    + "m, Discord election="
                                    + latestConfig.discordClusterLeaderElection()
                                    + ", leader="
                                    + ClusterNetworkChat.isDiscordLeader());
                } else {
                    addHealthMessage(messages, HealthSeverity.INFO,
                            "Межсерверный чат выключен");
                }

                if (!latestConfig.automaticDimensionSnapshots()) {
                    addHealthMessage(messages, HealthSeverity.WARNING,
                            "Автоматические snapshots выключены");
                }
                if (!latestConfig.automaticFailover()) {
                    addHealthMessage(messages, HealthSeverity.WARNING,
                            "Автоматический failover выключен");
                }
                if (latestConfig.pendingApplyRestartEnabled()) {
                    addHealthMessage(messages, HealthSeverity.OK,
                            "Контролируемый pending apply restart включён, требуется ручное подтверждение, delay="
                                    + latestConfig.pendingApplyRestartDelaySeconds()
                                    + "s, confirmation timeout="
                                    + latestConfig.pendingApplyRestartConfirmationTimeoutSeconds()
                                    + "s");
                } else {
                    addHealthMessage(messages, HealthSeverity.INFO,
                            "Контролируемый pending apply restart выключен; операции применятся при обычном рестарте");
                }

                if (latestConfig.automaticOperationRecovery()) {
                    long scanAt = lastAutomaticOperationRecoveryScanAtMillis;
                    long scanAgeSeconds = scanAt <= 0L
                            ? -1L
                            : Math.max(0L, (System.currentTimeMillis() - scanAt) / 1_000L);
                    String scanState = scanAgeSeconds < 0L
                            ? "watchdog ещё не выполнялся"
                            : "последняя проверка " + scanAgeSeconds + "s назад";
                    addHealthMessage(messages, HealthSeverity.OK,
                            "Автоматическое восстановление drain/rebalance включено, watchdog="
                                    + latestConfig.automaticOperationRecoveryIntervalSeconds()
                                    + "s, " + scanState);
                } else {
                    addHealthMessage(messages, HealthSeverity.INFO,
                            "Автоматическое восстановление drain/rebalance выключено; доступен ручной retry");
                }

                checkHealthStagingPath(messages, latestConfig.dimensionMigrationStagingPath());

                if (latestConfig.enabled()) {
                    ClusterDatabase.TestResult database = ClusterDatabase.test(
                            latestConfig,
                            server,
                            localActivity
                    );
                    List<ClusterDatabase.ClusterNodeStatus> nodes =
                            ClusterDatabase.listNodes(latestConfig);
                    List<ClusterDatabase.DimensionAssignmentInfo> assignments =
                            ClusterDatabase.listDimensionAssignments(
                                    latestConfig,
                                    registeredDimensions
                            );
                    List<ClusterDatabase.DimensionSnapshotCoverage> coverage =
                            ClusterDatabase.listDimensionSnapshotCoverage(latestConfig);
                    ClusterDatabase.OperationalHealth operations =
                            ClusterDatabase.readOperationalHealth(latestConfig);
                    List<ClusterDatabase.PendingApplyOperation> localPendingApply =
                            ClusterDatabase.listPendingApplyOperationsForNode(latestConfig);
                    List<ClusterDatabase.DimensionMigration> pendingMigrations =
                            ClusterDatabase.listPendingDimensionMigrations(latestConfig);

                    addHealthMessage(messages, HealthSeverity.OK,
                            "MySQL доступен: " + database.databaseName()
                                    + " " + database.databaseVersion()
                                    + ", schema=" + database.catalog());

                    Map<String, ClusterDatabase.ClusterNodeStatus> nodesById =
                            new LinkedHashMap<>();
                    Map<String, List<String>> redirectNodes = new LinkedHashMap<>();
                    int onlineNodes = 0;
                    int offlineNodes = 0;
                    for (ClusterDatabase.ClusterNodeStatus node : nodes) {
                        nodesById.put(node.nodeId(), node);
                        redirectNodes.computeIfAbsent(
                                node.redirectAddress().toLowerCase(java.util.Locale.ROOT),
                                ignored -> new ArrayList<>()
                        ).add(node.nodeId());
                        if (node.online()) {
                            onlineNodes++;
                        } else {
                            offlineNodes++;
                        }
                    }

                    ClusterDatabase.ClusterNodeStatus localNode =
                            nodesById.get(latestConfig.nodeId());
                    if (localNode == null || !localNode.online()) {
                        addHealthMessage(messages, HealthSeverity.CRITICAL,
                                "Текущий узел отсутствует в cluster_nodes или считается OFFLINE");
                    } else {
                        addHealthMessage(messages, HealthSeverity.OK,
                                "Узлы: ONLINE=" + onlineNodes
                                        + ", OFFLINE=" + offlineNodes
                                        + ", текущий heartbeat="
                                        + localNode.heartbeatAgeSeconds() + "s");
                    }

                    List<String> duplicateRedirects = new ArrayList<>();
                    for (Map.Entry<String, List<String>> entry : redirectNodes.entrySet()) {
                        if (entry.getValue().size() > 1) {
                            duplicateRedirects.add(entry.getKey() + "=" + entry.getValue());
                        }
                    }
                    if (!duplicateRedirects.isEmpty()) {
                        addHealthMessage(messages, HealthSeverity.CRITICAL,
                                "Одинаковые redirect_address: "
                                        + healthPreview(duplicateRedirects, 5));
                    }

                    Set<String> registeredSet = Set.copyOf(registeredDimensions);
                    Map<String, ClusterDatabase.DimensionAssignmentInfo> assignmentsById =
                            new LinkedHashMap<>();
                    for (ClusterDatabase.DimensionAssignmentInfo assignment : assignments) {
                        assignmentsById.put(assignment.dimensionId(), assignment);
                    }

                    List<String> unassigned = new ArrayList<>();
                    List<String> unregisteredAssignments = new ArrayList<>();
                    List<String> orphanOwners = new ArrayList<>();
                    List<String> offlineOwners = new ArrayList<>();
                    List<String> conflicts = new ArrayList<>();
                    int assignedRegistered = 0;
                    int pinned = 0;

                    for (ClusterDatabase.DimensionAssignmentInfo assignment : assignments) {
                        if (!registeredSet.contains(assignment.dimensionId())
                                && assignment.nodeId() != null
                                && !assignment.nodeId().isBlank()) {
                            unregisteredAssignments.add(
                                    assignment.dimensionId()
                                            + "->"
                                            + assignment.nodeId()
                            );
                        }
                        if (assignment.pinned()) {
                            pinned++;
                        }
                        if (assignment.activeNodes().size() > 1) {
                            conflicts.add(assignment.dimensionId()
                                    + "=" + assignment.activeNodes());
                        }
                        String owner = assignment.nodeId();
                        if (owner != null && !owner.isBlank()) {
                            if (!nodesById.containsKey(owner)) {
                                orphanOwners.add(assignment.dimensionId() + "->" + owner);
                            } else if (!nodesById.get(owner).online()) {
                                offlineOwners.add(assignment.dimensionId() + "->" + owner);
                            }
                        }
                    }

                    for (String dimensionId : registeredDimensions) {
                        ClusterDatabase.DimensionAssignmentInfo assignment =
                                assignmentsById.get(dimensionId);
                        if (assignment == null
                                || assignment.nodeId() == null
                                || assignment.nodeId().isBlank()) {
                            unassigned.add(dimensionId);
                        } else {
                            assignedRegistered++;
                        }
                    }

                    if (unassigned.isEmpty()) {
                        addHealthMessage(messages, HealthSeverity.OK,
                                "Измерения: зарегистрировано=" + registeredDimensions.size()
                                        + ", назначено=" + assignedRegistered
                                        + ", pinned=" + pinned
                                        + ", загружено=" + finalLoadedDimensions);
                    } else {
                        addHealthMessage(messages, HealthSeverity.CRITICAL,
                                "Измерения без владельца: "
                                        + healthPreview(unassigned, 8));
                    }
                    if (!unregisteredAssignments.isEmpty()) {
                        addHealthMessage(messages, HealthSeverity.INFO,
                                "Назначения вне локального registry: "
                                        + unregisteredAssignments.size()
                                        + " (могут быть динамическими): "
                                        + healthPreview(
                                                unregisteredAssignments,
                                                8
                                        ));
                    }
                    if (!orphanOwners.isEmpty()) {
                        addHealthMessage(messages, HealthSeverity.CRITICAL,
                                "Назначения на неизвестные узлы: "
                                        + healthPreview(orphanOwners, 8));
                    }
                    if (!offlineOwners.isEmpty()) {
                        addHealthMessage(messages, HealthSeverity.WARNING,
                                "Измерения принадлежат OFFLINE-узлам: "
                                        + healthPreview(offlineOwners, 8));
                    }
                    if (!conflicts.isEmpty()) {
                        addHealthMessage(messages, HealthSeverity.CRITICAL,
                                "Конфликты активности измерений: "
                                        + healthPreview(conflicts, 8));
                    }

                    long freshCutoff = System.currentTimeMillis()
                            - latestConfig.dimensionSnapshotMaxAgeMinutes() * 60_000L;
                    List<String> missingSnapshots = new ArrayList<>();
                    List<String> staleSnapshots = new ArrayList<>();
                    int freshSnapshots = 0;
                    for (ClusterDatabase.DimensionSnapshotCoverage item : coverage) {
                        if (!registeredSet.contains(item.dimensionId())) {
                            continue;
                        }
                        if (item.latestReadyAt() == null) {
                            missingSnapshots.add(item.dimensionId());
                        } else if (item.latestReadyAt().toEpochMilli() < freshCutoff) {
                            staleSnapshots.add(item.dimensionId());
                        } else {
                            freshSnapshots++;
                        }
                    }
                    if (missingSnapshots.isEmpty() && staleSnapshots.isEmpty()) {
                        addHealthMessage(messages, HealthSeverity.OK,
                                "Snapshots кроме Overworld: свежих покрытий=" + freshSnapshots
                                        + ", max age="
                                        + latestConfig.dimensionSnapshotMaxAgeMinutes()
                                        + " min");
                    } else {
                        if (!missingSnapshots.isEmpty()) {
                            addHealthMessage(messages, HealthSeverity.WARNING,
                                    "Нет READY snapshot: "
                                            + healthPreview(missingSnapshots, 8));
                        }
                        if (!staleSnapshots.isEmpty()) {
                            addHealthMessage(messages, HealthSeverity.WARNING,
                                    "Устаревшие snapshots: "
                                            + healthPreview(staleSnapshots, 8));
                        }
                    }

                    int activeOperations = operations.activeMigrations()
                            + operations.activeSnapshots()
                            + operations.activeFailovers()
                            + operations.activeFailbacks()
                            + operations.activeDrains()
                            + operations.activeRebalances();
                    addHealthMessage(messages,
                            activeOperations == 0
                                    ? HealthSeverity.OK
                                    : HealthSeverity.INFO,
                            "Операции: migrations=" + operations.activeMigrations()
                                    + ", snapshots=" + operations.activeSnapshots()
                                    + ", failovers=" + operations.activeFailovers()
                                    + ", failbacks=" + operations.activeFailbacks()
                                    + ", drains=" + operations.activeDrains()
                                    + ", rebalances=" + operations.activeRebalances()
                                    + ", leases=" + operations.activeOperationLeases());

                    List<ClusterDatabase.DimensionMigration> incompleteAppliedMigrations =
                            pendingMigrations.stream()
                                    .filter(migration ->
                                            migration.status().equals("APPLIED")
                                                    || migration.status().equals("VERIFIED")
                                                    || migration.status().equals("FINALIZE_READY")
                                    )
                                    .toList();
                    if (!incompleteAppliedMigrations.isEmpty()) {
                        Map<String, Integer> incompleteCounts = new LinkedHashMap<>();
                        List<String> incompleteDimensions = new ArrayList<>();
                        for (ClusterDatabase.DimensionMigration migration
                                : incompleteAppliedMigrations) {
                            incompleteCounts.merge(
                                    migration.status(),
                                    1,
                                    Integer::sum
                            );
                            incompleteDimensions.add(
                                    migration.dimensionId()
                                            + "(" + migration.status() + ")"
                            );
                        }
                        addHealthMessage(messages, HealthSeverity.WARNING,
                                "Незавершённые migrations после применения: APPLIED="
                                        + incompleteCounts.getOrDefault("APPLIED", 0)
                                        + ", VERIFIED="
                                        + incompleteCounts.getOrDefault("VERIFIED", 0)
                                        + ", FINALIZE_READY="
                                        + incompleteCounts.getOrDefault("FINALIZE_READY", 0)
                                        + " | "
                                        + healthPreview(incompleteDimensions, 8)
                                        + ". Проверь /gtocluster migration pending");
                    }

                    int migrationStaleWarningMinutes =
                            latestConfig.dimensionMigrationStaleWarningMinutes();
                    List<ClusterDatabase.DimensionMigration> stalePendingMigrations =
                            pendingMigrations.stream()
                                    .filter(migration -> isPendingMigrationStale(
                                            migration,
                                            migrationStaleWarningMinutes
                                    ))
                                    .toList();
                    if (!stalePendingMigrations.isEmpty()) {
                        Map<String, Integer> staleCounts = new LinkedHashMap<>();
                        List<String> staleDimensions = new ArrayList<>();
                        for (ClusterDatabase.DimensionMigration migration
                                : stalePendingMigrations) {
                            staleCounts.merge(
                                    migration.status(),
                                    1,
                                    Integer::sum
                            );
                            staleDimensions.add(
                                    migration.dimensionId()
                                            + "("
                                            + migration.status()
                                            + ", "
                                            + pendingMigrationAge(migration)
                                            + ")"
                            );
                        }
                        addHealthMessage(messages, HealthSeverity.WARNING,
                                "Слишком старые незавершённые migrations: "
                                        + pendingMigrationCounts(staleCounts)
                                        + " | порог="
                                        + migrationStaleWarningMinutes
                                        + " мин | "
                                        + healthPreview(staleDimensions, 8)
                                        + ". Проверь /gtocluster migration pending");
                    }

                    if (operations.recentFailedOperations() > 0) {
                        addHealthMessage(messages, HealthSeverity.WARNING,
                                "Ошибок операций за 24 часа: "
                                        + operations.recentFailedOperations());
                    }
                    if (operations.stuckOperations() > 0) {
                        addHealthMessage(messages, HealthSeverity.CRITICAL,
                                "Операции без обновления более 10 минут: "
                                        + operations.stuckOperations());
                    }
                    if (operations.readyFailoversWithOnlineSource() > 0) {
                        addHealthMessage(messages, HealthSeverity.CRITICAL,
                                "READY failover при ONLINE source: "
                                        + operations.readyFailoversWithOnlineSource());
                    }
                    if (operations.readyFailoversAwaitingApply() > 0
                            || operations.readyMigrationsAwaitingApply() > 0) {
                        addHealthMessage(messages, HealthSeverity.WARNING,
                                "Ожидают применения после перезапуска target-узлов: failovers="
                                        + operations.readyFailoversAwaitingApply()
                                        + ", migrations="
                                        + operations.readyMigrationsAwaitingApply()
                                        + ", targets="
                                        + operations.pendingApplyNodes());
                    }
                    if (!localPendingApply.isEmpty()) {
                        addHealthMessage(messages, HealthSeverity.WARNING,
                                "Текущий узел ожидает pending apply: "
                                        + pendingApplySummary(localPendingApply)
                                        + ". Проверь /gtocluster applyrestart status");
                    }

                    HealthSeverity transferSeverity =
                            operations.staleClaimedTransfers() > 0
                                    || operations.expiredPlayerSessions() > 0
                                    ? HealthSeverity.WARNING
                                    : HealthSeverity.OK;
                    addHealthMessage(messages, transferSeverity,
                            "Transfers: active=" + operations.activeTransfers()
                                    + ", stale CLAIMED="
                                    + operations.staleClaimedTransfers()
                                    + ", expired sessions="
                                    + operations.expiredPlayerSessions());
                }
            } catch (Exception exception) {
                if (isDatabaseConnectionFailure(exception)) {
                    String summary = exceptionSummary(exception);
                    LOGGER.warn(
                            "Cluster health check could not reach MySQL: {}",
                            summary
                    );
                    addHealthMessage(messages, HealthSeverity.CRITICAL,
                            "MySQL недоступен: " + summary);
                } else {
                    LOGGER.error("Cluster health check failed", exception);
                    addHealthMessage(messages, HealthSeverity.CRITICAL,
                            "Диагностика завершилась ошибкой: "
                                    + exception.getClass().getSimpleName()
                                    + ": " + exceptionSummary(exception));
                }
            }

            List<HealthMessage> result = List.copyOf(messages);
            server.execute(() -> sendClusterHealth(source, result));
        });
        return 1;
    }

    private static boolean isDatabaseConnectionFailure(
            Throwable throwable
    ) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth++ < 16) {
            String className = current.getClass().getName();
            if (current instanceof java.net.ConnectException
                    || current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof java.sql.SQLTransientConnectionException
                    || current instanceof java.sql.SQLNonTransientConnectionException
                    || className.endsWith(".CommunicationsException")
                    || className.endsWith(".CJCommunicationsException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String exceptionSummary(
            Throwable throwable
    ) {
        Throwable current = throwable;
        String summary = null;
        int depth = 0;
        while (current != null && depth++ < 16) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                int lineBreak = message.indexOf('\n');
                summary = (lineBreak >= 0
                        ? message.substring(0, lineBreak)
                        : message).trim();
            }
            current = current.getCause();
        }
        if (summary == null || summary.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return summary;
    }

    private static void checkHealthStagingPath(
            List<HealthMessage> messages,
            Path stagingPath
    ) {
        if (stagingPath == null) {
            addHealthMessage(messages, HealthSeverity.CRITICAL,
                    "dimension_migration_staging_path не настроен");
            return;
        }
        Path absolute = stagingPath.toAbsolutePath().normalize();
        if (Files.exists(absolute)) {
            if (!Files.isDirectory(absolute)) {
                addHealthMessage(messages, HealthSeverity.CRITICAL,
                        "Staging path не является папкой: " + absolute);
            } else if (!Files.isReadable(absolute) || !Files.isWritable(absolute)) {
                addHealthMessage(messages, HealthSeverity.CRITICAL,
                        "Нет прав чтения или записи staging: " + absolute);
            } else {
                addHealthMessage(messages, HealthSeverity.OK,
                        "Staging доступен для чтения и записи: " + absolute);
            }
            return;
        }
        Path parent = absolute.getParent();
        if (parent != null && Files.isDirectory(parent) && Files.isWritable(parent)) {
            addHealthMessage(messages, HealthSeverity.WARNING,
                    "Staging ещё не создан, но родительская папка доступна: " + absolute);
        } else {
            addHealthMessage(messages, HealthSeverity.CRITICAL,
                    "Staging недоступен: " + absolute);
        }
    }

    private static void addHealthMessage(
            List<HealthMessage> messages,
            HealthSeverity severity,
            String text
    ) {
        messages.add(new HealthMessage(severity, text));
    }

    private static String healthPreview(
            List<String> values,
            int limit
    ) {
        int safeLimit = Math.max(1, limit);
        StringBuilder result = new StringBuilder();
        int shown = Math.min(values.size(), safeLimit);
        for (int index = 0; index < shown; index++) {
            if (index > 0) {
                result.append(", ");
            }
            result.append(values.get(index));
        }
        if (values.size() > shown) {
            result.append(" и ещё ").append(values.size() - shown);
        }
        return result.toString();
    }

    private static void sendClusterHealth(
            CommandSourceStack source,
            List<HealthMessage> messages
    ) {
        int critical = 0;
        int warnings = 0;
        for (HealthMessage message : messages) {
            if (message.severity() == HealthSeverity.CRITICAL) {
                critical++;
            } else if (message.severity() == HealthSeverity.WARNING) {
                warnings++;
            }
        }

        String state;
        if (critical > 0) {
            state = "§cCRITICAL";
        } else if (warnings > 0) {
            state = "§eWARNING";
        } else {
            state = "§aHEALTHY";
        }

        int finalCritical = critical;
        int finalWarnings = warnings;
        source.sendSuccess(() -> Component.literal(
                "§6=== CointCoreGTO Cluster Health ==="
        ), false);
        source.sendSuccess(() -> Component.literal(
                "§6Итог: " + state
                        + "§7 | critical: §f" + finalCritical
                        + "§7 | warnings: §f" + finalWarnings
        ), false);

        for (HealthMessage message : messages) {
            String prefix = switch (message.severity()) {
                case OK -> "§a[OK] ";
                case INFO -> "§b[INFO] ";
                case WARNING -> "§e[WARN] ";
                case CRITICAL -> "§c[CRITICAL] ";
            };
            source.sendSuccess(() -> Component.literal(
                    prefix + "§f" + message.text()
            ), false);
        }
    }

    private int showNodes(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;

        if (currentConfig == null
                || !currentConfig.enabled()) {
            source.sendFailure(
                    Component.literal(
                            "§cКластер выключен или конфиг ещё не загружен."
                    )
            );

            return 0;
        }

        MinecraftServer server = source.getServer();

        source.sendSuccess(
                () -> Component.literal(
                        "§eПолучаю состояние узлов..."
                ),
                false
        );

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig =
                        ClusterConfig.load();

                config = latestConfig;

                List<ClusterDatabase.ClusterNodeStatus> nodes =
                        ClusterDatabase.listNodes(
                                latestConfig
                        );

                server.execute(() -> {
                    if (nodes.isEmpty()) {
                        source.sendFailure(
                                Component.literal(
                                        "§cВ cluster_nodes нет узлов."
                                )
                        );

                        return;
                    }

                    source.sendSuccess(
                            () -> Component.literal(
                                    "§6Узлы кластера:"
                            ),
                            false
                    );

                    for (ClusterDatabase.ClusterNodeStatus node
                            : nodes) {
                        String state = node.online()
                                ? "§aONLINE"
                                : "§cOFFLINE";

                        source.sendSuccess(
                                () -> Component.literal(
                                        state
                                                + " §f"
                                                + node.nodeId()
                                                + "§7 | players: §f"
                                                + node.playerCount()
                                                + "§7 | assignments: §f"
                                                + node.dimensionCount()
                                                + "§7 | heartbeat: §f"
                                                + node.heartbeatAgeSeconds()
                                                + "s"
                                                + "§7 | redirect: §f"
                                                + node.redirectAddress()
                                ),
                                false
                        );
                    }
                });
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to list cluster nodes",
                        exception
                );

                server.execute(
                        () -> source.sendFailure(
                                Component.literal(
                                        "§cНе удалось получить список узлов: "
                                                + exception
                                                .getClass()
                                                .getSimpleName()
                                                + ": "
                                                + exception.getMessage()
                                )
                        )
                );
            }
        });

        return 1;
    }

    private int showDimensionTickStatus(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;

        if (currentConfig == null) {
            source.sendFailure(
                    Component.literal(
                            "§cКонфиг кластера ещё не загружен."
                    )
            );
            return 0;
        }

        MinecraftServer server = source.getServer();

        source.sendSuccess(
                () -> Component.literal(
                        "§6Изоляция тиков измерений: §f"
                                + currentConfig.dimensionTickIsolation()
                                + "§7 | mixin active: §f"
                                + DIMENSION_TICK_GUARD_ACTIVE.get()
                                + "§7 | узел: §f"
                                + currentConfig.nodeId()
                                + "§7 | неизвестный владелец: §cFROZEN (fail-closed)"
                                + "§7 | owner cache: §f"
                                + dimensionOwnerCacheState(currentConfig)
                ),
                false
        );

        int loadedDimensions = 0;

        for (ServerLevel level : server.getAllLevels()) {
            loadedDimensions++;

            String dimensionId =
                    level.dimension().location().toString();

            String ownerNode =
                    DIMENSION_OWNER_CACHE.get(dimensionId);

            boolean suppressed =
                    isDimensionTickSuppressed(level);

            boolean migrationFrozen =
                    DIMENSION_MIGRATION_FROZEN.contains(dimensionId);
            boolean activeMigration =
                    DIMENSION_MIGRATION_BLOCKED.contains(dimensionId);
            boolean snapshotFrozen =
                    DIMENSION_SNAPSHOT_FROZEN.contains(dimensionId);
            boolean ownerKnown =
                    ownerNode != null && !ownerNode.isBlank();
            boolean ownerCacheFresh =
                    isDimensionOwnerCacheFresh(currentConfig);
            boolean ownershipFrozen =
                    currentConfig.dimensionTickIsolation()
                            && ownerCacheFresh
                            && ownerKnown
                            && !ownerNode.equalsIgnoreCase(
                                    currentConfig.nodeId()
                            );

            String state;
            if (!currentConfig.enabled()) {
                state = "§eTICKING §7(cluster disabled)";
            } else if (currentConfig.dimensionTickIsolation()
                    && !ownerCacheFresh) {
                state = "§cFROZEN §7(owner cache "
                        + (DIMENSION_OWNER_CACHE_REFRESHED_AT_MILLIS <= 0L
                        ? "not loaded"
                        : "stale")
                        + ")";
            } else if (migrationFrozen
                    && (activeMigration || !ownershipFrozen)) {
                state = "§cFROZEN §7(migration)";
            } else if (snapshotFrozen && !ownershipFrozen) {
                state = "§cFROZEN §7(snapshot)";
            } else if (!currentConfig.dimensionTickIsolation()) {
                state = "§eTICKING §7(isolation disabled)";
            } else if (!ownerKnown) {
                state = "§cFROZEN §7(owner unknown)";
            } else if (suppressed) {
                state = "§cFROZEN";
            } else {
                state = "§aTICKING";
            }

            String ownerDisplay = ownerNode == null
                    || ownerNode.isBlank()
                    ? "§7unknown"
                    : "§f" + ownerNode;

            source.sendSuccess(
                    () -> Component.literal(
                            state
                                    + " §f"
                                    + dimensionId
                                    + "§7 | owner: "
                                    + ownerDisplay
                    ),
                    false
            );
        }

        final int loadedCount = loadedDimensions;
        source.sendSuccess(
                () -> Component.literal(
                        "§7Загружено измерений: §f"
                                + loadedCount
                ),
                false
        );

        return 1;
    }

    private void sendStatus(
            CommandSourceStack source
    ) {
        ClusterConfig currentConfig = config;

        if (currentConfig == null) {
            source.sendFailure(
                    Component.literal(
                            "§cКонфиг кластера ещё не загружен."
                    )
            );

            return;
        }

        source.sendSuccess(
                () -> Component.literal(
                        "§7Cluster enabled: §f"
                                + currentConfig.enabled()
                                + "§7, node: §f"
                                + currentConfig.nodeId()
                                + "§7, redirect: §f"
                                + currentConfig.redirectAddress()
                                + "§7, heartbeat ticks: §f"
                                + currentConfig.heartbeatIntervalTicks()
                                + "§7, node timeout: §f"
                                + currentConfig.nodeTimeoutSeconds()
                                + "s§7, auto failover: §f"
                                + currentConfig.automaticFailover()
                                + "§7, failover confirmation: §f"
                                + currentConfig.automaticFailoverConfirmationSeconds()
                                + "s§7, failover lease: §f"
                                + currentConfig.automaticFailoverLeaseSeconds()
                                + "s§7, failover clean stops: §f"
                                + currentConfig.automaticFailoverIncludeCleanStops()
                                + "§7, automatic operation recovery: §f"
                                + currentConfig.automaticOperationRecovery()
                                + "§7, operation recovery interval: §f"
                                + currentConfig.automaticOperationRecoveryIntervalSeconds()
                                + "s§7, fail-closed routing: §f"
                                + currentConfig.failClosedRouting()
                                + "§7, player-data sync: §f"
                                + currentConfig.syncPlayerData()
                                + "§7, Forge capabilities: §f"
                                + currentConfig.syncForgeCapabilities()
                                + "§7, max player-data: §f"
                                + currentConfig.maxPlayerDataBytes()
                                + " bytes§7, session lease: §f"
                                + currentConfig.playerSessionLeaseSeconds()
                                + "s§7, transfer lock timeout: §f"
                                + currentConfig.transferLockTimeoutSeconds()
                                + "s§7, backup retention: §f"
                                + currentConfig.playerBackupRetentionDays()
                                + " days§7, dimension tick isolation: §f"
                                + currentConfig.dimensionTickIsolation()
                                + "§7, dimension owner cache max age: §f"
                                + currentConfig.dimensionOwnerCacheMaxAgeSeconds()
                                + "s"
                                + "§7, migration staging: §f"
                                + (currentConfig.dimensionMigrationStagingPath() == null
                                ? "not configured"
                                : currentConfig.dimensionMigrationStagingPath())
                                + "§7, automatic snapshots: §f"
                                + currentConfig.automaticDimensionSnapshots()
                                + "§7, snapshot interval: §f"
                                + currentConfig.dimensionSnapshotIntervalMinutes()
                                + " min§7, snapshot retention: §f"
                                + currentConfig.dimensionSnapshotRetentionDays()
                                + " days§7, snapshot max per dimension: §f"
                                + currentConfig.dimensionSnapshotMaxPerDimension()
                                + "§7, failover snapshot max age: §f"
                                + currentConfig.dimensionSnapshotMaxAgeMinutes()
                                + " min"
                ),
                false
        );

        ClusterDatabase.TestResult result =
                lastResult;

        if (result != null) {
            source.sendSuccess(
                    () -> Component.literal(
                            "§aПоследняя проверка успешна: §f"
                                    + result.databaseName()
                                    + " "
                                    + result.databaseVersion()
                                    + "§a, узлов в таблице: §f"
                                    + result.registeredNodes()
                    ),
                    false
            );
        } else if (lastError != null) {
            source.sendFailure(
                    Component.literal(
                            "§cПоследняя ошибка: "
                                    + lastError
                    )
            );
        } else {
            source.sendSuccess(
                    () -> Component.literal(
                            "§eПроверка ещё не выполнялась."
                    ),
                    false
            );
        }

        if (lastAutomaticOperationRecoverySummary != null) {
            source.sendSuccess(
                    () -> Component.literal(
                            "§7Automatic operation recovery: §f"
                                    + lastAutomaticOperationRecoverySummary
                    ),
                    false
            );
        }
        if (currentConfig.automaticOperationRecovery()) {
            long scanAt = lastAutomaticOperationRecoveryScanAtMillis;
            long scanAgeSeconds = scanAt <= 0L
                    ? -1L
                    : Math.max(0L, (System.currentTimeMillis() - scanAt) / 1_000L);
            String scanAge = scanAgeSeconds < 0L
                    ? "ещё не выполнялась"
                    : scanAgeSeconds + "s назад";
            String scanSummary = lastAutomaticOperationRecoveryScanSummary == null
                    ? "нет данных"
                    : lastAutomaticOperationRecoveryScanSummary;
            source.sendSuccess(
                    () -> Component.literal(
                            "§7Operation recovery watchdog: §finterval="
                                    + currentConfig.automaticOperationRecoveryIntervalSeconds()
                                    + "s§7, last scan: §f" + scanAge
                                    + "§7, result: §f" + scanSummary
                    ),
                    false
            );
        }
    }

    private static void suppressDimensionRoute(
            UUID playerUuid,
            String dimensionId
    ) {
        if (playerUuid == null
                || dimensionId == null
                || dimensionId.isBlank()) {
            return;
        }

        DIMENSION_ROUTE_SUPPRESSIONS.put(
                playerUuid,
                new DimensionRouteSuppression(
                        dimensionId,
                        System.nanoTime()
                                + DIMENSION_ROUTE_SUPPRESSION_NANOS
                )
        );
    }

    private static boolean isDimensionRouteSuppressed(
            UUID playerUuid,
            String dimensionId
    ) {
        DimensionRouteSuppression suppression =
                DIMENSION_ROUTE_SUPPRESSIONS.get(playerUuid);

        if (suppression == null) {
            return false;
        }

        if (System.nanoTime() > suppression.expiresAtNanos()) {
            DIMENSION_ROUTE_SUPPRESSIONS.remove(
                    playerUuid,
                    suppression
            );
            return false;
        }

        return suppression.dimensionId().equals(dimensionId);
    }

    private record PendingApplyConfirmation(
            String code,
            String fingerprint,
            long expiresAtMillis
    ) {
    }

    private enum HealthSeverity {
        OK,
        INFO,
        WARNING,
        CRITICAL
    }

    private record HealthMessage(
            HealthSeverity severity,
            String text
    ) {
    }

    private static final class NodeDrainBatchState {
        private final ClusterDatabase.NodeDrain drain;
        private final List<ClusterDatabase.DimensionDrainItem> items;
        private final int skipped;
        private final boolean recovery;
        private final int alreadyReady;
        private final int alreadyApplied;
        private final String leaseName;
        private final boolean automatic;
        private int index;
        private int ready;
        private int failed;

        private NodeDrainBatchState(
                ClusterDatabase.NodeDrain drain,
                List<ClusterDatabase.DimensionDrainItem> items,
                int skipped,
                boolean recovery,
                int alreadyReady,
                int alreadyApplied,
                String leaseName,
                boolean automatic
        ) {
            this.drain = drain;
            this.items = items;
            this.skipped = skipped;
            this.recovery = recovery;
            this.alreadyReady = alreadyReady;
            this.alreadyApplied = alreadyApplied;
            this.leaseName = leaseName;
            this.automatic = automatic;
        }
    }

    private static final class DimensionFailbackBatchState {
        private final List<ClusterDatabase.DimensionFailback> failbacks;
        private final int skipped;
        private int index;
        private int ready;
        private int failed;

        private DimensionFailbackBatchState(
                List<ClusterDatabase.DimensionFailback> failbacks,
                int skipped
        ) {
            this.failbacks = failbacks;
            this.skipped = skipped;
        }
    }

    private static final class SnapshotBatchState {
        private final List<String> dimensions;
        private final boolean automatic;
        private int index;
        private int created;
        private int skipped;
        private int failed;

        private SnapshotBatchState(
                List<String> dimensions,
                boolean automatic
        ) {
            this.dimensions = dimensions;
            this.automatic = automatic;
        }
    }

    private record SnapshotCleanupResult(
            int deleted,
            long bytes
    ) {
    }

    private record DimensionRouteSuppression(
            String dimensionId,
            long expiresAtNanos
    ) {
    }

    private static void broadcastToOperators(
            MinecraftServer server,
            String message
    ) {
        server.getPlayerList()
                .getPlayers()
                .stream()
                .filter(
                        player ->
                                server.getPlayerList()
                                        .isOp(
                                                player.getGameProfile()
                                        )
                )
                .forEach(
                        player ->
                                player.sendSystemMessage(
                                        Component.literal(message)
                                )
                );

        LOGGER.info(
                message.replace('§', '&')
        );
    }
}