package Crazer.cubeofinterest.cointcoregto.exchanger;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import Crazer.cubeofinterest.cointcoregto.currency.CurrencyConfig;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ExchangerProgression {
    private static final BigInteger TEN_THOUSAND = BigInteger.valueOf(10000L);

    private ExchangerProgression() {
    }

    public static Quote quote(
            ServerPlayer player,
            String requiredTierId,
            long basePricePerDeal,
            int deals
    ) {
        long safeBasePrice = Math.max(0L, basePricePerDeal);
        int safeDeals = Math.max(1, deals);
        long baseTotal = Math.multiplyExact(safeBasePrice, safeDeals);
        String requiredTier = CurrencyConfig.normalizeTierId(requiredTierId);

        if (!CurrencyConfig.exchangerProgressionEnabled() || requiredTier.isEmpty()) {
            return new Quote(
                    Status.UNRESTRICTED,
                    requiredTier,
                    -1,
                    playerTier(player),
                    0,
                    baseTotal,
                    baseTotal
            );
        }

        int requiredIndex = CurrencyConfig.exchangerTierIndex(requiredTier);
        int playerIndex = playerTier(player);
        if (requiredIndex < 0) {
            return new Quote(
                    Status.INVALID_REQUIRED_TIER,
                    requiredTier,
                    -1,
                    playerIndex,
                    0,
                    baseTotal,
                    baseTotal
            );
        }
        if (playerIndex < requiredIndex) {
            return new Quote(
                    Status.BELOW_REQUIRED,
                    requiredTier,
                    requiredIndex,
                    playerIndex,
                    0,
                    baseTotal,
                    baseTotal
            );
        }

        int discount = CurrencyConfig.exchangerDiscountBasisPoints(playerIndex - requiredIndex);
        long effectiveTotal = applyDiscount(baseTotal, discount);
        return new Quote(
                Status.ELIGIBLE,
                requiredTier,
                requiredIndex,
                playerIndex,
                discount,
                baseTotal,
                effectiveTotal
        );
    }

    public static String automaticRequiredTier(ItemStack product) {
        if (product == null || product.isEmpty()) {
            return "";
        }
        String selectedTier = "";
        int selectedIndex = -1;
        for (String rawRule : CurrencyConfig.exchangerProductTierRules()) {
            int separator = rawRule.lastIndexOf('=');
            if (separator <= 0 || separator >= rawRule.length() - 1) {
                continue;
            }
            String selector = rawRule.substring(0, separator).trim();
            String tier = CurrencyConfig.normalizeTierId(rawRule.substring(separator + 1));
            if (!matchesProduct(product, selector)) {
                continue;
            }
            int tierIndex = CurrencyConfig.exchangerTierIndex(tier);
            if (tierIndex < 0) {
                return tier;
            }
            if (tierIndex > selectedIndex) {
                selectedIndex = tierIndex;
                selectedTier = tier;
            }
        }
        return selectedTier;
    }

    public static String effectiveRequiredTier(ItemStack product, String manualTierId) {
        String automatic = automaticRequiredTier(product);
        String manual = CurrencyConfig.normalizeTierId(manualTierId);
        if (!automatic.isEmpty() && CurrencyConfig.exchangerTierIndex(automatic) < 0) {
            return automatic;
        }
        if (!manual.isEmpty() && CurrencyConfig.exchangerTierIndex(manual) < 0) {
            return manual;
        }
        int automaticIndex = CurrencyConfig.exchangerTierIndex(automatic);
        int manualIndex = CurrencyConfig.exchangerTierIndex(manual);
        return automaticIndex >= manualIndex ? automatic : manual;
    }

    private static boolean matchesProduct(ItemStack product, String selector) {
        if (selector == null || selector.isBlank()) {
            return false;
        }
        String normalized = selector.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("#")) {
            try {
                ResourceLocation tagId = new ResourceLocation(normalized.substring(1));
                return product.is(TagKey.create(Registries.ITEM, tagId));
            } catch (RuntimeException ignored) {
                return false;
            }
        }

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(product.getItem());
        if (itemId == null) {
            return false;
        }
        String actual = itemId.toString();
        if (normalized.endsWith("*")) {
            return actual.startsWith(normalized.substring(0, normalized.length() - 1));
        }
        return actual.equals(normalized);
    }

    public static int playerTier(ServerPlayer player) {
        if (player == null) {
            return -1;
        }
        List<String> tiers = CurrencyConfig.exchangerTierOrder();
        if (tiers.isEmpty()) {
            return -1;
        }
        if (CurrencyConfig.exchangerOperatorsHaveMaxTier() && player.hasPermissions(2)) {
            return tiers.size() - 1;
        }

        int highest = -1;
        String permissionPrefix = CurrencyConfig.exchangerTierPermissionPrefix();
        if (!permissionPrefix.isEmpty()) {
            for (int index = 0; index < tiers.size(); index++) {
                if (CointCoreGTO.hasPermissionNode(player, permissionPrefix + tiers.get(index))) {
                    highest = index;
                }
            }
        }

        if (CurrencyConfig.exchangerMatchPrimaryGroup()) {
            String primaryGroup = primaryGroup(player);
            if (!primaryGroup.isEmpty()) {
                Map<String, List<String>> aliases = CurrencyConfig.exchangerTierGroupAliases();
                for (int index = 0; index < tiers.size(); index++) {
                    String tier = tiers.get(index);
                    if (tier.equals(primaryGroup)
                            || aliases.getOrDefault(tier, List.of()).contains(primaryGroup)) {
                        highest = Math.max(highest, index);
                    }
                }
            }
        }
        return highest;
    }

    public static long applyDiscount(long baseAmount, int basisPoints) {
        long safeAmount = Math.max(0L, baseAmount);
        int safeBasisPoints = Math.max(0, Math.min(10000, basisPoints));
        if (safeAmount == 0L || safeBasisPoints == 0) {
            return safeAmount;
        }
        if (safeBasisPoints >= 10000) {
            return 0L;
        }
        BigInteger numerator = BigInteger.valueOf(safeAmount)
                .multiply(BigInteger.valueOf(10000L - safeBasisPoints))
                .add(TEN_THOUSAND.subtract(BigInteger.ONE));
        return numerator.divide(TEN_THOUSAND).longValueExact();
    }

    public static String tierDisplayName(List<String> tiers, int index) {
        if (index < 0 || index >= tiers.size()) {
            return "нет";
        }
        return tiers.get(index).toUpperCase(Locale.ROOT);
    }

    private static String primaryGroup(ServerPlayer player) {
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            User user = luckPerms.getPlayerAdapter(ServerPlayer.class).getUser(player);
            return CurrencyConfig.normalizeTierId(user.getPrimaryGroup());
        } catch (Throwable ignored) {
            return "";
        }
    }

    public enum Status {
        UNRESTRICTED,
        ELIGIBLE,
        BELOW_REQUIRED,
        INVALID_REQUIRED_TIER
    }

    public record Quote(
            Status status,
            String requiredTierId,
            int requiredTierIndex,
            int playerTierIndex,
            int discountBasisPoints,
            long baseTotal,
            long effectiveTotal
    ) {
        public boolean allowed() {
            return status == Status.UNRESTRICTED || status == Status.ELIGIBLE;
        }

        public long discountAmount() {
            return Math.max(0L, baseTotal - effectiveTotal);
        }
    }
}
