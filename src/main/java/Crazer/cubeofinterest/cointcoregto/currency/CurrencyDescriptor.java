package Crazer.cubeofinterest.cointcoregto.currency;

public record CurrencyDescriptor(
        String currencyId,
        String displayName,
        String symbol,
        int fractionDigits,
        long maximumBalance
) {
    public CurrencyDescriptor {
        if (currencyId == null || currencyId.isBlank()) {
            throw new IllegalArgumentException("currencyId");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName");
        }
        symbol = symbol == null ? "" : symbol;
        if (fractionDigits < 0 || fractionDigits > 6) {
            throw new IllegalArgumentException("fractionDigits");
        }
        if (maximumBalance <= 0L) {
            throw new IllegalArgumentException("maximumBalance");
        }
    }
}
