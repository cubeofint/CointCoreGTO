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

    private static volatile Map<String, String>
            DIMENSION_OWNER_CACHE = Map.of();

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

        if (!level.players().isEmpty()) {
            DIMENSION_TICK_SUPPRESSION_LOGGED.remove(dimensionId);
            return false;
        }

        if (!currentConfig.dimensionTickIsolation()) {
            DIMENSION_TICK_SUPPRESSION_LOGGED.remove(dimensionId);
            return false;
        }

        String ownerNode =
                DIMENSION_OWNER_CACHE.get(dimensionId);

        if (ownerNode == null || ownerNode.isBlank()) {
            DIMENSION_TICK_SUPPRESSION_LOGGED.remove(dimensionId);
            return false;
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

        nextAutomaticSnapshotAtMillis = System.currentTimeMillis()
                + config.dimensionSnapshotIntervalMinutes() * 60_000L;

        DATABASE_EXECUTOR.execute(
                () -> runTest(server, false)
        );
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
        DIMENSION_PLAYER_COUNT_SNAPSHOT = Map.of();
        DIMENSION_MIGRATION_FROZEN = Set.of();
        DIMENSION_MIGRATION_BLOCKED = Set.of();
        DIMENSION_SNAPSHOT_FROZEN = Set.of();
        DIMENSION_ROUTE_SUPPRESSIONS.clear();
        DIMENSION_TICK_SUPPRESSION_LOGGED.clear();
        DIMENSION_TICK_GUARD_ACTIVE.set(false);
        SNAPSHOT_OPERATION_IN_FLIGHT.set(false);
        nextAutomaticSnapshotAtMillis = 0L;
        lastAutomaticSnapshotSummary = null;
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
                                Commands.literal("tickstatus")
                                        .executes(context ->
                                                showDimensionTickStatus(
                                                        context.getSource()
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

                    if (totalPages > 1) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "§7Следующая страница: §f/gtocluster dimensions list "
                                                + Math.min(
                                                        totalPages,
                                                        page + 1
                                                )
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
        DIMENSION_OWNER_CACHE =
                ClusterDatabase.listDimensionOwners(
                        currentConfig
                );
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
                        if (candidate.eligible()) {
                            state = "§cREADY";
                        } else if (candidate.secondsRemaining() > 0L) {
                            state = "§eWAIT " + candidate.secondsRemaining() + "s";
                        } else {
                            state = "§7SKIP";
                        }
                        source.sendSuccess(() -> Component.literal(
                                state
                                        + " §f"
                                        + candidate.nodeId()
                                        + "§7 | heartbeat: §f"
                                        + candidate.heartbeatAgeSeconds()
                                        + "s§7 | dimensions: §f"
                                        + candidate.dimensionCount()
                                        + "§7 | clean stop: §f"
                                        + candidate.cleanStop()
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
                            "§aFailover READY: §f" + failovers.size() + "§a. Перезапусти target node, указанные ниже."
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
                    LOGGER.warn(
                            "Prepared {} snapshot failovers for offline node {}. Restart target nodes to apply them.",
                            failovers.size(),
                            offlineNode
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
                                                + "§7 | dimensions: §f"
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
                                + "§7 | неизвестный владелец: §aTICKING (fail-open)"
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

            String state;
            if (!currentConfig.enabled()) {
                state = "§eTICKING §7(cluster disabled)";
            } else if (DIMENSION_MIGRATION_FROZEN.contains(dimensionId)) {
                state = "§cFROZEN §7(migration)";
            } else if (!currentConfig.dimensionTickIsolation()) {
                state = "§eTICKING §7(isolation disabled)";
            } else if (!level.players().isEmpty()) {
                state = "§eTICKING §7(players present)";
            } else if (ownerNode == null || ownerNode.isBlank()) {
                state = "§eTICKING §7(owner unknown)";
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
                                + "§7, fail-closed routing: §f"
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