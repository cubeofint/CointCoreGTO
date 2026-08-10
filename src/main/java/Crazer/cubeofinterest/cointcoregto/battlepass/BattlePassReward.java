package Crazer.cubeofinterest.cointcoregto.battlepass;

import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BattlePassReward {
    private final List<ItemStack> freeRewards;
    private final List<ItemStack> premiumRewards;

    public BattlePassReward(List<ItemStack> freeRewards, List<ItemStack> premiumRewards) {
        this.freeRewards = copyStacks(freeRewards);
        this.premiumRewards = copyStacks(premiumRewards);
    }

    public List<ItemStack> freeRewards() {
        return copyStacks(this.freeRewards);
    }

    public List<ItemStack> premiumRewards() {
        return copyStacks(this.premiumRewards);
    }

    private static List<ItemStack> copyStacks(List<ItemStack> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<ItemStack> copy = new ArrayList<>(source.size());
        for (ItemStack stack : source) {
            if (stack != null && !stack.isEmpty()) {
                copy.add(stack.copy());
            }
        }
        return Collections.unmodifiableList(copy);
    }
}
