package Crazer.cubeofinterest.cointcoregto.monitor;

import Crazer.cubeofinterest.cointcoregto.ClusterConfig;
import Crazer.cubeofinterest.cointcoregto.ClusterDatabase;
import Crazer.cubeofinterest.cointcoregto.supply.SupplyBufferDatabase;
import Crazer.cubeofinterest.cointcoregto.supply.SupplyKeyCodec;
import appeng.api.stacks.AEKey;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ClusterMonitorService {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO-ClusterMonitor");
    private static final long CONFIG_CACHE_MILLIS = 15_000L;
    private static final long OPERATIONS_CACHE_MILLIS = 2_500L;
    private static final int OPERATION_HISTORY_LIMIT = 50;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "CointCoreGTO-ClusterMonitor-DB");
        thread.setDaemon(true);
        thread.setContextClassLoader(ClusterMonitorService.class.getClassLoader());
        return thread;
    });

    private static volatile ClusterConfig cachedConfig;
    private static volatile long configCacheUntil;
    private static volatile List<ClusterMonitorSnapshot.OperationEntry> cachedOperations = List.of();
    private static volatile long operationsCacheUntil;

    private ClusterMonitorService() {
    }

    public static CompletableFuture<ClusterMonitorSnapshot> readSnapshot(boolean includeOperations) {
        return CompletableFuture.supplyAsync(() -> {
            ClusterConfig config = currentConfig();
            if (config == null) {
                return ClusterMonitorSnapshot.error("", "Конфиг кластера недоступен");
            }
            if (!config.enabled()) {
                return new ClusterMonitorSnapshot(
                        false,
                        config.nodeId(),
                        System.currentTimeMillis(),
                        0,
                        List.of(),
                        List.of(),
                        List.of(),
                        "Кластер отключён в cointcoregto-cluster.properties"
                );
            }

            try {
                List<ClusterMonitorSnapshot.NodeEntry> nodes = new ArrayList<>();
                for (ClusterDatabase.ClusterNodeStatus node : ClusterDatabase.listNodes(config)) {
                    nodes.add(new ClusterMonitorSnapshot.NodeEntry(
                            node.nodeId(),
                            node.nodeRole(),
                            node.playerCount(),
                            node.dimensionCount(),
                            node.online(),
                            node.heartbeatAgeSeconds()
                    ));
                }

                List<ClusterMonitorSnapshot.BufferEntry> buffers = new ArrayList<>();
                for (SupplyBufferDatabase.EndpointStatus endpoint : SupplyBufferDatabase.listEndpoints(config)) {
                    List<ClusterMonitorSnapshot.ResourceEntry> resources = new ArrayList<>();
                    for (SupplyBufferDatabase.ResourceSnapshot resource : endpoint.resources()) {
                        resources.add(new ClusterMonitorSnapshot.ResourceEntry(
                                resource.resourceType().name(),
                                resource.filterIndex(),
                                resource.displayName(),
                                resource.resourceKey(),
                                resource.amount(),
                                resource.capacity(),
                                resource.refillBelowPercent(),
                                resource.refillToPercent()
                        ));
                    }

                    buffers.add(new ClusterMonitorSnapshot.BufferEntry(
                            endpoint.endpointId(),
                            endpoint.linkId(),
                            endpoint.role(),
                            endpoint.nodeId(),
                            endpoint.providerNode(),
                            endpoint.dimensionId(),
                            endpoint.blockPosition(),
                            endpoint.ownerName(),
                            endpoint.online(),
                            endpoint.aeOnline(),
                            endpoint.linkOnline(),
                            endpoint.pendingCount(),
                            endpoint.priority(),
                            endpoint.heartbeatAgeSeconds(),
                            resources
                    ));
                }

                List<ClusterMonitorSnapshot.OperationEntry> operations = includeOperations
                        ? recentOperations(config)
                        : List.of();

                return new ClusterMonitorSnapshot(
                        true,
                        config.nodeId(),
                        System.currentTimeMillis(),
                        SupplyBufferDatabase.countActiveOperations(config),
                        nodes,
                        buffers,
                        operations,
                        ""
                );
            } catch (Exception exception) {
                Throwable root = unwrap(exception);
                LOGGER.warn("Could not read cluster monitor snapshot: {}", root.getMessage());
                return ClusterMonitorSnapshot.error(config.nodeId(), root.getMessage());
            }
        }, EXECUTOR);
    }

    private static List<ClusterMonitorSnapshot.OperationEntry> recentOperations(
            ClusterConfig config
    ) throws Exception {
        long now = System.currentTimeMillis();
        List<ClusterMonitorSnapshot.OperationEntry> current = cachedOperations;
        if (now < operationsCacheUntil) {
            return current;
        }

        List<ClusterMonitorSnapshot.OperationEntry> result = new ArrayList<>();
        for (SupplyBufferDatabase.MonitorOperation operation
                : SupplyBufferDatabase.listRecentOperations(config, OPERATION_HISTORY_LIMIT)) {
            String displayName = "";
            String resourceKey = "";
            try {
                AEKey key = SupplyKeyCodec.decode(operation.keyPayload());
                displayName = key.getDisplayName().getString();
                if (key.getId() != null) {
                    resourceKey = key.getId().toString();
                }
            } catch (RuntimeException ignored) {
                displayName = operation.resourceType().name();
            }

            result.add(new ClusterMonitorSnapshot.OperationEntry(
                    operation.operationId().toString(),
                    operation.linkId(),
                    operation.sourceNode(),
                    operation.providerNode(),
                    operation.direction().name(),
                    operation.resourceType().name(),
                    displayName,
                    resourceKey,
                    operation.requestedAmount(),
                    operation.deliveredAmount(),
                    operation.priority(),
                    operation.status(),
                    operation.errorText(),
                    operation.createdAgeSeconds(),
                    operation.updatedAgeSeconds()
            ));
        }

        current = List.copyOf(result);
        cachedOperations = current;
        operationsCacheUntil = now + OPERATIONS_CACHE_MILLIS;
        return current;
    }

    private static ClusterConfig currentConfig() {
        long now = System.currentTimeMillis();
        ClusterConfig current = cachedConfig;
        if (current != null && now < configCacheUntil) {
            return current;
        }

        synchronized (ClusterMonitorService.class) {
            now = System.currentTimeMillis();
            current = cachedConfig;
            if (current != null && now < configCacheUntil) {
                return current;
            }

            try {
                current = ClusterConfig.load();
                cachedConfig = current;
                configCacheUntil = now + CONFIG_CACHE_MILLIS;
                return current;
            } catch (IOException exception) {
                cachedConfig = null;
                configCacheUntil = now + 5_000L;
                return null;
            }
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? throwable : current;
    }
}
