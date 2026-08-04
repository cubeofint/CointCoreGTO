package Crazer.cubeofinterest.cointcoregto.currency;

import java.util.UUID;

public record CurrencyOperationResult(
        boolean success,
        boolean duplicate,
        String code,
        String message,
        UUID operationId,
        long sourceBalance,
        long targetBalance
) {
    public static CurrencyOperationResult failure(UUID operationId, String code, String message) {
        return new CurrencyOperationResult(false, false, code, message, operationId, 0L, 0L);
    }
}
