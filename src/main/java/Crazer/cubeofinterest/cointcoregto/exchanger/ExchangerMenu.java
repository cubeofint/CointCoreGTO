package Crazer.cubeofinterest.cointcoregto.exchanger;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.NotNull;

public class ExchangerMenu extends AbstractContainerMenu {
    private static final int TEMPLATE_SLOT_COUNT = 2;
    private static final int PLAYER_INVENTORY_START = TEMPLATE_SLOT_COUNT;
    private static final int PLAYER_INVENTORY_END = PLAYER_INVENTORY_START + 27;
    private static final int HOTBAR_START = PLAYER_INVENTORY_END;
    private static final int HOTBAR_END = HOTBAR_START + 9;

    private final ExchangerBlockEntity exchanger;
    private final BlockPos blockPos;
    private final boolean canEdit;
    private boolean editMode;
    private final SimpleContainerData syncedData = new SimpleContainerData(2);

    public ExchangerMenu(int windowId, Inventory playerInventory, BlockPos pos, boolean canEdit) {
        this(windowId, playerInventory, getBlockEntity(playerInventory, pos), canEdit);
    }

    public ExchangerMenu(
            int windowId,
            Inventory playerInventory,
            ExchangerBlockEntity exchanger,
            boolean canEdit
    ) {
        super(CointExchangerRegistry.EXCHANGER_MENU.get(), windowId);
        this.exchanger = exchanger;
        this.blockPos = exchanger.getBlockPos();
        this.canEdit = canEdit;
        this.editMode = canEdit;
        addDataSlots(this.syncedData);

        addSlot(new TemplateSlot(
                exchanger.getItems(),
                ExchangerBlockEntity.SLOT_PRODUCT,
                48,
                44
        ));
        addSlot(new TemplateSlot(
                exchanger.getItems(),
                ExchangerBlockEntity.SLOT_PRICE,
                136,
                44
        ));

        addPlayerInventory(playerInventory);
        addPlayerHotbar(playerInventory);
    }

    private static ExchangerBlockEntity getBlockEntity(Inventory inventory, BlockPos pos) {
        BlockEntity blockEntity = inventory.player.level().getBlockEntity(pos);
        if (!(blockEntity instanceof ExchangerBlockEntity exchanger)) {
            throw new IllegalStateException("Expected ExchangerBlockEntity at " + pos);
        }
        return exchanger;
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    public boolean canEdit() {
        return canEdit;
    }

    public boolean isEditMode() {
        return canEdit && editMode;
    }

    public boolean isBuyerMode() {
        return !isEditMode();
    }

    public void setEditMode(boolean editMode) {
        this.editMode = canEdit && editMode;
    }

    public ExchangerBlockEntity getExchanger() {
        return exchanger;
    }

    public long getAvailableProductCount() {
        long low = Integer.toUnsignedLong(this.syncedData.get(0));
        long high = Integer.toUnsignedLong(this.syncedData.get(1));
        return (high << 32) | low;
    }

    @Override
    public void broadcastChanges() {
        if (this.exchanger.getLevel() != null && !this.exchanger.getLevel().isClientSide) {
            long available = this.exchanger.getAvailableProductCount();
            this.syncedData.set(0, (int) available);
            this.syncedData.set(1, (int) (available >>> 32));
        }
        super.broadcastChanges();
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockEntity(blockPos) == exchanger
                && player.distanceToSqr(
                blockPos.getX() + 0.5D,
                blockPos.getY() + 0.5D,
                blockPos.getZ() + 0.5D
        ) <= 64.0D;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (isTemplateSlot(slotId)) {
            if (!isEditMode()) {
                return;
            }
            handleTemplateClick(slotId, button, clickType, player);
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    private void handleTemplateClick(int slotId, int button, ClickType clickType, Player player) {
        if (clickType == ClickType.PICKUP) {
            ItemStack carried = getCarried();
            if (carried.isEmpty()) {
                setTemplate(slotId, ItemStack.EMPTY);
                return;
            }

            ItemStack template = carried.copy();
            if (button == 1) {
                template.setCount(1);
            }
            setTemplate(slotId, template);
            return;
        }

        if (clickType == ClickType.SWAP && button >= 0 && button < 9) {
            setTemplate(slotId, player.getInventory().getItem(button));
            return;
        }

        if (clickType == ClickType.THROW) {
            setTemplate(slotId, ItemStack.EMPTY);
        }
    }

    private void setTemplate(int slot, ItemStack stack) {
        exchanger.getItems().setStackInSlot(slot, sanitizeTemplate(stack));
    }

    private static ItemStack sanitizeTemplate(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack result = stack.copy();
        int maximum = Math.max(1, Math.min(64, result.getMaxStackSize()));
        result.setCount(Math.max(1, Math.min(result.getCount(), maximum)));
        return result;
    }

    private static boolean isTemplateSlot(int slotId) {
        return slotId >= 0 && slotId < TEMPLATE_SLOT_COUNT;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        if (slot == slots.get(ExchangerBlockEntity.SLOT_PRODUCT)
                || slot == slots.get(ExchangerBlockEntity.SLOT_PRICE)) {
            return false;
        }
        return super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    public @NotNull ItemStack quickMoveStack(Player player, int index) {
        if (index < PLAYER_INVENTORY_START || index >= HOTBAR_END || index >= slots.size()) {
            return ItemStack.EMPTY;
        }

        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack sourceStack = slot.getItem();
        ItemStack result = sourceStack.copy();
        boolean moved;

        if (index < PLAYER_INVENTORY_END) {
            moved = moveItemStackTo(sourceStack, HOTBAR_START, HOTBAR_END, false);
        } else {
            moved = moveItemStackTo(sourceStack, PLAYER_INVENTORY_START, PLAYER_INVENTORY_END, false);
        }

        if (!moved) {
            return ItemStack.EMPTY;
        }

        if (sourceStack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        if (sourceStack.getCount() == result.getCount()) {
            return ItemStack.EMPTY;
        }

        slot.onTake(player, sourceStack);
        return result;
    }

    private void addPlayerInventory(Inventory playerInventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(
                        playerInventory,
                        column + row * 9 + 9,
                        19 + column * 18,
                        131 + row * 18
                ));
            }
        }
    }

    private void addPlayerHotbar(Inventory playerInventory) {
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(
                    playerInventory,
                    column,
                    19 + column * 18,
                    187
            ));
        }
    }

    private static final class TemplateSlot extends SlotItemHandler {
        private TemplateSlot(IItemHandler itemHandler, int index, int xPosition, int yPosition) {
            super(itemHandler, index, xPosition, yPosition);
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