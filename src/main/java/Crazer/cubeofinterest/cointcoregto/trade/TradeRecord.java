package Crazer.cubeofinterest.cointcoregto.trade;

import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TradeRecord(
        UUID tradeId,
        UUID initiatorUuid,
        String initiatorName,
        String initiatorNode,
        UUID targetUuid,
        String targetName,
        String targetNode,
        TradeStatus status,
        List<ItemStack> initiatorOffer,
        List<ItemStack> targetOffer,
        long initiatorCurrency,
        long targetCurrency,
        boolean initiatorReady,
        boolean targetReady,
        boolean initiatorPrepared,
        boolean targetPrepared,
        boolean initiatorDelivered,
        boolean targetDelivered,
        boolean initiatorReturned,
        boolean targetReturned,
        String errorText,
        Instant createdAt,
        Instant updatedAt
) {
    public TradeRecord {
        initiatorOffer = copyStacks(initiatorOffer);
        targetOffer = copyStacks(targetOffer);
        initiatorName = initiatorName == null ? "" : initiatorName;
        targetName = targetName == null ? "" : targetName;
        initiatorNode = initiatorNode == null ? "" : initiatorNode;
        targetNode = targetNode == null ? "" : targetNode;
        errorText = errorText == null ? "" : errorText;
    }

    public TradeSide sideOf(UUID playerUuid) {
        if (initiatorUuid.equals(playerUuid)) {
            return TradeSide.INITIATOR;
        }
        if (targetUuid.equals(playerUuid)) {
            return TradeSide.TARGET;
        }
        return null;
    }

    public UUID uuid(TradeSide side) {
        return side == TradeSide.INITIATOR ? initiatorUuid : targetUuid;
    }

    public String name(TradeSide side) {
        return side == TradeSide.INITIATOR ? initiatorName : targetName;
    }

    public String node(TradeSide side) {
        return side == TradeSide.INITIATOR ? initiatorNode : targetNode;
    }

    public List<ItemStack> offer(TradeSide side) {
        return copyStacks(side == TradeSide.INITIATOR ? initiatorOffer : targetOffer);
    }

    public long currency(TradeSide side) {
        return side == TradeSide.INITIATOR ? initiatorCurrency : targetCurrency;
    }

    public boolean ready(TradeSide side) {
        return side == TradeSide.INITIATOR ? initiatorReady : targetReady;
    }

    public boolean prepared(TradeSide side) {
        return side == TradeSide.INITIATOR ? initiatorPrepared : targetPrepared;
    }

    public boolean delivered(TradeSide side) {
        return side == TradeSide.INITIATOR ? initiatorDelivered : targetDelivered;
    }

    public boolean returned(TradeSide side) {
        return side == TradeSide.INITIATOR ? initiatorReturned : targetReturned;
    }

    private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<ItemStack> result = new java.util.ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            result.add(stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy());
        }
        return List.copyOf(result);
    }
}
