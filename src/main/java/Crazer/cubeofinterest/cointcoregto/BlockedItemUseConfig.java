package Crazer.cubeofinterest.cointcoregto;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.List;

@Mod.EventBusSubscriber(modid = CointCoreGTO.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class BlockedItemUseConfig {
    public static final String FILE_NAME = "CointCoreGTO-BlockedItems.toml";

    static final List<String> DEFAULT_ITEMS = List.of(
            // Botania Bore Lens: directly breaks blocks with mana bursts.
            "botania:lens_mine",
            // Entropic Lens: creates block-damaging explosions.
            "botania:lens_explosive",
            // Force Lens: can move blocks and is also unsafe around protected claims.
            "botania:lens_piston"
    );

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue LOG_BLOCKED_ATTEMPTS;
    public static final ForgeConfigSpec.BooleanValue NOTIFY_PLAYER;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BLOCKED_ITEMS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        ENABLED = builder
                .comment("Master switch for server-side blocked item use.")
                .define("enabled", true);
        LOG_BLOCKED_ATTEMPTS = builder
                .comment("Write blocked use attempts to the server log.")
                .define("log_blocked_attempts", true);
        NOTIFY_PLAYER = builder
                .comment("Show an actionbar message when a player tries to use a blocked item.")
                .define("notify_player", true);
        BLOCKED_ITEMS = builder
                .comment(
                        "Items that cannot be used by players.",
                        "Supported rules:",
                        "  exact item id: botania:lens_mine",
                        "  prefix wildcard: botania:lens_*",
                        "  whole namespace: botania:*",
                        "  item tag: #forge:some_tag",
                        "  everything: *"
                )
                .defineListAllowEmpty(
                        "blocked_items",
                        DEFAULT_ITEMS,
                        BlockedItemUseConfig::isValidRule
                );

        SPEC = builder.build();
    }

    private BlockedItemUseConfig() {
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
        BlockedItemUseGuard.reloadFromConfig();
    }

    @SubscribeEvent
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            BlockedItemUseGuard.reloadFromConfig();
        }
    }

    @SubscribeEvent
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            BlockedItemUseGuard.reloadFromConfig();
        }
    }

    private static boolean isValidRule(Object value) {
        if (!(value instanceof String string)) {
            return false;
        }

        String rule = string.trim();

        if (rule.isEmpty() || rule.equals("#") || rule.indexOf('*') != rule.lastIndexOf('*')) {
            return false;
        }

        if (rule.startsWith("#")) {
            return parseResourceLocation(rule.substring(1)) != null;
        }

        if (rule.equals("*")) {
            return true;
        }

        int wildcard = rule.indexOf('*');

        if (wildcard >= 0 && wildcard != rule.length() - 1) {
            return false;
        }

        if (wildcard < 0) {
            return parseResourceLocation(rule) != null;
        }

        String candidate = rule.substring(0, rule.length() - 1);
        int separator = candidate.indexOf(':');

        if (separator <= 0) {
            return false;
        }

        String namespace = candidate.substring(0, separator);
        String path = candidate.substring(separator + 1);
        String validationPath = path.isEmpty() ? "item" : path + "item";

        return parseResourceLocation(namespace + ":" + validationPath) != null;
    }

    private static ResourceLocation parseResourceLocation(String value) {
        try {
            return new ResourceLocation(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
