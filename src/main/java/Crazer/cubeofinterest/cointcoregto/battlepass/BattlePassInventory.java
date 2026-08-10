package Crazer.cubeofinterest.cointcoregto.battlepass;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class BattlePassInventory {
    private BattlePassInventory() {
    }

    public static boolean canFitAll(Inventory inventory, List<ItemStack> rewards) {
        List<ItemStack> simulated = new ArrayList<>(inventory.items.size());
        for (ItemStack existing : inventory.items) {
            simulated.add(existing.copy());
        }

        for (ItemStack reward : rewards) {
            ItemStack remaining = reward.copy();
            mergeIntoExisting(simulated, remaining);
            placeIntoEmpty(simulated, remaining);
            if (!remaining.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static void giveAll(Inventory inventory, List<ItemStack> rewards) {
        for (ItemStack reward : rewards) {
            ItemStack remaining = reward.copy();
            inventory.add(remaining);
        }
        inventory.setChanged();
    }

    private static void mergeIntoExisting(List<ItemStack> slots, ItemStack remaining) {
        if (remaining.isEmpty()) {
            return;
        }
        for (ItemStack slot : slots) {
            if (slot.isEmpty() || !ItemStack.isSameItemSameTags(slot, remaining)) {
                continue;
            }
            int limit = Math.min(slot.getMaxStackSize(), 64);
            int space = limit - slot.getCount();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, remaining.getCount());
            slot.grow(moved);
            remaining.shrink(moved);
            if (remaining.isEmpty()) {
                return;
            }
        }
    }

    private static void placeIntoEmpty(List<ItemStack> slots, ItemStack remaining) {
        if (remaining.isEmpty()) {
            return;
        }
        for (int index = 0; index < slots.size(); index++) {
            if (!slots.get(index).isEmpty()) {
                continue;
            }
            int moved = Math.min(remaining.getCount(), remaining.getMaxStackSize());
            ItemStack placed = remaining.copy();
            placed.setCount(moved);
            slots.set(index, placed);
            remaining.shrink(moved);
            if (remaining.isEmpty()) {
                return;
            }
        }
    }
}
