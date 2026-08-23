package Crazer.cubeofinterest.cointcoregto.supply;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import appeng.api.util.AECableType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class SupplyBufferBlockEntity extends BlockEntity
        implements MenuProvider, IInWorldGridNodeHost, IActionHost {

    public static final int REQUEST_FILTER_COUNT = 9;
    public static final int SUPPLY_SLOTS_PER_FILTER = 9;
    public static final int SUPPLY_SLOT_COUNT = REQUEST_FILTER_COUNT * SUPPLY_SLOTS_PER_FILTER;
    public static final int EXPORT_SLOT_COUNT = 18;
    public static final int FLUID_CAPACITY_PER_FILTER = 16_000_000;
    public static final int FLUID_CAPACITY = FLUID_CAPACITY_PER_FILTER;

    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO-SupplyBuffer");
    private static final int[] THRESHOLD_OPTIONS = {10, 25, 50, 75, 90};
    private static final int[] TARGET_OPTIONS = {25, 50, 75, 100};
    private static final int REMOTE_SYNC_INTERVAL_TICKS = 10;
    private static final int PROVIDER_POLL_INTERVAL_TICKS = 10;
    private static final int PROVIDER_HEARTBEAT_INTERVAL_TICKS = 100;
    private static final long MAX_OUTBOUND_BATCH = 4096L;

    private static final IGridNodeListener<SupplyBufferBlockEntity> NODE_LISTENER =
            new IGridNodeListener<>() {
                @Override
                public void onSaveChanges(SupplyBufferBlockEntity owner, IGridNode node) {
                    owner.setChangedAndSync();
                }
            };

    private final String[] itemFilterPayloads = new String[REQUEST_FILTER_COUNT];
    private final String[] fluidFilterPayloads = new String[REQUEST_FILTER_COUNT];

    private final ItemStackHandler supplyItems = new ItemStackHandler(SUPPLY_SLOT_COUNT) {
        @Override
        protected void onContentsChanged(int slot) {
            markDirty();
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            int filterIndex = filterIndexForSupplySlot(slot);
            AEItemKey configured = getConfiguredItemKey(filterIndex);
            return configured != null && configured.matches(stack);
        }
    };

    private final ItemStackHandler exportItems = new ItemStackHandler(EXPORT_SLOT_COUNT) {
        @Override
        protected void onContentsChanged(int slot) {
            markDirty();
        }
    };

    private final FluidTank[] fluidTanks = createFluidTanks();

    private final IManagedGridNode mainNode = GridHelper.createManagedNode(this, NODE_LISTENER)
            .setTagName("supplyMainNode")
            .setInWorldNode(true)
            .setExposedOnSides(EnumSet.allOf(Direction.class))
            .setIdlePowerUsage(1.0)
            .setVisualRepresentation(SupplyBufferRegistry.SUPPLY_BUFFER_ITEM.get());

    private final IItemHandler exportInputView = new InsertOnlyItemHandler(exportItems);
    private final IItemHandler supplyOutputView = new ExtractOnlyItemHandler(supplyItems);
    private final IFluidHandler fluidOutputView = new DrainOnlyFluidHandler(fluidTanks);

    private LazyOptional<IItemHandler> exportInputCapability = LazyOptional.of(() -> exportInputView);
    private LazyOptional<IItemHandler> supplyOutputCapability = LazyOptional.of(() -> supplyOutputView);
    private LazyOptional<IFluidHandler> fluidOutputCapability = LazyOptional.of(() -> fluidOutputView);

    private SupplyBufferRole role = SupplyBufferRole.UNLINKED;
    private UUID ownerUuid;
    private String ownerName = "";
    private String linkId = "";
    private String providerNode = "";
    private int itemRefillBelowPercent = 50;
    private int itemRefillToPercent = 100;
    private int fluidRefillBelowPercent = 50;
    private int fluidRefillToPercent = 100;

    private PendingTransfer pendingOutbound;
    private final PendingTransfer[] pendingItemRequests = new PendingTransfer[REQUEST_FILTER_COUNT];
    private final PendingTransfer[] pendingFluidRequests = new PendingTransfer[REQUEST_FILTER_COUNT];
    private ProviderJournal providerJournal;
    private final Set<UUID> pendingAcknowledgements = new LinkedHashSet<>();

    private transient CompletableFuture<SupplyBufferDatabase.RemoteSyncResult> remoteSyncFuture;
    private transient CompletableFuture<Void> providerHeartbeatFuture;
    private transient CompletableFuture<SupplyBufferDatabase.Operation> providerClaimFuture;
    private transient CompletableFuture<Void> providerOperationFuture;
    private transient ProviderFutureKind providerFutureKind = ProviderFutureKind.NONE;
    private transient SupplyBufferDatabase.Operation providerOperation;
    private transient boolean remoteProviderOnline;
    private transient int tickCounter;
    private transient long lastErrorLogMillis;

    private FluidTank[] createFluidTanks() {
        FluidTank[] result = new FluidTank[REQUEST_FILTER_COUNT];
        for (int index = 0; index < result.length; index++) {
            result[index] = new FluidTank(FLUID_CAPACITY_PER_FILTER) {
                @Override
                protected void onContentsChanged() {
                    markDirty();
                }
            };
        }
        return result;
    }

    public SupplyBufferBlockEntity(BlockPos pos, BlockState state) {
        super(SupplyBufferRegistry.SUPPLY_BUFFER_BLOCK_ENTITY.get(), pos, state);
        Arrays.fill(itemFilterPayloads, "");
        Arrays.fill(fluidFilterPayloads, "");
    }

    public static void serverTick(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            SupplyBufferBlockEntity blockEntity
    ) {
        blockEntity.serverTick();
    }

    private void serverTick() {
        tickCounter++;
        ensureGridNodeForRole();

        if (role == SupplyBufferRole.REMOTE) {
            compactVisibleSupplySlots();
        }

        if (role == SupplyBufferRole.PROVIDER) {
            tickProvider();
        } else if (role == SupplyBufferRole.REMOTE) {
            tickRemote();
        }
    }

    private void tickProvider() {
        if (linkId.isBlank() || !SupplyBufferService.clusterEnabled()) {
            return;
        }

        processProviderHeartbeatFuture();
        if (providerHeartbeatFuture == null
                && tickCounter % PROVIDER_HEARTBEAT_INTERVAL_TICKS == 0
                && level != null) {
            providerHeartbeatFuture = SupplyBufferService.touchProvider(
                    linkId,
                    level.dimension().location().toString(),
                    worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ(),
                    isProviderAeOnline()
            );
        }

        if (processProviderOperationFuture()) {
            return;
        }

        if (providerJournal != null) {
            providerFutureKind = ProviderFutureKind.MARK_APPLIED;
            providerOperationFuture = SupplyBufferService.markApplied(
                    providerJournal.operationId(),
                    providerJournal.deliveredAmount()
            );
            return;
        }

        if (providerOperation != null) {
            processClaimedProviderOperation(providerOperation);
            return;
        }

        if (providerClaimFuture != null) {
            if (!providerClaimFuture.isDone()) {
                return;
            }
            try {
                providerOperation = providerClaimFuture.join();
            } catch (CompletionException exception) {
                logAsyncError("claim provider operation", exception);
            } finally {
                providerClaimFuture = null;
            }
            if (providerOperation != null) {
                processClaimedProviderOperation(providerOperation);
            }
            return;
        }

        if (tickCounter % PROVIDER_POLL_INTERVAL_TICKS == 0) {
            providerClaimFuture = SupplyBufferService.claimNext(linkId);
        }
    }

    private void processClaimedProviderOperation(SupplyBufferDatabase.Operation operation) {
        AEKey key;
        try {
            key = SupplyKeyCodec.decode(operation.keyPayload());
            if (operation.resourceType() == SupplyBufferDatabase.ResourceType.ITEM
                    && !(key instanceof AEItemKey)) {
                throw new IllegalArgumentException("Operation says ITEM but payload is not an item key");
            }
            if (operation.resourceType() == SupplyBufferDatabase.ResourceType.FLUID
                    && !(key instanceof AEFluidKey)) {
                throw new IllegalArgumentException("Operation says FLUID but payload is not a fluid key");
            }
        } catch (RuntimeException exception) {
            providerFutureKind = ProviderFutureKind.MARK_FAILED;
            providerOperationFuture = SupplyBufferService.markFailed(
                    operation.operationId(),
                    "Invalid resource payload: " + exception.getMessage()
            );
            return;
        }

        MEStorage storage = getProviderStorage();
        if (storage == null) {
            providerFutureKind = ProviderFutureKind.RELEASE;
            providerOperationFuture = SupplyBufferService.releaseClaim(
                    operation.operationId(),
                    "Provider ME network is offline"
            );
            return;
        }

        long requested = Math.max(0L, operation.requestedAmount());
        if (requested <= 0L) {
            providerFutureKind = ProviderFutureKind.MARK_FAILED;
            providerOperationFuture = SupplyBufferService.markFailed(
                    operation.operationId(),
                    "Requested amount is zero"
            );
            return;
        }

        IActionSource source = IActionSource.ofMachine(this);
        long simulated;
        long delivered;

        try {
            if (operation.direction() == SupplyBufferDatabase.TransferDirection.REMOTE_TO_MAIN) {
                simulated = storage.insert(key, requested, Actionable.SIMULATE, source);
                if (simulated <= 0L) {
                    providerFutureKind = ProviderFutureKind.RELEASE;
                    providerOperationFuture = SupplyBufferService.releaseClaim(
                            operation.operationId(),
                            "Provider ME network cannot accept this resource yet"
                    );
                    return;
                }
                delivered = storage.insert(
                        key,
                        Math.min(requested, simulated),
                        Actionable.MODULATE,
                        source
                );
            } else {
                simulated = storage.extract(key, requested, Actionable.SIMULATE, source);
                if (simulated <= 0L) {
                    providerFutureKind = ProviderFutureKind.RELEASE;
                    providerOperationFuture = SupplyBufferService.releaseClaim(
                            operation.operationId(),
                            "Requested resource is not available in provider ME network"
                    );
                    return;
                }
                delivered = storage.extract(
                        key,
                        Math.min(requested, simulated),
                        Actionable.MODULATE,
                        source
                );
            }
        } catch (RuntimeException exception) {
            providerFutureKind = ProviderFutureKind.RELEASE;
            providerOperationFuture = SupplyBufferService.releaseClaim(
                    operation.operationId(),
                    "AE2 operation failed: " + exception.getMessage()
            );
            return;
        }

        if (delivered <= 0L) {
            providerFutureKind = ProviderFutureKind.RELEASE;
            providerOperationFuture = SupplyBufferService.releaseClaim(
                    operation.operationId(),
                    "AE2 returned zero after simulation"
            );
            return;
        }

        // Durable-ish local journal: if the SQL acknowledgement fails, the same block
        // retries only the database state change instead of touching AE2 a second time.
        providerJournal = new ProviderJournal(operation.operationId(), delivered);
        markDirty();
        providerFutureKind = ProviderFutureKind.MARK_APPLIED;
        providerOperationFuture = SupplyBufferService.markApplied(
                operation.operationId(),
                delivered
        );
    }

    private boolean processProviderOperationFuture() {
        if (providerOperationFuture == null) {
            return false;
        }
        if (!providerOperationFuture.isDone()) {
            return true;
        }

        ProviderFutureKind completedKind = providerFutureKind;
        try {
            providerOperationFuture.join();
            if (completedKind == ProviderFutureKind.MARK_APPLIED) {
                providerJournal = null;
                providerOperation = null;
                markDirty();
            } else if (completedKind == ProviderFutureKind.RELEASE
                    || completedKind == ProviderFutureKind.MARK_FAILED) {
                providerOperation = null;
            }
        } catch (CompletionException exception) {
            logAsyncError("finish provider operation", exception);
            // Keep the journal/operation and retry on a later tick.
        } finally {
            providerOperationFuture = null;
            providerFutureKind = ProviderFutureKind.NONE;
        }
        return true;
    }

    private void processProviderHeartbeatFuture() {
        if (providerHeartbeatFuture == null || !providerHeartbeatFuture.isDone()) {
            return;
        }
        try {
            providerHeartbeatFuture.join();
        } catch (CompletionException exception) {
            logAsyncError("provider heartbeat", exception);
        } finally {
            providerHeartbeatFuture = null;
        }
    }

    private void tickRemote() {
        processRemoteSyncFuture();

        if (linkId.isBlank() || !SupplyBufferService.clusterEnabled()) {
            remoteProviderOnline = false;
            return;
        }

        prepareRemotePendingTransfers();

        if (remoteSyncFuture == null && tickCounter % REMOTE_SYNC_INTERVAL_TICKS == 0) {
            List<SupplyBufferDatabase.PendingDescriptor> descriptors =
                    new ArrayList<>(1 + REQUEST_FILTER_COUNT * 2);
            addDescriptor(descriptors, pendingOutbound);
            for (PendingTransfer pending : pendingItemRequests) {
                addDescriptor(descriptors, pending);
            }
            for (PendingTransfer pending : pendingFluidRequests) {
                addDescriptor(descriptors, pending);
            }

            remoteSyncFuture = SupplyBufferService.syncRemote(
                    linkId,
                    descriptors,
                    List.copyOf(pendingAcknowledgements)
            );
        }
    }

    private void prepareRemotePendingTransfers() {
        if (role != SupplyBufferRole.REMOTE || linkId.isBlank()) {
            return;
        }

        if (pendingOutbound == null) {
            pendingOutbound = reserveOutboundItems();
            if (pendingOutbound != null) {
                markDirty();
            }
        }

        for (int filterIndex = 0; filterIndex < REQUEST_FILTER_COUNT; filterIndex++) {
            if (pendingItemRequests[filterIndex] == null) {
                pendingItemRequests[filterIndex] = createItemRequestIfNeeded(filterIndex);
                if (pendingItemRequests[filterIndex] != null) {
                    markDirty();
                }
            }
            if (pendingFluidRequests[filterIndex] == null) {
                pendingFluidRequests[filterIndex] = createFluidRequestIfNeeded(filterIndex);
                if (pendingFluidRequests[filterIndex] != null) {
                    markDirty();
                }
            }
        }
    }

    private PendingTransfer reserveOutboundItems() {
        ItemStack first = ItemStack.EMPTY;
        for (int slot = 0; slot < exportItems.getSlots(); slot++) {
            ItemStack stack = exportItems.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                first = stack.copy();
                first.setCount(1);
                break;
            }
        }
        if (first.isEmpty()) {
            return null;
        }

        AEItemKey key = AEItemKey.of(first);
        long reserved = 0L;
        for (int slot = 0; slot < exportItems.getSlots() && reserved < MAX_OUTBOUND_BATCH; slot++) {
            ItemStack stack = exportItems.getStackInSlot(slot);
            if (stack.isEmpty() || !key.matches(stack)) {
                continue;
            }

            int take = (int) Math.min((long) stack.getCount(), MAX_OUTBOUND_BATCH - reserved);
            exportItems.extractItem(slot, take, false);
            reserved += take;
        }

        if (reserved <= 0L) {
            return null;
        }

        return PendingTransfer.create(
                SupplyBufferDatabase.TransferDirection.REMOTE_TO_MAIN,
                SupplyBufferDatabase.ResourceType.ITEM,
                SupplyKeyCodec.encode(key),
                reserved
        );
    }

    private PendingTransfer createItemRequestIfNeeded(int filterIndex) {
        AEItemKey key = getConfiguredItemKey(filterIndex);
        if (key == null) {
            return null;
        }

        long current = countSupplyItems(filterIndex, key);
        long capacity = getConfiguredSupplyItemCapacity(filterIndex);
        long threshold = percentage(capacity, itemRefillBelowPercent);
        long target = percentage(capacity, itemRefillToPercent);
        if (current >= threshold || target <= current) {
            return null;
        }

        return PendingTransfer.create(
                SupplyBufferDatabase.TransferDirection.MAIN_TO_REMOTE,
                SupplyBufferDatabase.ResourceType.ITEM,
                SupplyKeyCodec.encode(key),
                target - current
        );
    }

    private PendingTransfer createFluidRequestIfNeeded(int filterIndex) {
        AEFluidKey key = getConfiguredFluidKey(filterIndex);
        if (key == null) {
            return null;
        }

        FluidTank tank = fluidTanks[filterIndex];
        FluidStack currentFluid = tank.getFluid();
        if (!currentFluid.isEmpty() && !key.matches(currentFluid)) {
            return null;
        }

        long current = tank.getFluidAmount();
        long threshold = percentage(tank.getCapacity(), fluidRefillBelowPercent);
        long target = percentage(tank.getCapacity(), fluidRefillToPercent);
        if (current >= threshold || target <= current) {
            return null;
        }

        return PendingTransfer.create(
                SupplyBufferDatabase.TransferDirection.MAIN_TO_REMOTE,
                SupplyBufferDatabase.ResourceType.FLUID,
                SupplyKeyCodec.encode(key),
                target - current
        );
    }

    private void processRemoteSyncFuture() {
        if (remoteSyncFuture == null || !remoteSyncFuture.isDone()) {
            return;
        }

        try {
            SupplyBufferDatabase.RemoteSyncResult syncResult = remoteSyncFuture.join();
            remoteProviderOnline = syncResult.providerOnline();
            for (UUID acknowledged : syncResult.acknowledged()) {
                pendingAcknowledgements.remove(acknowledged);
            }

            pendingOutbound = processOutboundResult(
                    pendingOutbound,
                    pendingOutbound == null ? null : syncResult.results().get(pendingOutbound.operationId())
            );
            for (int filterIndex = 0; filterIndex < REQUEST_FILTER_COUNT; filterIndex++) {
                PendingTransfer itemPending = pendingItemRequests[filterIndex];
                pendingItemRequests[filterIndex] = processItemRequestResult(
                        itemPending,
                        itemPending == null ? null : syncResult.results().get(itemPending.operationId())
                );

                PendingTransfer fluidPending = pendingFluidRequests[filterIndex];
                pendingFluidRequests[filterIndex] = processFluidRequestResult(
                        fluidPending,
                        fluidPending == null ? null : syncResult.results().get(fluidPending.operationId())
                );
            }
            markDirty();
        } catch (CompletionException exception) {
            remoteProviderOnline = false;
            logAsyncError("remote sync", exception);
        } finally {
            remoteSyncFuture = null;
        }
    }

    private PendingTransfer processOutboundResult(
            @Nullable PendingTransfer pending,
            @Nullable SupplyBufferDatabase.OperationResult result
    ) {
        if (pending == null || result == null) {
            return pending;
        }

        if (result.failed()) {
            restoreOutboundReservation(pending);
            pendingAcknowledgements.add(pending.operationId());
            return null;
        }
        if (!result.applied()) {
            return pending;
        }

        long delivered = Math.max(0L, Math.min(pending.amount(), result.deliveredAmount()));
        if (delivered <= 0L) {
            return pending;
        }

        pendingAcknowledgements.add(pending.operationId());
        long remainder = pending.amount() - delivered;
        if (remainder <= 0L) {
            return null;
        }

        return PendingTransfer.create(
                pending.direction(),
                pending.resourceType(),
                pending.keyPayload(),
                remainder
        );
    }

    private PendingTransfer processItemRequestResult(
            @Nullable PendingTransfer pending,
            @Nullable SupplyBufferDatabase.OperationResult result
    ) {
        if (pending == null || result == null) {
            return pending;
        }
        if (result.failed()) {
            pendingAcknowledgements.add(pending.operationId());
            return null;
        }
        if (!result.applied()) {
            return pending;
        }

        long delivered = Math.max(0L, Math.min(pending.amount(), result.deliveredAmount()));
        if (delivered <= 0L) {
            return pending;
        }

        AEKey decoded;
        try {
            decoded = SupplyKeyCodec.decode(pending.keyPayload());
        } catch (RuntimeException exception) {
            pendingAcknowledgements.add(pending.operationId());
            return null;
        }
        if (!(decoded instanceof AEItemKey key)) {
            pendingAcknowledgements.add(pending.operationId());
            return null;
        }

        int filterIndex = findItemFilterIndex(key);
        if (filterIndex < 0 || !canFitSupplyItems(filterIndex, key, delivered)) {
            // Do not ACK yet. The provider has already reserved/extracted the resource.
            return pending;
        }

        long inserted = insertSupplyItems(filterIndex, key, delivered);
        if (inserted != delivered) {
            LOGGER.error(
                    "Supply Buffer at {} simulated {} items fitting but inserted only {}",
                    worldPosition,
                    delivered,
                    inserted
            );
            return pending;
        }

        pendingAcknowledgements.add(pending.operationId());
        return null;
    }

    private PendingTransfer processFluidRequestResult(
            @Nullable PendingTransfer pending,
            @Nullable SupplyBufferDatabase.OperationResult result
    ) {
        if (pending == null || result == null) {
            return pending;
        }
        if (result.failed()) {
            pendingAcknowledgements.add(pending.operationId());
            return null;
        }
        if (!result.applied()) {
            return pending;
        }

        long deliveredLong = Math.max(0L, Math.min(pending.amount(), result.deliveredAmount()));
        if (deliveredLong <= 0L) {
            return pending;
        }
        int delivered = (int) Math.min(Integer.MAX_VALUE, deliveredLong);

        AEKey decoded;
        try {
            decoded = SupplyKeyCodec.decode(pending.keyPayload());
        } catch (RuntimeException exception) {
            pendingAcknowledgements.add(pending.operationId());
            return null;
        }
        if (!(decoded instanceof AEFluidKey key)) {
            pendingAcknowledgements.add(pending.operationId());
            return null;
        }

        int filterIndex = findFluidFilterIndex(key);
        if (filterIndex < 0) {
            return pending;
        }

        FluidStack stack = key.toStack(delivered);
        if (stack.isEmpty()) {
            pendingAcknowledgements.add(pending.operationId());
            return null;
        }

        FluidTank tank = fluidTanks[filterIndex];
        if (tank.fill(stack, IFluidHandler.FluidAction.SIMULATE) < delivered) {
            // External automation can only drain these tanks, so waiting is safe.
            return pending;
        }

        int filled = tank.fill(stack, IFluidHandler.FluidAction.EXECUTE);
        if (filled != delivered) {
            LOGGER.error(
                    "Supply Buffer at {} simulated {} mB fitting but filled only {} mB",
                    worldPosition,
                    delivered,
                    filled
            );
            return pending;
        }

        pendingAcknowledgements.add(pending.operationId());
        return null;
    }

    private void restoreOutboundReservation(PendingTransfer pending) {
        if (pending.resourceType() != SupplyBufferDatabase.ResourceType.ITEM || level == null) {
            return;
        }

        AEKey decoded;
        try {
            decoded = SupplyKeyCodec.decode(pending.keyPayload());
        } catch (RuntimeException exception) {
            return;
        }
        if (!(decoded instanceof AEItemKey key)) {
            return;
        }

        long remaining = pending.amount();
        while (remaining > 0L) {
            int partSize = (int) Math.min((long) key.getMaxStackSize(), remaining);
            ItemStack part = key.toStack(partSize);
            ItemStack leftover = ItemHandlerHelper.insertItemStacked(exportItems, part, false);
            if (!leftover.isEmpty()) {
                Containers.dropItemStack(
                        level,
                        worldPosition.getX() + 0.5D,
                        worldPosition.getY() + 0.75D,
                        worldPosition.getZ() + 0.5D,
                        leftover
                );
            }
            remaining -= partSize;
        }
    }

    private boolean canFitSupplyItems(int filterIndex, AEItemKey key, long amount) {
        if (!validFilterIndex(filterIndex)) {
            return false;
        }
        long remaining = amount;
        int start = supplyRegionStart(filterIndex);
        int end = start + SUPPLY_SLOTS_PER_FILTER;
        for (int slot = start; slot < end && remaining > 0L; slot++) {
            ItemStack current = supplyItems.getStackInSlot(slot);
            if (current.isEmpty()) {
                remaining -= Math.min((long) key.getMaxStackSize(), remaining);
            } else if (key.matches(current)) {
                int space = Math.max(0, Math.min(current.getMaxStackSize(), key.getMaxStackSize()) - current.getCount());
                remaining -= Math.min((long) space, remaining);
            }
        }
        return remaining <= 0L;
    }

    private long insertSupplyItems(int filterIndex, AEItemKey key, long amount) {
        if (!validFilterIndex(filterIndex)) {
            return 0L;
        }
        long remaining = amount;
        int start = supplyRegionStart(filterIndex);
        int end = start + SUPPLY_SLOTS_PER_FILTER;
        while (remaining > 0L) {
            int partSize = (int) Math.min((long) key.getMaxStackSize(), remaining);
            ItemStack remainder = key.toStack(partSize);
            for (int slot = start; slot < end && !remainder.isEmpty(); slot++) {
                remainder = supplyItems.insertItem(slot, remainder, false);
            }
            int inserted = partSize - (remainder.isEmpty() ? 0 : remainder.getCount());
            remaining -= inserted;
            if (inserted <= 0 || !remainder.isEmpty()) {
                break;
            }
        }
        return amount - remaining;
    }

    private long countSupplyItems(int filterIndex, AEItemKey key) {
        if (!validFilterIndex(filterIndex)) {
            return 0L;
        }
        long count = 0L;
        int start = supplyRegionStart(filterIndex);
        int end = start + SUPPLY_SLOTS_PER_FILTER;
        for (int slot = start; slot < end; slot++) {
            ItemStack stack = supplyItems.getStackInSlot(slot);
            if (!stack.isEmpty() && key.matches(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void compactVisibleSupplySlots() {
        for (int filterIndex = 0; filterIndex < REQUEST_FILTER_COUNT; filterIndex++) {
            int start = supplyRegionStart(filterIndex);
            if (!supplyItems.getStackInSlot(start).isEmpty()) {
                continue;
            }
            int end = start + SUPPLY_SLOTS_PER_FILTER;
            for (int slot = start + 1; slot < end; slot++) {
                ItemStack stack = supplyItems.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    supplyItems.setStackInSlot(start, stack.copy());
                    supplyItems.setStackInSlot(slot, ItemStack.EMPTY);
                    break;
                }
            }
        }
    }

    private static int filterIndexForSupplySlot(int slot) {
        return slot < 0 ? -1 : slot / SUPPLY_SLOTS_PER_FILTER;
    }

    public static int supplyRegionStart(int filterIndex) {
        return filterIndex * SUPPLY_SLOTS_PER_FILTER;
    }

    private static boolean validFilterIndex(int filterIndex) {
        return filterIndex >= 0 && filterIndex < REQUEST_FILTER_COUNT;
    }

    private void addDescriptor(
            List<SupplyBufferDatabase.PendingDescriptor> target,
            @Nullable PendingTransfer pending
    ) {
        if (pending == null) {
            return;
        }
        target.add(new SupplyBufferDatabase.PendingDescriptor(
                pending.operationId(),
                pending.direction(),
                pending.resourceType(),
                pending.keyPayload(),
                pending.amount()
        ));
    }

    private static long percentage(long capacity, int percent) {
        return Math.max(0L, capacity) * Math.max(0, Math.min(100, percent)) / 100L;
    }

    @Nullable
    private MEStorage getProviderStorage() {
        if (role != SupplyBufferRole.PROVIDER || !mainNode.isOnline()) {
            return null;
        }
        IGrid grid = mainNode.getGrid();
        return grid == null ? null : grid.getStorageService().getInventory();
    }

    public boolean isProviderAeOnline() {
        return role == SupplyBufferRole.PROVIDER && mainNode.isOnline() && mainNode.getGrid() != null;
    }

    public boolean isRemoteProviderOnline() {
        return role == SupplyBufferRole.REMOTE && remoteProviderOnline;
    }

    public boolean hasPendingTransfers() {
        return pendingOutbound != null
                || hasAnyPending(pendingItemRequests)
                || hasAnyPending(pendingFluidRequests)
                || providerJournal != null
                || providerOperation != null;
    }

    public int getPendingTransferCount() {
        int count = 0;
        if (pendingOutbound != null) count++;
        count += countPending(pendingItemRequests);
        count += countPending(pendingFluidRequests);
        if (providerJournal != null || providerOperation != null) count++;
        return count;
    }

    private static boolean hasAnyPending(PendingTransfer[] transfers) {
        for (PendingTransfer transfer : transfers) {
            if (transfer != null) {
                return true;
            }
        }
        return false;
    }

    private static int countPending(PendingTransfer[] transfers) {
        int count = 0;
        for (PendingTransfer transfer : transfers) {
            if (transfer != null) {
                count++;
            }
        }
        return count;
    }

    public ItemStackHandler getSupplyItems() {
        return supplyItems;
    }

    public ItemStackHandler getExportItems() {
        return exportItems;
    }

    public FluidTank getFluidTank(int filterIndex) {
        return validFilterIndex(filterIndex) ? fluidTanks[filterIndex] : fluidTanks[0];
    }

    public SupplyBufferRole getRole() {
        return role;
    }

    public String getLinkId() {
        return linkId == null ? "" : linkId;
    }

    public String getProviderNode() {
        return providerNode == null ? "" : providerNode;
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public String getOwnerName() {
        return ownerName == null ? "" : ownerName;
    }

    public int getItemRefillBelowPercent() {
        return itemRefillBelowPercent;
    }

    public int getItemRefillToPercent() {
        return itemRefillToPercent;
    }

    public int getFluidRefillBelowPercent() {
        return fluidRefillBelowPercent;
    }

    public int getFluidRefillToPercent() {
        return fluidRefillToPercent;
    }

    public long getConfiguredSupplyItemCount(int filterIndex) {
        AEItemKey key = getConfiguredItemKey(filterIndex);
        return key == null ? 0L : countSupplyItems(filterIndex, key);
    }

    public long getConfiguredSupplyItemCapacity(int filterIndex) {
        AEItemKey key = getConfiguredItemKey(filterIndex);
        return key == null ? 0L : (long) SUPPLY_SLOTS_PER_FILTER * Math.max(1, key.getMaxStackSize());
    }

    public long getConfiguredSupplyItemCount() {
        long total = 0L;
        for (int index = 0; index < REQUEST_FILTER_COUNT; index++) {
            total += getConfiguredSupplyItemCount(index);
        }
        return total;
    }

    public long getConfiguredSupplyItemCapacity() {
        long total = 0L;
        for (int index = 0; index < REQUEST_FILTER_COUNT; index++) {
            total += getConfiguredSupplyItemCapacity(index);
        }
        return total;
    }

    @Nullable
    public AEItemKey getConfiguredItemKey(int filterIndex) {
        if (!validFilterIndex(filterIndex)) {
            return null;
        }
        String payload = itemFilterPayloads[filterIndex];
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            AEKey key = SupplyKeyCodec.decode(payload);
            return key instanceof AEItemKey itemKey ? itemKey : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    public AEFluidKey getConfiguredFluidKey(int filterIndex) {
        if (!validFilterIndex(filterIndex)) {
            return null;
        }
        String payload = fluidFilterPayloads[filterIndex];
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            AEKey key = SupplyKeyCodec.decode(payload);
            return key instanceof AEFluidKey fluidKey ? fluidKey : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public ItemStack getConfiguredItemStack(int filterIndex) {
        AEItemKey key = getConfiguredItemKey(filterIndex);
        return key == null ? ItemStack.EMPTY : key.toStack(1);
    }

    public FluidStack getConfiguredFluidStack(int filterIndex) {
        AEFluidKey key = getConfiguredFluidKey(filterIndex);
        return key == null ? FluidStack.EMPTY : key.toStack(1);
    }

    private int findItemFilterIndex(AEItemKey key) {
        for (int index = 0; index < REQUEST_FILTER_COUNT; index++) {
            AEItemKey configured = getConfiguredItemKey(index);
            if (configured != null && configured.equals(key)) {
                return index;
            }
        }
        return -1;
    }

    private int findFluidFilterIndex(AEFluidKey key) {
        for (int index = 0; index < REQUEST_FILTER_COUNT; index++) {
            AEFluidKey configured = getConfiguredFluidKey(index);
            if (configured != null && configured.equals(key)) {
                return index;
            }
        }
        return -1;
    }

    public boolean setFilterPayload(
            SupplyBufferDatabase.ResourceType resourceType,
            int filterIndex,
            String payload,
            Player player
    ) {
        if (!canEdit(player) || role != SupplyBufferRole.REMOTE || !validFilterIndex(filterIndex)) {
            return false;
        }

        String normalized = payload == null ? "" : payload.trim();
        AEKey decoded = null;
        if (!normalized.isBlank()) {
            try {
                decoded = SupplyKeyCodec.decode(normalized);
            } catch (RuntimeException exception) {
                return false;
            }
        }

        if (resourceType == SupplyBufferDatabase.ResourceType.ITEM) {
            if (decoded != null && !(decoded instanceof AEItemKey)) {
                return false;
            }
            if (pendingItemRequests[filterIndex] != null) {
                player.displayClientMessage(Component.literal("§eПодожди завершения запроса для этого предмета."), true);
                return false;
            }
            AEItemKey oldKey = getConfiguredItemKey(filterIndex);
            AEItemKey newKey = decoded instanceof AEItemKey itemKey ? itemKey : null;
            if (!sameKey(oldKey, newKey) && hasSupplyItems(filterIndex)) {
                player.displayClientMessage(Component.literal("§cСначала забери предметы из этого буфера."), true);
                return false;
            }
            if (newKey != null && isDuplicateItemFilter(filterIndex, newKey)) {
                player.displayClientMessage(Component.literal("§eЭтот предмет уже добавлен в другой фильтр."), true);
                return false;
            }
            itemFilterPayloads[filterIndex] = newKey == null ? "" : SupplyKeyCodec.encode(newKey);
        } else {
            if (decoded != null && !(decoded instanceof AEFluidKey)) {
                return false;
            }
            if (pendingFluidRequests[filterIndex] != null) {
                player.displayClientMessage(Component.literal("§eПодожди завершения запроса для этой жидкости."), true);
                return false;
            }
            AEFluidKey oldKey = getConfiguredFluidKey(filterIndex);
            AEFluidKey newKey = decoded instanceof AEFluidKey fluidKey ? fluidKey : null;
            if (!sameKey(oldKey, newKey) && !fluidTanks[filterIndex].isEmpty()) {
                player.displayClientMessage(Component.literal("§cСначала опустоши этот бак Supply Buffer."), true);
                return false;
            }
            if (newKey != null && isDuplicateFluidFilter(filterIndex, newKey)) {
                player.displayClientMessage(Component.literal("§eЭта жидкость уже добавлена в другой фильтр."), true);
                return false;
            }
            fluidFilterPayloads[filterIndex] = newKey == null ? "" : SupplyKeyCodec.encode(newKey);
        }

        setChangedAndSync();
        return true;
    }

    private boolean hasSupplyItems(int filterIndex) {
        int start = supplyRegionStart(filterIndex);
        int end = start + SUPPLY_SLOTS_PER_FILTER;
        for (int slot = start; slot < end; slot++) {
            if (!supplyItems.getStackInSlot(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isDuplicateItemFilter(int exceptIndex, AEItemKey key) {
        for (int index = 0; index < REQUEST_FILTER_COUNT; index++) {
            if (index == exceptIndex) continue;
            AEItemKey configured = getConfiguredItemKey(index);
            if (configured != null && configured.equals(key)) return true;
        }
        return false;
    }

    private boolean isDuplicateFluidFilter(int exceptIndex, AEFluidKey key) {
        for (int index = 0; index < REQUEST_FILTER_COUNT; index++) {
            if (index == exceptIndex) continue;
            AEFluidKey configured = getConfiguredFluidKey(index);
            if (configured != null && configured.equals(key)) return true;
        }
        return false;
    }

    private static boolean sameKey(@Nullable AEKey first, @Nullable AEKey second) {
        return first == null ? second == null : first.equals(second);
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
        return player.hasPermissions(2)
                || (ownerUuid != null && ownerUuid.equals(player.getUUID()));
    }

    public void handleLinkCard(ServerPlayer player, ItemStack card, boolean sneaking) {
        if (!canEdit(player)) {
            player.displayClientMessage(Component.literal("§cТолько владелец или OP может менять связь Supply Buffer."), true);
            return;
        }
        if (!(card.getItem() instanceof SupplyLinkCardItem)) {
            return;
        }

        String cardLink = SupplyLinkCardItem.getLinkId(card);
        String cardProvider = SupplyLinkCardItem.getProviderNode(card);

        if (cardLink.isBlank()) {
            if (role == SupplyBufferRole.UNLINKED || linkId.isBlank()) {
                String node = SupplyBufferService.currentNodeId();
                if (node.isBlank()) {
                    player.displayClientMessage(Component.literal(
                            "§cСначала настрой и включи cointcoregto-cluster.properties."
                    ), true);
                    return;
                }

                role = SupplyBufferRole.PROVIDER;
                linkId = UUID.randomUUID().toString();
                providerNode = node;
                SupplyLinkCardItem.bind(card, linkId, providerNode);
                ensureGridNodeForRole();
                setChangedAndSync();
                player.displayClientMessage(Component.literal(
                        "§aSupply Provider создан. Карта привязана: §b" + shortLinkId(linkId)
                ), true);
                return;
            }

            SupplyLinkCardItem.bind(card, linkId, providerNode);
            player.displayClientMessage(Component.literal(
                    "§aСвязь скопирована на карту: §b" + shortLinkId(linkId)
            ), true);
            return;
        }

        if (role != SupplyBufferRole.UNLINKED && !linkId.isBlank()) {
            if (linkId.equals(cardLink)) {
                player.displayClientMessage(Component.literal(
                        "§eЭтот Supply Buffer уже использует эту связь."
                ), true);
            } else {
                player.displayClientMessage(Component.literal(
                        "§cБлок уже привязан. Сломай и поставь его заново, чтобы изменить связь."
                ), true);
            }
            return;
        }

        if (sneaking) {
            String node = SupplyBufferService.currentNodeId();
            if (node.isBlank()) {
                player.displayClientMessage(Component.literal(
                        "§cКластер не настроен на этой ноде."
                ), true);
                return;
            }
            role = SupplyBufferRole.PROVIDER;
            linkId = cardLink;
            providerNode = node;
            SupplyLinkCardItem.bind(card, linkId, providerNode);
            player.displayClientMessage(Component.literal(
                    "§aSupply Provider восстановлен из карты: §b" + shortLinkId(linkId)
            ), true);
        } else {
            role = SupplyBufferRole.REMOTE;
            linkId = cardLink;
            providerNode = cardProvider;
            player.displayClientMessage(Component.literal(
                    "§aRemote Supply Buffer подключён: §b" + shortLinkId(linkId)
            ), true);
        }

        ensureGridNodeForRole();
        setChangedAndSync();
    }

    public void cycleSetting(int action) {
        switch (action) {
            case 0 -> itemRefillBelowPercent = nextThreshold(itemRefillBelowPercent, itemRefillToPercent);
            case 1 -> itemRefillToPercent = nextTarget(itemRefillToPercent, itemRefillBelowPercent);
            case 2 -> fluidRefillBelowPercent = nextThreshold(fluidRefillBelowPercent, fluidRefillToPercent);
            case 3 -> fluidRefillToPercent = nextTarget(fluidRefillToPercent, fluidRefillBelowPercent);
            default -> {
                return;
            }
        }
        markDirty();
    }

    private static int nextThreshold(int current, int target) {
        int start = indexOfOrBefore(THRESHOLD_OPTIONS, current);
        for (int offset = 1; offset <= THRESHOLD_OPTIONS.length; offset++) {
            int candidate = THRESHOLD_OPTIONS[(start + offset) % THRESHOLD_OPTIONS.length];
            if (candidate < target) {
                return candidate;
            }
        }
        return Math.max(1, target - 1);
    }

    private static int nextTarget(int current, int threshold) {
        int start = indexOfOrBefore(TARGET_OPTIONS, current);
        for (int offset = 1; offset <= TARGET_OPTIONS.length; offset++) {
            int candidate = TARGET_OPTIONS[(start + offset) % TARGET_OPTIONS.length];
            if (candidate > threshold) {
                return candidate;
            }
        }
        return 100;
    }

    private static int indexOfOrBefore(int[] values, int value) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == value) {
                return index;
            }
        }
        return 0;
    }

    private static String shortLinkId(String value) {
        if (value == null || value.length() <= 8) {
            return value == null ? "" : value;
        }
        return value.substring(0, 8);
    }

    public void dropRealContents() {
        if (level == null || level.isClientSide) {
            return;
        }

        SimpleContainer container = new SimpleContainer(supplyItems.getSlots() + exportItems.getSlots());
        int index = 0;
        for (int slot = 0; slot < supplyItems.getSlots(); slot++) {
            container.setItem(index++, supplyItems.getStackInSlot(slot));
            supplyItems.setStackInSlot(slot, ItemStack.EMPTY);
        }
        for (int slot = 0; slot < exportItems.getSlots(); slot++) {
            container.setItem(index++, exportItems.getStackInSlot(slot));
            exportItems.setStackInSlot(slot, ItemStack.EMPTY);
        }
        Containers.dropContents(level, worldPosition, container);
    }

    private void ensureGridNodeForRole() {
        if (level == null || level.isClientSide) {
            return;
        }

        if (role == SupplyBufferRole.PROVIDER) {
            if (!mainNode.isReady()) {
                mainNode.create(level, worldPosition);
            }
        } else if (mainNode.isReady()) {
            mainNode.destroy();
        }
    }

    private void markDirty() {
        setChanged();
    }

    private void setChangedAndSync() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private void logAsyncError(String action, Throwable throwable) {
        long now = System.currentTimeMillis();
        if (now - lastErrorLogMillis < 10_000L) {
            return;
        }
        lastErrorLogMillis = now;
        Throwable root = SupplyBufferService.unwrap(throwable);
        LOGGER.warn(
                "Supply Buffer {} failed at {}: {}",
                action,
                worldPosition,
                root == null ? "unknown error" : root.getMessage()
        );
    }

    @Override
    public Component getDisplayName() {
        return Component.literal("Межсерверный буфер снабжения");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowId, Inventory inventory, Player player) {
        return new SupplyBufferMenu(windowId, inventory, this, canEdit(player));
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            ensureGridNodeForRole();
        }
    }

    @Override
    public void setRemoved() {
        if (mainNode.isReady()) {
            mainNode.destroy();
        }
        super.setRemoved();
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        exportInputCapability.invalidate();
        supplyOutputCapability.invalidate();
        fluidOutputCapability.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        exportInputCapability = LazyOptional.of(() -> exportInputView);
        supplyOutputCapability = LazyOptional.of(() -> supplyOutputView);
        fluidOutputCapability = LazyOptional.of(() -> fluidOutputView);
    }

    @Override
    public <T> @NotNull LazyOptional<T> getCapability(
            @NotNull Capability<T> cap,
            @Nullable Direction side
    ) {
        if (role == SupplyBufferRole.REMOTE) {
            if (cap == ForgeCapabilities.ITEM_HANDLER) {
                if (side == Direction.DOWN) {
                    return exportInputCapability.cast();
                }
                if (side == null || side.getAxis().isHorizontal()) {
                    return supplyOutputCapability.cast();
                }
            }
            if (cap == ForgeCapabilities.FLUID_HANDLER
                    && (side == Direction.UP || side == null)) {
                return fluidOutputCapability.cast();
            }
        }
        return super.getCapability(cap, side);
    }

    @Override
    public IGridNode getGridNode(Direction direction) {
        return role == SupplyBufferRole.PROVIDER ? mainNode.getNode() : null;
    }

    @Override
    public IGridNode getActionableNode() {
        return role == SupplyBufferRole.PROVIDER ? mainNode.getNode() : null;
    }

    @Override
    public AECableType getCableConnectionType(Direction direction) {
        return role == SupplyBufferRole.PROVIDER ? AECableType.SMART : AECableType.NONE;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString("Role", role.name());
        tag.putString("LinkId", getLinkId());
        tag.putString("ProviderNode", getProviderNode());
        if (ownerUuid != null) {
            tag.putUUID("OwnerUuid", ownerUuid);
        }
        tag.putString("OwnerName", getOwnerName());
        tag.putInt("ItemRefillBelow", itemRefillBelowPercent);
        tag.putInt("ItemRefillTo", itemRefillToPercent);
        tag.putInt("FluidRefillBelow", fluidRefillBelowPercent);
        tag.putInt("FluidRefillTo", fluidRefillToPercent);
        tag.put("ItemFilters", writeFilterPayloads(itemFilterPayloads));
        tag.put("FluidFilters", writeFilterPayloads(fluidFilterPayloads));
        tag.put("SupplyItems", supplyItems.serializeNBT());
        tag.put("ExportItems", exportItems.serializeNBT());
        ListTag fluidTankTags = new ListTag();
        for (int index = 0; index < REQUEST_FILTER_COUNT; index++) {
            CompoundTag tankEntry = new CompoundTag();
            tankEntry.putInt("Index", index);
            tankEntry.put("Tank", fluidTanks[index].writeToNBT(new CompoundTag()));
            fluidTankTags.add(tankEntry);
        }
        tag.put("FluidTanks", fluidTankTags);
        mainNode.saveToNBT(tag);

        putPending(tag, "PendingOutbound", pendingOutbound);
        tag.put("PendingItemRequests", writePendingArray(pendingItemRequests));
        tag.put("PendingFluidRequests", writePendingArray(pendingFluidRequests));
        if (providerJournal != null) {
            CompoundTag journalTag = new CompoundTag();
            journalTag.putUUID("OperationId", providerJournal.operationId());
            journalTag.putLong("DeliveredAmount", providerJournal.deliveredAmount());
            tag.put("ProviderJournal", journalTag);
        }

        ListTag acknowledgements = new ListTag();
        for (UUID operationId : pendingAcknowledgements) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("OperationId", operationId);
            acknowledgements.add(entry);
        }
        tag.put("PendingAcknowledgements", acknowledgements);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        role = SupplyBufferRole.fromName(tag.getString("Role"));
        linkId = tag.getString("LinkId");
        providerNode = tag.getString("ProviderNode");
        ownerUuid = tag.hasUUID("OwnerUuid") ? tag.getUUID("OwnerUuid") : null;
        ownerName = tag.getString("OwnerName");
        itemRefillBelowPercent = sanitizePercent(tag.getInt("ItemRefillBelow"), 50);
        itemRefillToPercent = sanitizePercent(tag.getInt("ItemRefillTo"), 100);
        fluidRefillBelowPercent = sanitizePercent(tag.getInt("FluidRefillBelow"), 50);
        fluidRefillToPercent = sanitizePercent(tag.getInt("FluidRefillTo"), 100);
        if (itemRefillBelowPercent >= itemRefillToPercent) itemRefillBelowPercent = 50;
        if (fluidRefillBelowPercent >= fluidRefillToPercent) fluidRefillBelowPercent = 50;

        Arrays.fill(itemFilterPayloads, "");
        Arrays.fill(fluidFilterPayloads, "");
        if (tag.contains("ItemFilters", Tag.TAG_LIST)) {
            readFilterPayloads(tag.getList("ItemFilters", Tag.TAG_COMPOUND), itemFilterPayloads);
        }
        if (tag.contains("FluidFilters", Tag.TAG_LIST)) {
            readFilterPayloads(tag.getList("FluidFilters", Tag.TAG_COMPOUND), fluidFilterPayloads);
        }
        if (!tag.contains("ItemFilters", Tag.TAG_LIST) && tag.contains("ConfigTemplates", Tag.TAG_COMPOUND)) {
            migrateLegacyConfigTemplates(tag.getCompound("ConfigTemplates"));
        }

        if (tag.contains("SupplyItems", Tag.TAG_COMPOUND)) {
            loadSupplyItems(tag.getCompound("SupplyItems"));
        }
        if (tag.contains("ExportItems", Tag.TAG_COMPOUND)) {
            exportItems.deserializeNBT(tag.getCompound("ExportItems"));
        }

        for (FluidTank tank : fluidTanks) {
            tank.setFluid(FluidStack.EMPTY);
        }
        if (tag.contains("FluidTanks", Tag.TAG_LIST)) {
            ListTag tanks = tag.getList("FluidTanks", Tag.TAG_COMPOUND);
            for (Tag tankTag : tanks) {
                if (tankTag instanceof CompoundTag entry) {
                    int index = entry.getInt("Index");
                    if (validFilterIndex(index) && entry.contains("Tank", Tag.TAG_COMPOUND)) {
                        fluidTanks[index].readFromNBT(entry.getCompound("Tank"));
                    }
                }
            }
        } else if (tag.contains("FluidTank", Tag.TAG_COMPOUND)) {
            fluidTanks[0].readFromNBT(tag.getCompound("FluidTank"));
        }
        mainNode.loadFromNBT(tag);

        pendingOutbound = readPending(tag, "PendingOutbound");
        Arrays.fill(pendingItemRequests, null);
        Arrays.fill(pendingFluidRequests, null);
        if (tag.contains("PendingItemRequests", Tag.TAG_LIST)) {
            readPendingArray(tag.getList("PendingItemRequests", Tag.TAG_COMPOUND), pendingItemRequests);
        } else {
            assignLegacyPending(readPending(tag, "PendingItemRequest"), pendingItemRequests);
        }
        if (tag.contains("PendingFluidRequests", Tag.TAG_LIST)) {
            readPendingArray(tag.getList("PendingFluidRequests", Tag.TAG_COMPOUND), pendingFluidRequests);
        } else {
            assignLegacyPending(readPending(tag, "PendingFluidRequest"), pendingFluidRequests);
        }
        providerJournal = null;
        if (tag.contains("ProviderJournal", Tag.TAG_COMPOUND)) {
            CompoundTag journalTag = tag.getCompound("ProviderJournal");
            if (journalTag.hasUUID("OperationId")) {
                providerJournal = new ProviderJournal(
                        journalTag.getUUID("OperationId"),
                        Math.max(0L, journalTag.getLong("DeliveredAmount"))
                );
            }
        }

        pendingAcknowledgements.clear();
        if (tag.contains("PendingAcknowledgements", Tag.TAG_LIST)) {
            ListTag acknowledgements = tag.getList("PendingAcknowledgements", Tag.TAG_COMPOUND);
            for (Tag entryTag : acknowledgements) {
                if (entryTag instanceof CompoundTag entry && entry.hasUUID("OperationId")) {
                    pendingAcknowledgements.add(entry.getUUID("OperationId"));
                }
            }
        }
    }

    private static ListTag writeFilterPayloads(String[] payloads) {
        ListTag list = new ListTag();
        for (int index = 0; index < payloads.length; index++) {
            String payload = payloads[index];
            if (payload == null || payload.isBlank()) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt("Index", index);
            entry.putString("Payload", payload);
            list.add(entry);
        }
        return list;
    }

    private static void readFilterPayloads(ListTag list, String[] target) {
        for (Tag entryTag : list) {
            if (!(entryTag instanceof CompoundTag entry)) {
                continue;
            }
            int index = entry.getInt("Index");
            if (index >= 0 && index < target.length) {
                target[index] = entry.getString("Payload");
            }
        }
    }

    private void migrateLegacyConfigTemplates(CompoundTag legacyTag) {
        ItemStackHandler legacy = new ItemStackHandler(2);
        legacy.deserializeNBT(legacyTag);

        ItemStack itemTemplate = legacy.getStackInSlot(0);
        if (!itemTemplate.isEmpty()) {
            itemFilterPayloads[0] = SupplyKeyCodec.encode(AEItemKey.of(itemTemplate));
        }

        ItemStack fluidTemplate = legacy.getStackInSlot(1);
        if (!fluidTemplate.isEmpty()) {
            FluidStack fluid = FluidUtil.getFluidContained(fluidTemplate).orElse(FluidStack.EMPTY);
            if (!fluid.isEmpty()) {
                fluidFilterPayloads[0] = SupplyKeyCodec.encode(AEFluidKey.of(fluid));
            }
        }
    }

    private void loadSupplyItems(CompoundTag supplyTag) {
        int serializedSize = supplyTag.getInt("Size");
        if (serializedSize == SUPPLY_SLOT_COUNT) {
            supplyItems.deserializeNBT(supplyTag);
            return;
        }

        ItemStackHandler legacy = new ItemStackHandler(Math.max(1, serializedSize));
        legacy.deserializeNBT(supplyTag);
        for (int slot = 0; slot < supplyItems.getSlots(); slot++) {
            supplyItems.setStackInSlot(slot, ItemStack.EMPTY);
        }
        int limit = Math.min(legacy.getSlots(), SUPPLY_SLOTS_PER_FILTER);
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = legacy.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                supplyItems.setStackInSlot(slot, stack.copy());
            }
        }
    }

    private static ListTag writePendingArray(PendingTransfer[] transfers) {
        ListTag list = new ListTag();
        for (int index = 0; index < transfers.length; index++) {
            PendingTransfer transfer = transfers[index];
            if (transfer == null) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putInt("Index", index);
            putPending(entry, "Transfer", transfer);
            list.add(entry);
        }
        return list;
    }

    private static void readPendingArray(ListTag list, PendingTransfer[] target) {
        for (Tag entryTag : list) {
            if (!(entryTag instanceof CompoundTag entry)) {
                continue;
            }
            int index = entry.getInt("Index");
            if (index < 0 || index >= target.length) {
                continue;
            }
            target[index] = readPending(entry, "Transfer");
        }
    }

    private void assignLegacyPending(@Nullable PendingTransfer pending, PendingTransfer[] target) {
        if (pending == null) {
            return;
        }
        try {
            AEKey key = SupplyKeyCodec.decode(pending.keyPayload());
            int index = key instanceof AEItemKey itemKey
                    ? findItemFilterIndex(itemKey)
                    : key instanceof AEFluidKey fluidKey
                    ? findFluidFilterIndex(fluidKey)
                    : -1;
            if (index >= 0 && index < target.length) {
                target[index] = pending;
            }
        } catch (RuntimeException ignored) {
        }
    }

    private static int sanitizePercent(int value, int fallback) {
        return value >= 1 && value <= 100 ? value : fallback;
    }

    private static void putPending(CompoundTag root, String name, @Nullable PendingTransfer pending) {
        if (pending == null) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putUUID("OperationId", pending.operationId());
        tag.putString("Direction", pending.direction().name());
        tag.putString("ResourceType", pending.resourceType().name());
        tag.putString("KeyPayload", pending.keyPayload());
        tag.putLong("Amount", pending.amount());
        root.put(name, tag);
    }

    @Nullable
    private static PendingTransfer readPending(CompoundTag root, String name) {
        if (!root.contains(name, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag tag = root.getCompound(name);
        if (!tag.hasUUID("OperationId")) {
            return null;
        }
        try {
            return new PendingTransfer(
                    tag.getUUID("OperationId"),
                    SupplyBufferDatabase.TransferDirection.valueOf(tag.getString("Direction")),
                    SupplyBufferDatabase.ResourceType.valueOf(tag.getString("ResourceType")),
                    tag.getString("KeyPayload"),
                    Math.max(0L, tag.getLong("Amount"))
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private enum ProviderFutureKind {
        NONE,
        MARK_APPLIED,
        RELEASE,
        MARK_FAILED
    }

    private record ProviderJournal(UUID operationId, long deliveredAmount) {
    }

    private record PendingTransfer(
            UUID operationId,
            SupplyBufferDatabase.TransferDirection direction,
            SupplyBufferDatabase.ResourceType resourceType,
            String keyPayload,
            long amount
    ) {
        private static PendingTransfer create(
                SupplyBufferDatabase.TransferDirection direction,
                SupplyBufferDatabase.ResourceType resourceType,
                String keyPayload,
                long amount
        ) {
            return new PendingTransfer(
                    UUID.randomUUID(),
                    direction,
                    resourceType,
                    keyPayload,
                    Math.max(1L, amount)
            );
        }
    }

    private static final class InsertOnlyItemHandler implements IItemHandler {
        private final IItemHandler delegate;

        private InsertOnlyItemHandler(IItemHandler delegate) {
            this.delegate = delegate;
        }

        @Override public int getSlots() { return delegate.getSlots(); }
        @Override public @NotNull ItemStack getStackInSlot(int slot) { return delegate.getStackInSlot(slot); }
        @Override public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) { return delegate.insertItem(slot, stack, simulate); }
        @Override public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }
        @Override public int getSlotLimit(int slot) { return delegate.getSlotLimit(slot); }
        @Override public boolean isItemValid(int slot, @NotNull ItemStack stack) { return delegate.isItemValid(slot, stack); }
    }

    private static final class ExtractOnlyItemHandler implements IItemHandler {
        private final IItemHandler delegate;

        private ExtractOnlyItemHandler(IItemHandler delegate) {
            this.delegate = delegate;
        }

        @Override public int getSlots() { return delegate.getSlots(); }
        @Override public @NotNull ItemStack getStackInSlot(int slot) { return delegate.getStackInSlot(slot); }
        @Override public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) { return stack; }
        @Override public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) { return delegate.extractItem(slot, amount, simulate); }
        @Override public int getSlotLimit(int slot) { return delegate.getSlotLimit(slot); }
        @Override public boolean isItemValid(int slot, @NotNull ItemStack stack) { return false; }
    }

    private static final class DrainOnlyFluidHandler implements IFluidHandler {
        private final FluidTank[] tanks;

        private DrainOnlyFluidHandler(FluidTank[] tanks) {
            this.tanks = tanks;
        }

        @Override public int getTanks() { return tanks.length; }

        @Override
        public @NotNull FluidStack getFluidInTank(int tank) {
            return tank >= 0 && tank < tanks.length ? tanks[tank].getFluid() : FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(int tank) {
            return tank >= 0 && tank < tanks.length ? tanks[tank].getCapacity() : 0;
        }

        @Override public boolean isFluidValid(int tank, @NotNull FluidStack stack) { return false; }
        @Override public int fill(FluidStack resource, FluidAction action) { return 0; }

        @Override
        public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
            if (resource == null || resource.isEmpty()) {
                return FluidStack.EMPTY;
            }
            for (FluidTank tank : tanks) {
                FluidStack stored = tank.getFluid();
                if (!stored.isEmpty() && stored.isFluidEqual(resource)) {
                    return tank.drain(resource, action);
                }
            }
            return FluidStack.EMPTY;
        }

        @Override
        public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
            if (maxDrain <= 0) {
                return FluidStack.EMPTY;
            }
            for (FluidTank tank : tanks) {
                if (!tank.isEmpty()) {
                    return tank.drain(maxDrain, action);
                }
            }
            return FluidStack.EMPTY;
        }
    }
}

