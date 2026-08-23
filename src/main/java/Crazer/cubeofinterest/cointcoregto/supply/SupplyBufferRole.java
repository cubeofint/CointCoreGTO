package Crazer.cubeofinterest.cointcoregto.supply;

public enum SupplyBufferRole {
    UNLINKED,
    PROVIDER,
    REMOTE;

    public static SupplyBufferRole fromName(String value) {
        if (value == null || value.isBlank()) {
            return UNLINKED;
        }

        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return UNLINKED;
        }
    }
}
