package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockedBlockPlacementGuard {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:BlockedBlocks");
    private static final Map<String, Long> LAST_LOG_TIMES = new ConcurrentHashMap<>();
    private static volatile Snapshot snapshot = Snapshot.fromDefaults();

    private BlockedBlockPlacementGuard() {
    }

    public static void reloadFromConfig() {
        boolean enabled = true;
        boolean logging = true;
        List<? extends String> configuredRules = BlockedBlockPlacementConfig.DEFAULT_BLOCKS;

        try {
            enabled = BlockedBlockPlacementConfig.ENABLED.get();
        } catch (Throwable ignored) {
        }

        try {
            logging = BlockedBlockPlacementConfig.LOG_BLOCKED_ATTEMPTS.get();
        } catch (Throwable ignored) {
        }

        try {
            configuredRules = BlockedBlockPlacementConfig.BLOCKED_BLOCKS.get();
        } catch (Throwable ignored) {
        }

        snapshot = Snapshot.parse(enabled, logging, configuredRules);
        LAST_LOG_TIMES.clear();
        LOGGER.info("Blocked block placement guard loaded: enabled={}, rules={}", enabled, snapshot.rules().size());
    }

    public static boolean shouldDenyPlacement(
            ServerLevel level,
            BlockPos pos,
            BlockState currentState,
            BlockState requestedState
    ) {
        Snapshot currentSnapshot = snapshot;

        if (!currentSnapshot.enabled() || requestedState == null || requestedState.isAir()) {
            return false;
        }

        if (currentState != null && currentState.getBlock() == requestedState.getBlock()) {
            return false;
        }

        if (!currentSnapshot.matches(requestedState)) {
            return false;
        }

        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(requestedState.getBlock());
        ResourceKey<Level> dimension = level.dimension();
        String key = "placement|" + blockId;

        logRateLimited(
                currentSnapshot,
                key,
                "Blocked forbidden block placement: block={}, dimension={}, pos={} {} {}",
                blockId,
                dimension.location(),
                pos.getX(),
                pos.getY(),
                pos.getZ()
        );

        return true;
    }

    public static ForbiddenBlock findForbiddenBlock(
            ServerLevel level,
            int startX,
            int startY,
            int startZ,
            int sizeX,
            int sizeY,
            int sizeZ
    ) {
        Snapshot currentSnapshot = snapshot;

        if (!currentSnapshot.enabled() || currentSnapshot.rules().isEmpty()) {
            return null;
        }

        int width = Math.max(0, sizeX);
        int height = Math.max(0, sizeY);
        int depth = Math.max(0, sizeZ);
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int xOffset = 0; xOffset < width; xOffset++) {
            for (int zOffset = 0; zOffset < depth; zOffset++) {
                for (int yOffset = 0; yOffset < height; yOffset++) {
                    mutablePos.set(startX + xOffset, startY + yOffset, startZ + zOffset);
                    BlockState state = level.getBlockState(mutablePos);

                    if (currentSnapshot.matches(state)) {
                        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                        return new ForbiddenBlock(blockId, mutablePos.immutable());
                    }
                }
            }
        }

        return null;
    }

    public static void logDeniedSpatialTransfer(
            ServerLevel level,
            ForbiddenBlock forbiddenBlock
    ) {
        Snapshot currentSnapshot = snapshot;
        String key = "spatial|" + forbiddenBlock.blockId();

        logRateLimited(
                currentSnapshot,
                key,
                "Blocked AE2 spatial transfer containing forbidden block: block={}, dimension={}, pos={} {} {}",
                forbiddenBlock.blockId(),
                level.dimension().location(),
                forbiddenBlock.pos().getX(),
                forbiddenBlock.pos().getY(),
                forbiddenBlock.pos().getZ()
        );
    }

    private static void logRateLimited(
            Snapshot currentSnapshot,
            String key,
            String message,
            Object... arguments
    ) {
        if (!currentSnapshot.logging()) {
            return;
        }

        long now = System.currentTimeMillis();
        Long previous = LAST_LOG_TIMES.put(key, now);

        if (previous != null && now - previous < 2000L) {
            return;
        }

        if (LAST_LOG_TIMES.size() > 256) {
            LAST_LOG_TIMES.clear();
            LAST_LOG_TIMES.put(key, now);
        }

        LOGGER.warn(message, arguments);
    }

    public record ForbiddenBlock(ResourceLocation blockId, BlockPos pos) {
    }

    private record Snapshot(boolean enabled, boolean logging, List<Rule> rules) {
        private static Snapshot fromDefaults() {
            return parse(true, true, BlockedBlockPlacementConfig.DEFAULT_BLOCKS);
        }

        private static Snapshot parse(
                boolean enabled,
                boolean logging,
                List<? extends String> configuredRules
        ) {
            List<Rule> parsedRules = new ArrayList<>();

            if (configuredRules != null) {
                for (String configuredRule : configuredRules) {
                    Rule parsedRule = Rule.parse(configuredRule);

                    if (parsedRule != null) {
                        parsedRules.add(parsedRule);
                    }
                }
            }

            return new Snapshot(enabled, logging, List.copyOf(parsedRules));
        }

        private boolean matches(BlockState state) {
            if (state == null || state.isAir()) {
                return false;
            }

            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());

            for (Rule rule : rules) {
                if (rule.matches(state, blockId)) {
                    return true;
                }
            }

            return false;
        }
    }

    private interface Rule {
        boolean matches(BlockState state, ResourceLocation blockId);

        static Rule parse(String configuredRule) {
            if (configuredRule == null) {
                return null;
            }

            String rule = configuredRule.trim();

            if (rule.isEmpty()) {
                return null;
            }

            if (rule.equals("*")) {
                return (state, blockId) -> true;
            }

            if (rule.startsWith("#")) {
                ResourceLocation tagId = parseResourceLocation(rule.substring(1));

                if (tagId == null) {
                    return null;
                }

                TagKey<Block> tag = TagKey.create(Registries.BLOCK, tagId);
                return (state, blockId) -> state.is(tag);
            }

            if (rule.endsWith("*")) {
                String prefix = rule.substring(0, rule.length() - 1);
                int separator = prefix.indexOf(':');

                if (separator <= 0) {
                    return null;
                }

                String namespace = prefix.substring(0, separator);
                String pathPrefix = prefix.substring(separator + 1);

                return (state, blockId) -> blockId.getNamespace().equals(namespace)
                        && blockId.getPath().startsWith(pathPrefix);
            }

            ResourceLocation exactId = parseResourceLocation(rule);

            if (exactId == null) {
                return null;
            }

            return (state, blockId) -> blockId.equals(exactId);
        }

        private static ResourceLocation parseResourceLocation(String value) {
            try {
                return new ResourceLocation(value);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }
}
