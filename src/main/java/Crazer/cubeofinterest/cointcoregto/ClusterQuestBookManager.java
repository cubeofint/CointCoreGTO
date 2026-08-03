package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClusterQuestBookManager {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:QuestBook");
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "CointCoreGTO-QuestBook-Sync");
        thread.setDaemon(true);
        thread.setContextClassLoader(ClusterQuestBookManager.class.getClassLoader());
        return thread;
    });

    private static volatile long nextSyncAtMillis;
    private static volatile String lastSummary;
    private static volatile long lastCompletedAtMillis;

    private ClusterQuestBookManager() {
    }

    public static void started(
            MinecraftServer server,
            ClusterConfig config
    ) {
        IN_FLIGHT.set(false);
        lastSummary = null;
        lastCompletedAtMillis = 0L;
        nextSyncAtMillis = System.currentTimeMillis() + 2_000L;
    }

    public static void stopping() {
        IN_FLIGHT.set(false);
        nextSyncAtMillis = 0L;
        lastSummary = null;
        lastCompletedAtMillis = 0L;
    }

    public static void tick(
            MinecraftServer server,
            ClusterConfig config
    ) {
        if (server == null
                || config == null
                || !config.enabled()
                || !config.syncFtbQuestBook()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextSyncAtMillis) {
            return;
        }
        nextSyncAtMillis = now + config.ftbQuestBookSyncIntervalSeconds() * 1_000L;

        if (!IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }

        ClusterQuestBookCodec.Snapshot local;
        try {
            local = ClusterQuestBookCodec.capture(
                    server,
                    config.maxFtbQuestBookBytes()
            );
        } catch (Exception exception) {
            complete("capture failed: " + message(exception));
            LOGGER.error("Unable to capture local FTB Quests book", exception);
            return;
        }

        EXECUTOR.execute(() -> evaluateAutomatic(server, config, local));
    }

    public static int showStatus(
            CommandSourceStack source,
            ClusterConfig config
    ) {
        if (!validateEnabled(source, config)) {
            return 0;
        }

        MinecraftServer server = source.getServer();
        ClusterQuestBookCodec.Snapshot local;
        try {
            local = ClusterQuestBookCodec.capture(
                    server,
                    config.maxFtbQuestBookBytes()
            );
        } catch (Exception exception) {
            source.sendFailure(Component.literal(
                    "Не удалось прочитать локальную книгу FTB Quests: " + message(exception)
            ));
            return 0;
        }

        EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.QuestBookRevision latest =
                        ClusterDatabase.findLatestQuestBookRevision(config);
                ClusterDatabase.QuestBookNodeState state =
                        ClusterDatabase.findQuestBookNodeState(config, config.nodeId());
                String text = buildStatus(config, local, latest, state);
                server.execute(() -> source.sendSuccess(
                        () -> Component.literal(text),
                        false
                ));
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "Не удалось получить состояние книги FTB Quests: " + message(exception)
                )));
            }
        });
        return 1;
    }

    public static int publish(
            CommandSourceStack source,
            ClusterConfig config,
            boolean force
    ) {
        if (!validateEnabled(source, config)) {
            return 0;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            source.sendFailure(Component.literal(
                    "Операция с книгой FTB Quests уже выполняется."
            ));
            return 0;
        }

        MinecraftServer server = source.getServer();
        ClusterQuestBookCodec.Snapshot local;
        try {
            local = ClusterQuestBookCodec.capture(
                    server,
                    config.maxFtbQuestBookBytes()
            );
        } catch (Exception exception) {
            IN_FLIGHT.set(false);
            source.sendFailure(Component.literal(
                    "Не удалось подготовить книгу FTB Quests: " + message(exception)
            ));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal("§eПубликую ревизию книги FTB Quests..."),
                false
        );

        EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.QuestBookRevision latest =
                        ClusterDatabase.findLatestQuestBookRevision(config);
                ClusterDatabase.QuestBookNodeState state =
                        ClusterDatabase.findQuestBookNodeState(config, config.nodeId());

                if (!force && latest != null) {
                    if (state == null
                            || state.appliedRevisionId() == null
                            || state.appliedRevisionId() != latest.revisionId()) {
                        throw new IllegalStateException(
                                "локальная нода не основана на последней ревизии "
                                        + latest.revisionId()
                                        + "; сначала выполните /gtocluster questbook sync"
                        );
                    }
                }

                ClusterDatabase.QuestBookPublishResult result =
                        ClusterDatabase.publishQuestBookRevision(
                                config,
                                local,
                                force ? "FORCE" : "PUBLISH",
                                null,
                                force
                        );
                ClusterDatabase.QuestBookRevision revision = result.revision();
                ClusterDatabase.updateQuestBookNodeState(
                        config,
                        revision.revisionId(),
                        revision.archiveSha256(),
                        local.archiveSha256(),
                        "APPLIED",
                        null
                );
                String text = result.created()
                        ? "§aКнига FTB Quests опубликована как ревизия §f"
                                + revision.revisionId()
                                + "§a, файлов: §f"
                                + revision.fileCount()
                                + "§a, размер: §f"
                                + revision.archiveSize()
                        : "§eКнига FTB Quests уже совпадает с ревизией §f"
                                + revision.revisionId();
                complete("published revision " + revision.revisionId());
                server.execute(() -> source.sendSuccess(
                        () -> Component.literal(text),
                        true
                ));
            } catch (Exception exception) {
                complete("publish failed: " + message(exception));
                LOGGER.error("Unable to publish FTB Quests book", exception);
                server.execute(() -> source.sendFailure(Component.literal(
                        "Не удалось опубликовать книгу FTB Quests: " + message(exception)
                )));
            }
        });
        return 1;
    }

    public static int sync(
            CommandSourceStack source,
            ClusterConfig config,
            boolean force
    ) {
        if (!validateEnabled(source, config)) {
            return 0;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            source.sendFailure(Component.literal(
                    "Операция с книгой FTB Quests уже выполняется."
            ));
            return 0;
        }

        MinecraftServer server = source.getServer();
        ClusterQuestBookCodec.Snapshot local;
        try {
            local = ClusterQuestBookCodec.capture(
                    server,
                    config.maxFtbQuestBookBytes()
            );
        } catch (Exception exception) {
            IN_FLIGHT.set(false);
            source.sendFailure(Component.literal(
                    "Не удалось прочитать локальную книгу FTB Quests: " + message(exception)
            ));
            return 0;
        }

        EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.QuestBookRevision latest =
                        ClusterDatabase.findLatestQuestBookRevision(config);
                if (latest == null) {
                    throw new IllegalStateException("в кластере ещё нет опубликованной ревизии");
                }
                ClusterDatabase.QuestBookNodeState state =
                        ClusterDatabase.findQuestBookNodeState(config, config.nodeId());
                if (!force && hasLocalConflict(local, latest, state)) {
                    throw new IllegalStateException(
                            "обнаружены локальные изменения; используйте publish для публикации "
                                    + "или sync force для их замены"
                    );
                }
                if (local.archiveSha256().equalsIgnoreCase(latest.archiveSha256())) {
                    ClusterDatabase.updateQuestBookNodeState(
                            config,
                            latest.revisionId(),
                            latest.archiveSha256(),
                            local.archiveSha256(),
                            "APPLIED",
                            null
                    );
                    complete("already at revision " + latest.revisionId());
                    server.execute(() -> source.sendSuccess(
                            () -> Component.literal(
                                    "§aЛокальная книга уже соответствует ревизии §f"
                                            + latest.revisionId()
                            ),
                            false
                    ));
                    return;
                }
                server.execute(() -> applyRevision(
                        server,
                        config,
                        latest,
                        source,
                        "Ручная синхронизация"
                ));
            } catch (Exception exception) {
                complete("sync failed: " + message(exception));
                LOGGER.error("Unable to synchronize FTB Quests book", exception);
                server.execute(() -> source.sendFailure(Component.literal(
                        "Не удалось синхронизировать книгу FTB Quests: " + message(exception)
                )));
            }
        });
        return 1;
    }

    public static int revisions(
            CommandSourceStack source,
            ClusterConfig config
    ) {
        if (!validateEnabled(source, config)) {
            return 0;
        }
        MinecraftServer server = source.getServer();
        EXECUTOR.execute(() -> {
            try {
                List<ClusterDatabase.QuestBookRevisionInfo> revisions =
                        ClusterDatabase.listQuestBookRevisions(config, 15);
                StringBuilder text = new StringBuilder("§6=== FTB Quests Book Revisions ===");
                if (revisions.isEmpty()) {
                    text.append("\n§7Ревизий пока нет.");
                } else {
                    for (ClusterDatabase.QuestBookRevisionInfo revision : revisions) {
                        text.append("\n§f#")
                                .append(revision.revisionId())
                                .append(" §7")
                                .append(revision.revisionKind())
                                .append(" §8| §f")
                                .append(revision.sourceNode())
                                .append(" §8| §7files=")
                                .append(revision.fileCount())
                                .append(" bytes=")
                                .append(revision.archiveSize())
                                .append(" sha=")
                                .append(shortHash(revision.archiveSha256()));
                        if (revision.rollbackOfRevision() != null) {
                            text.append(" rollback=#").append(revision.rollbackOfRevision());
                        }
                    }
                }
                server.execute(() -> source.sendSuccess(
                        () -> Component.literal(text.toString()),
                        false
                ));
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "Не удалось получить ревизии книги FTB Quests: " + message(exception)
                )));
            }
        });
        return 1;
    }

    public static int rollback(
            CommandSourceStack source,
            ClusterConfig config,
            long revisionId
    ) {
        if (!validateEnabled(source, config)) {
            return 0;
        }
        if (!config.nodeId().equalsIgnoreCase(config.ftbQuestBookAuthorityNode())) {
            source.sendFailure(Component.literal(
                    "Rollback книги разрешён только на authority-ноде "
                            + config.ftbQuestBookAuthorityNode()
            ));
            return 0;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            source.sendFailure(Component.literal(
                    "Операция с книгой FTB Quests уже выполняется."
            ));
            return 0;
        }

        MinecraftServer server = source.getServer();
        EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.QuestBookRevision selected =
                        ClusterDatabase.findQuestBookRevision(config, revisionId);
                if (selected == null) {
                    throw new IllegalArgumentException("ревизия " + revisionId + " не найдена");
                }
                ClusterQuestBookCodec.validateRevision(
                        selected,
                        config.maxFtbQuestBookBytes()
                );
                ClusterQuestBookCodec.Snapshot snapshot = new ClusterQuestBookCodec.Snapshot(
                        selected.archiveData(),
                        selected.archiveSha256(),
                        selected.archiveSize(),
                        selected.fileCount(),
                        selected.createdAt()
                );
                ClusterDatabase.QuestBookPublishResult published =
                        ClusterDatabase.publishQuestBookRevision(
                                config,
                                snapshot,
                                "ROLLBACK",
                                revisionId,
                                true
                        );
                server.execute(() -> applyRevision(
                        server,
                        config,
                        published.revision(),
                        source,
                        "Rollback с ревизии " + revisionId
                ));
            } catch (Exception exception) {
                complete("rollback failed: " + message(exception));
                LOGGER.error("Unable to rollback FTB Quests book", exception);
                server.execute(() -> source.sendFailure(Component.literal(
                        "Не удалось выполнить rollback книги FTB Quests: " + message(exception)
                )));
            }
        });
        return 1;
    }

    private static void evaluateAutomatic(
            MinecraftServer server,
            ClusterConfig config,
            ClusterQuestBookCodec.Snapshot local
    ) {
        try {
            ClusterDatabase.QuestBookRevision latest =
                    ClusterDatabase.findLatestQuestBookRevision(config);
            ClusterDatabase.QuestBookNodeState state =
                    ClusterDatabase.findQuestBookNodeState(config, config.nodeId());
            boolean authority = config.nodeId().equalsIgnoreCase(
                    config.ftbQuestBookAuthorityNode()
            );

            if (latest == null) {
                if (authority && config.ftbQuestBookAutoPublish()) {
                    ClusterDatabase.QuestBookPublishResult published =
                            ClusterDatabase.publishQuestBookRevision(
                                    config,
                                    local,
                                    "BOOTSTRAP",
                                    null,
                                    false
                            );
                    ClusterDatabase.updateQuestBookNodeState(
                            config,
                            published.revision().revisionId(),
                            published.revision().archiveSha256(),
                            local.archiveSha256(),
                            "APPLIED",
                            null
                    );
                    complete("bootstrapped revision " + published.revision().revisionId());
                    LOGGER.info(
                            "Published initial FTB Quests book revision {} from node {}",
                            published.revision().revisionId(),
                            config.nodeId()
                    );
                } else {
                    ClusterDatabase.updateQuestBookNodeState(
                            config,
                            null,
                            null,
                            local.archiveSha256(),
                            "WAITING",
                            "No cluster revision is available"
                    );
                    complete("waiting for initial revision");
                }
                return;
            }

            if (local.archiveSha256().equalsIgnoreCase(latest.archiveSha256())) {
                ClusterDatabase.updateQuestBookNodeState(
                        config,
                        latest.revisionId(),
                        latest.archiveSha256(),
                        local.archiveSha256(),
                        "APPLIED",
                        null
                );
                complete("revision " + latest.revisionId() + " is current");
                return;
            }

            if (state == null || state.appliedRevisionId() == null) {
                if (local.fileCount() == 0) {
                    server.execute(() -> applyRevision(
                            server,
                            config,
                            latest,
                            null,
                            "Первичная синхронизация пустой книги"
                    ));
                } else {
                    ClusterDatabase.updateQuestBookNodeState(
                            config,
                            null,
                            null,
                            local.archiveSha256(),
                            "BOOTSTRAP_CONFLICT",
                            "Local quest book differs from the first cluster revision"
                    );
                    complete("bootstrap conflict with revision " + latest.revisionId());
                    LOGGER.warn(
                            "FTB Quests book bootstrap conflict on node {}: latest={}, localSha={}, latestSha={}",
                            config.nodeId(),
                            latest.revisionId(),
                            local.archiveSha256(),
                            latest.archiveSha256()
                    );
                }
                return;
            }

            if (state.appliedRevisionId() == latest.revisionId()) {
                if (authority && config.ftbQuestBookAutoPublish()) {
                    ClusterDatabase.QuestBookPublishResult published =
                            ClusterDatabase.publishQuestBookRevision(
                                    config,
                                    local,
                                    "AUTO",
                                    null,
                                    false
                            );
                    ClusterDatabase.updateQuestBookNodeState(
                            config,
                            published.revision().revisionId(),
                            published.revision().archiveSha256(),
                            local.archiveSha256(),
                            "APPLIED",
                            null
                    );
                    complete("auto-published revision " + published.revision().revisionId());
                    LOGGER.info(
                            "Auto-published FTB Quests book revision {} from node {}",
                            published.revision().revisionId(),
                            config.nodeId()
                    );
                } else {
                    ClusterDatabase.updateQuestBookNodeState(
                            config,
                            state.appliedRevisionId(),
                            state.appliedSha256(),
                            local.archiveSha256(),
                            "LOCAL_CHANGES",
                            "Local definitions differ from the applied revision"
                    );
                    complete("local changes detected");
                }
                return;
            }

            if (state.appliedSha256() != null
                    && local.archiveSha256().equalsIgnoreCase(state.appliedSha256())) {
                server.execute(() -> applyRevision(
                        server,
                        config,
                        latest,
                        null,
                        "Автоматическая синхронизация"
                ));
                return;
            }

            ClusterDatabase.updateQuestBookNodeState(
                    config,
                    state.appliedRevisionId(),
                    state.appliedSha256(),
                    local.archiveSha256(),
                    "CONFLICT",
                    "Local definitions changed while a newer cluster revision exists"
            );
            complete("conflict with revision " + latest.revisionId());
            LOGGER.warn(
                    "FTB Quests book conflict on node {}: applied={}, latest={}, localSha={}, appliedSha={}",
                    config.nodeId(),
                    state.appliedRevisionId(),
                    latest.revisionId(),
                    local.archiveSha256(),
                    state.appliedSha256()
            );
        } catch (Exception exception) {
            complete("automatic sync failed: " + message(exception));
            LOGGER.error("Automatic FTB Quests book synchronization failed", exception);
        }
    }

    private static void applyRevision(
            MinecraftServer server,
            ClusterConfig config,
            ClusterDatabase.QuestBookRevision revision,
            CommandSourceStack source,
            String reason
    ) {
        try {
            ClusterQuestBookCodec.ApplyResult result = ClusterQuestBookCodec.apply(
                    server,
                    revision,
                    config.maxFtbQuestBookBytes(),
                    config.ftbQuestBookBackupRetention()
            );
            EXECUTOR.execute(() -> {
                try {
                    ClusterDatabase.updateQuestBookNodeState(
                            config,
                            revision.revisionId(),
                            revision.archiveSha256(),
                            revision.archiveSha256(),
                            "APPLIED",
                            null
                    );
                    complete("applied revision " + revision.revisionId());
                    LOGGER.info(
                            "Applied FTB Quests book revision {} on node {}: files={}, bytes={}, reason={}",
                            revision.revisionId(),
                            config.nodeId(),
                            result.fileCount(),
                            result.extractedBytes(),
                            reason
                    );
                    if (source != null) {
                        server.execute(() -> source.sendSuccess(
                                () -> Component.literal(
                                        "§aПрименена ревизия книги FTB Quests §f"
                                                + revision.revisionId()
                                                + "§a, файлов: §f"
                                                + result.fileCount()
                                ),
                                true
                        ));
                    }
                } catch (Exception exception) {
                    complete("state update failed: " + message(exception));
                    LOGGER.error(
                            "FTB Quests book revision {} applied but node state update failed",
                            revision.revisionId(),
                            exception
                    );
                }
            });
        } catch (Exception exception) {
            EXECUTOR.execute(() -> {
                try {
                    ClusterDatabase.QuestBookNodeState state =
                            ClusterDatabase.findQuestBookNodeState(config, config.nodeId());
                    ClusterDatabase.updateQuestBookNodeState(
                            config,
                            state == null ? null : state.appliedRevisionId(),
                            state == null ? null : state.appliedSha256(),
                            state == null ? null : state.localSha256(),
                            "FAILED",
                            message(exception)
                    );
                } catch (Exception stateException) {
                    exception.addSuppressed(stateException);
                }
                complete("apply failed: " + message(exception));
            });
            LOGGER.error(
                    "Unable to apply FTB Quests book revision {} on node {}",
                    revision.revisionId(),
                    config.nodeId(),
                    exception
            );
            if (source != null) {
                source.sendFailure(Component.literal(
                        "Не удалось применить ревизию книги FTB Quests: " + message(exception)
                ));
            }
        }
    }

    private static boolean hasLocalConflict(
            ClusterQuestBookCodec.Snapshot local,
            ClusterDatabase.QuestBookRevision latest,
            ClusterDatabase.QuestBookNodeState state
    ) {
        if (local.archiveSha256().equalsIgnoreCase(latest.archiveSha256())) {
            return false;
        }
        if (state == null || state.appliedRevisionId() == null) {
            return false;
        }
        if (state.appliedRevisionId() == latest.revisionId()) {
            return true;
        }
        return state.appliedSha256() != null
                && !local.archiveSha256().equalsIgnoreCase(state.appliedSha256());
    }

    private static boolean validateEnabled(
            CommandSourceStack source,
            ClusterConfig config
    ) {
        if (config == null) {
            source.sendFailure(Component.literal("Конфиг кластера ещё не загружен."));
            return false;
        }
        if (!config.enabled()) {
            source.sendFailure(Component.literal("Кластерная система выключена."));
            return false;
        }
        if (!config.syncFtbQuestBook()) {
            source.sendFailure(Component.literal(
                    "Синхронизация книги FTB Quests выключена в конфиге."
            ));
            return false;
        }
        return true;
    }

    private static String buildStatus(
            ClusterConfig config,
            ClusterQuestBookCodec.Snapshot local,
            ClusterDatabase.QuestBookRevision latest,
            ClusterDatabase.QuestBookNodeState state
    ) {
        StringBuilder text = new StringBuilder("§6=== FTB Quests Book Cluster Sync ===")
                .append("\n§7Node: §f").append(config.nodeId())
                .append("§7 | authority: §f").append(config.ftbQuestBookAuthorityNode())
                .append("§7 | auto publish: §f").append(config.ftbQuestBookAutoPublish())
                .append("\n§7Folder: §f").append(ClusterQuestBookCodec.questFolder())
                .append("\n§7Local: §f").append(shortHash(local.archiveSha256()))
                .append("§7 | files: §f").append(local.fileCount())
                .append("§7 | bytes: §f").append(local.archiveSize());
        if (latest == null) {
            text.append("\n§7Cluster revision: §8none");
        } else {
            text.append("\n§7Cluster revision: §f#")
                    .append(latest.revisionId())
                    .append(" §7")
                    .append(latest.revisionKind())
                    .append(" from §f")
                    .append(latest.sourceNode())
                    .append("§7 | sha: §f")
                    .append(shortHash(latest.archiveSha256()))
                    .append("§7 | files: §f")
                    .append(latest.fileCount());
        }
        if (state == null) {
            text.append("\n§7Node state: §8not registered");
        } else {
            text.append("\n§7Node state: §f")
                    .append(state.status())
                    .append("§7 | applied: §f")
                    .append(state.appliedRevisionId() == null ? "none" : "#" + state.appliedRevisionId());
            if (state.errorText() != null && !state.errorText().isBlank()) {
                text.append("\n§7Last error: §c").append(state.errorText());
            }
        }
        text.append("\n§7In flight: §f")
                .append(IN_FLIGHT.get())
                .append("§7 | last: §f")
                .append(lastSummary == null ? "none" : lastSummary);
        return text.toString();
    }

    private static void complete(String summary) {
        lastSummary = summary;
        lastCompletedAtMillis = System.currentTimeMillis();
        IN_FLIGHT.set(false);
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "none";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    private static String message(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String text = throwable.getMessage();
        if (text == null || text.isBlank()) {
            text = throwable.getClass().getSimpleName();
        }
        return text.replace('\n', ' ').replace('\r', ' ');
    }
}
