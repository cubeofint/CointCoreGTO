package Crazer.cubeofinterest.cointcoregto.supply;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.SlotItemHandler;
import org.jetbrains.annotations.NotNull;

public class SupplyBufferMenu extends AbstractContainerMenu {
    public static final int FILTER_COUNT = SupplyBufferBlockEntity.REQUEST_FILTER_COUNT;

    private static final int SUPPLY_START = 0;
    private static final int SUPPLY_END = SUPPLY_START + FILTER_COUNT;
    private static final int EXPORT_START = SUPPLY_END;
    private static final int EXPORT_END = EXPORT_START + SupplyBufferBlockEntity.EXPORT_SLOT_COUNT;
    private static final int PLAYER_INVENTORY_START = EXPORT_END;
    private static final int PLAYER_INVENTORY_END = PLAYER_INVENTORY_START + 27;
    private static final int HOTBAR_START = PLAYER_INVENTORY_END;
    private static final int HOTBAR_END = HOTBAR_START + 9;

    private static final int DATA_ROLE = 0;
    private static final int DATA_ITEM_BELOW = 1;
    private static final int DATA_ITEM_TARGET = 2;
    private static final int DATA_FLUID_BELOW = 3;
    private static final int DATA_FLUID_TARGET = 4;
    private static final int DATA_LINK_ONLINE = 5;
    private static final int DATA_PENDING = 6;
    private static final int DATA_CLUSTER_ENABLED = 7;
    private static final int DATA_ITEM_COUNTS = 8;
    private static final int DATA_ITEM_CAPACITIES = DATA_ITEM_COUNTS + FILTER_COUNT;
    private static final int DATA_FLUID_AMOUNTS = DATA_ITEM_CAPACITIES + FILTER_COUNT;
    private static final int DATA_FLUID_CAPACITIES = DATA_FLUID_AMOUNTS + FILTER_COUNT;
    private static final int DATA_COUNT = DATA_FLUID_CAPACITIES + FILTER_COUNT;

    // These coordinates are shared with SupplyBufferScreen.
    public static final int FILTER_ROW_X = 35;
    public static final int SUPPLY_ROW_Y = 133;
    public static final int EXPORT_ROW_Y = 172;
    public static final int PLAYER_INVENTORY_Y = 240;
    public static final int HOTBAR_Y = 298;

    private final SupplyBufferBlockEntity supplyBuffer;
    private final BlockPos blockPos;
    private final boolean canEdit;
    private final String initialLinkId;
    private final String initialProviderNode;
    private final SimpleContainerData syncedData = new SimpleContainerData(DATA_COUNT);

    public SupplyBufferMenu(
            int windowId,
            Inventory playerInventory,
            BlockPos pos,
            boolean canEdit,
            String linkId,
            String providerNode
    ) {
        this(
                windowId,
                playerInventory,
                getBlockEntity(playerInventory, pos),
                canEdit,
                linkId,
                providerNode
        );
    }

    public SupplyBufferMenu(
            int windowId,
            Inventory playerInventory,
            SupplyBufferBlockEntity supplyBuffer,
            boolean canEdit
    ) {
        this(
                windowId,
                playerInventory,
                supplyBuffer,
                canEdit,
                supplyBuffer.getLinkId(),
                supplyBuffer.getProviderNode()
        );
    }

    private SupplyBufferMenu(
            int windowId,
            Inventory playerInventory,
            SupplyBufferBlockEntity supplyBuffer,
            boolean canEdit,
            String linkId,
            String providerNode
    ) {
        super(SupplyBufferRegistry.SUPPLY_BUFFER_MENU.get(), windowId);
        this.supplyBuffer = supplyBuffer;
        this.blockPos = supplyBuffer.getBlockPos();
        this.canEdit = canEdit;
        this.initialLinkId = linkId == null ? "" : linkId;
        this.initialProviderNode = providerNode == null ? "" : providerNode;
        addDataSlots(syncedData);

        // One visible extraction slot per configured item filter. Internally every
        // filter has 9 stacks of storage; the block entity compacts the first
        // non-empty stack into this representative slot.
        for (int filter = 0; filter < FILTER_COUNT; filter++) {
            addSlot(new SupplyOutputSlot(
                    supplyBuffer,
                    SupplyBufferBlockEntity.supplyRegionStart(filter),
                    FILTER_ROW_X + filter * 18,
                    SUPPLY_ROW_Y
            ));
        }

        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 9; column++) {
                int slot = row * 9 + column;
                addSlot(new ExportInputSlot(
                        supplyBuffer,
                        slot,
                        FILTER_ROW_X + column * 18,
                        EXPORT_ROW_Y + row * 18
                ));
            }
        }

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(
                        playerInventory,
                        column + row * 9 + 9,
                        FILTER_ROW_X + column * 18,
                        PLAYER_INVENTORY_Y + row * 18
                ));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(
                    playerInventory,
                    column,
                    FILTER_ROW_X + column * 18,
                    HOTBAR_Y
            ));
        }
    }

    private static SupplyBufferBlockEntity getBlockEntity(Inventory inventory, BlockPos pos) {
        BlockEntity blockEntity = inventory.player.level().getBlockEntity(pos);
        if (!(blockEntity instanceof SupplyBufferBlockEntity supplyBuffer)) {
            throw new IllegalStateException("Expected SupplyBufferBlockEntity at " + pos);
        }
        return supplyBuffer;
    }

    public BlockPos getBlockPos() {
        return blockPos;
    }

    public SupplyBufferBlockEntity getSupplyBuffer() {
        return supplyBuffer;
    }

    public boolean canEdit() {
        return canEdit;
    }

    public String getLinkId() {
        String current = supplyBuffer.getLinkId();
        return current.isBlank() ? initialLinkId : current;
    }

    public String getProviderNode() {
        String current = supplyBuffer.getProviderNode();
        return current.isBlank() ? initialProviderNode : current;
    }

    public SupplyBufferRole getRole() {
        if (supplyBuffer.getLevel() != null && !supplyBuffer.getLevel().isClientSide) {
            return supplyBuffer.getRole();
        }
        int ordinal = syncedData.get(DATA_ROLE);
        SupplyBufferRole[] values = SupplyBufferRole.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : SupplyBufferRole.UNLINKED;
    }

    public int getItemRefillBelowPercent() {
        return syncedData.get(DATA_ITEM_BELOW);
    }

    public int getItemRefillToPercent() {
        return syncedData.get(DATA_ITEM_TARGET);
    }

    public int getFluidRefillBelowPercent() {
        return syncedData.get(DATA_FLUID_BELOW);
    }

    public int getFluidRefillToPercent() {
        return syncedData.get(DATA_FLUID_TARGET);
    }

    public int getSupplyItemCount(int filterIndex) {
        return validFilterIndex(filterIndex)
                ? Math.max(0, syncedData.get(DATA_ITEM_COUNTS + filterIndex))
                : 0;
    }

    public int getSupplyItemCapacity(int filterIndex) {
        return validFilterIndex(filterIndex)
                ? Math.max(0, syncedData.get(DATA_ITEM_CAPACITIES + filterIndex))
                : 0;
    }

    public int getFluidAmount(int filterIndex) {
        return validFilterIndex(filterIndex)
                ? Math.max(0, syncedData.get(DATA_FLUID_AMOUNTS + filterIndex))
                : 0;
    }

    public int getFluidCapacity(int filterIndex) {
        return validFilterIndex(filterIndex)
                ? Math.max(0, syncedData.get(DATA_FLUID_CAPACITIES + filterIndex))
                : 0;
    }

    public boolean isLinkOnline() {
        return syncedData.get(DATA_LINK_ONLINE) != 0;
    }

    public int getPendingTransferCount() {
        return Math.max(0, syncedData.get(DATA_PENDING));
    }

    public boolean isClusterEnabled() {
        return syncedData.get(DATA_CLUSTER_ENABLED) != 0;
    }

    @Override
    public void broadcastChanges() {
        if (supplyBuffer.getLevel() != null && !supplyBuffer.getLevel().isClientSide) {
            syncedData.set(DATA_ROLE, supplyBuffer.getRole().ordinal());
            syncedData.set(DATA_ITEM_BELOW, supplyBuffer.getItemRefillBelowPercent());
            syncedData.set(DATA_ITEM_TARGET, supplyBuffer.getItemRefillToPercent());
            syncedData.set(DATA_FLUID_BELOW, supplyBuffer.getFluidRefillBelowPercent());
            syncedData.set(DATA_FLUID_TARGET, supplyBuffer.getFluidRefillToPercent());

            boolean online = supplyBuffer.getRole() == SupplyBufferRole.PROVIDER
                    ? supplyBuffer.isProviderAeOnline()
                    : supplyBuffer.isRemoteProviderOnline();
            syncedData.set(DATA_LINK_ONLINE, online ? 1 : 0);
            syncedData.set(DATA_PENDING, supplyBuffer.getPendingTransferCount());
            syncedData.set(DATA_CLUSTER_ENABLED, SupplyBufferService.clusterEnabled() ? 1 : 0);

            for (int filter = 0; filter < FILTER_COUNT; filter++) {
                syncedData.set(
                        DATA_ITEM_COUNTS + filter,
                        clampToInt(supplyBuffer.getConfiguredSupplyItemCount(filter))
                );
                syncedData.set(
                        DATA_ITEM_CAPACITIES + filter,
                        clampToInt(supplyBuffer.getConfiguredSupplyItemCapacity(filter))
                );
                syncedData.set(
                        DATA_FLUID_AMOUNTS + filter,
                        supplyBuffer.getFluidTank(filter).getFluidAmount()
                );
                syncedData.set(
                        DATA_FLUID_CAPACITIES + filter,
                        supplyBuffer.getFluidTank(filter).getCapacity()
                );
            }
        }
        super.broadcastChanges();
    }

    private static int clampToInt(long value) {
        return (int) Math.max(0L, Math.min((long) Integer.MAX_VALUE, value));
    }

    private static boolean validFilterIndex(int filterIndex) {
        return filterIndex >= 0 && filterIndex < FILTER_COUNT;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockEntity(blockPos) == supplyBuffer
                && player.distanceToSqr(
                blockPos.getX() + 0.5D,
                blockPos.getY() + 0.5D,
                blockPos.getZ() + 0.5D
        ) <= 64.0D;
    }

    @Override
    public @NotNull ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }

        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack source = slot.getItem();
        ItemStack original = source.copy();

        if (index >= SUPPLY_START && index < SUPPLY_END) {
            if (!moveItemStackTo(source, PLAYER_INVENTORY_START, HOTBAR_END, true)) {
                return ItemStack.EMPTY;
            }
        } else if (index >= EXPORT_START && index < EXPORT_END) {
            if (!moveItemStackTo(source, PLAYER_INVENTORY_START, HOTBAR_END, true)) {
                return ItemStack.EMPTY;
            }
        } else if (index >= PLAYER_INVENTORY_START && index < HOTBAR_END) {
            if (getRole() != SupplyBufferRole.REMOTE
                    || !moveItemStackTo(source, EXPORT_START, EXPORT_END, false)) {
                if (index < HOTBAR_START) {
                    if (!moveItemStackTo(source, HOTBAR_START, HOTBAR_END, false)) {
                        return ItemStack.EMPTY;
                    }
                } else if (!moveItemStackTo(source, PLAYER_INVENTORY_START, PLAYER_INVENTORY_END, false)) {
                    return ItemStack.EMPTY;
                }
            }
        } else {
            return ItemStack.EMPTY;
        }

        if (source.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (source.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, source);
        return original;
    }

    private static final class SupplyOutputSlot extends SlotItemHandler {
        private SupplyOutputSlot(SupplyBufferBlockEntity blockEntity, int handlerSlot, int x, int y) {
            super(blockEntity.getSupplyItems(), handlerSlot, x, y);
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            return false;
        }
    }

    private static final class ExportInputSlot extends SlotItemHandler {
        private final SupplyBufferBlockEntity blockEntity;

        private ExportInputSlot(SupplyBufferBlockEntity blockEntity, int handlerSlot, int x, int y) {
            super(blockEntity.getExportItems(), handlerSlot, x, y);
            this.blockEntity = blockEntity;
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            return blockEntity.getRole() == SupplyBufferRole.REMOTE && super.mayPlace(stack);
        }
    }
}
