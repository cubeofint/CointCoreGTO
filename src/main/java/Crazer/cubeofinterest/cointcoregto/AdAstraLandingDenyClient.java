package Crazer.cubeofinterest.cointcoregto;

public final class AdAstraLandingDenyClient {
    private static long visibleUntilMillis;

    private AdAstraLandingDenyClient() {
    }

    public static void show() {
        visibleUntilMillis = System.currentTimeMillis() + 30_000L;
    }

    public static boolean isVisible() {
        return System.currentTimeMillis() < visibleUntilMillis;
    }

    public static void clear() {
        visibleUntilMillis = 0L;
    }
}