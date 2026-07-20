package Crazer.cubeofinterest.cointcoregto;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
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
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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

    private ClusterTestModule() {
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            MinecraftForge.EVENT_BUS.register(INSTANCE);
        }
    }

    @SubscribeEvent
    public void onServerStarted(
            ServerStartedEvent event
    ) {
        MinecraftServer server = event.getServer();

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

                if (transfer == null) {
                    return;
                }

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
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to claim pending transfer for player {}",
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
        ServerPlayer player;

        try {
            player = source.getPlayerOrException();
        } catch (Exception exception) {
            source.sendFailure(
                    Component.literal(
                            "§cЭту команду нужно выполнять игроком."
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

        String dimensionId =
                player.level()
                        .dimension()
                        .location()
                        .toString();

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();

        float yaw = player.getYRot();
        float pitch = player.getXRot();

        source.sendSuccess(
                () -> Component.literal(
                        "§eСоздаю transfer на узел §f"
                                + targetNode
                                + "§e..."
                ),
                false
        );

        DATABASE_EXECUTOR.execute(() -> {
            try {
                ClusterConfig latestConfig =
                        ClusterConfig.load();

                config = latestConfig;

                ClusterDatabase.CreatedTransfer transfer =
                        ClusterDatabase.createTransfer(
                                latestConfig,
                                playerUuid,
                                targetNode,
                                dimensionId,
                                x,
                                y,
                                z,
                                yaw,
                                pitch
                        );

                server.execute(() -> {
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
                                                + "\n§eВыполни вручную в консоли Velocity:"
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
                });

                LOGGER.info(
                        "Created transfer {} for player {}: {} -> {}, redirect={}",
                        transfer.transferId(),
                        transfer.playerUuid(),
                        transfer.sourceNode(),
                        transfer.targetNode(),
                        transfer.redirectAddress()
                );
            } catch (Exception exception) {
                LOGGER.error(
                        "Unable to create transfer for player {}",
                        playerUuid,
                        exception
                );

                server.execute(() -> {
                    ServerPlayer onlinePlayer =
                            server.getPlayerList()
                                    .getPlayer(playerUuid);

                    if (onlinePlayer == null) {
                        return;
                    }

                    onlinePlayer.sendSystemMessage(
                            Component.literal(
                                    "§cНе удалось создать transfer: "
                                            + exception
                                            .getClass()
                                            .getSimpleName()
                                            + ": "
                                            + exception.getMessage()
                            )
                    );
                });
            }
        });

        return 1;
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
            player.teleportTo(
                    targetLevel,
                    transfer.x(),
                    transfer.y(),
                    transfer.z(),
                    transfer.yaw(),
                    transfer.pitch()
            );

            player.sendSystemMessage(
                    Component.literal(
                            "§aTransfer выполнен: §f"
                                    + transfer.sourceNode()
                                    + " §7-> §f"
                                    + transfer.targetNode()
                                    + "§a, dimension: §f"
                                    + transfer.dimensionId()
                    )
            );

            LOGGER.info(
                    "Applied transfer {} for player {} on node {}",
                    transfer.transferId(),
                    transfer.playerUuid(),
                    currentConfig.nodeId()
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
                            "§cОшибка телепортации transfer: "
                                    + exception.getMessage()
                    )
            );
        }
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