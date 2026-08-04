package Crazer.cubeofinterest.cointcoregto.currency;

import java.util.UUID;

public record CurrencySettlementEntry(UUID recipientUuid, long amount) {
    public CurrencySettlementEntry {
        if (recipientUuid == null) {
            throw new IllegalArgumentException("recipientUuid");
        }
        if (amount <= 0L) {
            throw new IllegalArgumentException("amount");
        }
    }
}
