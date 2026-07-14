package Crazer.cubeofinterest.cointcoregto;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class DimensionQuestLockConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> LOCKS;

    public static final ForgeConfigSpec.IntValue CHECK_INTERVAL_TICKS;
    public static final ForgeConfigSpec.IntValue BYPASS_PERMISSION_LEVEL;

    public static final ForgeConfigSpec.ConfigValue<String> DENY_MESSAGE;
    public static final ForgeConfigSpec.ConfigValue<String> RETURN_MESSAGE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment(
                "CointCoreGTO integration with FTB Quests.",
                "Locks dimensions behind completed FTB quests."
        );

        ENABLED = builder
                .comment("Enable dimension locking.")
                .define("enabled", true);

        CHECK_INTERVAL_TICKS = builder
                .comment(
                        "How often players already inside dimensions are checked.",
                        "20 ticks = approximately 1 second."
                )
                .defineInRange(
                        "checkIntervalTicks",
                        20,
                        1,
                        1200
                );

        BYPASS_PERMISSION_LEVEL = builder
                .comment(
                        "Vanilla permission level that bypasses dimension locks.",
                        "0 = everyone bypasses",
                        "2 = command-block/operator level",
                        "3 = server administrator",
                        "4 = server owner"
                )
                .defineInRange(
                        "bypassPermissionLevel",
                        3,
                        0,
                        4
                );

        DENY_MESSAGE = builder
                .comment(
                        "Message shown when dimension travel is blocked.",
                        "Use & for Minecraft colour codes."
                )
                .define(
                        "denyMessage",
                        "&cДля посещения этого измерения сначала завершите необходимый квест."
                );

        RETURN_MESSAGE = builder
                .comment(
                        "Message shown when a player is returned to the Overworld.",
                        "Use & for Minecraft colour codes."
                )
                .define(
                        "returnMessage",
                        "&cВы возвращены в обычный мир: необходимый квест не завершён."
                );

        LOCKS = builder
                .comment(
                        "Dimension locks.",
                        "Format: \"dimension_id=ftb_quest_id\"",
                        "",
                        "Examples:",
                        "\"ad_astra:moon=4A91C6728F318D20\"",
                        "\"ad_astra:mars=57C0D12D69834AF1\"",
                        "",
                        "Quest IDs may be written with or without 0x."
                )
                .defineListAllowEmpty(
                        "locks",
                        List.of(
                                "minecraft:the_end=0000000000000001"
                        ),
                        DimensionQuestLockConfig::isValidLock
                );

        SPEC = builder.build();
    }

    private DimensionQuestLockConfig() {
    }

    private static boolean isValidLock(Object value) {
        if (!(value instanceof String string)) {
            return false;
        }

        int separator = string.indexOf('=');

        if (separator <= 0 || separator >= string.length() - 1) {
            return false;
        }

        String dimension = string
                .substring(0, separator)
                .trim();

        String questId = string
                .substring(separator + 1)
                .trim();

        if (!dimension.contains(":")) {
            return false;
        }

        if (
                questId.startsWith("0x")
                        || questId.startsWith("0X")
        ) {
            questId = questId.substring(2);
        }

        if (questId.isEmpty() || questId.length() > 16) {
            return false;
        }

        for (int index = 0; index < questId.length(); index++) {
            char character = questId.charAt(index);

            boolean hexadecimal =
                    character >= '0' && character <= '9'
                            || character >= 'a' && character <= 'f'
                            || character >= 'A' && character <= 'F';

            if (!hexadecimal) {
                return false;
            }
        }

        return true;
    }
}