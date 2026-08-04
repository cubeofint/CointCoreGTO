package Crazer.cubeofinterest.cointcoregto.currency;

import Crazer.cubeofinterest.cointcoregto.ClusterConfig;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CurrencyService {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:Currency");
    private static final MysqlCurrencyProvider MYSQL_PROVIDER = new MysqlCurrencyProvider();
    private static final Map<UUID, CachedBalance> BALANCE_CACHE = new ConcurrentHashMap<>();

    private static volatile MinecraftServer server;
    private static volatile CurrencyProvider activeProvider;
    private static volatile String lastError = "";
    private static volatile long nextExpiredHoldCheckMillis;

    private CurrencyService() {
    }

    public static synchronized void start(MinecraftServer minecraftServer) {
        server = minecraftServer;
        if (CurrencyApi.getProvider(MysqlCurrencyProvider.ID) == null) {
            CurrencyApi.registerProvider(MYSQL_PROVIDER);
        }
        reload();
    }

    public static synchronized void reload() {
        BALANCE_CACHE.clear();
        closeCurrentProvider();
        activeProvider = null;
        CurrencyApi.setActiveProviderId("");
        lastError = "";

        if (!CurrencyConfig.enabled()) {
            lastError = "Currency system is disabled";
            return;
        }

        String providerId = CurrencyConfig.providerId();
        CurrencyProvider provider = CurrencyApi.getProvider(providerId);
        if (provider == null) {
            lastError = "Currency provider is not registered: " + providerId;
            LOGGER.error(lastError);
            return;
        }

        try {
            provider.initialize();
            activeProvider = provider;
            CurrencyApi.setActiveProviderId(providerId);
            nextExpiredHoldCheckMillis = 0L;
            LOGGER.info(
                    "Currency provider initialized: provider={}, currency={}, name={}",
                    providerId,
                    provider.descriptor().currencyId(),
                    provider.descriptor().displayName()
            );
        } catch (Exception exception) {
            lastError = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            LOGGER.error("Unable to initialize currency provider {}", providerId, exception);
        }
    }

    public static synchronized void stop() {
        BALANCE_CACHE.clear();
        closeCurrentProvider();
        activeProvider = null;
        CurrencyApi.setActiveProviderId("");
        server = null;
    }

    public static void tick(MinecraftServer minecraftServer) {
        CurrencyProvider provider = activeProvider;
        if (provider == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextExpiredHoldCheckMillis) {
            return;
        }
        nextExpiredHoldCheckMillis = now + CurrencyConfig.holdExpirationCheckSeconds() * 1000L;
        try {
            int released = provider.releaseExpiredHolds(
                    200,
                    CurrencyContext.system(nodeId(), "expired hold cleanup", "CURRENCY_CLEANUP", "scheduled")
            );
            if (released > 0) {
                BALANCE_CACHE.clear();
                LOGGER.info("Released {} expired currency holds", released);
            }
        } catch (Exception exception) {
            lastError = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            LOGGER.error("Unable to release expired currency holds", exception);
        }
    }

    public static boolean available() {
        return CurrencyConfig.enabled() && activeProvider != null;
    }

    public static String lastError() {
        return lastError;
    }

    public static CurrencyProvider provider() {
        return activeProvider;
    }

    public static CurrencyDescriptor descriptor() {
        CurrencyProvider provider = activeProvider;
        return provider == null ? CurrencyConfig.descriptor() : provider.descriptor();
    }

    public static CurrencyBalance balance(UUID playerUuid) {
        if (!available()) {
            return CurrencyBalance.failure("UNAVAILABLE", unavailableMessage());
        }
        CachedBalance cached = BALANCE_CACHE.get(playerUuid);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAtMillis() <= 1000L) {
            return CurrencyBalance.success(cached.amount());
        }
        try {
            CurrencyBalance result = activeProvider.getBalance(playerUuid);
            if (result.success()) {
                BALANCE_CACHE.put(playerUuid, new CachedBalance(result.amount(), now));
            }
            return result;
        } catch (Exception exception) {
            return providerFailure(exception);
        }
    }

    public static CurrencyOperationResult credit(
            UUID playerUuid,
            long amount,
            UUID operationId,
            CurrencyContext context
    ) {
        return execute(operationId, () -> activeProvider.credit(playerUuid, amount, operationId, context), null, playerUuid);
    }

    public static CurrencyOperationResult debit(
            UUID playerUuid,
            long amount,
            UUID operationId,
            CurrencyContext context
    ) {
        return execute(operationId, () -> activeProvider.debit(playerUuid, amount, operationId, context), playerUuid, null);
    }

    public static CurrencyOperationResult transfer(
            UUID sourcePlayerUuid,
            UUID targetPlayerUuid,
            long amount,
            UUID operationId,
            CurrencyContext context
    ) {
        return execute(
                operationId,
                () -> activeProvider.transfer(sourcePlayerUuid, targetPlayerUuid, amount, operationId, context),
                sourcePlayerUuid,
                targetPlayerUuid
        );
    }

    public static CurrencyOperationResult hold(
            UUID playerUuid,
            long amount,
            UUID holdId,
            Instant expiresAt,
            CurrencyContext context
    ) {
        return execute(
                holdId,
                () -> activeProvider.hold(playerUuid, amount, holdId, expiresAt, context),
                playerUuid,
                null
        );
    }

    public static CurrencyOperationResult capture(
            UUID holdId,
            UUID targetPlayerUuid,
            UUID operationId,
            CurrencyContext context
    ) {
        return execute(
                operationId,
                () -> activeProvider.capture(holdId, targetPlayerUuid, operationId, context),
                null,
                targetPlayerUuid
        );
    }

    public static CurrencyOperationResult release(
            UUID holdId,
            UUID ownerUuid,
            UUID operationId,
            CurrencyContext context
    ) {
        return execute(
                operationId,
                () -> activeProvider.release(holdId, operationId, context),
                ownerUuid,
                null
        );
    }

    public static CurrencyOperationResult settle(
            UUID holdId,
            UUID ownerUuid,
            List<CurrencySettlementEntry> entries,
            UUID operationId,
            CurrencyContext context
    ) {
        CurrencyOperationResult result = execute(
                operationId,
                () -> activeProvider.settle(holdId, entries, operationId, context),
                ownerUuid,
                null
        );
        if (entries != null) {
            for (CurrencySettlementEntry entry : entries) {
                if (entry != null && entry.recipientUuid() != null) {
                    BALANCE_CACHE.remove(entry.recipientUuid());
                }
            }
        }
        return result;
    }

    public static CurrencyContext context(
            UUID actorUuid,
            String actorName,
            String reason,
            String sourceType,
            String sourceId,
            Map<String, String> metadata
    ) {
        return new CurrencyContext(
                actorUuid,
                actorName,
                nodeId(),
                reason,
                sourceType,
                sourceId,
                metadata
        );
    }

    public static String format(long amount) {
        return CurrencyAmounts.format(amount, descriptor());
    }

    public static String statusText() {
        CurrencyProvider provider = activeProvider;
        if (provider == null) {
            return "недоступна: " + unavailableMessage();
        }
        CurrencyDescriptor descriptor = provider.descriptor();
        return "provider=" + provider.providerId()
                + ", currency=" + descriptor.currencyId()
                + ", name=" + descriptor.displayName()
                + ", fraction_digits=" + descriptor.fractionDigits();
    }

    private static CurrencyOperationResult execute(
            UUID operationId,
            Operation operation,
            UUID sourceUuid,
            UUID targetUuid
    ) {
        if (!available()) {
            return CurrencyOperationResult.failure(operationId, "UNAVAILABLE", unavailableMessage());
        }
        try {
            CurrencyOperationResult result = operation.run();
            if (sourceUuid != null) {
                BALANCE_CACHE.remove(sourceUuid);
            }
            if (targetUuid != null) {
                BALANCE_CACHE.remove(targetUuid);
            }
            return result;
        } catch (Exception exception) {
            CurrencyBalance failure = providerFailure(exception);
            return CurrencyOperationResult.failure(operationId, failure.code(), failure.message());
        }
    }

    private static CurrencyBalance providerFailure(Exception exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        lastError = message;
        LOGGER.error("Currency provider operation failed", exception);
        return CurrencyBalance.failure("PROVIDER_ERROR", message);
    }

    private static String unavailableMessage() {
        return lastError == null || lastError.isBlank() ? "Валютная система недоступна" : lastError;
    }

    private static String nodeId() {
        try {
            return ClusterConfig.load().nodeId();
        } catch (Exception ignored) {
            MinecraftServer current = server;
            return current == null ? "unknown" : "minecraft-server";
        }
    }

    private static void closeCurrentProvider() {
        CurrencyProvider provider = activeProvider;
        if (provider == null) {
            return;
        }
        try {
            provider.close();
        } catch (Exception exception) {
            LOGGER.error("Unable to close currency provider {}", provider.providerId(), exception);
        }
    }

    private interface Operation {
        CurrencyOperationResult run() throws Exception;
    }

    private record CachedBalance(long amount, long loadedAtMillis) {
    }
}
