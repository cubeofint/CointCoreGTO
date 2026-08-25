package Crazer.cubeofinterest.cointcoregto.supply;

import Crazer.cubeofinterest.cointcoregto.ClusterConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SupplyBufferService {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO-SupplyBuffer");
    private static final long CONFIG_CACHE_MILLIS = 15_000L;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "CointCoreGTO-SupplyBuffer-DB");
        thread.setDaemon(true);
        thread.setContextClassLoader(SupplyBufferService.class.getClassLoader());
        return thread;
    });

    private static volatile ClusterConfig cachedConfig;
    private static volatile long configCacheUntil;

    private SupplyBufferService() {
    }

    public static String currentNodeId() {
        ClusterConfig config = currentConfig();
        return config == null ? "" : config.nodeId();
    }

    public static boolean clusterEnabled() {
        ClusterConfig config = currentConfig();
        return config != null && config.enabled();
    }

    public static CompletableFuture<Void> touchEndpoint(
            String endpointId,
            String linkId,
            String role,
            String providerNode,
            String dimensionId,
            String blockPosition,
            UUID ownerUuid,
            String ownerName,
            boolean aeOnline,
            boolean linkOnline,
            int pendingCount,
            Collection<SupplyBufferDatabase.ResourceSnapshot> resources
    ) {
        return CompletableFuture.runAsync(() -> {
            ClusterConfig config = requireConfig();
            try {
                SupplyBufferDatabase.touchEndpoint(
                        config,
                        endpointId,
                        linkId,
                        role,
                        config.nodeId(),
                        providerNode,
                        dimensionId,
                        blockPosition,
                        ownerUuid,
                        ownerName,
                        aeOnline,
                        linkOnline,
                        pendingCount,
                        resources
                );
            } catch (Exception exception) {
                throw new SupplyServiceException(exception);
            }
        }, EXECUTOR);
    }

    public static CompletableFuture<Void> touchProvider(
            String linkId,
            String dimensionId,
            String blockPosition,
            boolean aeOnline
    ) {
        return CompletableFuture.runAsync(() -> {
            ClusterConfig config = requireConfig();
            try {
                SupplyBufferDatabase.touchProvider(
                        config,
                        linkId,
                        config.nodeId(),
                        dimensionId,
                        blockPosition,
                        aeOnline
                );
            } catch (Exception exception) {
                throw new SupplyServiceException(exception);
            }
        }, EXECUTOR);
    }

    public static CompletableFuture<SupplyBufferDatabase.Operation> claimNext(String linkId) {
        return CompletableFuture.supplyAsync(() -> {
            ClusterConfig config = requireConfig();
            try {
                return SupplyBufferDatabase.claimNext(config, linkId, config.nodeId());
            } catch (Exception exception) {
                throw new SupplyServiceException(exception);
            }
        }, EXECUTOR);
    }

    public static CompletableFuture<Void> markApplied(UUID operationId, long deliveredAmount) {
        return CompletableFuture.runAsync(() -> {
            ClusterConfig config = requireConfig();
            try {
                SupplyBufferDatabase.markApplied(
                        config,
                        operationId,
                        config.nodeId(),
                        deliveredAmount
                );
            } catch (Exception exception) {
                throw new SupplyServiceException(exception);
            }
        }, EXECUTOR);
    }

    public static CompletableFuture<Void> releaseClaim(UUID operationId, String reason) {
        return CompletableFuture.runAsync(() -> {
            ClusterConfig config = requireConfig();
            try {
                SupplyBufferDatabase.releaseClaim(
                        config,
                        operationId,
                        config.nodeId(),
                        reason
                );
            } catch (Exception exception) {
                throw new SupplyServiceException(exception);
            }
        }, EXECUTOR);
    }

    public static CompletableFuture<Void> markFailed(UUID operationId, String error) {
        return CompletableFuture.runAsync(() -> {
            ClusterConfig config = requireConfig();
            try {
                SupplyBufferDatabase.markFailed(
                        config,
                        operationId,
                        config.nodeId(),
                        error
                );
            } catch (Exception exception) {
                throw new SupplyServiceException(exception);
            }
        }, EXECUTOR);
    }

    public static CompletableFuture<SupplyBufferDatabase.CancelResult> tryCancelPending(UUID operationId) {
        return CompletableFuture.supplyAsync(() -> {
            ClusterConfig config = requireConfig();
            try {
                return SupplyBufferDatabase.tryCancelPending(
                        config,
                        operationId,
                        config.nodeId()
                );
            } catch (Exception exception) {
                throw new SupplyServiceException(exception);
            }
        }, EXECUTOR);
    }

    public static CompletableFuture<SupplyBufferDatabase.RemoteSyncResult> syncRemote(
            String linkId,
            Collection<SupplyBufferDatabase.PendingDescriptor> pending,
            Collection<UUID> acknowledgements
    ) {
        return CompletableFuture.supplyAsync(() -> {
            ClusterConfig config = requireConfig();
            try {
                return SupplyBufferDatabase.syncRemote(
                        config,
                        linkId,
                        config.nodeId(),
                        pending,
                        acknowledgements
                );
            } catch (Exception exception) {
                throw new SupplyServiceException(exception);
            }
        }, EXECUTOR);
    }

    public static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException
                || current instanceof SupplyServiceException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? throwable : current;
    }

    private static ClusterConfig requireConfig() {
        ClusterConfig config = currentConfig();
        if (config == null) {
            throw new SupplyServiceException(new IllegalStateException(
                    "Cluster config is unavailable"
            ));
        }
        if (!config.enabled()) {
            throw new SupplyServiceException(new IllegalStateException(
                    "Cluster mode is disabled in cointcoregto-cluster.properties"
            ));
        }
        return config;
    }

    private static ClusterConfig currentConfig() {
        long now = System.currentTimeMillis();
        ClusterConfig current = cachedConfig;
        if (current != null && now < configCacheUntil) {
            return current;
        }

        synchronized (SupplyBufferService.class) {
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
                LOGGER.warn("Could not load cluster config for Supply Buffer: {}", exception.getMessage());
                cachedConfig = null;
                configCacheUntil = now + 5_000L;
                return null;
            }
        }
    }

    private static final class SupplyServiceException extends RuntimeException {
        private SupplyServiceException(Throwable cause) {
            super(cause);
        }
    }
}
