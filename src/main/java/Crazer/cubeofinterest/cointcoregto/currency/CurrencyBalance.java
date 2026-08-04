package Crazer.cubeofinterest.cointcoregto.currency;

public record CurrencyBalance(
        boolean success,
        String code,
        String message,
        long amount
) {
    public static CurrencyBalance success(long amount) {
        return new CurrencyBalance(true, "OK", "", amount);
    }

    public static CurrencyBalance failure(String code, String message) {
        return new CurrencyBalance(false, code, message, 0L);
    }
}
