package Crazer.cubeofinterest.cointcoregto.currency;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CurrencyApi {
    private static final Map<String, CurrencyProvider> PROVIDERS = new ConcurrentHashMap<>();
    private static volatile String activeProviderId = "";

    private CurrencyApi() {
    }

    public static void registerProvider(CurrencyProvider provider) {
        if (provider == null || provider.providerId() == null || provider.providerId().isBlank()) {
            throw new IllegalArgumentException("provider");
        }
        CurrencyProvider previous = PROVIDERS.putIfAbsent(provider.providerId(), provider);
        if (previous != null && previous != provider) {
            throw new IllegalStateException("Currency provider already registered: " + provider.providerId());
        }
    }

    public static CurrencyProvider getProvider(String providerId) {
        return providerId == null ? null : PROVIDERS.get(providerId);
    }

    public static Collection<String> providerIds() {
        return List.copyOf(PROVIDERS.keySet());
    }

    static void setActiveProviderId(String providerId) {
        activeProviderId = providerId == null ? "" : providerId;
    }

    public static String activeProviderId() {
        return activeProviderId;
    }

    public static CurrencyProvider activeProvider() {
        return getProvider(activeProviderId);
    }
}
