package Crazer.cubeofinterest.cointcoregto.exchanger;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.api.util.AECableType;
import appeng.items.tools.powered.WirelessTerminalItem;
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
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

public class ExchangerBlockEntity extends BlockEntity implements MenuProvider, IInWorldGridNodeHost, IActionHost {
    public static final int SLOT_PRODUCT = 0;
    public static final int SLOT_PRICE = 1;
    public static final int SLOT_PAYMENT = 2;

    private static final int MAX_DEALS = 64;

    private static final IGridNodeListener<ExchangerBlockEntity> NODE_LISTENER =
            new IGridNodeListener<>() {
                @Override
                public void onSaveChanges(ExchangerBlockEntity owner, IGridNode node) {
                    owner.setChangedAndSync();
                }
            };

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

    private final IManagedGridNode mainNode = GridHelper.createManagedNode(this, NODE_LISTENER)
            .setTagName("mainNode")
            .setInWorldNode(true)
            .setExposedOnSides(EnumSet.allOf(Direction.class))
            .setIdlePowerUsage(1.0)
            .setVisualRepresentation(CointExchangerRegistry.EXCHANGER_ITEM.get());

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
        mainNode.setOwningPlayer(player);
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

    public boolean buy(ServerPlayer player, int requestedDeals, boolean buyerAeMode) {
        if (player == null) {
            return false;
        }

        int deals = Math.max(1, Math.min(MAX_DEALS, requestedDeals));
        ItemStack productTemplate = items.getStackInSlot(SLOT_PRODUCT);
        ItemStack priceTemplate = items.getStackInSlot(SLOT_PRICE);

        if (productTemplate.isEmpty() || priceTemplate.isEmpty()) {
            fail(player, "Обменник не настроен.");
            return false;
        }

        MEStorage sellerStorage = getSellerStorage();
        if (sellerStorage == null) {
            fail(player, "Обменник не подключён к активной ME-сети.");
            return false;
        }

        long totalProduct = (long) Math.max(1, productTemplate.getCount()) * deals;
        long totalPrice = (long) Math.max(1, priceTemplate.getCount()) * deals;

        AEItemKey productKey = AEItemKey.of(productTemplate);
        AEItemKey priceKey = AEItemKey.of(priceTemplate);
        IActionSource source = IActionSource.ofPlayer(player, this);

        if (sellerStorage.extract(productKey, totalProduct, Actionable.SIMULATE, source) != totalProduct) {
            fail(player, "В ME-сети продавца недостаточно товара.");
            return false;
        }

        if (sellerStorage.insert(priceKey, totalPrice, Actionable.SIMULATE, source) != totalPrice) {
            fail(player, "В ME-сети продавца нет места для оплаты.");
            return false;
        }

        if (buyerAeMode) {
            return buyWithBuyerAe(player, deals, productKey, priceKey, totalProduct, totalPrice, sellerStorage, source);
        }

        return buyWithInventory(player, deals, productTemplate, priceTemplate, productKey, priceKey,
                totalProduct, totalPrice, sellerStorage, source);
    }

    private boolean buyWithInventory(
            ServerPlayer player,
            int deals,
            ItemStack productTemplate,
            ItemStack priceTemplate,
            AEItemKey productKey,
            AEItemKey priceKey,
            long totalProduct,
            long totalPrice,
            MEStorage sellerStorage,
            IActionSource source
    ) {
        if (totalProduct > Integer.MAX_VALUE || totalPrice > Integer.MAX_VALUE) {
            fail(player, "Слишком большое количество сделок.");
            return false;
        }

        int productAmount = (int) totalProduct;
        int priceAmount = (int) totalPrice;

        if (countPlayerItems(player, priceTemplate) < priceAmount) {
            fail(player, "У вас недостаточно предметов для оплаты.");
            return false;
        }

        if (!canFitPlayerInventory(player, productTemplate, productAmount, priceTemplate, priceAmount)) {
            fail(player, "В инвентаре недостаточно места для товара.");
            return false;
        }

        removePlayerItems(player, priceTemplate, priceAmount);

        long extractedProduct = sellerStorage.extract(productKey, totalProduct, Actionable.MODULATE, source);
        if (extractedProduct != totalProduct) {
            refundPlayer(player, priceTemplate, priceAmount);
            if (extractedProduct > 0) {
                sellerStorage.insert(productKey, extractedProduct, Actionable.MODULATE, source);
            }
            fail(player, "Не удалось забрать товар из ME-сети.");
            return false;
        }

        long insertedPayment = sellerStorage.insert(priceKey, totalPrice, Actionable.MODULATE, source);
        if (insertedPayment != totalPrice) {
            if (insertedPayment > 0) {
                sellerStorage.extract(priceKey, insertedPayment, Actionable.MODULATE, source);
            }
            sellerStorage.insert(productKey, totalProduct, Actionable.MODULATE, source);
            refundPlayer(player, priceTemplate, priceAmount);
            fail(player, "Не удалось положить оплату в ME-сеть.");
            return false;
        }

        giveLargeStack(player, productTemplate, productAmount);
        success(player, deals, totalProduct, false);
        return true;
    }

    private boolean buyWithBuyerAe(
            ServerPlayer player,
            int deals,
            AEItemKey productKey,
            AEItemKey priceKey,
            long totalProduct,
            long totalPrice,
            MEStorage sellerStorage,
            IActionSource source
    ) {
        IGrid buyerGrid = findBuyerWirelessGrid(player);
        if (buyerGrid == null) {
            fail(player, "Рабочий беспроводной ME-терминал не найден в инвентаре или Curios.");
            return false;
        }

        MEStorage buyerStorage = buyerGrid.getStorageService().getInventory();

        if (buyerStorage.extract(priceKey, totalPrice, Actionable.SIMULATE, source) != totalPrice) {
            fail(player, "В вашей ME-сети недостаточно предметов для оплаты.");
            return false;
        }

        if (buyerStorage.insert(productKey, totalProduct, Actionable.SIMULATE, source) != totalProduct) {
            fail(player, "В вашей ME-сети нет места для товара.");
            return false;
        }

        long extractedPayment = buyerStorage.extract(priceKey, totalPrice, Actionable.MODULATE, source);
        if (extractedPayment != totalPrice) {
            if (extractedPayment > 0) {
                buyerStorage.insert(priceKey, extractedPayment, Actionable.MODULATE, source);
            }
            fail(player, "Не удалось забрать оплату из вашей ME-сети.");
            return false;
        }

        long extractedProduct = sellerStorage.extract(productKey, totalProduct, Actionable.MODULATE, source);
        if (extractedProduct != totalProduct) {
            buyerStorage.insert(priceKey, totalPrice, Actionable.MODULATE, source);
            if (extractedProduct > 0) {
                sellerStorage.insert(productKey, extractedProduct, Actionable.MODULATE, source);
            }
            fail(player, "Не удалось забрать товар из ME-сети продавца.");
            return false;
        }

        long insertedPayment = sellerStorage.insert(priceKey, totalPrice, Actionable.MODULATE, source);
        if (insertedPayment != totalPrice) {
            if (insertedPayment > 0) {
                sellerStorage.extract(priceKey, insertedPayment, Actionable.MODULATE, source);
            }
            sellerStorage.insert(productKey, totalProduct, Actionable.MODULATE, source);
            buyerStorage.insert(priceKey, totalPrice, Actionable.MODULATE, source);
            fail(player, "Не удалось положить оплату в ME-сеть продавца.");
            return false;
        }

        long insertedProduct = buyerStorage.insert(productKey, totalProduct, Actionable.MODULATE, source);
        if (insertedProduct != totalProduct) {
            if (insertedProduct > 0) {
                buyerStorage.extract(productKey, insertedProduct, Actionable.MODULATE, source);
            }
            sellerStorage.extract(priceKey, totalPrice, Actionable.MODULATE, source);
            sellerStorage.insert(productKey, totalProduct, Actionable.MODULATE, source);
            buyerStorage.insert(priceKey, totalPrice, Actionable.MODULATE, source);
            fail(player, "Не удалось положить товар в вашу ME-сеть.");
            return false;
        }

        success(player, deals, totalProduct, true);
        return true;
    }

    public long getAvailableProductCount() {
        ItemStack productTemplate = items.getStackInSlot(SLOT_PRODUCT);
        if (productTemplate.isEmpty()) {
            return 0L;
        }

        MEStorage sellerStorage = getSellerStorage();
        if (sellerStorage == null) {
            return 0L;
        }

        AEItemKey productKey = AEItemKey.of(productTemplate);
        return sellerStorage.extract(
                productKey,
                Long.MAX_VALUE,
                Actionable.SIMULATE,
                IActionSource.ofMachine(this)
        );
    }

    @Nullable
    private MEStorage getSellerStorage() {
        if (!mainNode.isOnline()) {
            return null;
        }

        IGrid grid = mainNode.getGrid();
        return grid == null ? null : grid.getStorageService().getInventory();
    }

    @Nullable
    private static IGrid findBuyerWirelessGrid(ServerPlayer player) {
        for (ItemStack stack : player.getInventory().items) {
            IGrid grid = getWirelessGrid(stack, player);
            if (grid != null) {
                return grid;
            }
        }

        for (ItemStack stack : player.getInventory().offhand) {
            IGrid grid = getWirelessGrid(stack, player);
            if (grid != null) {
                return grid;
            }
        }

        for (ItemStack stack : player.getInventory().armor) {
            IGrid grid = getWirelessGrid(stack, player);
            if (grid != null) {
                return grid;
            }
        }

        return findWirelessGridInCurios(player);
    }

    @Nullable
    private static IGrid getWirelessGrid(ItemStack stack, ServerPlayer player) {
        if (stack.isEmpty() || !(stack.getItem() instanceof WirelessTerminalItem terminal)) {
            return null;
        }

        try {
            return terminal.getLinkedGrid(stack, player.level(), player);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    private static IGrid findWirelessGridInCurios(ServerPlayer player) {
        try {
            Class<?> curiosApi = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Method getCuriosInventory = curiosApi.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class);
            Object lazyOptional = getCuriosInventory.invoke(null, player);
            Method resolve = lazyOptional.getClass().getMethod("resolve");
            Optional<?> optional = (Optional<?>) resolve.invoke(lazyOptional);

            if (optional.isEmpty()) {
                return null;
            }

            Object curiosHandler = optional.get();
            Method getEquippedCurios = curiosHandler.getClass().getMethod("getEquippedCurios");
            Object equipped = getEquippedCurios.invoke(curiosHandler);
            Method getSlots = equipped.getClass().getMethod("getSlots");
            Method getStackInSlot = equipped.getClass().getMethod("getStackInSlot", int.class);
            int slots = (int) getSlots.invoke(equipped);

            for (int slot = 0; slot < slots; slot++) {
                ItemStack stack = (ItemStack) getStackInSlot.invoke(equipped, slot);
                IGrid grid = getWirelessGrid(stack, player);
                if (grid != null) {
                    return grid;
                }
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }

        return null;
    }

    private static int countPlayerItems(ServerPlayer player, ItemStack template) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (ItemStack.isSameItemSameTags(stack, template)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void removePlayerItems(ServerPlayer player, ItemStack template, int amount) {
        int remaining = amount;
        for (ItemStack stack : player.getInventory().items) {
            if (remaining <= 0) {
                break;
            }
            if (ItemStack.isSameItemSameTags(stack, template)) {
                int removed = Math.min(remaining, stack.getCount());
                stack.shrink(removed);
                remaining -= removed;
            }
        }
        player.getInventory().setChanged();
    }

    private static boolean canFitPlayerInventory(
            ServerPlayer player,
            ItemStack productTemplate,
            int productAmount,
            ItemStack paymentTemplate,
            int paymentAmount
    ) {
        int remainingPayment = paymentAmount;
        int capacity = 0;

        for (ItemStack current : player.getInventory().items) {
            int resultingCount = current.getCount();
            if (remainingPayment > 0 && ItemStack.isSameItemSameTags(current, paymentTemplate)) {
                int removed = Math.min(remainingPayment, resultingCount);
                resultingCount -= removed;
                remainingPayment -= removed;
            }

            if (resultingCount == 0) {
                capacity += productTemplate.getMaxStackSize();
            } else if (ItemStack.isSameItemSameTags(current, productTemplate)) {
                capacity += Math.max(0, Math.min(current.getMaxStackSize(), productTemplate.getMaxStackSize()) - resultingCount);
            }

            if (capacity >= productAmount) {
                return true;
            }
        }

        return capacity >= productAmount;
    }

    private static void refundPlayer(ServerPlayer player, ItemStack template, int amount) {
        giveLargeStack(player, template, amount);
    }

    private static void giveLargeStack(ServerPlayer player, ItemStack template, int amount) {
        int remaining = amount;
        int maxStackSize = Math.max(1, template.getMaxStackSize());

        while (remaining > 0) {
            ItemStack part = template.copy();
            part.setCount(Math.min(maxStackSize, remaining));
            if (!player.getInventory().add(part) || !part.isEmpty()) {
                player.drop(part, false);
            }
            remaining -= Math.min(maxStackSize, remaining);
        }

        player.getInventory().setChanged();
    }

    private static void fail(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal("§c" + message), true);
    }

    private static void success(ServerPlayer player, int deals, long totalProduct, boolean aeMode) {
        player.displayClientMessage(Component.literal(
                "§aСделок: §e" + deals + "§a. Получено: §e" + totalProduct + " шт.§a Режим: "
                        + (aeMode ? "§bME" : "§fинвентарь")
        ), true);
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
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            mainNode.create(level, worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        mainNode.destroy();
        super.setRemoved();
    }

    @Override
    public IGridNode getGridNode(Direction direction) {
        return mainNode.getNode();
    }

    @Override
    public IGridNode getActionableNode() {
        return mainNode.getNode();
    }

    @Override
    public AECableType getCableConnectionType(Direction direction) {
        return AECableType.SMART;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("Items", items.serializeNBT());
        mainNode.saveToNBT(tag);

        if (ownerUuid != null) {
            tag.putUUID("OwnerUuid", ownerUuid);
        }
        tag.putString("OwnerName", ownerName == null ? "" : ownerName);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        items.deserializeNBT(tag.getCompound("Items"));
        mainNode.loadFromNBT(tag);

        ownerUuid = tag.hasUUID("OwnerUuid") ? tag.getUUID("OwnerUuid") : null;
        ownerName = tag.getString("OwnerName");
    }

    private void setChangedAndSync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
}
