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
public final class BlockedBlockPlacementConfig {
    public static final String FILE_NAME = "CointCoreGTO-BlockedBlocks.toml";

    static final List<String> DEFAULT_BLOCKS = List.of(
            "pipez:*",
            "botania:spawner_claw",
            "ae2:spatial_pylon",
            "ae2:spatial_anchor",
            "gtocore:area_destruction_tools",
            "gtocore:naquadria_charge"
    );

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue ENABLED;
    public static final ForgeConfigSpec.BooleanValue LOG_BLOCKED_ATTEMPTS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BLOCKED_BLOCKS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        ENABLED = builder.define("enabled", true);
        LOG_BLOCKED_ATTEMPTS = builder.define("log_blocked_attempts", true);
        BLOCKED_BLOCKS = builder.defineListAllowEmpty(
                "blocked_blocks",
                DEFAULT_BLOCKS,
                BlockedBlockPlacementConfig::isValidRule
        );

        SPEC = builder.build();
    }

    private BlockedBlockPlacementConfig() {
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
        BlockedBlockPlacementGuard.reloadFromConfig();
    }

    @SubscribeEvent
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            BlockedBlockPlacementGuard.reloadFromConfig();
        }
    }

    @SubscribeEvent
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            BlockedBlockPlacementGuard.reloadFromConfig();
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
        String validationPath = path.isEmpty() ? "block" : path + "block";

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
