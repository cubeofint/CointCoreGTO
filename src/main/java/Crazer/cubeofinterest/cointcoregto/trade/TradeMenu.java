package Crazer.cubeofinterest.cointcoregto.trade;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public final class TradeMenu extends AbstractContainerMenu {
    private static final int OFFER_TOTAL = TradeService.OFFER_SLOTS * 2;
    private static final int PLAYER_START = OFFER_TOTAL;
    private static final int PLAYER_END = PLAYER_START + 27;
    private static final int HOTBAR_START = PLAYER_END;
    private static final int HOTBAR_END = HOTBAR_START + 9;

    private final UUID tradeId;
    private final TradeSide localSide;
    private final Player viewer;
    private final SimpleContainer localOffer = new SimpleContainer(TradeService.OFFER_SLOTS);
    private final SimpleContainer remoteOffer = new SimpleContainer(TradeService.OFFER_SLOTS);
    private final SimpleContainerData data = new SimpleContainerData(7);

    public TradeMenu(int windowId, Inventory inventory, UUID tradeId, TradeSide localSide) {
        super(TradeRegistry.TRADE_MENU.get(), windowId);
        this.tradeId = tradeId;
        this.localSide = localSide;
        this.viewer = inventory.player;
        addDataSlots(data);

        for (int slot = 0; slot < TradeService.OFFER_SLOTS; slot++) {
            int column = slot % 3;
            int row = slot / 3;
            addSlot(new GhostSlot(localOffer, slot, 28 + column * 18, 45 + row * 18));
        }
        for (int slot = 0; slot < TradeService.OFFER_SLOTS; slot++) {
            int column = slot % 3;
            int row = slot / 3;
            addSlot(new GhostSlot(remoteOffer, slot, 158 + column * 18, 45 + row * 18));
        }

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 41 + column * 18, 132 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 41 + column * 18, 190));
        }
    }

    public UUID tradeId() {
        return tradeId;
    }

    public TradeSide localSide() {
        return localSide;
    }

    public long localCurrency() {
        return combine(data.get(0), data.get(1));
    }

    public long remoteCurrency() {
        return combine(data.get(2), data.get(3));
    }

    public boolean localReady() {
        return data.get(4) != 0;
    }

    public boolean remoteReady() {
        return data.get(5) != 0;
    }

    public TradeStatus status() {
        int ordinal = data.get(6);
        TradeStatus[] values = TradeStatus.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : TradeStatus.CANCELLED;
    }

    @Override
    public void broadcastChanges() {
        if (!viewer.level().isClientSide) {
            TradeService.find(tradeId).ifPresent(trade -> {
                TradeSide actualSide = trade.sideOf(viewer.getUUID());
                if (actualSide == null) {
                    return;
                }
                setContainer(localOffer, trade.offer(actualSide));
                setContainer(remoteOffer, trade.offer(actualSide.opposite()));
                setLong(0, trade.currency(actualSide));
                setLong(2, trade.currency(actualSide.opposite()));
                data.set(4, trade.ready(actualSide) ? 1 : 0);
                data.set(5, trade.ready(actualSide.opposite()) ? 1 : 0);
                data.set(6, trade.status().ordinal());
            });
        }
        super.broadcastChanges();
    }

    @Override
    public boolean stillValid(Player player) {
        if (player.level().isClientSide) {
            return true;
        }
        return TradeService.find(tradeId)
                .map(trade -> trade.sideOf(player.getUUID()) != null && trade.status().active())
                .orElse(false);
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (slotId >= 0 && slotId < TradeService.OFFER_SLOTS) {
            ItemStack selected = ItemStack.EMPTY;
            if (clickType == ClickType.PICKUP) {
                ItemStack carried = getCarried();
                if (!carried.isEmpty()) {
                    selected = carried.copy();
                    if (button == 1) {
                        selected.setCount(1);
                    }
                }
            } else if (clickType == ClickType.SWAP && button >= 0 && button < 9) {
                selected = player.getInventory().getItem(button).copy();
            }
            selected = sanitize(selected);
            if (player.level().isClientSide) {
                localOffer.setItem(slotId, selected);
            } else {
                TradeService.OperationResult result = TradeService.setOfferItem(
                        (net.minecraft.server.level.ServerPlayer) player,
                        tradeId,
                        slotId,
                        selected
                );
                if (!result.success()) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c" + result.message()));
                }
            }
            return;
        }
        if (slotId >= TradeService.OFFER_SLOTS && slotId < OFFER_TOTAL) {
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    @Override
    public @NotNull ItemStack quickMoveStack(Player player, int index) {
        if (index < PLAYER_START || index >= HOTBAR_END || index >= slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack copy = stack.copy();
        boolean moved = index < PLAYER_END
                ? moveItemStackTo(stack, HOTBAR_START, HOTBAR_END, false)
                : moveItemStackTo(stack, PLAYER_START, PLAYER_END, false);
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return !(slot instanceof GhostSlot) && super.canTakeItemForPickAll(stack, slot);
    }

    private void setLong(int index, long value) {
        data.set(index, (int) value);
        data.set(index + 1, (int) (value >>> 32));
    }

    private static long combine(int low, int high) {
        return (Integer.toUnsignedLong(high) << 32) | Integer.toUnsignedLong(low);
    }

    private static void setContainer(SimpleContainer container, List<ItemStack> stacks) {
        for (int slot = 0; slot < TradeService.OFFER_SLOTS; slot++) {
            ItemStack stack = slot < stacks.size() ? stacks.get(slot) : ItemStack.EMPTY;
            container.setItem(slot, stack.copy());
        }
    }

    private static ItemStack sanitize(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack result = stack.copy();
        result.setCount(Math.max(1, Math.min(result.getCount(), result.getMaxStackSize())));
        return result;
    }

    private static final class GhostSlot extends Slot {
        private GhostSlot(SimpleContainer container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }
}
