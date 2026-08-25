package Crazer.cubeofinterest.cointcoregto.monitor;

import Crazer.cubeofinterest.cointcoregto.ClusterConfig;
import Crazer.cubeofinterest.cointcoregto.ClusterDatabase;
import Crazer.cubeofinterest.cointcoregto.supply.SupplyBufferDatabase;
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

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "CointCoreGTO-ClusterMonitor-DB");
        thread.setDaemon(true);
        thread.setContextClassLoader(ClusterMonitorService.class.getClassLoader());
        return thread;
    });

    private static volatile ClusterConfig cachedConfig;
    private static volatile long configCacheUntil;

    private ClusterMonitorService() {
    }

    public static CompletableFuture<ClusterMonitorSnapshot> readSnapshot() {
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
                            endpoint.heartbeatAgeSeconds(),
                            resources
                    ));
                }

                return new ClusterMonitorSnapshot(
                        true,
                        config.nodeId(),
                        System.currentTimeMillis(),
                        SupplyBufferDatabase.countActiveOperations(config),
                        nodes,
                        buffers,
                        ""
                );
            } catch (Exception exception) {
                Throwable root = unwrap(exception);
                LOGGER.warn("Could not read cluster monitor snapshot: {}", root.getMessage());
                return ClusterMonitorSnapshot.error(config.nodeId(), root.getMessage());
            }
        }, EXECUTOR);
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
