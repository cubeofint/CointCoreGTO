package Crazer.cubeofinterest.cointcoregto.currency;

import net.minecraftforge.common.ForgeConfigSpec;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;

public final class CurrencyConfig {
    public static final String FILE_NAME = "CointCoreGTO-Currency.toml";
    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.ConfigValue<String> PROVIDER;
    private static final ForgeConfigSpec.ConfigValue<String> CURRENCY_ID;
    private static final ForgeConfigSpec.ConfigValue<String> DISPLAY_NAME;
    private static final ForgeConfigSpec.ConfigValue<String> SYMBOL;
    private static final ForgeConfigSpec.IntValue FRACTION_DIGITS;
    private static final ForgeConfigSpec.LongValue MAXIMUM_BALANCE;
    private static final ForgeConfigSpec.BooleanValue AUDIT_METADATA;
    private static final ForgeConfigSpec.IntValue HOLD_EXPIRATION_CHECK_SECONDS;

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
