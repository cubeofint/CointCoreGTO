package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockedItemUseGuard {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:BlockedItems");
    private static final Map<String, Long> LAST_LOG_TIMES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_NOTIFY_TIMES = new ConcurrentHashMap<>();
    private static volatile Snapshot snapshot = Snapshot.fromDefaults();

    private BlockedItemUseGuard() {
    }

    public static void reloadFromConfig() {
        boolean enabled = true;
        boolean logging = true;
        boolean notifyPlayer = true;
        List<? extends String> configuredRules = BlockedItemUseConfig.DEFAULT_ITEMS;

        try {
            enabled = BlockedItemUseConfig.ENABLED.get();
        } catch (Throwable ignored) {
        }

        try {
            logging = BlockedItemUseConfig.LOG_BLOCKED_ATTEMPTS.get();
        } catch (Throwable ignored) {
        }

        try {
            notifyPlayer = BlockedItemUseConfig.NOTIFY_PLAYER.get();
        } catch (Throwable ignored) {
        }

        try {
            configuredRules = BlockedItemUseConfig.BLOCKED_ITEMS.get();
        } catch (Throwable ignored) {
        }

        snapshot = Snapshot.parse(enabled, logging, notifyPlayer, configuredRules);
        LAST_LOG_TIMES.clear();
        LAST_NOTIFY_TIMES.clear();
        LOGGER.info(
                "Blocked item use guard loaded: enabled={}, rules={}",
                enabled,
                snapshot.rules().size()
        );
    }

    /**
     * Checks a normal player interaction. This is intentionally a use-ban, not an inventory wipe:
     * the item stays in the player's inventory, but the server refuses the action.
     */
    public static boolean shouldDenyUse(ServerPlayer player, ItemStack stack, String action) {
        Snapshot currentSnapshot = snapshot;

        if (!currentSnapshot.enabled() || stack == null || stack.isEmpty()) {
            return false;
        }

        if (!currentSnapshot.matches(stack)) {
            return false;
        }

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String safeAction = action == null || action.isBlank() ? "use" : action;

        logRateLimited(
                currentSnapshot,
                "player|" + itemId,
                "Blocked forbidden item use: item={}, player={} ({}) action={}",
                itemId,
                player.getGameProfile().getName(),
                player.getUUID(),
                safeAction
        );

        notifyPlayerRateLimited(currentSnapshot, player, itemId);
        return true;
    }

    /**
     * Used by integrations where the dangerous item is already installed into a machine/tool and
     * no vanilla/Forge player-use event is fired (for example Botania lenses on mana bursts).
     */
    public static boolean shouldDenyAutomatedEffect(ResourceLocation itemId, String action) {
        Snapshot currentSnapshot = snapshot;

        if (!currentSnapshot.enabled() || itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
            return false;
        }

        Item item = BuiltInRegistries.ITEM.get(itemId);
        ItemStack stack = new ItemStack(item);

        if (!currentSnapshot.matches(stack)) {
            return false;
        }

        String safeAction = action == null || action.isBlank() ? "automated_effect" : action;

        logRateLimited(
                currentSnapshot,
                "automation|" + itemId,
                "Blocked forbidden automated item effect: item={}, action={}",
                itemId,
                safeAction
        );

        return true;
    }

    public static boolean isBlocked(ItemStack stack) {
        Snapshot currentSnapshot = snapshot;
        return currentSnapshot.enabled()
                && stack != null
                && !stack.isEmpty()
                && currentSnapshot.matches(stack);
    }

    private static void notifyPlayerRateLimited(
            Snapshot currentSnapshot,
            ServerPlayer player,
            ResourceLocation itemId
    ) {
        if (!currentSnapshot.notifyPlayer() || player == null) {
            return;
        }

        long now = System.currentTimeMillis();
        Long previous = LAST_NOTIFY_TIMES.put(player.getUUID(), now);

        if (previous != null && now - previous < 1000L) {
            return;
        }

        if (LAST_NOTIFY_TIMES.size() > 512) {
            LAST_NOTIFY_TIMES.clear();
            LAST_NOTIFY_TIMES.put(player.getUUID(), now);
        }

        player.displayClientMessage(
                Component.literal("§cЭтот предмет запрещён на сервере: §f" + itemId),
                true
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

    private record Snapshot(
            boolean enabled,
            boolean logging,
            boolean notifyPlayer,
            List<Rule> rules
    ) {
        private static Snapshot fromDefaults() {
            return parse(true, true, true, BlockedItemUseConfig.DEFAULT_ITEMS);
        }

        private static Snapshot parse(
                boolean enabled,
                boolean logging,
                boolean notifyPlayer,
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

            return new Snapshot(
                    enabled,
                    logging,
                    notifyPlayer,
                    List.copyOf(parsedRules)
            );
        }

        private boolean matches(ItemStack stack) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());

            for (Rule rule : rules) {
                if (rule.matches(stack, itemId)) {
                    return true;
                }
            }

            return false;
        }
    }

    private interface Rule {
        boolean matches(ItemStack stack, ResourceLocation itemId);

        static Rule parse(String configuredRule) {
            if (configuredRule == null) {
                return null;
            }

            String rule = configuredRule.trim();

            if (rule.isEmpty()) {
                return null;
            }

            if (rule.equals("*")) {
                return (stack, itemId) -> true;
            }

            if (rule.startsWith("#")) {
                ResourceLocation tagId = parseResourceLocation(rule.substring(1));

                if (tagId == null) {
                    return null;
                }

                TagKey<Item> tag = TagKey.create(Registries.ITEM, tagId);
                return (stack, itemId) -> stack.is(tag);
            }

            if (rule.endsWith("*")) {
                String prefix = rule.substring(0, rule.length() - 1);
                int separator = prefix.indexOf(':');

                if (separator <= 0) {
                    return null;
                }

                String namespace = prefix.substring(0, separator);
                String pathPrefix = prefix.substring(separator + 1);

                return (stack, itemId) -> itemId.getNamespace().equals(namespace)
                        && itemId.getPath().startsWith(pathPrefix);
            }

            ResourceLocation exactId = parseResourceLocation(rule);

            if (exactId == null) {
                return null;
            }

            return (stack, itemId) -> itemId.equals(exactId);
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
