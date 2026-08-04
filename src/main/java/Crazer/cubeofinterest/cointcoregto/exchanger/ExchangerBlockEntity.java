package Crazer.cubeofinterest.cointcoregto.exchanger;

import Crazer.cubeofinterest.cointcoregto.currency.CurrencyConfig;
import Crazer.cubeofinterest.cointcoregto.currency.CurrencyContext;
import Crazer.cubeofinterest.cointcoregto.currency.CurrencyOperationResult;
import Crazer.cubeofinterest.cointcoregto.currency.CurrencyService;
import Crazer.cubeofinterest.cointcoregto.currency.CurrencySettlementEntry;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private long currencyPricePerDeal;
    private String requiredTierId = "";

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

    public boolean canEditRequiredTier(Player player) {
        if (player == null) {
            return false;
        }
        if (player.hasPermissions(2)) {
            return true;
        }
        return CurrencyConfig.exchangerOwnersCanSetRequiredTier()
                && ownerUuid != null
                && ownerUuid.equals(player.getUUID());
    }

    public String getOwnerName() {
        return ownerName == null ? "" : ownerName;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public long getCurrencyPricePerDeal() {
        return Math.max(0L, currencyPricePerDeal);
    }

    public void setCurrencyPricePerDeal(long amount) {
        currencyPricePerDeal = Math.max(
                0L,
                Math.min(amount, CurrencyConfig.descriptor().maximumBalance())
        );
        setChangedAndSync();
    }

    public String getRequiredTierId() {
        return CurrencyConfig.normalizeTierId(requiredTierId);
    }

    public String getEffectiveRequiredTierId() {
        return ExchangerProgression.effectiveRequiredTier(
                items.getStackInSlot(SLOT_PRODUCT),
                getRequiredTierId()
        );
    }

    public void setRequiredTierId(String tierId) {
        String normalized = CurrencyConfig.normalizeTierId(tierId);
        requiredTierId = CurrencyConfig.exchangerTierIndex(normalized) >= 0 ? normalized : "";
        setChangedAndSync();
    }

    public void cycleRequiredTier(int direction) {
        List<String> tiers = CurrencyConfig.exchangerTierOrder();
        if (tiers.isEmpty()) {
            setRequiredTierId("");
            return;
        }
        int current = CurrencyConfig.exchangerTierIndex(getEffectiveRequiredTierId());
        int slots = tiers.size() + 1;
        int encodedCurrent = current + 1;
        int next = Math.floorMod(encodedCurrent + (direction < 0 ? -1 : 1), slots);
        setRequiredTierId(next == 0 ? "" : tiers.get(next - 1));
    }

    public boolean buy(ServerPlayer player, int requestedDeals, boolean buyerAeMode) {
        if (player == null) {
            return false;
        }
        if (ownerUuid != null && ownerUuid.equals(player.getUUID())) {
            fail(player, "Нельзя покупать товары в собственном обменнике.");
            return false;
        }

        int deals = Math.max(1, Math.min(MAX_DEALS, requestedDeals));
        ItemStack productTemplate = items.getStackInSlot(SLOT_PRODUCT);
        ItemStack priceTemplate = items.getStackInSlot(SLOT_PRICE);
        long pricePerDeal = getCurrencyPricePerDeal();

        if (productTemplate.isEmpty()) {
            fail(player, "Обменник не настроен: отсутствует товар.");
            return false;
        }
        if (priceTemplate.isEmpty() && pricePerDeal <= 0L) {
            fail(player, "Обменник не настроен: отсутствует цена.");
            return false;
        }
        if (pricePerDeal > 0L && ownerUuid == null) {
            fail(player, "У обменника не указан владелец для получения валюты.");
            return false;
        }

        MEStorage sellerStorage = getSellerStorage();
        if (sellerStorage == null) {
            fail(player, "Обменник не подключён к активной ME-сети.");
            return false;
        }

        long totalProduct;
        long totalItemPrice;
        ExchangerProgression.Quote progressionQuote;
        try {
            totalProduct = Math.multiplyExact((long) Math.max(1, productTemplate.getCount()), deals);
            totalItemPrice = priceTemplate.isEmpty()
                    ? 0L
                    : Math.multiplyExact((long) Math.max(1, priceTemplate.getCount()), deals);
            progressionQuote = ExchangerProgression.quote(
                    player,
                    getEffectiveRequiredTierId(),
                    pricePerDeal,
                    deals
            );
        } catch (ArithmeticException exception) {
            fail(player, "Слишком большая сумма сделки.");
            return false;
        }

        if (!progressionQuote.allowed()) {
            if (progressionQuote.status() == ExchangerProgression.Status.INVALID_REQUIRED_TIER) {
                fail(player, "В обменнике указана неизвестная минимальная эпоха.");
            } else {
                String required = ExchangerProgression.tierDisplayName(
                        CurrencyConfig.exchangerTierOrder(),
                        progressionQuote.requiredTierIndex()
                );
                fail(player, "Для покупки требуется эпоха " + required + " или выше.");
            }
            return false;
        }
        long currencyBaseSubtotal = progressionQuote.baseTotal();
        long currencyDiscount = progressionQuote.discountAmount();
        long currencyTotal = progressionQuote.effectiveTotal();

        AEItemKey productKey = AEItemKey.of(productTemplate);
        AEItemKey priceKey = priceTemplate.isEmpty() ? null : AEItemKey.of(priceTemplate);
        IActionSource source = IActionSource.ofPlayer(player, this);

        if (sellerStorage.extract(productKey, totalProduct, Actionable.SIMULATE, source) != totalProduct) {
            fail(player, "В ME-сети продавца недостаточно товара.");
            return false;
        }
        if (priceKey != null
                && sellerStorage.insert(priceKey, totalItemPrice, Actionable.SIMULATE, source) != totalItemPrice) {
            fail(player, "В ME-сети продавца нет места для оплаты ресурсами.");
            return false;
        }
        if (currencyTotal > 0L && !CurrencyService.available()) {
            fail(player, "Валютная система недоступна: " + CurrencyService.lastError());
            return false;
        }

        UUID transactionId = UUID.randomUUID();
        CurrencyPayment currencyPayment;
        try {
            currencyPayment = createCurrencyPayment(
                    player,
                    transactionId,
                    deals,
                    productTemplate,
                    priceTemplate,
                    totalProduct,
                    totalItemPrice,
                    currencyBaseSubtotal,
                    currencyDiscount,
                    currencyTotal,
                    progressionQuote
            );
        } catch (IllegalArgumentException exception) {
            fail(player, exception.getMessage());
            return false;
        }

        if (buyerAeMode) {
            return buyWithBuyerAe(
                    player,
                    deals,
                    productTemplate,
                    priceTemplate,
                    productKey,
                    priceKey,
                    totalProduct,
                    totalItemPrice,
                    sellerStorage,
                    source,
                    currencyPayment
            );
        }

        return buyWithInventory(
                player,
                deals,
                productTemplate,
                priceTemplate,
                productKey,
                priceKey,
                totalProduct,
                totalItemPrice,
                sellerStorage,
                source,
                currencyPayment
        );
    }

    private boolean buyWithInventory(
            ServerPlayer player,
            int deals,
            ItemStack productTemplate,
            ItemStack priceTemplate,
            AEItemKey productKey,
            AEItemKey priceKey,
            long totalProduct,
            long totalItemPrice,
            MEStorage sellerStorage,
            IActionSource source,
            CurrencyPayment currencyPayment
    ) {
        if (totalProduct > Integer.MAX_VALUE || totalItemPrice > Integer.MAX_VALUE) {
            fail(player, "Слишком большое количество предметов.");
            return false;
        }

        int productAmount = (int) totalProduct;
        int itemPriceAmount = (int) totalItemPrice;
        if (itemPriceAmount > 0 && countPlayerItems(player, priceTemplate) < itemPriceAmount) {
            fail(player, "У вас недостаточно предметов для оплаты.");
            return false;
        }
        if (!canFitPlayerInventory(player, productTemplate, productAmount, priceTemplate, itemPriceAmount)) {
            fail(player, "В инвентаре недостаточно места для товара.");
            return false;
        }
        if (!reserveCurrency(player, currencyPayment)) {
            return false;
        }

        if (itemPriceAmount > 0) {
            removePlayerItems(player, priceTemplate, itemPriceAmount);
        }

        long extractedProduct = sellerStorage.extract(productKey, totalProduct, Actionable.MODULATE, source);
        if (extractedProduct != totalProduct) {
            if (itemPriceAmount > 0) {
                refundPlayer(player, priceTemplate, itemPriceAmount);
            }
            if (extractedProduct > 0) {
                sellerStorage.insert(productKey, extractedProduct, Actionable.MODULATE, source);
            }
            releaseCurrency(player, currencyPayment);
            fail(player, "Не удалось забрать товар из ME-сети.");
            return false;
        }

        long insertedPayment = 0L;
        if (priceKey != null) {
            insertedPayment = sellerStorage.insert(priceKey, totalItemPrice, Actionable.MODULATE, source);
            if (insertedPayment != totalItemPrice) {
                if (insertedPayment > 0) {
                    sellerStorage.extract(priceKey, insertedPayment, Actionable.MODULATE, source);
                }
                sellerStorage.insert(productKey, totalProduct, Actionable.MODULATE, source);
                refundPlayer(player, priceTemplate, itemPriceAmount);
                releaseCurrency(player, currencyPayment);
                fail(player, "Не удалось положить оплату в ME-сеть.");
                return false;
            }
        }

        if (!settleCurrency(player, currencyPayment)) {
            if (priceKey != null && insertedPayment > 0L) {
                sellerStorage.extract(priceKey, insertedPayment, Actionable.MODULATE, source);
            }
            sellerStorage.insert(productKey, totalProduct, Actionable.MODULATE, source);
            if (itemPriceAmount > 0) {
                refundPlayer(player, priceTemplate, itemPriceAmount);
            }
            releaseCurrency(player, currencyPayment);
            return false;
        }

        giveLargeStack(player, productTemplate, productAmount);
        success(player, deals, totalProduct, false, totalItemPrice, currencyPayment);
        return true;
    }

    private boolean buyWithBuyerAe(
            ServerPlayer player,
            int deals,
            ItemStack productTemplate,
            ItemStack priceTemplate,
            AEItemKey productKey,
            AEItemKey priceKey,
            long totalProduct,
            long totalItemPrice,
            MEStorage sellerStorage,
            IActionSource source,
            CurrencyPayment currencyPayment
    ) {
        IGrid buyerGrid = findBuyerWirelessGrid(player);
        if (buyerGrid == null) {
            fail(player, "Рабочий беспроводной ME-терминал не найден в инвентаре или Curios.");
            return false;
        }

        MEStorage buyerStorage = buyerGrid.getStorageService().getInventory();
        if (priceKey != null
                && buyerStorage.extract(priceKey, totalItemPrice, Actionable.SIMULATE, source) != totalItemPrice) {
            fail(player, "В вашей ME-сети недостаточно предметов для оплаты.");
            return false;
        }
        if (buyerStorage.insert(productKey, totalProduct, Actionable.SIMULATE, source) != totalProduct) {
            fail(player, "В вашей ME-сети нет места для товара.");
            return false;
        }
        if (!reserveCurrency(player, currencyPayment)) {
            return false;
        }

        long extractedPayment = 0L;
        if (priceKey != null) {
            extractedPayment = buyerStorage.extract(priceKey, totalItemPrice, Actionable.MODULATE, source);
            if (extractedPayment != totalItemPrice) {
                if (extractedPayment > 0) {
                    buyerStorage.insert(priceKey, extractedPayment, Actionable.MODULATE, source);
                }
                releaseCurrency(player, currencyPayment);
                fail(player, "Не удалось забрать оплату из вашей ME-сети.");
                return false;
            }
        }

        long extractedProduct = sellerStorage.extract(productKey, totalProduct, Actionable.MODULATE, source);
        if (extractedProduct != totalProduct) {
            if (priceKey != null && extractedPayment > 0L) {
                buyerStorage.insert(priceKey, extractedPayment, Actionable.MODULATE, source);
            }
            if (extractedProduct > 0) {
                sellerStorage.insert(productKey, extractedProduct, Actionable.MODULATE, source);
            }
            releaseCurrency(player, currencyPayment);
            fail(player, "Не удалось забрать товар из ME-сети продавца.");
            return false;
        }

        long insertedPayment = 0L;
        if (priceKey != null) {
            insertedPayment = sellerStorage.insert(priceKey, totalItemPrice, Actionable.MODULATE, source);
            if (insertedPayment != totalItemPrice) {
                if (insertedPayment > 0) {
                    sellerStorage.extract(priceKey, insertedPayment, Actionable.MODULATE, source);
                }
                sellerStorage.insert(productKey, totalProduct, Actionable.MODULATE, source);
                buyerStorage.insert(priceKey, totalItemPrice, Actionable.MODULATE, source);
                releaseCurrency(player, currencyPayment);
                fail(player, "Не удалось положить оплату в ME-сеть продавца.");
                return false;
            }
        }

        long insertedProduct = buyerStorage.insert(productKey, totalProduct, Actionable.MODULATE, source);
        if (insertedProduct != totalProduct) {
            if (insertedProduct > 0) {
                buyerStorage.extract(productKey, insertedProduct, Actionable.MODULATE, source);
            }
            if (priceKey != null && insertedPayment > 0L) {
                sellerStorage.extract(priceKey, insertedPayment, Actionable.MODULATE, source);
            }
            sellerStorage.insert(productKey, totalProduct, Actionable.MODULATE, source);
            if (priceKey != null && extractedPayment > 0L) {
                buyerStorage.insert(priceKey, totalItemPrice, Actionable.MODULATE, source);
            }
            releaseCurrency(player, currencyPayment);
            fail(player, "Не удалось положить товар в вашу ME-сеть.");
            return false;
        }

        if (!settleCurrency(player, currencyPayment)) {
            buyerStorage.extract(productKey, totalProduct, Actionable.MODULATE, source);
            if (priceKey != null && insertedPayment > 0L) {
                sellerStorage.extract(priceKey, insertedPayment, Actionable.MODULATE, source);
            }
            sellerStorage.insert(productKey, totalProduct, Actionable.MODULATE, source);
            if (priceKey != null && extractedPayment > 0L) {
                buyerStorage.insert(priceKey, totalItemPrice, Actionable.MODULATE, source);
            }
            releaseCurrency(player, currencyPayment);
            return false;
        }

        success(player, deals, totalProduct, true, totalItemPrice, currencyPayment);
        return true;
    }

    private CurrencyPayment createCurrencyPayment(
            ServerPlayer player,
            UUID transactionId,
            int deals,
            ItemStack productTemplate,
            ItemStack priceTemplate,
            long totalProduct,
            long totalItemPrice,
            long baseSubtotal,
            long discount,
            long total,
            ExchangerProgression.Quote progressionQuote
    ) {
        if (total <= 0L) {
            return CurrencyPayment.none(transactionId, baseSubtotal, discount, progressionQuote);
        }

        List<CurrencySettlementEntry> entries = List.of(
                new CurrencySettlementEntry(ownerUuid, total)
        );

        Map<String, String> metadata = new HashMap<>();
        metadata.put("transaction_id", transactionId.toString());
        metadata.put("buyer_uuid", player.getUUID().toString());
        metadata.put("buyer_name", player.getGameProfile().getName());
        metadata.put("seller_uuid", ownerUuid == null ? "" : ownerUuid.toString());
        metadata.put("seller_name", getOwnerName());
        metadata.put("dimension", level == null ? "" : level.dimension().location().toString());
        metadata.put("block_pos", worldPosition.toShortString());
        metadata.put("deals", Integer.toString(deals));
        metadata.put("product", productTemplate.getDescriptionId());
        metadata.put("product_amount", Long.toString(totalProduct));
        metadata.put("resource_price", priceTemplate.isEmpty() ? "" : priceTemplate.getDescriptionId());
        metadata.put("resource_price_amount", Long.toString(totalItemPrice));
        metadata.put("currency_base_subtotal", Long.toString(baseSubtotal));
        metadata.put("currency_discount", Long.toString(discount));
        metadata.put("currency_total", Long.toString(total));
        metadata.put("manual_required_tier", getRequiredTierId());
        metadata.put("automatic_required_tier", ExchangerProgression.automaticRequiredTier(productTemplate));
        metadata.put("required_tier", progressionQuote.requiredTierId());
        metadata.put("required_tier_index", Integer.toString(progressionQuote.requiredTierIndex()));
        metadata.put("buyer_tier_index", Integer.toString(progressionQuote.playerTierIndex()));
        metadata.put("discount_basis_points", Integer.toString(progressionQuote.discountBasisPoints()));

        CurrencyContext context = CurrencyService.context(
                player.getUUID(),
                player.getGameProfile().getName(),
                "Покупка в обменнике",
                "EXCHANGER",
                transactionId.toString(),
                metadata
        );
        return new CurrencyPayment(
                transactionId,
                operationId(transactionId, "hold"),
                operationId(transactionId, "settle"),
                operationId(transactionId, "release"),
                baseSubtotal,
                discount,
                total,
                progressionQuote.discountBasisPoints(),
                List.copyOf(entries),
                context
        );
    }

    private boolean reserveCurrency(ServerPlayer player, CurrencyPayment payment) {
        if (!payment.required()) {
            return true;
        }
        CurrencyOperationResult result = CurrencyService.hold(
                player.getUUID(),
                payment.total(),
                payment.holdId(),
                Instant.now().plusSeconds(300L),
                payment.context()
        );
        if (!result.success()) {
            fail(player, "Не удалось зарезервировать валюту: " + result.message());
            return false;
        }
        return true;
    }

    private boolean settleCurrency(ServerPlayer player, CurrencyPayment payment) {
        if (!payment.required()) {
            return true;
        }
        CurrencyOperationResult result = CurrencyService.settle(
                payment.holdId(),
                player.getUUID(),
                payment.entries(),
                payment.settleOperationId(),
                payment.context()
        );
        if (!result.success()) {
            fail(player, "Не удалось провести валютный платёж: " + result.message());
            return false;
        }
        return true;
    }

    private void releaseCurrency(ServerPlayer player, CurrencyPayment payment) {
        if (!payment.required()) {
            return;
        }
        CurrencyService.release(
                payment.holdId(),
                player.getUUID(),
                payment.releaseOperationId(),
                payment.context()
        );
    }

    private static UUID operationId(UUID transactionId, String suffix) {
        return UUID.nameUUIDFromBytes(
                (transactionId + ":" + suffix).getBytes(StandardCharsets.UTF_8)
        );
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

    private static void success(
            ServerPlayer player,
            int deals,
            long totalProduct,
            boolean aeMode,
            long totalItemPrice,
            CurrencyPayment payment
    ) {
        StringBuilder message = new StringBuilder()
                .append("§aСделок: §e").append(deals)
                .append("§a. Получено: §e").append(totalProduct).append(" шт.");
        if (totalItemPrice > 0L) {
            message.append(" §aРесурсы: §e").append(totalItemPrice).append(" шт.");
        }
        if (payment.baseSubtotal() > 0L) {
            message.append(" §aВалюта: §e").append(CurrencyService.format(payment.total()));
            if (payment.discount() > 0L) {
                message.append(" §7(скидка ")
                        .append(formatPercent(payment.discountBasisPoints()))
                        .append("%, -")
                        .append(CurrencyService.format(payment.discount()))
                        .append(")");
            }
        }
        message.append(" §aРежим: ").append(aeMode ? "§bME" : "§fинвентарь");
        player.displayClientMessage(Component.literal(message.toString()), true);
    }

    private static String formatPercent(int basisPoints) {
        if (basisPoints % 100 == 0) {
            return Integer.toString(basisPoints / 100);
        }
        if (basisPoints % 10 == 0) {
            return String.format(java.util.Locale.ROOT, "%.1f", basisPoints / 100.0D);
        }
        return String.format(java.util.Locale.ROOT, "%.2f", basisPoints / 100.0D);
    }

    private record CurrencyPayment(
            UUID transactionId,
            UUID holdId,
            UUID settleOperationId,
            UUID releaseOperationId,
            long baseSubtotal,
            long discount,
            long total,
            int discountBasisPoints,
            List<CurrencySettlementEntry> entries,
            CurrencyContext context
    ) {
        private static CurrencyPayment none(
                UUID transactionId,
                long baseSubtotal,
                long discount,
                ExchangerProgression.Quote progressionQuote
        ) {
            return new CurrencyPayment(
                    transactionId,
                    null,
                    null,
                    null,
                    baseSubtotal,
                    discount,
                    0L,
                    progressionQuote.discountBasisPoints(),
                    List.of(),
                    null
            );
        }

        private boolean required() {
            return total > 0L;
        }
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
        tag.putLong("CurrencyPricePerDeal", getCurrencyPricePerDeal());
        tag.putString("RequiredTierId", getRequiredTierId());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        items.deserializeNBT(tag.getCompound("Items"));
        mainNode.loadFromNBT(tag);

        ownerUuid = tag.hasUUID("OwnerUuid") ? tag.getUUID("OwnerUuid") : null;
        ownerName = tag.getString("OwnerName");
        currencyPricePerDeal = Math.max(0L, tag.getLong("CurrencyPricePerDeal"));
        requiredTierId = CurrencyConfig.normalizeTierId(tag.getString("RequiredTierId"));
    }

    private void setChangedAndSync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }
}
