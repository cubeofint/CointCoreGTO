package Crazer.cubeofinterest.cointcoregto.exchanger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class ExchangerBlockEntity extends BlockEntity implements MenuProvider {
    public static final int SLOT_PRODUCT = 0;
    public static final int SLOT_PRICE = 1;
    public static final int SLOT_PAYMENT = 2;

    private static final int MAX_DEALS = 64;

    private final ItemStackHandler items = new ItemStackHandler(3) {
        @Override
        protected void onContentsChanged(int slot) {
            setChangedAndSync();
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }
    };

    private UUID ownerUuid;
    private String ownerName = "";

    public ExchangerBlockEntity(BlockPos pos, BlockState state) {
        super(CointExchangerRegistry.EXCHANGER_BLOCK_ENTITY.get(), pos, state);
    }

    public ItemStackHandler getItems() {
        return items;
    }

    public BlockPos getBlockPos() {
        return worldPosition;
    }

    public void setOwner(Player player) {
        if (player == null || ownerUuid != null) {
            return;
        }

        ownerUuid = player.getUUID();
        ownerName = player.getGameProfile().getName();
        setChangedAndSync();
    }

    public boolean canEdit(Player player) {
        if (player == null) {
            return false;
        }

        if (player.hasPermissions(2)) {
            return true;
        }

        return ownerUuid != null && ownerUuid.equals(player.getUUID());
    }

    public String getOwnerName() {
        return ownerName == null ? "" : ownerName;
    }

    public boolean buy(ServerPlayer player, int requestedDeals) {
        if (player == null) {
            return false;
        }

        int deals = Math.max(1, Math.min(MAX_DEALS, requestedDeals));

        ItemStack productTemplate = items.getStackInSlot(SLOT_PRODUCT);
        ItemStack priceTemplate = items.getStackInSlot(SLOT_PRICE);

        if (productTemplate.isEmpty() || priceTemplate.isEmpty()) {
            player.displayClientMessage(Component.literal("§cОбменник не настроен."), true);
            return false;
        }

        IItemHandler storage = findNeighborItemHandler();

        if (storage == null) {
            player.displayClientMessage(Component.literal("§cРядом нет сундука, интерфейса или другого хранилища."), true);
            return false;
        }

        int productPerDeal = Math.max(1, productTemplate.getCount());
        int pricePerDeal = Math.max(1, priceTemplate.getCount());

        long totalProductLong = (long) productPerDeal * deals;
        long totalPriceLong = (long) pricePerDeal * deals;

        if (totalProductLong > Integer.MAX_VALUE || totalPriceLong > Integer.MAX_VALUE) {
            player.displayClientMessage(Component.literal("§cСлишком большое количество сделок."), true);
            return false;
        }

        int totalProduct = (int) totalProductLong;
        int totalPrice = (int) totalPriceLong;

        if (countPlayerItems(player, priceTemplate) < totalPrice) {
            player.displayClientMessage(Component.literal("§cУ тебя недостаточно предметов для оплаты."), true);
            return false;
        }

        if (!canExtractItemsFromHandler(storage, productTemplate, totalProduct)) {
            player.displayClientMessage(Component.literal("§cВ подключённом хранилище недостаточно товара."), true);
            return false;
        }

        if (!canInsertItemsIntoHandler(storage, priceTemplate, totalPrice)) {
            player.displayClientMessage(Component.literal("§cВ подключённом хранилище нет места для оплаты."), true);
            return false;
        }

        removePlayerItems(player, priceTemplate, totalPrice);

        if (!extractItemsFromHandler(storage, productTemplate, totalProduct)) {
            refundPlayer(player, priceTemplate, totalPrice);
            player.displayClientMessage(Component.literal("§cНе удалось забрать товар из хранилища."), true);
            return false;
        }

        if (!insertItemsIntoHandler(storage, priceTemplate, totalPrice)) {
            ItemStack productRefund = productTemplate.copy();
            productRefund.setCount(totalProduct);
            insertItemsIntoHandler(storage, productRefund, totalProduct);

            refundPlayer(player, priceTemplate, totalPrice);
            player.displayClientMessage(Component.literal("§cНе удалось положить оплату в хранилище."), true);
            return false;
        }

        ItemStack productToGive = productTemplate.copy();
        productToGive.setCount(totalProduct);

        giveLargeStack(player, productToGive);

        player.displayClientMessage(
                Component.literal("§aСделок: §e" + deals + "§a. Получено: §e" + totalProduct + " шт."),
                true
        );

        setChangedAndSync();
        return true;
    }

    @Nullable
    private IItemHandler findNeighborItemHandler() {
        if (level == null) {
            return null;
        }

        for (Direction direction : Direction.values()) {
            BlockEntity neighbor = level.getBlockEntity(worldPosition.relative(direction));

            if (neighbor == null) {
                continue;
            }

            LazyOptional<IItemHandler> capability = neighbor.getCapability(
                    ForgeCapabilities.ITEM_HANDLER,
                    direction.getOpposite()
            );

            if (capability.isPresent()) {
                return capability.orElse(null);
            }
        }

        return null;
    }

    private static int countItemsInHandler(IItemHandler handler, ItemStack template) {
        int count = 0;

        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);

            if (!ItemStack.isSameItemSameTags(stack, template)) {
                continue;
            }

            long nextCount = (long) count + stack.getCount();

            if (nextCount > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }

            count = (int) nextCount;
        }

        return count;
    }

    private static boolean canExtractItemsFromHandler(IItemHandler handler, ItemStack template, int amount) {
        return countItemsInHandler(handler, template) >= amount;
    }

    private static boolean extractItemsFromHandler(IItemHandler handler, ItemStack template, int amount) {
        int remaining = amount;

        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (remaining <= 0) {
                break;
            }

            ItemStack stack = handler.getStackInSlot(slot);

            if (!ItemStack.isSameItemSameTags(stack, template)) {
                continue;
            }

            int toExtract = Math.min(remaining, stack.getCount());
            ItemStack extracted = handler.extractItem(slot, toExtract, false);

            remaining -= extracted.getCount();
        }

        return remaining <= 0;
    }

    private static boolean canInsertItemsIntoHandler(IItemHandler handler, ItemStack template, int amount) {
        int remaining = amount;

        while (remaining > 0) {
            ItemStack part = template.copy();
            part.setCount(Math.min(template.getMaxStackSize(), remaining));

            ItemStack remainder = insertIntoHandler(handler, part, true);
            int inserted = part.getCount() - remainder.getCount();

            if (inserted <= 0) {
                return false;
            }

            remaining -= inserted;
        }

        return true;
    }

    private static boolean insertItemsIntoHandler(IItemHandler handler, ItemStack template, int amount) {
        int remaining = amount;

        while (remaining > 0) {
            ItemStack part = template.copy();
            part.setCount(Math.min(template.getMaxStackSize(), remaining));

            ItemStack remainder = insertIntoHandler(handler, part, false);
            int inserted = part.getCount() - remainder.getCount();

            if (inserted <= 0) {
                return false;
            }

            remaining -= inserted;
        }

        return true;
    }

    private static ItemStack insertIntoHandler(IItemHandler handler, ItemStack stack, boolean simulate) {
        ItemStack remaining = stack.copy();

        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (remaining.isEmpty()) {
                break;
            }

            remaining = handler.insertItem(slot, remaining, simulate);
        }

        return remaining;
    }

    private static int countPlayerItems(ServerPlayer player, ItemStack template) {
        int count = 0;

        for (ItemStack stack : player.getInventory().items) {
            if (!ItemStack.isSameItemSameTags(stack, template)) {
                continue;
            }

            long nextCount = (long) count + stack.getCount();

            if (nextCount > Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }

            count = (int) nextCount;
        }

        return count;
    }

    private static void removePlayerItems(ServerPlayer player, ItemStack template, int amount) {
        int remaining = amount;

        for (ItemStack stack : player.getInventory().items) {
            if (remaining <= 0) {
                break;
            }

            if (!ItemStack.isSameItemSameTags(stack, template)) {
                continue;
            }

            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            remaining -= removed;
        }

        player.getInventory().setChanged();
    }

    private static void refundPlayer(ServerPlayer player, ItemStack template, int amount) {
        ItemStack refund = template.copy();
        refund.setCount(amount);

        giveLargeStack(player, refund);
    }

    private static void giveLargeStack(ServerPlayer player, ItemStack stack) {
        int remaining = stack.getCount();
        int maxStackSize = Math.max(1, stack.getMaxStackSize());

        while (remaining > 0) {
            int amountToGive = Math.min(maxStackSize, remaining);

            ItemStack part = stack.copy();
            part.setCount(amountToGive);

            if (!player.getInventory().add(part)) {
                player.drop(part, false);
            } else if (!part.isEmpty()) {
                player.drop(part, false);
            }

            remaining -= amountToGive;
        }

        player.getInventory().setChanged();
    }

    public void dropContents() {
        if (level == null || level.isClientSide) {
            return;
        }

        SimpleContainer container = new SimpleContainer(items.getSlots());

        for (int slot = 0; slot < items.getSlots(); slot++) {
            container.setItem(slot, items.getStackInSlot(slot));
        }

        net.minecraft.world.Containers.dropContents(level, worldPosition, container);
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Обменник");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inventory, Player player) {
        return new ExchangerMenu(windowId, inventory, this, canEdit(player));
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);

        tag.put("Items", items.serializeNBT());

        if (ownerUuid != null) {
            tag.putUUID("OwnerUuid", ownerUuid);
        }

        tag.putString("OwnerName", ownerName == null ? "" : ownerName);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);

        items.deserializeNBT(tag.getCompound("Items"));

        if (tag.hasUUID("OwnerUuid")) {
            ownerUuid = tag.getUUID("OwnerUuid");
        } else {
            ownerUuid = null;
        }

        ownerName = tag.getString("OwnerName");
    }

    private void setChangedAndSync() {
        setChanged();

        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(
            @NotNull Capability<T> capability,
            @Nullable Direction side
    ) {
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
    }
}