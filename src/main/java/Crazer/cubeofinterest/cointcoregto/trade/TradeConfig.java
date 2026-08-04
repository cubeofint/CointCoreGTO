package Crazer.cubeofinterest.cointcoregto.trade;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class TradeConfig {
    public static final String FILE_NAME = "CointCoreGTO-Trade.toml";
    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.IntValue INVITE_TTL_SECONDS;
    private static final ForgeConfigSpec.IntValue POLL_INTERVAL_TICKS;
    private static final ForgeConfigSpec.ConfigValue<String> MODERATED_ITEM_MODE;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> MODERATED_ITEM_RULES;
    private static final ForgeConfigSpec.BooleanValue AUDIT_ENABLED;
    private static final ForgeConfigSpec.BooleanValue LOG_TO_CONSOLE;
    private static final ForgeConfigSpec.BooleanValue SEND_SUSPICIOUS_TO_DISCORD;
    private static final ForgeConfigSpec.LongValue SUSPICIOUS_CURRENCY_THRESHOLD;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("trade");
        INVITE_TTL_SECONDS = builder.defineInRange("invite_ttl_seconds", 300, 30, 3600);
        POLL_INTERVAL_TICKS = builder.defineInRange("poll_interval_ticks", 20, 5, 200);
        builder.pop();

        builder.push("moderation");
        MODERATED_ITEM_MODE = builder.define("moderated_item_mode", "ALERT");
        MODERATED_ITEM_RULES = builder.defineListAllowEmpty(
                "moderated_item_rules",
                List.of(
                        "minecraft:dirt",
                        "#minecraft:logs",
                        "#minecraft:planks",
                        "minecraft:gravel",
                        "minecraft:cobblestone",
                        "minecraft:stick"
                ),
                value -> value instanceof String
        );
        builder.pop();

        builder.push("audit");
        AUDIT_ENABLED = builder.define("enabled", true);
        LOG_TO_CONSOLE = builder.define("log_to_console", true);
        SEND_SUSPICIOUS_TO_DISCORD = builder.define("send_suspicious_to_discord_log", false);
        SUSPICIOUS_CURRENCY_THRESHOLD = builder.defineInRange(
                "suspicious_currency_threshold_minor_units",
                100000L,
                0L,
                Long.MAX_VALUE
        );
        builder.pop();
        SPEC = builder.build();
    }

    private TradeConfig() {
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

    public static int inviteTtlSeconds() {
        try {
            return INVITE_TTL_SECONDS.get();
        } catch (Throwable ignored) {
            return 300;
        }
    }

    public static int pollIntervalTicks() {
        try {
            return POLL_INTERVAL_TICKS.get();
        } catch (Throwable ignored) {
            return 20;
        }
    }

    public static ModerationMode moderationMode() {
        try {
            return ModerationMode.valueOf(MODERATED_ITEM_MODE.get().trim().toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            return ModerationMode.ALERT;
        }
    }

    public static List<String> moderatedRules() {
        try {
            return MODERATED_ITEM_RULES.get().stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .toList();
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    public static boolean auditEnabled() {
        try {
            return AUDIT_ENABLED.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean logToConsole() {
        try {
            return LOG_TO_CONSOLE.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean sendSuspiciousToDiscord() {
        try {
            return SEND_SUSPICIOUS_TO_DISCORD.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static long suspiciousCurrencyThreshold() {
        try {
            return SUSPICIOUS_CURRENCY_THRESHOLD.get();
        } catch (Throwable ignored) {
            return 100000L;
        }
    }

    public static boolean matchesModerated(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) {
            return false;
        }
        String actual = itemId.toString().toLowerCase(Locale.ROOT);
        for (String raw : moderatedRules()) {
            String rule = raw.toLowerCase(Locale.ROOT);
            if (rule.startsWith("#")) {
                try {
                    if (stack.is(TagKey.create(Registries.ITEM, new ResourceLocation(rule.substring(1))))) {
                        return true;
                    }
                } catch (RuntimeException ignored) {
                }
            } else if (rule.endsWith("*")) {
                if (actual.startsWith(rule.substring(0, rule.length() - 1))) {
                    return true;
                }
            } else if (actual.equals(rule)) {
                return true;
            }
        }
        return false;
    }

    public enum ModerationMode {
        OFF,
        ALERT,
        BLOCK
    }
}
