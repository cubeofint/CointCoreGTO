package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClusterFtbChunksManager {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:FTBChunks");
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static final Map<ClusterFtbChunksCodec.ChunkKey, Boolean> APPLIED_PHYSICAL_STATE =
            new ConcurrentHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "CointCoreGTO-FTBChunks-Sync");
        thread.setDaemon(true);
        thread.setContextClassLoader(ClusterFtbChunksManager.class.getClassLoader());
        return thread;
    });

    private static volatile ApplySession applySession;
    private static volatile long nextSyncAtMillis;
    private static volatile long lastCompletedAtMillis;
    private static volatile long nextPhysicalReconcileAtMillis;
    private static volatile String lastSummary;
    private static volatile String lastOwnerFingerprint;
    private static volatile boolean initialized;

    private ClusterFtbChunksManager() {
    }

    public static void started(
            MinecraftServer server,
            ClusterConfig config
    ) {
        applySession = null;
        IN_FLIGHT.set(false);
        initialized = false;
        APPLIED_PHYSICAL_STATE.clear();
        lastOwnerFingerprint = null;
        lastCompletedAtMillis = 0L;
        lastSummary = null;
        nextSyncAtMillis = System.currentTimeMillis() + 3_000L;
        nextPhysicalReconcileAtMillis = System.currentTimeMillis() + 30_000L;
    }

    public static void stopping() {
        applySession = null;
        IN_FLIGHT.set(false);
        initialized = false;
        APPLIED_PHYSICAL_STATE.clear();
        lastOwnerFingerprint = null;
        lastCompletedAtMillis = 0L;
        lastSummary = null;
        nextSyncAtMillis = 0L;
        nextPhysicalReconcileAtMillis = 0L;
    }

    public static void requestSyncSoon() {
        nextSyncAtMillis = Math.min(
                nextSyncAtMillis <= 0L ? Long.MAX_VALUE : nextSyncAtMillis,
                System.currentTimeMillis() + 1_000L
        );
    }

    public static void tick(
            MinecraftServer server,
            ClusterConfig config
    ) {
        if (server == null
                || config == null
                || !config.enabled()
                || !config.syncFtbChunks()) {
            return;
        }

        ApplySession session = applySession;
        if (session != null) {
            drainApplySession(server, config, session);
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextSyncAtMillis) {
            return;
        }
        nextSyncAtMillis = now + config.ftbChunksSyncIntervalSeconds() * 1_000L;

        if (!IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }

        startCycle(server, config, null);
    }

    public static int showStatus(
            CommandSourceStack source,
            ClusterConfig config
    ) {
        if (!validate(source, config)) {
            return 0;
        }

        MinecraftServer server = source.getServer();
        ClusterFtbChunksCodec.Snapshot local;
        try {
            local = ClusterFtbChunksCodec.capture(server);
        } catch (Exception exception) {
            source.sendFailure(Component.literal(
                    "Не удалось прочитать локальные данные FTB Chunks: " + message(exception)
            ));
            return 0;
        }

        EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.FtbChunkClusterSnapshot cluster =
                        ClusterDatabase.loadFtbChunkClusterSnapshot(config);
                List<ClusterDatabase.FtbChunkNodeState> nodes =
                        ClusterDatabase.listFtbChunkNodeStates(config);
                String text = buildStatus(config, local, cluster, nodes);
                server.execute(() -> source.sendSuccess(
                        () -> Component.literal(text),
                        false
                ));
            } catch (Exception exception) {
                server.execute(() -> source.sendFailure(Component.literal(
                        "Не удалось получить состояние FTB Chunks: " + message(exception)
                )));
            }
        });
        return 1;
    }

    public static int syncNow(
            CommandSourceStack source,
            ClusterConfig config
    ) {
        if (!validate(source, config)) {
            return 0;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            source.sendFailure(Component.literal(
                    "Синхронизация FTB Chunks уже выполняется."
            ));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal("§eЗапущена принудительная синхронизация FTB Chunks..."),
                false
        );
        startCycle(source.getServer(), config, source);
        return 1;
    }

    private static void startCycle(
            MinecraftServer server,
            ClusterConfig config,
            CommandSourceStack source
    ) {
        ClusterFtbChunksCodec.Snapshot local;
        Set<String> registeredDimensions;
        try {
            local = ClusterFtbChunksCodec.capture(server);
            registeredDimensions = registeredDimensions(server);
        } catch (Exception exception) {
            fail(server, config, source, "capture failed: " + message(exception), exception);
            return;
        }

        EXECUTOR.execute(() -> evaluate(
                server,
                config,
                local,
                registeredDimensions,
                source
        ));
    }

    private static void evaluate(
            MinecraftServer server,
            ClusterConfig config,
            ClusterFtbChunksCodec.Snapshot local,
            Set<String> registeredDimensions,
            CommandSourceStack source
    ) {
        try {
            ClusterDatabase.FtbChunkClusterSnapshot cluster =
                    ClusterDatabase.loadFtbChunkClusterSnapshot(config);

            Set<String> allDimensions = new LinkedHashSet<>(registeredDimensions);
            for (ClusterFtbChunksCodec.ClaimState claim : local.claims()) {
                allDimensions.add(claim.dimensionId());
            }
            for (ClusterFtbChunksCodec.ClaimState claim : cluster.claims()) {
                allDimensions.add(claim.dimensionId());
            }
            allDimensions.addAll(cluster.initializedDimensions());

            Map<String, String> owners = resolveOwners(config, allDimensions);
            String ownerFingerprint = ownerFingerprint(owners);
            boolean ownerMapChanged = lastOwnerFingerprint != null
                    && !lastOwnerFingerprint.equals(ownerFingerprint);

            Set<String> authoritativeDimensions = new LinkedHashSet<>();
            for (String dimensionId : allDimensions) {
                if (config.nodeId().equalsIgnoreCase(owners.get(dimensionId))) {
                    authoritativeDimensions.add(dimensionId);
                }
            }

            Set<String> bootstrapDimensions = new LinkedHashSet<>(authoritativeDimensions);
            bootstrapDimensions.removeAll(cluster.initializedDimensions());

            int publishedChanges = 0;
            if (!bootstrapDimensions.isEmpty()) {
                ClusterDatabase.FtbChunkPublishResult result =
                        ClusterDatabase.publishFtbChunkSnapshot(
                                config,
                                filterClaims(local.claims(), bootstrapDimensions),
                                bootstrapDimensions,
                                owners
                        );
                publishedChanges += result.changedRows();
                cluster = ClusterDatabase.loadFtbChunkClusterSnapshot(config);
            }

            if (!initialized && !authoritativeDimensions.isEmpty()) {
                List<ClusterFtbChunksCodec.ClaimState> recovered =
                        recoverInitialForceLoadState(
                                local,
                                cluster.claims(),
                                authoritativeDimensions
                        );
                if (recovered != null) {
                    ClusterDatabase.FtbChunkPublishResult result =
                            ClusterDatabase.publishFtbChunkSnapshot(
                                    config,
                                    recovered,
                                    authoritativeDimensions,
                                    owners
                            );
                    publishedChanges += result.changedRows();
                    cluster = ClusterDatabase.loadFtbChunkClusterSnapshot(config);
                }
            }

            boolean applyBeforePublish = !initialized || ownerMapChanged;
            if (!applyBeforePublish) {
                ClusterDatabase.FtbChunkPublishResult result =
                        ClusterDatabase.publishFtbChunkSnapshot(
                                config,
                                filterClaims(local.claims(), authoritativeDimensions),
                                authoritativeDimensions,
                                owners
                        );
                publishedChanges += result.changedRows();
                cluster = ClusterDatabase.loadFtbChunkClusterSnapshot(config);
            }

            long now = System.currentTimeMillis();
            boolean reconcilePhysicalState = now >= nextPhysicalReconcileAtMillis;
            if (reconcilePhysicalState) {
                nextPhysicalReconcileAtMillis = now + 30_000L;
            }

            List<Mutation> mutations = buildMutations(
                    config,
                    local,
                    cluster.claims(),
                    owners,
                    registeredDimensions,
                    reconcilePhysicalState
            );

            ApplySession session = new ApplySession(
                    cluster,
                    ownerFingerprint,
                    mutations,
                    publishedChanges,
                    source
            );
            server.execute(() -> {
                applySession = session;
                if (mutations.isEmpty()) {
                    finishApplySession(server, config, session);
                }
            });
        } catch (Exception exception) {
            fail(server, config, source, "database phase failed: " + message(exception), exception);
        }
    }

    private static void drainApplySession(
            MinecraftServer server,
            ClusterConfig config,
            ApplySession session
    ) {
        if (applySession != session) {
            return;
        }

        int limit = Math.max(1, config.ftbChunksApplyBatchSize());
        int processed = 0;
        try {
            while (processed < limit && session.index < session.mutations.size()) {
                Mutation mutation = session.mutations.get(session.index++);
                applyMutation(server, mutation);
                processed++;
            }

            if (session.index >= session.mutations.size()) {
                finishApplySession(server, config, session);
            }
        } catch (Exception exception) {
            applySession = null;
            fail(server, config, session.source, "apply failed: " + message(exception), exception);
        }
    }

    private static void finishApplySession(
            MinecraftServer server,
            ClusterConfig config,
            ApplySession session
    ) {
        if (applySession != session) {
            return;
        }
        applySession = null;

        ClusterFtbChunksCodec.Snapshot applied;
        try {
            if (session.mutations.stream().anyMatch(Mutation::clientStateChanged)) {
                ClusterFtbChunksCodec.syncClients(server);
            }
            applied = ClusterFtbChunksCodec.capture(server);
            APPLIED_PHYSICAL_STATE.keySet().retainAll(applied.byKey().keySet());
        } catch (Exception exception) {
            fail(server, config, session.source, "verification failed: " + message(exception), exception);
            return;
        }

        int mutationCount = session.mutations.size();
        EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.updateFtbChunkNodeState(
                        config,
                        session.cluster.latestRevisionId(),
                        applied.claims().size(),
                        (int) applied.forceLoadedCount(),
                        "SYNCED",
                        null
                );
                ClusterDatabase.cleanupFtbChunkHistory(
                        config,
                        config.ftbChunksEventRetentionDays()
                );

                initialized = true;
                lastOwnerFingerprint = session.ownerFingerprint;
                complete(
                        "revision=" + session.cluster.latestRevisionId()
                                + ", published=" + session.publishedChanges
                                + ", applied=" + mutationCount
                                + ", local=" + applied.claims().size()
                );

                if (session.source != null) {
                    server.execute(() -> session.source.sendSuccess(
                            () -> Component.literal(
                                    "§aFTB Chunks синхронизирован. Ревизия: §f"
                                            + session.cluster.latestRevisionId()
                                            + "§a, опубликовано изменений: §f"
                                            + session.publishedChanges
                                            + "§a, применено операций: §f"
                                            + mutationCount
                            ),
                            false
                    ));
                }
            } catch (Exception exception) {
                fail(server, config, session.source, "status update failed: " + message(exception), exception);
            }
        });
    }

    private static void applyMutation(
            MinecraftServer server,
            Mutation mutation
    ) throws Exception {
        switch (mutation.kind) {
            case UNCLAIM -> {
                ClusterFtbChunksCodec.unclaim(server, mutation.claim);
                APPLIED_PHYSICAL_STATE.remove(mutation.claim.key());
            }
            case ENSURE_CLAIM -> {
                ClusterFtbChunksCodec.ensureClaim(server, mutation.claim);
                APPLIED_PHYSICAL_STATE.put(mutation.claim.key(), false);
            }
            case SET_FORCE_LOADED -> {
                ClusterFtbChunksCodec.setForceLoaded(
                        server,
                        mutation.claim,
                        mutation.requestedForceLoaded,
                        mutation.physicalForceLoaded
                );
                APPLIED_PHYSICAL_STATE.put(
                        mutation.claim.key(),
                        mutation.physicalForceLoaded
                );
            }
        }
    }

    private static List<Mutation> buildMutations(
            ClusterConfig config,
            ClusterFtbChunksCodec.Snapshot local,
            Collection<ClusterFtbChunksCodec.ClaimState> clusterClaims,
            Map<String, String> owners,
            Set<String> registeredDimensions,
            boolean reconcilePhysicalState
    ) {
        Map<ClusterFtbChunksCodec.ChunkKey, ClusterFtbChunksCodec.ClaimState> localByKey =
                local.byKey();
        Map<ClusterFtbChunksCodec.ChunkKey, ClusterFtbChunksCodec.ClaimState> desiredByKey =
                new LinkedHashMap<>();
        for (ClusterFtbChunksCodec.ClaimState claim : clusterClaims) {
            if (claim.claimed()) {
                desiredByKey.put(claim.key(), claim);
            }
        }

        List<Mutation> result = new ArrayList<>();
        for (ClusterFtbChunksCodec.ClaimState current : localByKey.values()) {
            if (!desiredByKey.containsKey(current.key())) {
                result.add(new Mutation(
                        MutationKind.UNCLAIM,
                        current,
                        false,
                        false,
                        true
                ));
            }
        }

        for (ClusterFtbChunksCodec.ClaimState desired : desiredByKey.values()) {
            ClusterFtbChunksCodec.ClaimState current = localByKey.get(desired.key());
            boolean owner = config.nodeId().equalsIgnoreCase(
                    owners.getOrDefault(
                            desired.dimensionId(),
                            config.ftbChunksDefaultAuthorityNode()
                    )
            );
            boolean dimensionAvailableLocally = registeredDimensions.contains(
                    desired.dimensionId().toLowerCase(Locale.ROOT)
            );
            boolean desiredPhysicalForce = desired.forceLoaded()
                    && dimensionAvailableLocally
                    && (!config.ftbChunksForceLoadOwnerOnly() || owner);

            if (current == null
                    || !current.teamUuid().equals(desired.teamUuid())) {
                result.add(new Mutation(
                        MutationKind.ENSURE_CLAIM,
                        desired,
                        false,
                        false,
                        true
                ));
                if (desired.forceLoaded()) {
                    result.add(new Mutation(
                            MutationKind.SET_FORCE_LOADED,
                            desired,
                            true,
                            desiredPhysicalForce,
                            true
                    ));
                }
                continue;
            }

            Boolean appliedPhysical = APPLIED_PHYSICAL_STATE.get(desired.key());
            boolean logicalChanged = current.forceLoaded() != desired.forceLoaded();
            boolean physicalPolicyChanged = appliedPhysical == null
                    || appliedPhysical != desiredPhysicalForce;
            boolean periodicRemoval = reconcilePhysicalState
                    && desired.forceLoaded()
                    && !desiredPhysicalForce;
            if (logicalChanged || physicalPolicyChanged || periodicRemoval) {
                result.add(new Mutation(
                        MutationKind.SET_FORCE_LOADED,
                        desired,
                        desired.forceLoaded(),
                        desiredPhysicalForce,
                        logicalChanged
                ));
            }
        }

        result.sort(
                Comparator.comparingInt((Mutation mutation) -> mutationOrder(mutation.kind))
                        .thenComparing(mutation -> mutation.claim.dimensionId())
                        .thenComparingInt(mutation -> mutation.claim.chunkX())
                        .thenComparingInt(mutation -> mutation.claim.chunkZ())
        );
        return result;
    }

    private static List<ClusterFtbChunksCodec.ClaimState> recoverInitialForceLoadState(
            ClusterFtbChunksCodec.Snapshot local,
            Collection<ClusterFtbChunksCodec.ClaimState> clusterClaims,
            Set<String> authoritativeDimensions
    ) {
        Map<ClusterFtbChunksCodec.ChunkKey, ClusterFtbChunksCodec.ClaimState> localByKey =
                local.byKey();
        List<ClusterFtbChunksCodec.ClaimState> recovered = new ArrayList<>();
        boolean changed = false;

        for (ClusterFtbChunksCodec.ClaimState cluster : clusterClaims) {
            if (!cluster.claimed()
                    || !authoritativeDimensions.contains(cluster.dimensionId())) {
                continue;
            }
            ClusterFtbChunksCodec.ClaimState localClaim = localByKey.get(cluster.key());
            boolean localRequested = localClaim != null
                    && localClaim.teamUuid().equals(cluster.teamUuid())
                    && localClaim.forceLoaded();
            boolean requested = cluster.forceLoaded() || localRequested;
            if (requested != cluster.forceLoaded()) {
                changed = true;
            }
            recovered.add(new ClusterFtbChunksCodec.ClaimState(
                    cluster.dimensionId(),
                    cluster.chunkX(),
                    cluster.chunkZ(),
                    cluster.teamUuid(),
                    cluster.teamScope(),
                    cluster.teamName(),
                    true,
                    requested
            ));
        }
        return changed ? List.copyOf(recovered) : null;
    }

    private static int mutationOrder(MutationKind kind) {
        return switch (kind) {
            case UNCLAIM -> 0;
            case ENSURE_CLAIM -> 1;
            case SET_FORCE_LOADED -> 2;
        };
    }

    private static Map<String, String> resolveOwners(
            ClusterConfig config,
            Set<String> dimensions
    ) throws Exception {
        Map<String, String> result = new TreeMap<>();
        for (String dimensionId : dimensions) {
            result.put(dimensionId, config.ftbChunksDefaultAuthorityNode());
        }

        for (ClusterDatabase.DimensionAssignmentInfo assignment :
                ClusterDatabase.listDimensionAssignments(config, dimensions)) {
            if (assignment.nodeId() != null && !assignment.nodeId().isBlank()) {
                result.put(
                        assignment.dimensionId().toLowerCase(Locale.ROOT),
                        assignment.nodeId()
                );
            }
        }
        return Map.copyOf(result);
    }

    private static Set<String> registeredDimensions(
            MinecraftServer server
    ) {
        Set<String> result = new LinkedHashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            result.add(level.dimension().location().toString().toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    private static List<ClusterFtbChunksCodec.ClaimState> filterClaims(
            Collection<ClusterFtbChunksCodec.ClaimState> claims,
            Set<String> dimensions
    ) {
        List<ClusterFtbChunksCodec.ClaimState> result = new ArrayList<>();
        for (ClusterFtbChunksCodec.ClaimState claim : claims) {
            if (dimensions.contains(claim.dimensionId())) {
                result.add(claim);
            }
        }
        return result;
    }

    private static String ownerFingerprint(
            Map<String, String> owners
    ) {
        StringBuilder builder = new StringBuilder();
        new TreeMap<>(owners).forEach((dimension, node) -> builder
                .append(dimension)
                .append('=')
                .append(node == null ? "" : node.toLowerCase(Locale.ROOT))
                .append(';'));
        return builder.toString();
    }

    private static String buildStatus(
            ClusterConfig config,
            ClusterFtbChunksCodec.Snapshot local,
            ClusterDatabase.FtbChunkClusterSnapshot cluster,
            List<ClusterDatabase.FtbChunkNodeState> nodes
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("§6FTB Chunks cluster sync\n")
                .append("§7Нода: §f").append(config.nodeId()).append('\n')
                .append("§7Включено: §f").append(config.syncFtbChunks()).append('\n')
                .append("§7Интервал: §f").append(config.ftbChunksSyncIntervalSeconds()).append(" сек.\n")
                .append("§7Force-load только у владельца измерения: §f")
                .append(config.ftbChunksForceLoadOwnerOnly()).append('\n')
                .append("§7Локально claims: §f").append(local.claims().size())
                .append("§7, отмечено force-loaded: §f").append(local.forceLoadedCount()).append('\n')
                .append("§7Активно по данным FTB на этой ноде: §f")
                .append(local.activeForceLoadedCount()).append('\n')
                .append("§7Физически назначено политикой кластера: §f")
                .append(APPLIED_PHYSICAL_STATE.values().stream()
                        .filter(Boolean::booleanValue)
                        .count()).append('\n')
                .append("§7Кластер claims: §f").append(cluster.claims().size())
                .append("§7, запрошено force-loaded: §f").append(cluster.forceLoadedCount()).append('\n')
                .append("§7Ревизия: §f").append(cluster.latestRevisionId()).append('\n')
                .append("§7Инициализировано измерений: §f")
                .append(cluster.initializedDimensions().size()).append('\n')
                .append("§7Операция выполняется: §f").append(IN_FLIGHT.get()).append('\n');

        if (lastSummary != null) {
            builder.append("§7Последний результат: §f").append(lastSummary).append('\n');
        }
        if (lastCompletedAtMillis > 0L) {
            builder.append("§7Завершено назад: §f")
                    .append(Math.max(0L, (System.currentTimeMillis() - lastCompletedAtMillis) / 1_000L))
                    .append(" сек.\n");
        }

        if (!nodes.isEmpty()) {
            builder.append("§7Состояние нод:");
            for (ClusterDatabase.FtbChunkNodeState node : nodes) {
                builder.append("\n §8- §f")
                        .append(node.nodeId())
                        .append(" §7rev=").append(node.appliedRevisionId())
                        .append(", claims=").append(node.localClaimCount())
                        .append(", force=").append(node.localForceLoadedCount())
                        .append(", status=").append(node.status());
                if (node.errorText() != null && !node.errorText().isBlank()) {
                    builder.append(", error=").append(node.errorText());
                }
            }
        }
        return builder.toString();
    }

    private static boolean validate(
            CommandSourceStack source,
            ClusterConfig config
    ) {
        if (config == null || !config.enabled()) {
            source.sendFailure(Component.literal("Кластерная система отключена."));
            return false;
        }
        if (!config.syncFtbChunks()) {
            source.sendFailure(Component.literal(
                    "Синхронизация FTB Chunks отключена в конфигурации."
            ));
            return false;
        }
        if (!ClusterFtbChunksCodec.isLoaded()) {
            source.sendFailure(Component.literal("FTB Chunks не установлен."));
            return false;
        }
        return true;
    }

    private static void fail(
            MinecraftServer server,
            ClusterConfig config,
            CommandSourceStack source,
            String summary,
            Throwable throwable
    ) {
        applySession = null;
        complete(summary);
        LOGGER.error("FTB Chunks cluster synchronization failed", throwable);
        EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.updateFtbChunkNodeState(
                        config,
                        null,
                        0,
                        0,
                        "FAILED",
                        message(throwable)
                );
            } catch (Exception stateException) {
                LOGGER.warn("Unable to store FTB Chunks node failure", stateException);
            }
        });
        if (source != null) {
            server.execute(() -> source.sendFailure(Component.literal(
                    "Не удалось синхронизировать FTB Chunks: " + message(throwable)
            )));
        }
    }

    private static void complete(String summary) {
        lastSummary = summary;
        lastCompletedAtMillis = System.currentTimeMillis();
        nextSyncAtMillis = System.currentTimeMillis() + 1_000L;
        IN_FLIGHT.set(false);
    }

    private static String message(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String value = throwable.getMessage();
        return value == null || value.isBlank()
                ? throwable.getClass().getSimpleName()
                : value;
    }

    private enum MutationKind {
        UNCLAIM,
        ENSURE_CLAIM,
        SET_FORCE_LOADED
    }

    private record Mutation(
            MutationKind kind,
            ClusterFtbChunksCodec.ClaimState claim,
            boolean requestedForceLoaded,
            boolean physicalForceLoaded,
            boolean clientStateChanged
    ) {
    }

    private static final class ApplySession {
        private final ClusterDatabase.FtbChunkClusterSnapshot cluster;
        private final String ownerFingerprint;
        private final List<Mutation> mutations;
        private final int publishedChanges;
        private final CommandSourceStack source;
        private int index;

        private ApplySession(
                ClusterDatabase.FtbChunkClusterSnapshot cluster,
                String ownerFingerprint,
                List<Mutation> mutations,
                int publishedChanges,
                CommandSourceStack source
        ) {
            this.cluster = Objects.requireNonNull(cluster, "cluster");
            this.ownerFingerprint = ownerFingerprint;
            this.mutations = List.copyOf(mutations);
            this.publishedChanges = publishedChanges;
            this.source = source;
        }
    }
}
