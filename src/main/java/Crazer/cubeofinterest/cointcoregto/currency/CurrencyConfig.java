package Crazer.cubeofinterest.cointcoregto.currency;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CurrencyConfig {
    public static final String FILE_NAME = "CointCoreGTO-Currency.toml";
    public static final ForgeConfigSpec SPEC;

    private static final List<String> DEFAULT_TIER_ORDER = List.of(
            "steam", "lv", "mv", "hv", "ev", "iv", "luv", "zpm",
            "uv", "uhv", "uev", "uiv", "uxv", "opv", "max"
    );
    private static final List<Integer> DEFAULT_TIER_DISTANCE_DISCOUNTS = List.of(
            0, 3300, 6600, 7500, 8000, 8500, 9000
    );

    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.ConfigValue<String> PROVIDER;
    private static final ForgeConfigSpec.ConfigValue<String> CURRENCY_ID;
    private static final ForgeConfigSpec.ConfigValue<String> DISPLAY_NAME;
    private static final ForgeConfigSpec.ConfigValue<String> SYMBOL;
    private static final ForgeConfigSpec.IntValue FRACTION_DIGITS;
    private static final ForgeConfigSpec.LongValue MAXIMUM_BALANCE;
    private static final ForgeConfigSpec.BooleanValue AUDIT_METADATA;
    private static final ForgeConfigSpec.IntValue HOLD_EXPIRATION_CHECK_SECONDS;
    private static final ForgeConfigSpec.BooleanValue EXCHANGER_PROGRESSION_ENABLED;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXCHANGER_TIER_ORDER;
    private static final ForgeConfigSpec.ConfigValue<String> EXCHANGER_TIER_PERMISSION_PREFIX;
    private static final ForgeConfigSpec.BooleanValue EXCHANGER_MATCH_PRIMARY_GROUP;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXCHANGER_TIER_GROUP_ALIASES;
    private static final ForgeConfigSpec.ConfigValue<List<? extends Integer>> EXCHANGER_TIER_DISTANCE_DISCOUNTS;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> EXCHANGER_PRODUCT_TIER_RULES;
    private static final ForgeConfigSpec.BooleanValue EXCHANGER_OWNERS_CAN_SET_REQUIRED_TIER;
    private static final ForgeConfigSpec.BooleanValue EXCHANGER_OPERATORS_HAVE_MAX_TIER;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("currency");
        ENABLED = builder.define("enabled", true);
        PROVIDER = builder.define("provider", "cointcoregto:mysql");
        CURRENCY_ID = builder.define("currency_id", "activity_coin");
        DISPLAY_NAME = builder.define("display_name", "Монеты активности");
        SYMBOL = builder.define("symbol", "мон.");
        FRACTION_DIGITS = builder.defineInRange("fraction_digits", 0, 0, 6);
        MAXIMUM_BALANCE = builder.defineInRange(
                "maximum_balance_minor_units",
                9_000_000_000_000_000L,
                1L,
                Long.MAX_VALUE
        );
        AUDIT_METADATA = builder.define("audit_metadata", true);
        HOLD_EXPIRATION_CHECK_SECONDS = builder.defineInRange(
                "hold_expiration_check_seconds",
                30,
                5,
                3600
        );
        builder.pop();

        builder.push("exchanger_progression");
        EXCHANGER_PROGRESSION_ENABLED = builder
                .comment("Enables LuckPerms progression restrictions and currency discounts in exchangers.")
                .define("enabled", true);
        EXCHANGER_TIER_ORDER = builder
                .comment("Tier order from lowest to highest.")
                .defineListAllowEmpty(
                "tier_order",
                DEFAULT_TIER_ORDER,
                CurrencyConfig::isValidTierId
        );
        EXCHANGER_TIER_PERMISSION_PREFIX = builder
                .comment("Permission checked for every tier, for example cointcoregto.progression.hv.")
                .define("tier_permission_prefix", "cointcoregto.progression.");
        EXCHANGER_MATCH_PRIMARY_GROUP = builder
                .comment("Also matches the LuckPerms primary group against tier ids and aliases.")
                .define("match_primary_group", true);
        EXCHANGER_TIER_GROUP_ALIASES = builder
                .comment("Aliases use tier=group1,group2 syntax.")
                .defineListAllowEmpty(
                "tier_group_aliases",
                List.of(),
                value -> value instanceof String
        );
        EXCHANGER_TIER_DISTANCE_DISCOUNTS = builder
                .comment("Discount basis points by distance above the required tier. 3300 means 33 percent.")
                .defineListAllowEmpty(
                "discount_basis_points_by_tier_distance",
                DEFAULT_TIER_DISTANCE_DISCOUNTS,
                value -> value instanceof Integer integer && integer >= 0 && integer <= 10000
        );
        EXCHANGER_PRODUCT_TIER_RULES = builder
                .comment("Product rules use item_or_tag=tier. Supports exact ids, trailing wildcard and #item tags.")
                .defineListAllowEmpty(
                "product_tier_rules",
                List.of(),
                CurrencyConfig::isValidProductTierRule
        );
        EXCHANGER_OWNERS_CAN_SET_REQUIRED_TIER = builder
                .comment("Allows exchanger owners to add a manual minimum tier. Automatic rules cannot be lowered.")
                .define("owners_can_set_required_tier", false);
        EXCHANGER_OPERATORS_HAVE_MAX_TIER = builder
                .comment("Treats permission level 2 operators as the highest configured tier.")
                .define("operators_have_max_tier", true);
        builder.pop();
        SPEC = builder.build();
    }

    private CurrencyConfig() {
    }

    public static void reload() {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        CommentedFileConfig configData = CommentedFileConfig.builder(configPath)
                .sync()
                .autosave()
                .writingMode(WritingMode.REPLACE)
                .build();
        configData.load();
        SPEC.setConfig(configData);
    }

    public static boolean enabled() {
        return getBoolean(ENABLED, true);
    }

    public static String providerId() {
        return getString(PROVIDER, "cointcoregto:mysql");
    }

    public static CurrencyDescriptor descriptor() {
        return new CurrencyDescriptor(
                getString(CURRENCY_ID, "activity_coin"),
                getString(DISPLAY_NAME, "Монеты активности"),
                getString(SYMBOL, "мон."),
                getInt(FRACTION_DIGITS, 0),
                getLong(MAXIMUM_BALANCE, 9_000_000_000_000_000L)
        );
    }

    public static boolean auditMetadata() {
        return getBoolean(AUDIT_METADATA, true);
    }

    public static int holdExpirationCheckSeconds() {
        return getInt(HOLD_EXPIRATION_CHECK_SECONDS, 30);
    }

    public static boolean exchangerProgressionEnabled() {
        return getBoolean(EXCHANGER_PROGRESSION_ENABLED, true);
    }

    public static List<String> exchangerTierOrder() {
        List<? extends String> configured;
        try {
            configured = EXCHANGER_TIER_ORDER.get();
        } catch (Throwable ignored) {
            configured = DEFAULT_TIER_ORDER;
        }

        if (configured == null) {
            configured = DEFAULT_TIER_ORDER;
        }
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        for (String value : configured) {
            String tier = normalizeTierId(value);
            if (!tier.isEmpty()) {
                normalized.putIfAbsent(tier, tier);
            }
        }
        if (normalized.isEmpty()) {
            for (String value : DEFAULT_TIER_ORDER) {
                normalized.put(value, value);
            }
        }
        return List.copyOf(normalized.values());
    }

    public static String exchangerTierPermissionPrefix() {
        try {
            String value = EXCHANGER_TIER_PERMISSION_PREFIX.get();
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "cointcoregto.progression.";
        }
    }

    public static boolean exchangerMatchPrimaryGroup() {
        return getBoolean(EXCHANGER_MATCH_PRIMARY_GROUP, true);
    }

    public static List<String> exchangerProductTierRules() {
        List<? extends String> configured;
        try {
            configured = EXCHANGER_PRODUCT_TIER_RULES.get();
        } catch (Throwable ignored) {
            configured = List.of();
        }
        if (configured == null) {
            configured = List.of();
        }
        List<String> result = new ArrayList<>();
        for (String value : configured) {
            if (value != null && isValidProductTierRule(value)) {
                result.add(value.trim());
            }
        }
        return List.copyOf(result);
    }

    public static boolean exchangerOwnersCanSetRequiredTier() {
        return getBoolean(EXCHANGER_OWNERS_CAN_SET_REQUIRED_TIER, false);
    }

    public static boolean exchangerOperatorsHaveMaxTier() {
        return getBoolean(EXCHANGER_OPERATORS_HAVE_MAX_TIER, true);
    }

    public static Map<String, List<String>> exchangerTierGroupAliases() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        List<? extends String> configured;
        try {
            configured = EXCHANGER_TIER_GROUP_ALIASES.get();
        } catch (Throwable ignored) {
            configured = List.of();
        }

        if (configured == null) {
            configured = List.of();
        }
        for (String raw : configured) {
            if (raw == null) {
                continue;
            }
            int separator = raw.indexOf('=');
            if (separator <= 0 || separator >= raw.length() - 1) {
                continue;
            }
            String tier = normalizeTierId(raw.substring(0, separator));
            if (tier.isEmpty()) {
                continue;
            }
            List<String> aliases = new ArrayList<>();
            for (String aliasRaw : raw.substring(separator + 1).split(",")) {
                String alias = normalizeTierId(aliasRaw);
                if (!alias.isEmpty() && !aliases.contains(alias)) {
                    aliases.add(alias);
                }
            }
            if (!aliases.isEmpty()) {
                result.put(tier, List.copyOf(aliases));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public static List<Integer> exchangerTierDistanceDiscounts() {
        List<? extends Integer> configured;
        try {
            configured = EXCHANGER_TIER_DISTANCE_DISCOUNTS.get();
        } catch (Throwable ignored) {
            configured = DEFAULT_TIER_DISTANCE_DISCOUNTS;
        }
        if (configured == null) {
            configured = DEFAULT_TIER_DISTANCE_DISCOUNTS;
        }
        List<Integer> result = new ArrayList<>();
        for (Integer value : configured) {
            if (value != null) {
                result.add(Math.max(0, Math.min(10000, value)));
            }
        }
        if (result.isEmpty()) {
            result.addAll(DEFAULT_TIER_DISTANCE_DISCOUNTS);
        }
        return List.copyOf(result);
    }

    public static int exchangerDiscountBasisPoints(int tierDistance) {
        if (tierDistance <= 0) {
            return 0;
        }
        List<Integer> discounts = exchangerTierDistanceDiscounts();
        int index = Math.min(tierDistance, discounts.size() - 1);
        return discounts.get(index);
    }

    public static int exchangerTierIndex(String tierId) {
        String normalized = normalizeTierId(tierId);
        if (normalized.isEmpty()) {
            return -1;
        }
        return exchangerTierOrder().indexOf(normalized);
    }

    public static String normalizeTierId(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static boolean isValidTierId(Object value) {
        return value instanceof String string && !normalizeTierId(string).isEmpty();
    }

    private static boolean isValidProductTierRule(Object value) {
        if (!(value instanceof String string)) {
            return false;
        }
        int separator = string.lastIndexOf('=');
        if (separator <= 0 || separator >= string.length() - 1) {
            return false;
        }
        return !string.substring(0, separator).trim().isEmpty()
                && !normalizeTierId(string.substring(separator + 1)).isEmpty();
    }

    private static boolean getBoolean(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int getInt(ForgeConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static long getLong(ForgeConfigSpec.LongValue value, long fallback) {
        try {
            return value.get();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String getString(ForgeConfigSpec.ConfigValue<String> value, String fallback) {
        try {
            String current = value.get();
            return current == null || current.isBlank() ? fallback : current.trim();
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
