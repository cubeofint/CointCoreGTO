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
        String playerName =
                player.getGameProfile().getName();
        String loginDimensionId =
                player.level()
                        .dimension()
                        .location()
                        .toString();
        double loginX = player.getX();
        double loginY = player.getY();
        double loginZ = player.getZ();
        float loginYaw = player.getYRot();
        float loginPitch = player.getXRot();

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig =
                        ClusterConfig.load();

                config = latestConfig;

                if (!latestConfig.enabled()) {
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

                    server.execute(
                            () -> applyTransfer(
                                    server,
                                    latestConfig,
                                    transfer
                            )
                    );

                    return;
                }

                String dimensionOwner =
                        ClusterDatabase.findDimensionOwner(
                                latestConfig,
                                loginDimensionId
                        );

                if (dimensionOwner == null) {
                    return;
                }

                updateCachedDimensionOwner(
                        loginDimensionId,
                        dimensionOwner
                );

                if (dimensionOwner.equalsIgnoreCase(
                        latestConfig.nodeId()
                )) {
                    return;
                }

                LOGGER.warn(
                        "Player {} joined node {} in dimension owned by {}. Redirecting.",
                        playerUuid,
                        latestConfig.nodeId(),
                        dimensionOwner
                );

                createTransferAndScheduleRedirect(
                        server,
                        latestConfig,
                        playerUuid,
                        playerName,
                        dimensionOwner,
                        loginDimensionId,
                        loginX,
                        loginY,
                        loginZ,
                        loginYaw,
                        loginPitch,
                        null
                );
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to process cluster login for player {}",
                        playerUuid,
                        exception
                );
            }
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

        UUID playerUuid = player.getUUID();
        String playerName =
                player.getGameProfile().getName();
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        float yaw = player.getYRot();
        float pitch = player.getXRot();

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

                    return;
                }

                updateCachedDimensionOwner(
                        dimensionId,
                        owner
                );

                if (owner.equalsIgnoreCase(
                        latestConfig.nodeId()
                )) {
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

        ClusterPlayerDataCodec.Snapshot playerData;

        try {
            playerData = capturePlayerDataForTransfer(
                    player,
                    currentConfig
            );
        } catch (Exception exception) {
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
                    server.execute(() -> {
                        ServerPlayer onlinePlayer =
                                server.getPlayerList()
                                        .getPlayer(playerUuid);

                        if (onlinePlayer != null) {
                            onlinePlayer.sendSystemMessage(
                                    Component.literal(
                                            "§cДля dimension §f"
                                                    + normalizedDimension
                                                    + "§c владелец не назначен."
                                    )
                            );
                        }
                    });

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

        ClusterPlayerDataCodec.Snapshot playerData;

        try {
            playerData = capturePlayerDataForTransfer(
                    player,
                    currentConfig
            );
        } catch (Exception exception) {
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
                        playerUuid,
                        playerName,
                        transfer
                )
        );
    }

    private void redirectPlayer(
            MinecraftServer server,
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

        int redirectResult =
                server.getCommands()
                        .performPrefixedCommand(
                                server.createCommandSourceStack(),
                                redirectCommand
                        );

        if (redirectResult <= 0) {
            LOGGER.error(
                    "Server Redirect command returned {}: {}",
                    redirectResult,
                    redirectCommand
            );

            onlinePlayer.sendSystemMessage(
                    Component.literal(
                            "§cАвтоматический redirect не выполнился."
                                    + "\n§eЗапись осталась READY."
                                    + "\n§eВыполни вручную:"
                                    + "\n§fredirect "
                                    + playerName
                                    + " "
                                    + transfer.redirectAddress()
                    )
            );

            return;
        }

        LOGGER.info(
                "Automatic redirect command executed for player {}: {}",
                playerUuid,
                redirectCommand
        );
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
            return;
        }

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

        try {
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
            return;
        }

        ResourceLocation dimensionLocation =
                ResourceLocation.tryParse(dimensionId);

        if (dimensionLocation == null) {
            scheduleRouteFailure(
                    server,
                    playerUuid,
                    "Некорректный dimension id: "
                            + dimensionId
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
            scheduleRouteFailure(
                    server,
                    playerUuid,
                    "Dimension "
                            + dimensionId
                            + " назначена текущему узлу "
                            + currentConfig.nodeId()
                            + ", но не загружена на нём."
            );

            return;
        }

        try {
            int experienceLevel =
                    player.experienceLevel;

            player.teleportTo(
                    targetLevel,
                    x,
                    y,
                    z,
                    yaw,
                    pitch
            );

            player.experienceLevel =
                    experienceLevel;
        } catch (Exception exception) {
            LOGGER.error(
                    "Unable to finish local FTB Essentials teleport for player {} to {}",
                    playerUuid,
                    dimensionId,
                    exception
            );

            scheduleRouteFailure(
                    server,
                    playerUuid,
                    "Ошибка локальной телепортации: "
                            + exception.getMessage()
            );
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
                onlinePlayer.sendSystemMessage(
                        Component.literal(
                                "§c" + message
                        )
                );
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
                return;
            }

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
                    currentConfig,
                    transfer,
                    "Измерение отсутствует на узле: "
                            + transfer.dimensionId()
            );

            player.sendSystemMessage(
                    Component.literal(
                            "§cTransfer найден, но измерение §f"
                                    + transfer.dimensionId()
                                    + "§c отсутствует на узле §f"
                                    + currentConfig.nodeId()
                    )
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

            player.teleportTo(
                    targetLevel,
                    transfer.x(),
                    transfer.y(),
                    transfer.z(),
                    transfer.yaw(),
                    transfer.pitch()
            );

            // Persist both the restored state and the replay-protection marker
            // before the DB claim can become stale and be retried.
            server.getPlayerList().saveAll();

            player.sendSystemMessage(
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

            DATABASE_EXECUTOR.execute(() -> {
                try {
                    ClusterDatabase.markConsumed(
                            currentConfig,
                            transfer.transferId()
                    );
                } catch (Exception exception) {
                    LOGGER.error(
                            "Unable to mark transfer {} as CONSUMED",
                            transfer.transferId(),
                            exception
                    );
                }
            });
        } catch (Exception exception) {
            failTransfer(
                    currentConfig,
                    transfer,
                    exception.getClass().getSimpleName()
                            + ": "
                            + exception.getMessage()
            );

            player.sendSystemMessage(
                    Component.literal(
                            "§cОшибка применения transfer: "
                                    + exception.getMessage()
                    )
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

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.markFailed(
                        currentConfig,
                        transfer.transferId()
                );
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to mark transfer {} as FAILED",
                        transfer.transferId(),
                        exception
                );
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
                                + " bytes"
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