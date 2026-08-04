package Crazer.cubeofinterest.cointcoregto.currency;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CurrencyProvider {
    String providerId();

    CurrencyDescriptor descriptor();

    void initialize() throws Exception;

    void close() throws Exception;

    CurrencyBalance getBalance(UUID playerUuid) throws Exception;

    CurrencyOperationResult credit(
            UUID playerUuid,
            long amount,
            UUID operationId,
            CurrencyContext context
    ) throws Exception;

    CurrencyOperationResult debit(
            UUID playerUuid,
            long amount,
            UUID operationId,
            CurrencyContext context
    ) throws Exception;

    CurrencyOperationResult transfer(
            UUID sourcePlayerUuid,
            UUID targetPlayerUuid,
            long amount,
            UUID operationId,
            CurrencyContext context
    ) throws Exception;

    CurrencyOperationResult hold(
            UUID playerUuid,
            long amount,
            UUID holdId,
            Instant expiresAt,
            CurrencyContext context
    ) throws Exception;

    CurrencyOperationResult capture(
            UUID holdId,
            UUID targetPlayerUuid,
            UUID operationId,
            CurrencyContext context
    ) throws Exception;

    CurrencyOperationResult release(
            UUID holdId,
            UUID operationId,
            CurrencyContext context
    ) throws Exception;

    default CurrencyOperationResult settle(
            UUID holdId,
            List<CurrencySettlementEntry> entries,
            UUID operationId,
            CurrencyContext context
    ) throws Exception {
        if (entries != null && entries.size() == 1) {
            CurrencySettlementEntry entry = entries.get(0);
            return capture(holdId, entry.recipientUuid(), operationId, context);
        }
        return CurrencyOperationResult.failure(
                operationId,
                "SETTLEMENT_UNSUPPORTED",
                "Провайдер валюты не поддерживает разделение платежа"
        );
    }

    default int releaseExpiredHolds(int maximumCount, CurrencyContext context) throws Exception {
        return 0;
    }
}
