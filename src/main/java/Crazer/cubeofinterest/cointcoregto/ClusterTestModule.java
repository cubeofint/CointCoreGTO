package Crazer.cubeofinterest.cointcoregto;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
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
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClusterTestModule {
    private static final Logger LOGGER =
            LogManager.getLogger("CointCoreGTO:Cluster");

    private static final ClusterTestModule INSTANCE =
            new ClusterTestModule();

    private static final AtomicBoolean REGISTERED =
            new AtomicBoolean();

    private static final AtomicBoolean HEARTBEAT_IN_FLIGHT =
            new AtomicBoolean();

    private static volatile Map<String, String>
            DIMENSION_OWNER_CACHE = Map.of();

    private static final ConcurrentMap<UUID, DimensionRouteSuppression>
            DIMENSION_ROUTE_SUPPRESSIONS = new ConcurrentHashMap<>();

    private static final long DIMENSION_ROUTE_SUPPRESSION_NANOS =
            10_000_000_000L;

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
    private int heartbeatTickCounter;

    private ClusterTestModule() {
    }

    public static void register() {
        ClusterTransferGuard.register();

        if (REGISTERED.compareAndSet(false, true)) {
            MinecraftForge.EVENT_BUS.register(INSTANCE);
        }
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

        String cachedOwner =
                DIMENSION_OWNER_CACHE.get(dimensionId);

        if (cachedOwner != null
                && cachedOwner.equalsIgnoreCase(
                currentConfig.nodeId()
        )) {
            return false;
        }

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
    public void onServerStarted(
            ServerStartedEvent event
    ) {
        MinecraftServer server = event.getServer();
        ClusterTransferGuard.clearAll();
        activeServer = server;
        heartbeatTickCounter = 0;

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
                        server
                );

                performFailover(
                        currentConfig,
                        false
                );

                refreshDimensionOwnerCache(
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
        DIMENSION_ROUTE_SUPPRESSIONS.clear();
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
                                                runFailoverCommand(
                                                        context.getSource()
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

        if (dimensionOwner == null
                || dimensionOwner.equalsIgnoreCase(
                currentConfig.nodeId()
        )) {
            ClusterTransferGuard.unlock(player);
            return;
        }

        LOGGER.warn(
                "Player {} joined node {} in dimension owned by {} without a pending transfer. Disconnecting to prevent a redirect loop.",
                playerUuid,
                currentConfig.nodeId(),
                dimensionOwner
        );

        ClusterTransferGuard.unlock(player);
        player.connection.disconnect(
                Component.literal(
                        "Вы подключились к узлу "
                                + currentConfig.nodeId()
                                + " в измерении "
                                + loginDimensionId
                                + ", которое принадлежит узлу "
                                + dimensionOwner
                                + ".\nАвтоматический redirect без активного transfer "
                                + "заблокирован для защиты от бесконечного цикла. "
                                + "Подключитесь к правильному узлу или выполните "
                                + "кластерный переход из игры."
                )
        );
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

        if (cachedOwner != null
                && cachedOwner.equalsIgnoreCase(
                currentConfig.nodeId()
        )) {
            return;
        }

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

                String owner =
                        ClusterDatabase.findDimensionOwner(
                                latestConfig,
                                normalizedDimension
                        );

                server.execute(() -> {
                    if (owner == null) {
                        source.sendFailure(
                                Component.literal(
                                        "§cДля dimension §f"
                                                + normalizedDimension
                                                + "§c владелец не назначен."
                                )
                        );

                        return;
                    }

                    source.sendSuccess(
                            () -> Component.literal(
                                    "§aDimension §f"
                                            + normalizedDimension
                                            + "§a принадлежит узлу §f"
                                            + owner
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

    private List<ClusterDatabase.DimensionReassignment>
    performFailover(
            ClusterConfig currentConfig,
            boolean force
    ) throws java.sql.SQLException {
        if (!force && !currentConfig.automaticFailover()) {
            return List.of();
        }

        List<ClusterDatabase.DimensionReassignment> reassignments =
                ClusterDatabase.failoverOfflineDimensions(
                        currentConfig
                );

        for (ClusterDatabase.DimensionReassignment reassignment
                : reassignments) {
            LOGGER.warn(
                    "Cluster failover moved dimension {}: {} -> {}",
                    reassignment.dimensionId(),
                    reassignment.previousNodeId(),
                    reassignment.newNodeId()
            );
        }

        return reassignments;
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

                List<ClusterDatabase.DimensionReassignment> reassignments =
                        performFailover(
                                latestConfig,
                                true
                        );

                refreshDimensionOwnerCache(
                        latestConfig
                );

                server.execute(() -> {
                    if (reassignments.isEmpty()) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "§aFailover не требуется: все владельцы измерений ONLINE."
                                ),
                                false
                        );

                        return;
                    }

                    source.sendSuccess(
                            () -> Component.literal(
                                    "§aПереназначено измерений: §f"
                                            + reassignments.size()
                            ),
                            false
                    );

                    for (ClusterDatabase.DimensionReassignment reassignment
                            : reassignments) {
                        source.sendSuccess(
                                () -> Component.literal(
                                        "§f"
                                                + reassignment.dimensionId()
                                                + " §7| §c"
                                                + reassignment.previousNodeId()
                                                + " §7-> §a"
                                                + reassignment.newNodeId()
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
                            server
                    );

            performFailover(
                    currentConfig,
                    false
            );

            refreshDimensionOwnerCache(
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
                                + " days"
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