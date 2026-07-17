package Crazer.cubeofinterest.cointcoregto.exchanger;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.NotNull;

import java.util.function.BooleanSupplier;

public class ExchangerMenu extends AbstractContainerMenu {
    private final ExchangerBlockEntity exchanger;
    private final BlockPos blockPos;
    private final boolean canEdit;

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

        BooleanSupplier editSupplier = () -> this.canEdit;

        addSlot(new LockedSlotItemHandler(
                exchanger.getItems(),
                ExchangerBlockEntity.SLOT_PRODUCT,
                82,
                56,
                editSupplier
        ));

        addSlot(new LockedSlotItemHandler(
                exchanger.getItems(),
                ExchangerBlockEntity.SLOT_PRICE,
                176,
                56,
                editSupplier
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

    public ExchangerBlockEntity getExchanger() {
        return exchanger;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.distanceToSqr(
                blockPos.getX() + 0.5D,
                blockPos.getY() + 0.5D,
                blockPos.getZ() + 0.5D
        ) <= 64.0D;
    }

    @Override
    public @NotNull ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        Slot slot = slots.get(index);

        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack sourceStack = slot.getItem();
        result = sourceStack.copy();

        int exchangerSlots = 2;
        int inventoryEnd = exchangerSlots + 27;
        int hotbarEnd = inventoryEnd + 9;

        if (index < exchangerSlots) {
            if (!slot.mayPickup(player)) {
                return ItemStack.EMPTY;
            }

            if (!moveItemStackTo(sourceStack, exchangerSlots, hotbarEnd, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!canEdit) {
                return ItemStack.EMPTY;
            }

            if (!moveItemStackTo(sourceStack, 0, exchangerSlots, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (sourceStack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        return result;
    }

    private void addPlayerInventory(Inventory playerInventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(
                        playerInventory,
                        column + row * 9 + 9,
                        49 + column * 18,
                        156 + row * 18
                ));
            }
        }
    }

    private void addPlayerHotbar(Inventory playerInventory) {
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(
                    playerInventory,
                    column,
                    49 + column * 18,
                    214
            ));
        }
    }

    private static final class LockedSlotItemHandler extends SlotItemHandler {
        private final BooleanSupplier canEditSupplier;

        private LockedSlotItemHandler(
                net.minecraftforge.items.IItemHandler itemHandler,
                int index,
                int xPosition,
                int yPosition,
                BooleanSupplier canEditSupplier
        ) {
            super(itemHandler, index, xPosition, yPosition);
            this.canEditSupplier = canEditSupplier;
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            return canEditSupplier.getAsBoolean();
        }

        @Override
        public boolean mayPickup(Player playerIn) {
            return canEditSupplier.getAsBoolean();
        }
    }
}