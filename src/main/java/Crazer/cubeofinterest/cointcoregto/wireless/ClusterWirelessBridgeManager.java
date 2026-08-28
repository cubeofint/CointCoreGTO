package Crazer.cubeofinterest.cointcoregto.wireless;

import Crazer.cubeofinterest.cointcoregto.supply.SupplyBufferBlockEntity;
import Crazer.cubeofinterest.cointcoregto.supply.SupplyBufferDatabase;
import Crazer.cubeofinterest.cointcoregto.supply.SupplyBufferRole;
import Crazer.cubeofinterest.cointcoregto.supply.SupplyBufferService;
import Crazer.cubeofinterest.cointcoregto.supply.SupplyKeyCodec;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.blockentity.grid.AENetworkBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Turns ExtendedAE's ME Wireless Connector into a cross-server proxy to the
 * cluster MAIN ME network without attempting to merge AE2 IGrid instances
 * across JVMs. Normal ExtendedAE/GTO wireless behaviour is left untouched.
 *
 * On a MAIN/general node the connector acts as the provider endpoint. On any
 * other cluster node it mounts an asynchronous MEStorage proxy into the local
 * grid. Inserts are durably staged in SavedData and sent to MAIN. Extracts are
 * requested asynchronously and become available in a local incoming cache.
 */
@Mod.EventBusSubscriber(modid = "cointcoregto", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClusterWirelessBridgeManager {
    public static final ClusterWirelessBridgeManager INSTANCE = new ClusterWirelessBridgeManager();

    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO-ClusterWirelessME");
    private static final Set<String> WIRELESS_CLASSES = Set.of(
            "com.glodblock.github.extendedae.common.tileentities.TileWirelessConnector",
            "com.glodblock.github.extendedae.common.tileentities.TileWirelessHub"
    );
    private static final String LINK_ID = "cointcoregto:cluster_wireless_main";
    private static final int STORAGE_PRIORITY = 1000;
    private static final int PROVIDER_HEARTBEAT_TICKS = 100;
    private static final int PROVIDER_CLAIM_TICKS = 5;
    private static final int REMOTE_SYNC_TICKS = 10;
    private static final int CATALOG_REFRESH_TICKS = 60;
    private static final int CATALOG_PUBLISH_TICKS = 100;
    private static final int MAIN_DISCOVERY_TICKS = 100;
    private static final int MAX_PENDING = 64;
    private static final long MAX_OPERATION_AMOUNT = 1_000_000_000_000L;
    private static final long MAX_STAGED_PER_KEY = 9_000_000_000_000_000L;

    private final Map<EndpointKey, RuntimeBridge> bridges = new LinkedHashMap<>();
    private final Set<EndpointKey> pendingDiscovery = new LinkedHashSet<>();
    private long tickCounter;

    private ClusterWirelessBridgeManager() {
    }

    private void handleChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            discover(level, blockEntity);
        }
    }

    private void handleChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        for (BlockPos pos : chunk.getBlockEntities().keySet()) {
            EndpointKey key = EndpointKey.of(level, pos);
            RuntimeBridge bridge = bridges.remove(key);
            if (bridge != null) {
                bridge.detach();
            }
        }
    }

    private void handleBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        pendingDiscovery.add(EndpointKey.of(level, event.getPos()));
    }

    private void handleBlockBroken(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(event.getPos());
        if (!isWirelessConnector(blockEntity)) {
            return;
        }

        EndpointKey key = EndpointKey.of(level, event.getPos());
        ClusterWirelessSavedData data = ClusterWirelessSavedData.get(level.getServer());
        ClusterWirelessSavedData.EndpointState state = data.endpoint(key.id());
        if (state.hasResources()) {
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(Component.literal(
                    "§cCluster Wireless ME занят передачей ресурсов. Дождись завершения операций перед разрушением."
            ), true);
            return;
        }

        RuntimeBridge bridge = bridges.remove(key);
        if (bridge != null) {
            bridge.detach();
        }
        data.removeEndpoint(key.id());
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        INSTANCE.handleChunkLoad(event);
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        INSTANCE.handleChunkUnload(event);
    }

    @SubscribeEvent
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        INSTANCE.handleBlockPlaced(event);
    }

    @SubscribeEvent
    public static void onBlockBroken(BlockEvent.BreakEvent event) {
        INSTANCE.handleBlockBroken(event);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        INSTANCE.tickFromServer(ServerLifecycleHooks.getCurrentServer());
    }

    public void tickFromServer(MinecraftServer server) {
        if (server == null) {
            return;
        }
        tick(server);
    }

    private void tick(MinecraftServer server) {
        tickCounter++;
        processPendingDiscovery(server);
        removeDeadBridges();
        electLeaders();

        ClusterWirelessSavedData data = ClusterWirelessSavedData.get(server);
        for (RuntimeBridge bridge : List.copyOf(bridges.values())) {
            bridge.tick(data, tickCounter);
        }
    }

    private void processPendingDiscovery(MinecraftServer server) {
        if (pendingDiscovery.isEmpty()) {
            return;
        }
        Iterator<EndpointKey> iterator = pendingDiscovery.iterator();
        while (iterator.hasNext()) {
            EndpointKey key = iterator.next();
            ServerLevel level = server.getLevel(key.dimension());
            if (level == null) {
                iterator.remove();
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(key.pos());
            if (blockEntity != null) {
                discover(level, blockEntity);
                iterator.remove();
            }
        }
    }

    private void discover(ServerLevel level, BlockEntity blockEntity) {
        if (!isWirelessConnector(blockEntity) || !(blockEntity instanceof AENetworkBlockEntity networked)) {
            return;
        }
        EndpointKey key = EndpointKey.of(level, blockEntity.getBlockPos());
        bridges.compute(key, (ignored, existing) -> {
            if (existing != null && existing.owner == networked) {
                return existing;
            }
            if (existing != null) {
                existing.detach();
            }
            return new RuntimeBridge(key, networked);
        });
    }

    private void removeDeadBridges() {
        Iterator<Map.Entry<EndpointKey, RuntimeBridge>> iterator = bridges.entrySet().iterator();
        while (iterator.hasNext()) {
            RuntimeBridge bridge = iterator.next().getValue();
            if (bridge.owner.isRemoved() || bridge.owner.getLevel() == null) {
                bridge.detach();
                iterator.remove();
            }
        }
    }

    private void electLeaders() {
        Map<IGrid, RuntimeBridge> leaders = new IdentityHashMap<>();
        for (RuntimeBridge bridge : bridges.values()) {
            IGrid grid = bridge.grid();
            if (grid == null) {
                continue;
            }
            RuntimeBridge current = leaders.get(grid);
            if (current == null || bridge.key.id().compareTo(current.key.id()) < 0) {
                leaders.put(grid, bridge);
            }
        }
        for (RuntimeBridge bridge : bridges.values()) {
            IGrid grid = bridge.grid();
            bridge.setLeader(grid != null && leaders.get(grid) == bridge);
        }
    }

    private static boolean isWirelessConnector(BlockEntity blockEntity) {
        if (blockEntity == null) {
            return false;
        }
        Class<?> type = blockEntity.getClass();
        while (type != null) {
            if (WIRELESS_CLASSES.contains(type.getName())) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static boolean isSupported(AEKey key) {
        return key instanceof AEItemKey || key instanceof AEFluidKey;
    }

    private static SupplyBufferDatabase.ResourceType resourceType(AEKey key) {
        return key instanceof AEFluidKey
                ? SupplyBufferDatabase.ResourceType.FLUID
                : SupplyBufferDatabase.ResourceType.ITEM;
    }

    private static long saturatingAdd(long first, long second) {
        if (second <= 0L) {
            return Math.max(0L, first);
        }
        if (first >= MAX_STAGED_PER_KEY - second) {
            return MAX_STAGED_PER_KEY;
        }
        return Math.max(0L, first) + second;
    }

    private static long positive(Map<String, Long> map, String key) {
        return Math.max(0L, map.getOrDefault(key, 0L));
    }

    private static void putPositive(Map<String, Long> map, String key, long amount) {
        if (amount <= 0L) {
            map.remove(key);
        } else {
            map.put(key, Math.min(MAX_STAGED_PER_KEY, amount));
        }
    }

    private static long reserveFor(IGrid grid, AEKey key) {
        long reserve = 0L;
        try {
            for (SupplyBufferBlockEntity buffer : grid.getMachines(SupplyBufferBlockEntity.class)) {
                if (buffer != null && buffer.getRole() == SupplyBufferRole.PROVIDER) {
                    reserve = Math.max(reserve, buffer.getClusterReserveAmount(key));
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.debug("Could not read MAIN reserve for {}: {}", key, exception.getMessage());
        }
        return reserve;
    }

    private final class RuntimeBridge {
        private final EndpointKey key;
        private final AENetworkBlockEntity owner;
        private final RemoteStorageProvider provider = new RemoteStorageProvider(this);
        private final Map<String, Long> catalog = new LinkedHashMap<>();
        private final Map<String, AEKey> decodedKeys = new HashMap<>();
        private final Map<String, Long> lastPublishedCatalog = new LinkedHashMap<>();

        private IGrid mountedGrid;
        private boolean leader;
        private boolean previousLeader;
        private boolean providerOnline;
        private String mainNode = "";
        private long catalogRevision;
        private boolean publishedInitialCatalog;

        private CompletableFuture<Void> heartbeatFuture;
        private CompletableFuture<SupplyBufferDatabase.Operation> claimFuture;
        private SupplyBufferDatabase.Operation claimedOperation;
        private CompletableFuture<Void> providerFuture;
        private ProviderFutureKind providerFutureKind = ProviderFutureKind.NONE;
        private CompletableFuture<SupplyBufferDatabase.RemoteSyncResult> syncFuture;
        private CompletableFuture<String> mainDiscoveryFuture;
        private CompletableFuture<SupplyBufferDatabase.WirelessCatalogDelta> catalogReadFuture;
        private CompletableFuture<Long> catalogWriteFuture;
        private Map<String, Long> catalogWriteSnapshot;

        private RuntimeBridge(EndpointKey key, AENetworkBlockEntity owner) {
            this.key = key;
            this.owner = owner;
        }

        private IGrid grid() {
            try {
                return owner.getMainNode().getGrid();
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        private void setLeader(boolean leader) {
            this.leader = leader;
        }

        private void tick(ClusterWirelessSavedData data, long tick) {
            if (!SupplyBufferService.clusterEnabled()) {
                detach();
                return;
            }

            IGrid grid = grid();
            if (grid == null || !owner.getMainNode().isOnline()) {
                detach();
                return;
            }

            boolean main = SupplyBufferService.isMainNode();
            if (!main) {
                attachRemote(grid);
            } else {
                detachRemoteOnly();
            }

            if (previousLeader != leader) {
                previousLeader = leader;
                try {
                    grid.getStorageService().invalidateCache();
                } catch (RuntimeException ignored) {
                }
            }
            if (!leader) {
                return;
            }

            if (main) {
                tickMain(data, grid, tick);
            } else {
                tickRemote(data, grid, tick);
            }
        }

        private void attachRemote(IGrid grid) {
            if (mountedGrid == grid) {
                return;
            }
            detachRemoteOnly();
            try {
                grid.getStorageService().addGlobalStorageProvider(provider);
                mountedGrid = grid;
                grid.getStorageService().invalidateCache();
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not mount Cluster Wireless ME at {}: {}", key.id(), exception.getMessage());
            }
        }

        private void detachRemoteOnly() {
            if (mountedGrid == null) {
                return;
            }
            try {
                mountedGrid.getStorageService().removeGlobalStorageProvider(provider);
                mountedGrid.getStorageService().invalidateCache();
            } catch (RuntimeException ignored) {
            }
            mountedGrid = null;
        }

        private void detach() {
            detachRemoteOnly();
            leader = false;
            previousLeader = false;
        }

        private void tickMain(ClusterWirelessSavedData data, IGrid grid, long tick) {
            processHeartbeatFuture();
            if (heartbeatFuture == null && (tick == 1L || tick % PROVIDER_HEARTBEAT_TICKS == 0L)) {
                heartbeatFuture = SupplyBufferService.touchProvider(
                        LINK_ID,
                        key.dimension().location().toString(),
                        key.pos().getX() + "," + key.pos().getY() + "," + key.pos().getZ(),
                        owner.getMainNode().isOnline()
                );
            }

            processProviderFuture(data);
            if (providerFuture != null) {
                return;
            }

            if (data.providerJournalOperationId() != null) {
                providerFutureKind = ProviderFutureKind.MARK_APPLIED;
                providerFuture = SupplyBufferService.markApplied(
                        data.providerJournalOperationId(),
                        data.providerJournalDelivered()
                );
                return;
            }

            if (claimedOperation != null) {
                processClaimedOperation(data, grid, claimedOperation);
                return;
            }

            if (claimFuture != null) {
                if (!claimFuture.isDone()) {
                    return;
                }
                try {
                    claimedOperation = claimFuture.join();
                } catch (CompletionException exception) {
                    logAsync("claim wireless operation", exception);
                } finally {
                    claimFuture = null;
                }
                if (claimedOperation != null) {
                    processClaimedOperation(data, grid, claimedOperation);
                }
                return;
            }

            if (tick % PROVIDER_CLAIM_TICKS == 0L) {
                claimFuture = SupplyBufferService.claimNext(LINK_ID);
            }

            processCatalogWriteFuture();
            if (catalogWriteFuture == null && tick % CATALOG_PUBLISH_TICKS == 0L) {
                publishCatalog(grid);
            }
        }

        private void processClaimedOperation(
                ClusterWirelessSavedData data,
                IGrid grid,
                SupplyBufferDatabase.Operation operation
        ) {
            AEKey resource;
            try {
                resource = SupplyKeyCodec.decode(operation.keyPayload());
                if (!isSupported(resource)) {
                    throw new IllegalArgumentException("unsupported AE key");
                }
            } catch (RuntimeException exception) {
                providerFutureKind = ProviderFutureKind.MARK_FAILED;
                providerFuture = SupplyBufferService.markFailed(operation.operationId(),
                        "Invalid Cluster Wireless resource: " + exception.getMessage());
                return;
            }

            MEStorage storage = grid.getStorageService().getInventory();
            long requested = Math.max(1L, operation.requestedAmount());
            IActionSource source = IActionSource.ofMachine(owner);
            long delivered;
            try {
                if (operation.direction() == SupplyBufferDatabase.TransferDirection.REMOTE_TO_MAIN) {
                    long accepted = storage.insert(resource, requested, Actionable.SIMULATE, source);
                    if (accepted <= 0L) {
                        release(operation, "MAIN ME cannot accept this resource yet");
                        return;
                    }
                    delivered = storage.insert(resource, Math.min(requested, accepted), Actionable.MODULATE, source);
                } else {
                    long reserve = reserveFor(grid, resource);
                    long probe = reserve > Long.MAX_VALUE - requested ? Long.MAX_VALUE : reserve + requested;
                    long available = storage.extract(resource, probe, Actionable.SIMULATE, source);
                    long aboveReserve = Math.max(0L, available - reserve);
                    if (aboveReserve <= 0L) {
                        release(operation, reserve > 0L
                                ? "MAIN reserve protects this resource (reserve=" + reserve + ")"
                                : "Resource is not available in MAIN ME");
                        return;
                    }
                    delivered = storage.extract(
                            resource,
                            Math.min(requested, aboveReserve),
                            Actionable.MODULATE,
                            source
                    );
                }
            } catch (RuntimeException exception) {
                release(operation, "MAIN AE2 operation failed: " + exception.getMessage());
                return;
            }

            if (delivered <= 0L) {
                release(operation, "MAIN AE2 returned zero after simulation");
                return;
            }

            data.setProviderJournal(operation.operationId(), delivered);
            providerFutureKind = ProviderFutureKind.MARK_APPLIED;
            providerFuture = SupplyBufferService.markApplied(operation.operationId(), delivered);
        }

        private void release(SupplyBufferDatabase.Operation operation, String reason) {
            providerFutureKind = ProviderFutureKind.RELEASE;
            providerFuture = SupplyBufferService.releaseClaim(operation.operationId(), reason);
        }

        private void processProviderFuture(ClusterWirelessSavedData data) {
            if (providerFuture == null || !providerFuture.isDone()) {
                return;
            }
            ProviderFutureKind completed = providerFutureKind;
            try {
                providerFuture.join();
                if (completed == ProviderFutureKind.MARK_APPLIED) {
                    data.clearProviderJournal();
                    claimedOperation = null;
                } else if (completed == ProviderFutureKind.RELEASE
                        || completed == ProviderFutureKind.MARK_FAILED) {
                    claimedOperation = null;
                }
            } catch (CompletionException exception) {
                logAsync("finish wireless provider operation", exception);
            } finally {
                providerFuture = null;
                providerFutureKind = ProviderFutureKind.NONE;
            }
        }

        private void processHeartbeatFuture() {
            if (heartbeatFuture == null || !heartbeatFuture.isDone()) {
                return;
            }
            try {
                heartbeatFuture.join();
            } catch (CompletionException exception) {
                logAsync("wireless provider heartbeat", exception);
            } finally {
                heartbeatFuture = null;
            }
        }

        private void publishCatalog(IGrid grid) {
            Map<String, Long> snapshot = buildCatalog(grid);
            List<SupplyBufferDatabase.WirelessCatalogEntry> changes = new ArrayList<>();
            if (!publishedInitialCatalog) {
                for (Map.Entry<String, Long> entry : snapshot.entrySet()) {
                    AEKey key = decode(entry.getKey());
                    if (key != null) {
                        changes.add(new SupplyBufferDatabase.WirelessCatalogEntry(
                                resourceType(key), entry.getKey(), entry.getValue()));
                    }
                }
                catalogWriteFuture = SupplyBufferService.replaceWirelessCatalog(
                        SupplyBufferService.currentNodeId(), changes);
            } else {
                Set<String> keys = new LinkedHashSet<>();
                keys.addAll(lastPublishedCatalog.keySet());
                keys.addAll(snapshot.keySet());
                for (String payload : keys) {
                    long oldAmount = positive(lastPublishedCatalog, payload);
                    long newAmount = positive(snapshot, payload);
                    if (oldAmount == newAmount) {
                        continue;
                    }
                    AEKey key = decode(payload);
                    if (key != null) {
                        changes.add(new SupplyBufferDatabase.WirelessCatalogEntry(
                                resourceType(key), payload, newAmount));
                    }
                }
                if (changes.isEmpty()) {
                    lastPublishedCatalog.clear();
                    lastPublishedCatalog.putAll(snapshot);
                    return;
                }
                catalogWriteFuture = SupplyBufferService.updateWirelessCatalog(
                        SupplyBufferService.currentNodeId(), changes);
            }
            catalogWriteSnapshot = snapshot;
        }

        private Map<String, Long> buildCatalog(IGrid grid) {
            Map<String, Long> result = new LinkedHashMap<>();
            KeyCounter available = grid.getStorageService().getCachedInventory();
            for (var entry : available) {
                AEKey resource = entry.getKey();
                if (!isSupported(resource)) {
                    continue;
                }
                long amount = Math.max(0L, entry.getLongValue());
                long reserve = reserveFor(grid, resource);
                long exposed = Math.max(0L, amount - reserve);
                if (exposed > 0L) {
                    result.put(SupplyKeyCodec.encode(resource), exposed);
                }
            }
            return result;
        }

        private void processCatalogWriteFuture() {
            if (catalogWriteFuture == null || !catalogWriteFuture.isDone()) {
                return;
            }
            try {
                catalogWriteFuture.join();
                if (catalogWriteSnapshot != null) {
                    lastPublishedCatalog.clear();
                    lastPublishedCatalog.putAll(catalogWriteSnapshot);
                }
                publishedInitialCatalog = true;
            } catch (CompletionException exception) {
                logAsync("publish MAIN wireless catalog", exception);
            } finally {
                catalogWriteFuture = null;
                catalogWriteSnapshot = null;
            }
        }

        private void tickRemote(ClusterWirelessSavedData data, IGrid grid, long tick) {
            ClusterWirelessSavedData.EndpointState state = data.endpoint(key.id());

            processMainDiscoveryFuture();
            if ((mainNode.isBlank() || tick % MAIN_DISCOVERY_TICKS == 0L) && mainDiscoveryFuture == null) {
                mainDiscoveryFuture = SupplyBufferService.findMainNode();
            }

            processCatalogReadFuture(grid);
            if (!mainNode.isBlank()
                    && catalogReadFuture == null
                    && tick % CATALOG_REFRESH_TICKS == 0L) {
                catalogReadFuture = SupplyBufferService.readWirelessCatalogDelta(mainNode, catalogRevision);
            }

            processRemoteSyncFuture(data, state, grid);
            createPendingTransfers(state, data);
            if (syncFuture == null
                    && tick % REMOTE_SYNC_TICKS == 0L
                    && (!state.pending().isEmpty()
                    || !state.acknowledgements().isEmpty()
                    || !state.outgoing().isEmpty()
                    || !state.wanted().isEmpty())) {
                List<SupplyBufferDatabase.PendingDescriptor> descriptors = state.pending().values().stream()
                        .map(ClusterWirelessSavedData.PendingTransfer::descriptor)
                        .toList();
                syncFuture = SupplyBufferService.syncRemote(LINK_ID, descriptors, state.acknowledgements());
            }
        }

        private void processMainDiscoveryFuture() {
            if (mainDiscoveryFuture == null || !mainDiscoveryFuture.isDone()) {
                return;
            }
            try {
                String resolved = mainDiscoveryFuture.join();
                if (resolved != null && !resolved.isBlank() && !resolved.equals(mainNode)) {
                    mainNode = resolved;
                    catalogRevision = 0L;
                    catalog.clear();
                    if (mountedGrid != null) {
                        mountedGrid.getStorageService().invalidateCache();
                    }
                }
            } catch (CompletionException exception) {
                logAsync("discover MAIN node for wireless ME", exception);
            } finally {
                mainDiscoveryFuture = null;
            }
        }

        private void processCatalogReadFuture(IGrid grid) {
            if (catalogReadFuture == null || !catalogReadFuture.isDone()) {
                return;
            }
            try {
                SupplyBufferDatabase.WirelessCatalogDelta delta = catalogReadFuture.join();
                if (catalogRevision == 0L) {
                    catalog.clear();
                }
                for (SupplyBufferDatabase.WirelessCatalogEntry entry : delta.entries()) {
                    putPositive(catalog, entry.keyPayload(), entry.amount());
                }
                catalogRevision = delta.revision();
                grid.getStorageService().invalidateCache();
            } catch (CompletionException exception) {
                logAsync("read MAIN wireless catalog", exception);
            } finally {
                catalogReadFuture = null;
            }
        }

        private void createPendingTransfers(
                ClusterWirelessSavedData.EndpointState state,
                ClusterWirelessSavedData data
        ) {
            if (state.pending().size() >= MAX_PENDING) {
                return;
            }
            Set<String> outgoingPending = new LinkedHashSet<>();
            Set<String> incomingPending = new LinkedHashSet<>();
            for (ClusterWirelessSavedData.PendingTransfer pending : state.pending().values()) {
                if (pending.direction() == SupplyBufferDatabase.TransferDirection.REMOTE_TO_MAIN) {
                    outgoingPending.add(pending.keyPayload());
                } else {
                    incomingPending.add(pending.keyPayload());
                }
            }

            for (Map.Entry<String, Long> entry : List.copyOf(state.outgoing().entrySet())) {
                if (state.pending().size() >= MAX_PENDING) {
                    break;
                }
                long amount = Math.max(0L, entry.getValue());
                if (amount <= 0L || outgoingPending.contains(entry.getKey())) {
                    continue;
                }
                AEKey key = decode(entry.getKey());
                if (key == null) {
                    state.outgoing().remove(entry.getKey());
                    data.setDirty();
                    continue;
                }
                ClusterWirelessSavedData.PendingTransfer transfer = new ClusterWirelessSavedData.PendingTransfer(
                        UUID.randomUUID(),
                        SupplyBufferDatabase.TransferDirection.REMOTE_TO_MAIN,
                        resourceType(key),
                        entry.getKey(),
                        Math.min(amount, MAX_OPERATION_AMOUNT),
                        0
                );
                state.pending().put(transfer.operationId(), transfer);
                outgoingPending.add(entry.getKey());
                data.setDirty();
            }

            for (Map.Entry<String, Long> entry : List.copyOf(state.wanted().entrySet())) {
                if (state.pending().size() >= MAX_PENDING) {
                    break;
                }
                long amount = Math.max(0L, entry.getValue());
                if (amount <= 0L || incomingPending.contains(entry.getKey())) {
                    continue;
                }
                AEKey key = decode(entry.getKey());
                if (key == null) {
                    state.wanted().remove(entry.getKey());
                    data.setDirty();
                    continue;
                }
                ClusterWirelessSavedData.PendingTransfer transfer = new ClusterWirelessSavedData.PendingTransfer(
                        UUID.randomUUID(),
                        SupplyBufferDatabase.TransferDirection.MAIN_TO_REMOTE,
                        resourceType(key),
                        entry.getKey(),
                        Math.min(amount, MAX_OPERATION_AMOUNT),
                        0
                );
                state.pending().put(transfer.operationId(), transfer);
                incomingPending.add(entry.getKey());
                data.setDirty();
            }
        }

        private void processRemoteSyncFuture(
                ClusterWirelessSavedData data,
                ClusterWirelessSavedData.EndpointState state,
                IGrid grid
        ) {
            if (syncFuture == null || !syncFuture.isDone()) {
                return;
            }
            try {
                SupplyBufferDatabase.RemoteSyncResult result = syncFuture.join();
                providerOnline = result.providerOnline();
                for (UUID acknowledged : result.acknowledged()) {
                    state.acknowledgements().remove(acknowledged);
                }
                for (Map.Entry<UUID, SupplyBufferDatabase.OperationResult> resultEntry : result.results().entrySet()) {
                    ClusterWirelessSavedData.PendingTransfer pending = state.pending().get(resultEntry.getKey());
                    if (pending == null) {
                        continue;
                    }
                    SupplyBufferDatabase.OperationResult operationResult = resultEntry.getValue();
                    if (operationResult.applied()) {
                        long delivered = Math.max(0L, operationResult.deliveredAmount());
                        if (pending.direction() == SupplyBufferDatabase.TransferDirection.REMOTE_TO_MAIN) {
                            long remaining = Math.max(0L, positive(state.outgoing(), pending.keyPayload()) - delivered);
                            putPositive(state.outgoing(), pending.keyPayload(), remaining);
                        } else {
                            long stored = positive(state.incoming(), pending.keyPayload());
                            putPositive(state.incoming(), pending.keyPayload(), saturatingAdd(stored, delivered));
                            long stillWanted = Math.max(0L, positive(state.wanted(), pending.keyPayload()) - delivered);
                            putPositive(state.wanted(), pending.keyPayload(), stillWanted);
                        }
                        state.pending().remove(pending.operationId());
                        state.acknowledgements().add(pending.operationId());
                        data.setDirty();
                        grid.getStorageService().invalidateCache();
                    } else if (operationResult.failed()) {
                        state.pending().remove(pending.operationId());
                        data.setDirty();
                    }
                }
            } catch (CompletionException exception) {
                providerOnline = false;
                logAsync("sync remote Cluster Wireless ME", exception);
            } finally {
                syncFuture = null;
            }
        }

        private AEKey decode(String payload) {
            if (payload == null || payload.isBlank()) {
                return null;
            }
            AEKey cached = decodedKeys.get(payload);
            if (cached != null) {
                return cached;
            }
            try {
                AEKey decoded = SupplyKeyCodec.decode(payload);
                if (isSupported(decoded)) {
                    decodedKeys.put(payload, decoded);
                    return decoded;
                }
            } catch (RuntimeException ignored) {
            }
            return null;
        }

        private long visibleAmount(String payload, ClusterWirelessSavedData.EndpointState state) {
            return saturatingAdd(positive(catalog, payload), positive(state.incoming(), payload));
        }

        private void logAsync(String action, Throwable throwable) {
            Throwable actual = SupplyBufferService.unwrap(throwable);
            LOGGER.warn("{} at {}: {}", action, key.id(), actual == null ? "unknown" : actual.getMessage());
        }
    }

    private static final class RemoteStorageProvider implements IStorageProvider {
        private final RuntimeBridge bridge;
        private final MEStorage storage;

        private RemoteStorageProvider(RuntimeBridge bridge) {
            this.bridge = bridge;
            this.storage = new RemoteStorage(bridge);
        }

        @Override
        public void mountInventories(IStorageMounts mounts) {
            mounts.mount(storage, STORAGE_PRIORITY);
        }
    }

    private static final class RemoteStorage implements MEStorage {
        private final RuntimeBridge bridge;

        private RemoteStorage(RuntimeBridge bridge) {
            this.bridge = bridge;
        }

        @Override
        public boolean isPreferredStorageFor(AEKey what, IActionSource source) {
            return bridge.leader && !SupplyBufferService.isMainNode() && isSupported(what);
        }

        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            MEStorage.checkPreconditions(what, amount, mode, source);
            if (!bridge.leader || SupplyBufferService.isMainNode() || !isSupported(what) || amount <= 0L) {
                return 0L;
            }
            String payload = SupplyKeyCodec.encode(what);
            ClusterWirelessSavedData data = savedData(bridge);
            if (data == null) {
                return 0L;
            }
            ClusterWirelessSavedData.EndpointState state = data.endpoint(bridge.key.id());
            long current = positive(state.outgoing(), payload);
            long accepted = Math.min(amount, Math.max(0L, MAX_STAGED_PER_KEY - current));
            if (accepted <= 0L) {
                return 0L;
            }
            if (mode == Actionable.MODULATE) {
                putPositive(state.outgoing(), payload, current + accepted);
                data.setDirty();
            }
            return accepted;
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            MEStorage.checkPreconditions(what, amount, mode, source);
            if (!bridge.leader || SupplyBufferService.isMainNode() || !isSupported(what) || amount <= 0L) {
                return 0L;
            }
            ClusterWirelessSavedData data = savedData(bridge);
            if (data == null) {
                return 0L;
            }
            String payload = SupplyKeyCodec.encode(what);
            ClusterWirelessSavedData.EndpointState state = data.endpoint(bridge.key.id());
            long incoming = positive(state.incoming(), payload);
            long catalog = positive(bridge.catalog, payload);
            long visible = saturatingAdd(incoming, catalog);
            if (mode == Actionable.SIMULATE) {
                return Math.min(amount, visible);
            }

            long extracted = Math.min(amount, incoming);
            if (extracted > 0L) {
                putPositive(state.incoming(), payload, incoming - extracted);
            }
            long missing = Math.max(0L, amount - extracted);
            if (missing > 0L && catalog > 0L) {
                long desired = Math.min(missing, catalog);
                long oldWanted = positive(state.wanted(), payload);
                if (desired > oldWanted) {
                    putPositive(state.wanted(), payload, desired);
                }
            }
            if (extracted > 0L || missing > 0L) {
                data.setDirty();
            }
            return extracted;
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            if (!bridge.leader || SupplyBufferService.isMainNode()) {
                return;
            }
            ClusterWirelessSavedData data = savedData(bridge);
            if (data == null) {
                return;
            }
            ClusterWirelessSavedData.EndpointState state = data.endpoint(bridge.key.id());
            Set<String> payloads = new LinkedHashSet<>();
            payloads.addAll(bridge.catalog.keySet());
            payloads.addAll(state.incoming().keySet());
            for (String payload : payloads) {
                AEKey key = bridge.decode(payload);
                if (key == null) {
                    continue;
                }
                long amount = bridge.visibleAmount(payload, state);
                if (amount > 0L) {
                    out.add(key, amount);
                }
            }
        }

        @Override
        public Component getDescription() {
            return Component.literal("MAIN ME (Cluster Wireless)");
        }

        private static ClusterWirelessSavedData savedData(RuntimeBridge bridge) {
            if (!(bridge.owner.getLevel() instanceof ServerLevel level)) {
                return null;
            }
            return ClusterWirelessSavedData.get(level.getServer());
        }
    }

    private enum ProviderFutureKind {
        NONE,
        MARK_APPLIED,
        RELEASE,
        MARK_FAILED
    }

    private record EndpointKey(
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            BlockPos pos
    ) {
        private static EndpointKey of(ServerLevel level, BlockPos pos) {
            return new EndpointKey(level.dimension(), pos.immutable());
        }

        private String id() {
            return dimension.location() + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }
    }
}
