package Crazer.cubeofinterest.cointcoregto.trade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

final class TradeItemCodec {
    private TradeItemCodec() {
    }

    static String encode(List<ItemStack> stacks) {
        CompoundTag root = new CompoundTag();
        ListTag items = new ListTag();
        if (stacks != null) {
            for (int slot = 0; slot < stacks.size(); slot++) {
                ItemStack stack = stacks.get(slot);
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                CompoundTag entry = new CompoundTag();
                entry.putInt("Slot", slot);
                entry.put("Stack", stack.save(new CompoundTag()));
                items.add(entry);
            }
        }
        root.put("Items", items);
        return root.toString();
    }

    static List<ItemStack> decode(String encoded, int slots) {
        ArrayList<ItemStack> result = new ArrayList<>(slots);
        for (int index = 0; index < slots; index++) {
            result.add(ItemStack.EMPTY);
        }
        if (encoded == null || encoded.isBlank()) {
            return result;
        }
        try {
            CompoundTag root = TagParser.parseTag(encoded);
            ListTag items = root.getList("Items", Tag.TAG_COMPOUND);
            for (int index = 0; index < items.size(); index++) {
                CompoundTag entry = items.getCompound(index);
                int slot = entry.getInt("Slot");
                if (slot < 0 || slot >= slots || !entry.contains("Stack", Tag.TAG_COMPOUND)) {
                    continue;
                }
                result.set(slot, ItemStack.of(entry.getCompound("Stack")));
            }
        } catch (Exception ignored) {
        }
        return result;
    }
}
