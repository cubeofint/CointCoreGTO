package Crazer.cubeofinterest.cointcoregto.wireless;

import Crazer.cubeofinterest.cointcoregto.supply.SupplyBufferBlockEntity;
import Crazer.cubeofinterest.cointcoregto.supply.SupplyBufferDatabase;
import Crazer.cubeofinterest.cointcoregto.supply.SupplyBufferRole;
import Crazer.cubeofinterest.cointcoregto.supply.SupplyBufferService;
import Crazer.cubeofinterest.cointcoregto.supply.SupplyKeyCodec;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
 * Bridges GTOCore's native wireless ME registry between cluster nodes.
 *
 * The native GTO GUI is intentionally left untouched. MAIN publishes its real
 * WirelessNetwork objects as metadata, REMOTE mirrors lightweight synthetic
 * WirelessNetwork objects into the local WirelessNetworkSavedData pool, and
 * the ordinary GTO ME-part UI keeps using joinNetwork/leaveNetwork normally.
 *
 * A synthetic network never tries to connect AE2 grids across JVMs. For the
 * first safe transport implementation, native GTO ME Output Bus/Hatch buffers
 * on REMOTE are drained through the existing durable cluster operation layer
 * into the AE2 grid of the selected SOURCE network on MAIN.
 */
@Mod.EventBusSubscriber(modid = "cointcoregto", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GtoNativeWirelessBridgeManager {
    public static final GtoNativeWirelessBridgeManager INSTANCE = new GtoNativeWirelessBridgeManager();

    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO-GtoNativeWireless");
    private static final String LINK_PREFIX = "gto:";
    private static final String GTO_CHILD_ROLE = "gto-child";
    private static final int NETWORK_PUBLISH_TICKS = 60;
    private static final int NETWORK_READ_TICKS = 60;
    private static final int MAIN_DISCOVERY_TICKS = 100;
    private static final int PROVIDER_HEARTBEAT_TICKS = 100;
    private static final int PROVIDER_CLAIM_TICKS = 5;
    private static final int REMOTE_SYNC_TICKS = 10;
    private static final int REMOTE_PRESENCE_TICKS = 40;
    private static final int MAIN_REMOTE_CHILD_READ_TICKS = 40;
    private static final int MAIN_SUMMARY_PATCH_TICKS = 20;
    private static final int MAX_PENDING_PER_ENDPOINT = 32;
    private static final long MAX_OPERATION_AMOUNT = 1_000_000_000_000L;

    private final NativeApi api = new NativeApi();
    private final Map<EndpointKey, NativeEndpoint> endpoints = new LinkedHashMap<>();
    private final Map<EndpointKey, KnownWirelessMachine> wirelessMachines = new LinkedHashMap<>();
    private final Map<String, ProviderRuntime> providerRuntimes = new LinkedHashMap<>();

    private CompletableFuture<Void> publishFuture;
    private CompletableFuture<String> mainDiscoveryFuture;
    private CompletableFuture<List<SupplyBufferDatabase.GtoWirelessNetworkSnapshot>> networkReadFuture;
    private CompletableFuture<Map<String, Integer>> remoteChildCountFuture;
    private Map<String, Integer> remoteChildCounts = Map.of();
    private Map<String, SupplyBufferDatabase.GtoWirelessNetworkSnapshot> remoteNetworkSnapshots = Map.of();
    private String mainNode = "";
    private Set<String> lastPublishedNetworkIds = Set.of();
    private Set<String> lastRemoteNetworkIds = Set.of();
    private boolean publishedLogInitialized;
    private boolean remoteLogInitialized;
    private boolean wirelessMachineListDirty;
    private long tickCounter;

    private GtoNativeWirelessBridgeManager() {
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
        INSTANCE.tick(ServerLifecycleHooks.getCurrentServer());
    }

    private void handleChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            discover(level, entry.getKey(), entry.getValue());
        }
    }

    private void handleChunkUnload(ChunkEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        for (BlockPos pos : chunk.getBlockEntities().keySet()) {
            EndpointKey key = EndpointKey.of(level, pos);
            NativeEndpoint removed = endpoints.remove(key);
            if (removed != null) {
                removed.publishPresenceOffline();
            }
            wirelessMachines.remove(key);
        }
    }

    private void handleBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockEntity owner = level.getBlockEntity(event.getPos());
        if (owner != null) {
            discover(level, event.getPos(), owner);
        }
    }

    private void handleBlockBroken(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        EndpointKey key = EndpointKey.of(level, event.getPos());
        NativeEndpoint endpoint = endpoints.get(key);
        if (endpoint == null || !endpoint.isOutput()) {
            if (wirelessMachines.remove(key) != null) {
                wirelessMachineListDirty = true;
            }
            return;
        }

        ClusterWirelessSavedData data = ClusterWirelessSavedData.get(level.getServer());
        ClusterWirelessSavedData.EndpointState state = data.endpoint(endpoint.stateId());
        String connected = api.connectedNetworkId(endpoint.machine);
        boolean clusterConnected = connected != null && data.nativeMirrorIds().contains(connected);
        if (clusterConnected && (!state.pending().isEmpty() || endpoint.hasBufferedResources())) {
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(Component.literal(
                    "§cWireless ME ещё передаёт ресурсы в MAIN. Дождись завершения операций перед разрушением."
            ), true);
            return;
        }

        endpoint.publishPresenceOffline();
        endpoints.remove(key);
        if (wirelessMachines.remove(key) != null) {
            wirelessMachineListDirty = true;
        }
        if (state.isEmpty()) {
            data.removeEndpoint(endpoint.stateId());
        }
    }

    private void discover(ServerLevel level, BlockPos pos, BlockEntity owner) {
        Object machine = api.metaMachine(owner);
        if (!api.isWirelessMachine(machine)) {
            return;
        }

        EndpointKey key = EndpointKey.of(level, pos);
        KnownWirelessMachine known = wirelessMachines.get(key);
        if (known == null || known.machine != machine || known.owner != owner) {
            wirelessMachines.put(key, new KnownWirelessMachine(owner, machine));
            wirelessMachineListDirty = true;
        }

        EndpointType type = api.endpointType(machine);
        if (type != EndpointType.OUTPUT_BUS && type != EndpointType.OUTPUT_HATCH) {
            endpoints.remove(key);
            return;
        }
        NativeEndpoint existing = endpoints.get(key);
        if (existing == null || existing.machine != machine || existing.owner != owner) {
            endpoints.put(key, new NativeEndpoint(key, owner, machine, type));
            LOGGER.debug("Discovered native GTO wireless {} at {} on {}",
                    type, key.id(), SupplyBufferService.currentNodeId());
        }
    }

    private void tick(MinecraftServer server) {
        if (server == null || !SupplyBufferService.clusterEnabled() || !api.available()) {
            return;
        }
        tickCounter++;
        removeDeadEndpoints();

        ClusterWirelessSavedData data = ClusterWirelessSavedData.get(server);
        if (SupplyBufferService.isMainNode()) {
            tickMain(server, data);
        } else {
            tickRemote(server, data);
        }
    }

    private void removeDeadEndpoints() {
        endpoints.entrySet().removeIf(entry -> {
            NativeEndpoint endpoint = entry.getValue();
            boolean dead = endpoint.owner.isRemoved() || endpoint.owner.getLevel() == null;
            if (dead) {
                endpoint.publishPresenceOffline();
            }
            return dead;
        });
        boolean removedWireless = wirelessMachines.entrySet().removeIf(entry -> {
            KnownWirelessMachine known = entry.getValue();
            return known.owner.isRemoved() || known.owner.getLevel() == null;
        });
        if (removedWireless) {
            wirelessMachineListDirty = true;
        }
    }

    private void tickMain(MinecraftServer server, ClusterWirelessSavedData data) {
        // A node that was previously PERSONAL must not keep stale synthetic entries
        // after being promoted to MAIN.
        if (!data.nativeMirrorIds().isEmpty()) {
            removeAllMirrors(data);
        }

        processPublishFuture();
        processRemoteChildCountFuture();
        if (remoteChildCountFuture == null
                && (tickCounter == 1L || tickCounter % MAIN_REMOTE_CHILD_READ_TICKS == 0L)) {
            remoteChildCountFuture = SupplyBufferService.readGtoWirelessRemoteChildCounts();
        }
        if (tickCounter == 1L || tickCounter % MAIN_SUMMARY_PATCH_TICKS == 0L) {
            patchMainNetworkSummaries();
        }

        Map<String, Object> networks = api.networksById();
        syncProviderRuntimes(networks);
        for (ProviderRuntime runtime : List.copyOf(providerRuntimes.values())) {
            runtime.tick(data, tickCounter);
        }

        if (publishFuture == null && (tickCounter == 1L || tickCounter % NETWORK_PUBLISH_TICKS == 0L)) {
            List<SupplyBufferDatabase.GtoWirelessNetworkSnapshot> snapshots = api.snapshotNetworks(networks.values());
            Set<String> ids = snapshots.stream()
                    .map(SupplyBufferDatabase.GtoWirelessNetworkSnapshot::networkId)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!publishedLogInitialized || !ids.equals(lastPublishedNetworkIds)) {
                publishedLogInitialized = true;
                lastPublishedNetworkIds = Set.copyOf(ids);
                LOGGER.info("Publishing {} native GTO wireless network(s) from MAIN {}: {}",
                        ids.size(), SupplyBufferService.currentNodeId(), ids);
            }
            publishFuture = SupplyBufferService.replaceGtoWirelessNetworks(
                    SupplyBufferService.currentNodeId(), snapshots);
        }
    }

    private void processPublishFuture() {
        if (publishFuture == null || !publishFuture.isDone()) {
            return;
        }
        try {
            publishFuture.join();
        } catch (CompletionException exception) {
            logAsync("publish native GTO wireless networks", exception);
        } finally {
            publishFuture = null;
        }
    }

    private void processRemoteChildCountFuture() {
        if (remoteChildCountFuture == null || !remoteChildCountFuture.isDone()) {
            return;
        }
        try {
            Map<String, Integer> counts = remoteChildCountFuture.join();
            Map<String, Integer> normalized = counts == null ? Map.of() : Map.copyOf(counts);
            if (!normalized.equals(remoteChildCounts)) {
                remoteChildCounts = normalized;
                LOGGER.info("Node {} sees remote GTO wireless CHILD count(s): {}",
                        SupplyBufferService.currentNodeId(), remoteChildCounts);
                if (SupplyBufferService.isMainNode()) {
                    patchMainNetworkSummaries();
                } else {
                    patchRemoteNetworkSummaries();
                }
            }
        } catch (CompletionException exception) {
            logAsync("read remote GTO wireless CHILD counts", exception);
        } finally {
            remoteChildCountFuture = null;
        }
    }

    private void patchMainNetworkSummaries() {
        if (!SupplyBufferService.isMainNode()) {
            return;
        }
        Set<Object> machines = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (KnownWirelessMachine known : wirelessMachines.values()) {
            machines.add(known.machine);
        }
        for (NativeEndpoint endpoint : endpoints.values()) {
            machines.add(endpoint.machine);
        }
        for (Object machine : machines) {
            api.patchNetworkSummaryChildCounts(machine, remoteChildCounts);
        }
    }

    private void patchRemoteNetworkSummaries() {
        if (SupplyBufferService.isMainNode() || remoteNetworkSnapshots.isEmpty()) {
            return;
        }
        Set<Object> machines = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (KnownWirelessMachine known : wirelessMachines.values()) {
            machines.add(known.machine);
        }
        for (NativeEndpoint endpoint : endpoints.values()) {
            machines.add(endpoint.machine);
        }
        for (Object machine : machines) {
            api.patchRemoteMirrorSummaryCounts(machine, remoteNetworkSnapshots, remoteChildCounts);
        }
    }

    private void syncProviderRuntimes(Map<String, Object> networks) {
        providerRuntimes.keySet().removeIf(id -> !networks.containsKey(id));
        for (Map.Entry<String, Object> entry : networks.entrySet()) {
            providerRuntimes.computeIfAbsent(entry.getKey(), ProviderRuntime::new).network = entry.getValue();
        }
    }

    private void tickRemote(MinecraftServer server, ClusterWirelessSavedData data) {
        processNetworkReadFuture(data);
        processRemoteChildCountFuture();
        if (networkReadFuture == null
                && (tickCounter == 1L || tickCounter % NETWORK_READ_TICKS == 0L)) {
            // Blank provider = current ONLINE general/main node(s).  Do not make
            // native registry visibility depend on a separate MAIN discovery future.
            networkReadFuture = SupplyBufferService.readGtoWirelessNetworks("");
        }
        if (remoteChildCountFuture == null
                && (tickCounter == 1L || tickCounter % MAIN_REMOTE_CHILD_READ_TICKS == 0L)) {
            // REMOTE uses the same global presence view as MAIN so every GTO GUI
            // displays the cluster-wide SOURCE/CHILD totals, not only local mirror nodes.
            remoteChildCountFuture = SupplyBufferService.readGtoWirelessRemoteChildCounts();
        }

        syncNativeEndpoints(server, data);
        if (wirelessMachineListDirty && !data.nativeMirrorIds().isEmpty()) {
            refreshAllMachineLists();
            wirelessMachineListDirty = false;
        }
        for (NativeEndpoint endpoint : List.copyOf(endpoints.values())) {
            endpoint.tickRemote(data, tickCounter);
        }
        sanitizeMirrorNetworks(data);
        if (tickCounter == 1L || tickCounter % MAIN_SUMMARY_PATCH_TICKS == 0L) {
            patchRemoteNetworkSummaries();
        }
    }

    private void syncNativeEndpoints(MinecraftServer server, ClusterWirelessSavedData data) {
        Map<Object, Object> pool = api.networkPool();
        if (pool == null || data.nativeMirrorIds().isEmpty()) {
            return;
        }

        for (String networkId : data.nativeMirrorIds()) {
            Object network = pool.get(networkId);
            if (!api.isWirelessNetwork(network)) {
                continue;
            }

            Set<Object> candidateMachines = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            candidateMachines.addAll(api.inputNodes(network));
            candidateMachines.addAll(api.outputNodes(network));
            for (Object machine : candidateMachines) {
                EndpointType type = api.endpointType(machine);
                if (type != EndpointType.OUTPUT_BUS && type != EndpointType.OUTPUT_HATCH) {
                    continue;
                }

                EndpointKey key = api.endpointKey(network, machine);
                if (key == null) {
                    continue;
                }
                ServerLevel level = server.getLevel(key.dimension());
                if (level == null) {
                    continue;
                }
                BlockEntity owner = level.getBlockEntity(key.pos());
                if (owner == null || owner.isRemoved()) {
                    continue;
                }

                NativeEndpoint existing = endpoints.get(key);
                if (existing == null || existing.machine != machine || existing.owner != owner) {
                    endpoints.put(key, new NativeEndpoint(key, owner, machine, type));
                    LOGGER.debug("Discovered native GTO wireless {} at {} on {}",
                            type, key.id(), SupplyBufferService.currentNodeId());
                }
            }
        }

        endpoints.entrySet().removeIf(entry -> entry.getValue().owner.isRemoved()
                || entry.getValue().owner.getLevel() == null);
    }

    private void sanitizeMirrorNetworks(ClusterWirelessSavedData data) {
        Map<Object, Object> pool = api.networkPool();
        if (pool == null || data.nativeMirrorIds().isEmpty()) {
            return;
        }
        boolean changed = false;
        for (String networkId : data.nativeMirrorIds()) {
            Object network = pool.get(networkId);
            if (!api.isWirelessNetwork(network)) {
                continue;
            }
            Set<Object> machines = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            machines.addAll(api.inputNodes(network));
            machines.addAll(api.outputNodes(network));
            for (Object machine : machines) {
                EndpointType type = api.endpointType(machine);
                if (type == EndpointType.OUTPUT_BUS || type == EndpointType.OUTPUT_HATCH) {
                    changed |= api.enforceMirrorChild(network, machine);
                }
            }
            api.suppressMirrorRefresh(network);
        }
        if (changed) {
            api.markNativeSavedDataDirty();
            api.requireWriteToAll();
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
                networkReadFuture = null;
            }
        } catch (CompletionException exception) {
            logAsync("discover MAIN for native GTO wireless", exception);
        } finally {
            mainDiscoveryFuture = null;
        }
    }

    private void processNetworkReadFuture(ClusterWirelessSavedData data) {
        if (networkReadFuture == null || !networkReadFuture.isDone()) {
            return;
        }
        try {
            List<SupplyBufferDatabase.GtoWirelessNetworkSnapshot> snapshots = networkReadFuture.join();
            applyMirrors(data, snapshots);
            Map<String, SupplyBufferDatabase.GtoWirelessNetworkSnapshot> snapshotMap = new LinkedHashMap<>();
            if (snapshots != null) {
                for (SupplyBufferDatabase.GtoWirelessNetworkSnapshot snapshot : snapshots) {
                    if (snapshot != null && snapshot.networkId() != null && !snapshot.networkId().isBlank()) {
                        snapshotMap.put(snapshot.networkId(), snapshot);
                    }
                }
            }
            remoteNetworkSnapshots = Map.copyOf(snapshotMap);
            Set<String> ids = snapshots == null ? Set.of() : snapshots.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(SupplyBufferDatabase.GtoWirelessNetworkSnapshot::networkId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!remoteLogInitialized || !ids.equals(lastRemoteNetworkIds)) {
                remoteLogInitialized = true;
                lastRemoteNetworkIds = Set.copyOf(ids);
                LOGGER.info("REMOTE {} received {} MAIN native GTO wireless network(s): {}",
                        SupplyBufferService.currentNodeId(), ids.size(), ids);
            }
            // GTO caches NetworkSummary per machine and does not rebuild that cache
            // simply because networkPool changed. Refresh every successful catalog read
            // so machines that were loaded/opened before the mirror arrived see MAIN too.
            if (!ids.isEmpty()) {
                refreshAllMachineLists();
                wirelessMachineListDirty = false;
                patchRemoteNetworkSummaries();
            }
        } catch (CompletionException exception) {
            logAsync("read native GTO wireless networks", exception);
        } finally {
            networkReadFuture = null;
        }
    }

    @SuppressWarnings("unchecked")
    private void applyMirrors(
            ClusterWirelessSavedData data,
            Collection<SupplyBufferDatabase.GtoWirelessNetworkSnapshot> snapshots
    ) {
        Map<Object, Object> pool = api.networkPool();
        if (pool == null) {
            return;
        }

        Map<String, SupplyBufferDatabase.GtoWirelessNetworkSnapshot> desired = new LinkedHashMap<>();
        if (snapshots != null) {
            for (SupplyBufferDatabase.GtoWirelessNetworkSnapshot snapshot : snapshots) {
                if (snapshot != null && !snapshot.networkId().isBlank()) {
                    desired.put(snapshot.networkId(), snapshot);
                }
            }
        }

        Set<String> oldManaged = new LinkedHashSet<>(data.nativeMirrorIds());
        Set<String> newManaged = new LinkedHashSet<>();
        boolean changed = false;

        for (String oldId : oldManaged) {
            if (desired.containsKey(oldId)) {
                continue;
            }
            disconnectMachinesFrom(oldId);
            Object removed = pool.remove(oldId);
            changed |= removed != null;
        }

        for (SupplyBufferDatabase.GtoWirelessNetworkSnapshot snapshot : desired.values()) {
            String id = snapshot.networkId();
            Object existing = pool.get(id);
            boolean wasManaged = oldManaged.contains(id);

            if (existing != null && !wasManaged) {
                // Never overwrite a genuinely local GTO network. UUID collisions are
                // extremely unlikely, but preserving local state is safer.
                continue;
            }

            if (existing == null || !api.isWirelessNetwork(existing)) {
                Object mirror = api.createWirelessNetwork(snapshot);
                if (mirror == null) {
                    continue;
                }
                pool.put(id, mirror);
                newManaged.add(id);
                changed = true;
            } else {
                changed |= api.updateWirelessNetwork(existing, snapshot);
                newManaged.add(id);
            }
        }

        if (!newManaged.equals(oldManaged)) {
            data.replaceNativeMirrorIds(newManaged);
            changed = true;
        }

        if (changed) {
            api.markNativeSavedDataDirty();
            api.requireWriteToAll();
            refreshAllMachineLists();
            LOGGER.info("Mirrored {} MAIN GTO wireless network(s) on node {}",
                    newManaged.size(), SupplyBufferService.currentNodeId());
        }
    }

    private void removeAllMirrors(ClusterWirelessSavedData data) {
        Map<Object, Object> pool = api.networkPool();
        if (pool == null) {
            return;
        }
        boolean changed = false;
        for (String id : List.copyOf(data.nativeMirrorIds())) {
            disconnectMachinesFrom(id);
            changed |= pool.remove(id) != null;
        }
        data.replaceNativeMirrorIds(List.of());
        if (changed) {
            api.markNativeSavedDataDirty();
            api.requireWriteToAll();
            refreshAllMachineLists();
        }
    }

    private void disconnectMachinesFrom(String networkId) {
        Map<Object, Object> pool = api.networkPool();
        Object network = pool == null ? null : pool.get(networkId);
        if (api.isWirelessNetwork(network)) {
            for (Object machine : List.copyOf(api.outputNodes(network))) {
                api.leaveNetwork(machine);
            }
        }
        for (NativeEndpoint endpoint : endpoints.values()) {
            if (networkId.equals(api.connectedNetworkId(endpoint.machine))) {
                api.leaveNetwork(endpoint.machine);
            }
        }
    }

    private void refreshAllMachineLists() {
        Set<Object> machines = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (KnownWirelessMachine known : wirelessMachines.values()) {
            machines.add(known.machine);
        }
        for (NativeEndpoint endpoint : endpoints.values()) {
            machines.add(endpoint.machine);
        }
        Map<Object, Object> pool = api.networkPool();
        if (pool != null) {
            for (Object network : pool.values()) {
                if (api.isWirelessNetwork(network)) {
                    machines.addAll(api.inputNodes(network));
                    machines.addAll(api.outputNodes(network));
                }
            }
        }
        for (Object machine : machines) {
            api.refreshNetworkList(machine);
        }
    }

    private static boolean isSupported(AEKey key) {
        return key instanceof AEItemKey || key instanceof AEFluidKey;
    }

    private static SupplyBufferDatabase.ResourceType resourceType(AEKey key) {
        return key instanceof AEFluidKey
                ? SupplyBufferDatabase.ResourceType.FLUID
                : SupplyBufferDatabase.ResourceType.ITEM;
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

    private void logAsync(String action, Throwable throwable) {
        Throwable actual = SupplyBufferService.unwrap(throwable);
        LOGGER.warn("{}: {}", action, actual == null ? "unknown" : actual.getMessage());
    }

    private final class ProviderRuntime {
        private final String networkId;
        private final String linkId;
        private Object network;
        private CompletableFuture<Void> heartbeatFuture;
        private CompletableFuture<SupplyBufferDatabase.Operation> claimFuture;
        private SupplyBufferDatabase.Operation claimedOperation;
        private CompletableFuture<Void> providerFuture;
        private ProviderFutureKind futureKind = ProviderFutureKind.NONE;

        private ProviderRuntime(String networkId) {
            this.networkId = networkId;
            this.linkId = LINK_PREFIX + networkId;
        }

        private void tick(ClusterWirelessSavedData data, long tick) {
            IGrid grid = api.resolveSourceGrid(network);
            processHeartbeatFuture();
            if (heartbeatFuture == null && (tick == 1L || tick % PROVIDER_HEARTBEAT_TICKS == 0L)) {
                heartbeatFuture = SupplyBufferService.touchProvider(
                        linkId,
                        "gto:wireless",
                        networkId,
                        grid != null
                );
            }

            processProviderFuture(data);
            if (providerFuture != null) {
                return;
            }

            ClusterWirelessSavedData.NativeProviderJournal journal = data.nativeProviderJournal(linkId);
            if (journal != null) {
                futureKind = ProviderFutureKind.MARK_APPLIED;
                providerFuture = SupplyBufferService.markApplied(journal.operationId(), journal.delivered());
                return;
            }

            if (grid == null) {
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
                    logAsync("claim GTO wireless operation for " + networkId, exception);
                } finally {
                    claimFuture = null;
                }
                if (claimedOperation != null) {
                    processClaimedOperation(data, grid, claimedOperation);
                }
                return;
            }

            if (tick % PROVIDER_CLAIM_TICKS == 0L) {
                claimFuture = SupplyBufferService.claimNext(linkId);
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
                futureKind = ProviderFutureKind.MARK_FAILED;
                providerFuture = SupplyBufferService.markFailed(
                        operation.operationId(),
                        "Invalid native GTO wireless resource: " + exception.getMessage()
                );
                return;
            }

            MEStorage storage = grid.getStorageService().getInventory();
            IActionSource source = IActionSource.empty();
            long requested = Math.max(1L, operation.requestedAmount());
            long delivered;
            try {
                if (operation.direction() == SupplyBufferDatabase.TransferDirection.REMOTE_TO_MAIN) {
                    long accepted = storage.insert(resource, requested, Actionable.SIMULATE, source);
                    if (accepted <= 0L) {
                        release(operation, "MAIN ME cannot accept this resource yet");
                        return;
                    }
                    delivered = storage.insert(
                            resource,
                            Math.min(requested, accepted),
                            Actionable.MODULATE,
                            source
                    );
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
                futureKind = ProviderFutureKind.MARK_FAILED;
                providerFuture = SupplyBufferService.markFailed(
                        operation.operationId(),
                        "MAIN AE operation failed: " + exception.getMessage()
                );
                return;
            }

            if (delivered <= 0L) {
                release(operation, "MAIN ME transferred 0 units");
                return;
            }

            // Persist the result before acknowledging SQL. If the JVM stops after
            // the AE mutation, the next boot only retries markApplied and never
            // performs the inventory mutation a second time.
            data.setNativeProviderJournal(linkId, operation.operationId(), delivered);
            futureKind = ProviderFutureKind.MARK_APPLIED;
            providerFuture = SupplyBufferService.markApplied(operation.operationId(), delivered);
        }

        private void release(SupplyBufferDatabase.Operation operation, String reason) {
            futureKind = ProviderFutureKind.RELEASE;
            providerFuture = SupplyBufferService.releaseClaim(operation.operationId(), reason);
        }

        private void processProviderFuture(ClusterWirelessSavedData data) {
            if (providerFuture == null || !providerFuture.isDone()) {
                return;
            }
            ProviderFutureKind completed = futureKind;
            try {
                providerFuture.join();
                if (completed == ProviderFutureKind.MARK_APPLIED) {
                    data.clearNativeProviderJournal(linkId);
                    claimedOperation = null;
                } else if (completed == ProviderFutureKind.RELEASE
                        || completed == ProviderFutureKind.MARK_FAILED) {
                    claimedOperation = null;
                }
            } catch (CompletionException exception) {
                logAsync("finish GTO wireless provider operation for " + networkId, exception);
            } finally {
                providerFuture = null;
                futureKind = ProviderFutureKind.NONE;
            }
        }

        private void processHeartbeatFuture() {
            if (heartbeatFuture == null || !heartbeatFuture.isDone()) {
                return;
            }
            try {
                heartbeatFuture.join();
            } catch (CompletionException exception) {
                logAsync("GTO wireless provider heartbeat for " + networkId, exception);
            } finally {
                heartbeatFuture = null;
            }
        }
    }

    private final class NativeEndpoint {
        private final EndpointKey key;
        private final BlockEntity owner;
        private final Object machine;
        private final EndpointType type;
        private CompletableFuture<SupplyBufferDatabase.RemoteSyncResult> syncFuture;
        private CompletableFuture<Void> presenceFuture;
        private String lastPresenceLink;
        private boolean clusterVisualOnline;

        private NativeEndpoint(EndpointKey key, BlockEntity owner, Object machine, EndpointType type) {
            this.key = key;
            this.owner = owner;
            this.machine = machine;
            this.type = type;
        }

        private String stateId() {
            return "gto-native:" + key.id();
        }

        private boolean isOutput() {
            return type == EndpointType.OUTPUT_BUS || type == EndpointType.OUTPUT_HATCH;
        }

        private void tickRemote(ClusterWirelessSavedData data, long tick) {
            if (!isOutput()) {
                return;
            }
            ClusterWirelessSavedData.EndpointState state = data.endpoint(stateId());
            processSyncFuture(data, state);

            processPresenceFuture();

            String connected = api.connectedNetworkId(machine);
            if (connected != null && data.nativeMirrorIds().contains(connected)) {
                Map<Object, Object> pool = api.networkPool();
                Object mirror = pool == null ? null : pool.get(connected);
                if (api.isWirelessNetwork(mirror) && api.enforceMirrorChild(mirror, machine)) {
                    LOGGER.info("Normalized remote native GTO {} at {} to CHILD in mirrored network {}",
                            type, key.id(), connected);
                    api.markNativeSavedDataDirty();
                    api.requireWriteToAll();
                }
            }
            String desiredLink = connected != null && data.nativeMirrorIds().contains(connected)
                    ? LINK_PREFIX + connected
                    : "";

            // A mirrored cross-server network cannot create a real AE2 IGrid connection
            // inside this REMOTE JVM. GTO therefore keeps MEPartMachine.onlineField=false
            // even though CointCoreGTO is successfully transporting the buffer to MAIN.
            // Keep GTO's visual/status flag in sync with the cluster binding only. This
            // does not fabricate an AE2 grid and does not touch the transport path.
            boolean shouldShowClusterOnline = !desiredLink.isBlank();
            if (shouldShowClusterOnline) {
                api.setOnlineField(machine, true);
                clusterVisualOnline = true;
            } else if (clusterVisualOnline) {
                // We previously overrode the visual flag for a cluster mirror. Release
                // that override after disconnect/removal so native GTO state takes over.
                api.setOnlineField(machine, false);
                clusterVisualOnline = false;
            }

            publishPresence(state, desiredLink, tick);

            if (state.pending().isEmpty() && state.acknowledgements().isEmpty()
                    && !state.activeLinkId().equals(desiredLink)) {
                state.setActiveLinkId(desiredLink);
                data.setDirty();
            }
            String activeLink = state.activeLinkId();
            if (activeLink.isBlank() && !desiredLink.isBlank()
                    && state.pending().isEmpty() && state.acknowledgements().isEmpty()) {
                activeLink = desiredLink;
                state.setActiveLinkId(activeLink);
                data.setDirty();
            }

            if (!desiredLink.isBlank()
                    && desiredLink.equals(activeLink)
                    && state.pending().size() < MAX_PENDING_PER_ENDPOINT) {
                createPendingFromBuffer(data, state);
            }

            if (syncFuture == null
                    && !activeLink.isBlank()
                    && tick % REMOTE_SYNC_TICKS == 0L
                    && (!state.pending().isEmpty() || !state.acknowledgements().isEmpty())) {
                List<SupplyBufferDatabase.PendingDescriptor> descriptors = state.pending().values().stream()
                        .map(ClusterWirelessSavedData.PendingTransfer::descriptor)
                        .toList();
                syncFuture = SupplyBufferService.syncRemote(activeLink, descriptors, state.acknowledgements());
            }
        }

        private void publishPresence(
                ClusterWirelessSavedData.EndpointState state,
                String desiredLink,
                long tick
        ) {
            boolean changed = lastPresenceLink == null || !lastPresenceLink.equals(desiredLink);
            boolean heartbeatDue = !desiredLink.isBlank()
                    && (tick == 1L || tick % REMOTE_PRESENCE_TICKS == 0L);
            if (presenceFuture != null || (!changed && !heartbeatDue)) {
                return;
            }

            boolean connected = !desiredLink.isBlank();
            lastPresenceLink = desiredLink;
            presenceFuture = SupplyBufferService.touchEndpoint(
                    stateId(),
                    desiredLink,
                    GTO_CHILD_ROLE,
                    "",
                    key.dimension().location().toString(),
                    key.pos().getX() + "," + key.pos().getY() + "," + key.pos().getZ(),
                    null,
                    "",
                    connected,
                    connected,
                    state.pending().size(),
                    0,
                    List.of()
            );
        }

        private void processPresenceFuture() {
            if (presenceFuture == null || !presenceFuture.isDone()) {
                return;
            }
            try {
                presenceFuture.join();
            } catch (CompletionException exception) {
                logAsync("publish native GTO CHILD presence for " + stateId(), exception);
            } finally {
                presenceFuture = null;
            }
        }

        private void publishPresenceOffline() {
            String previous = lastPresenceLink;
            if (previous == null || previous.isBlank()) {
                return;
            }
            lastPresenceLink = "";
            CompletableFuture<Void> prior = presenceFuture;
            CompletableFuture<Void> barrier = prior == null
                    ? CompletableFuture.completedFuture(null)
                    : prior.handle((unused, throwable) -> null);
            presenceFuture = barrier.thenCompose(unused -> SupplyBufferService.touchEndpoint(
                    stateId(),
                    "",
                    GTO_CHILD_ROLE,
                    "",
                    key.dimension().location().toString(),
                    key.pos().getX() + "," + key.pos().getY() + "," + key.pos().getZ(),
                    null,
                    "",
                    false,
                    false,
                    0,
                    0,
                    List.of()
            ));
            presenceFuture.whenComplete((unused, throwable) -> {
                if (throwable != null) {
                    logAsync("clear native GTO CHILD presence for " + stateId(), throwable);
                }
            });
        }

        private void createPendingFromBuffer(
                ClusterWirelessSavedData data,
                ClusterWirelessSavedData.EndpointState state
        ) {
            Set<String> pendingKeys = new LinkedHashSet<>();
            for (ClusterWirelessSavedData.PendingTransfer transfer : state.pending().values()) {
                if (transfer.direction() == SupplyBufferDatabase.TransferDirection.REMOTE_TO_MAIN) {
                    pendingKeys.add(transfer.keyPayload());
                }
            }

            for (BufferEntry entry : api.readInternalBuffer(machine)) {
                if (state.pending().size() >= MAX_PENDING_PER_ENDPOINT) {
                    break;
                }
                if (entry.amount() <= 0L || pendingKeys.contains(entry.payload())) {
                    continue;
                }
                ClusterWirelessSavedData.PendingTransfer transfer = new ClusterWirelessSavedData.PendingTransfer(
                        UUID.randomUUID(),
                        SupplyBufferDatabase.TransferDirection.REMOTE_TO_MAIN,
                        resourceType(entry.key()),
                        entry.payload(),
                        Math.min(entry.amount(), MAX_OPERATION_AMOUNT),
                        0
                );
                state.pending().put(transfer.operationId(), transfer);
                pendingKeys.add(entry.payload());
                data.setDirty();
            }
        }

        private void processSyncFuture(
                ClusterWirelessSavedData data,
                ClusterWirelessSavedData.EndpointState state
        ) {
            if (syncFuture == null || !syncFuture.isDone()) {
                return;
            }
            try {
                SupplyBufferDatabase.RemoteSyncResult result = syncFuture.join();
                if (result.acknowledged() != null && !result.acknowledged().isEmpty()) {
                    if (state.acknowledgements().removeAll(result.acknowledged())) {
                        data.setDirty();
                    }
                }

                for (Map.Entry<UUID, SupplyBufferDatabase.OperationResult> resultEntry : result.results().entrySet()) {
                    ClusterWirelessSavedData.PendingTransfer pending = state.pending().get(resultEntry.getKey());
                    if (pending == null) {
                        continue;
                    }
                    SupplyBufferDatabase.OperationResult operationResult = resultEntry.getValue();
                    if (operationResult.applied()) {
                        long delivered = Math.min(pending.amount(), Math.max(0L, operationResult.deliveredAmount()));
                        if (delivered <= 0L) {
                            continue;
                        }
                        if (!api.removeFromInternalBuffer(machine, pending.keyPayload(), delivered)) {
                            LOGGER.warn("MAIN accepted {} units for {}, but local GTO buffer could not be decremented yet; will retry",
                                    delivered, stateId());
                            continue;
                        }
                        state.pending().remove(pending.operationId());
                        state.acknowledgements().add(pending.operationId());
                        data.setDirty();
                    } else if (operationResult.failed()) {
                        state.pending().remove(pending.operationId());
                        state.acknowledgements().add(pending.operationId());
                        data.setDirty();
                        LOGGER.warn("Native GTO wireless transfer failed at {}: {}",
                                stateId(), operationResult.errorText());
                    }
                }
            } catch (CompletionException exception) {
                logAsync("sync native GTO output at " + stateId(), exception);
            } finally {
                syncFuture = null;
            }
        }

        private boolean hasBufferedResources() {
            return !api.readInternalBuffer(machine).isEmpty();
        }
    }

    private enum EndpointType {
        OTHER,
        OUTPUT_BUS,
        OUTPUT_HATCH
    }

    private enum ProviderFutureKind {
        NONE,
        MARK_APPLIED,
        RELEASE,
        MARK_FAILED
    }

    private record KnownWirelessMachine(BlockEntity owner, Object machine) {
    }

    private record BufferEntry(AEKey key, String payload, long amount) {
    }

    private record EndpointKey(ResourceKey<Level> dimension, BlockPos pos) {
        private static EndpointKey of(ServerLevel level, BlockPos pos) {
            return new EndpointKey(level.dimension(), pos.immutable());
        }

        private String id() {
            return dimension.location() + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }
    }

    /** Reflection boundary so CointCoreGTO keeps compiling without GTOCore/GTOLib in build.gradle. */
    private static final class NativeApi {
        private static final String SAVED_DATA = "com.gtocore.common.saved.WirelessNetworkSavedData";
        private static final String WIRELESS_NETWORK = "com.gtocore.integration.ae.wireless.WirelessNetwork";
        private static final String WIRELESS_MACHINE = "com.gtocore.integration.ae.wireless.WirelessMachine";
        private static final String NODE_INFO = "com.gtocore.integration.ae.wireless.WirelessNetwork$NodeInfo";
        private static final String NODE_TYPE = "com.gtocore.integration.ae.wireless.WirelessMachine$NodeType";
        private static final String NETWORK_SUMMARY = "com.gtocore.common.saved.NetworkSummary";
        private static final String META_MACHINE_BLOCK_ENTITY = "com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity";
        private static final String ME_PART = "com.gtocore.common.machine.multiblock.part.ae.MEPartMachine";
        private static final String ME_WIRELESS_CONNECT = "com.gtocore.integration.ae.MeWirelessConnectMachine";
        private static final String OUTPUT_BUS = "com.gtocore.common.machine.multiblock.part.ae.MEOutputBusPartMachine";
        private static final String OUTPUT_HATCH = "com.gtocore.common.machine.multiblock.part.ae.MEOutputHatchPartMachine";

        private boolean initialized;
        private boolean available;
        private Class<?> savedDataClass;
        private Class<?> wirelessNetworkClass;
        private Class<?> wirelessMachineClass;
        private Class<?> nodeInfoClass;
        private Class<?> nodeTypeClass;
        private Class<?> networkSummaryClass;
        private Class<?> metaMachineBlockEntityClass;
        private Class<?> mePartClass;
        private Class<?> meWirelessConnectClass;
        private Class<?> outputBusClass;
        private Class<?> outputHatchClass;
        private Constructor<?> wirelessNetworkConstructor;
        private Constructor<?> networkSummaryConstructor;
        private Object childNodeType;
        private Method savedDataGet;
        private Method savedDataRequireWriteToAll;
        private Method getNetworkPool;
        private Method getNetworkId;
        private Method getNetworkOwner;
        private Method getNetworkNickname;
        private Method setNetworkNickname;
        private Method getMaxOutputs;
        private Method setMaxOutputs;
        private Method getInputCount;
        private Method getOutputCount;
        private Method getTotalCapacity;
        private Method getInputNodes;
        private Method getOutputNodes;
        private Method getNodeInfoTable;
        private Method getNodeInfoPos;
        private Method getNodeInfoLevel;
        private Method setNodeInfoNodeType;
        private Method setNeedsRefresh;
        private Method getMainNode;
        private Method getWirelessConnectMainNode;
        private Method getConnectedNetworkId;
        private Method getNodeType;
        private Method setNodeType;
        private Method switchNodeType;
        private Method getNetworkListCache;
        private Method getNodeTypeSync;
        private Method syncedFieldGet;
        private Method syncedFieldSetAndSyncToClient;
        private Method intSyncedFieldSetAndSyncToClient;
        private Method summaryGetId;
        private Method summaryGetNickname;
        private Method summaryIsDefault;
        private Method summaryGetInputCount;
        private Method summaryGetOutputCount;
        private Method summaryGetCapacity;
        private Method summaryGetUnassignedCount;
        private Method summaryIsConnected;
        private Method refreshNetworkListOnServer;
        private Method leaveNetwork;
        private Method setOnlineField;
        private Method bufferIterator;
        private Field metaMachineField;
        private Field internalBufferBus;
        private Field internalBufferHatch;

        private boolean available() {
            if (!initialized) {
                initialize();
            }
            return available;
        }

        private synchronized void initialize() {
            if (initialized) {
                return;
            }
            initialized = true;
            try {
                ClassLoader loader = GtoNativeWirelessBridgeManager.class.getClassLoader();
                savedDataClass = Class.forName(SAVED_DATA, false, loader);
                wirelessNetworkClass = Class.forName(WIRELESS_NETWORK, false, loader);
                wirelessMachineClass = Class.forName(WIRELESS_MACHINE, false, loader);
                nodeInfoClass = Class.forName(NODE_INFO, false, loader);
                nodeTypeClass = Class.forName(NODE_TYPE, false, loader);
                networkSummaryClass = Class.forName(NETWORK_SUMMARY, false, loader);
                metaMachineBlockEntityClass = Class.forName(META_MACHINE_BLOCK_ENTITY, false, loader);
                mePartClass = Class.forName(ME_PART, false, loader);
                meWirelessConnectClass = Class.forName(ME_WIRELESS_CONNECT, false, loader);
                outputBusClass = Class.forName(OUTPUT_BUS, false, loader);
                outputHatchClass = Class.forName(OUTPUT_HATCH, false, loader);

                savedDataGet = savedDataClass.getMethod("get");
                savedDataRequireWriteToAll = savedDataClass.getMethod("requireWriteToAll");
                getNetworkPool = savedDataClass.getMethod("getNetworkPool");
                wirelessNetworkConstructor = wirelessNetworkClass.getConstructor(
                        String.class, UUID.class, String.class, int.class);
                networkSummaryConstructor = networkSummaryClass.getConstructor(
                        String.class, String.class, boolean.class, int.class, int.class,
                        int.class, int.class, boolean.class);
                childNodeType = nodeTypeClass.getField("CHILD").get(null);
                getNetworkId = wirelessNetworkClass.getMethod("getId");
                getNetworkOwner = wirelessNetworkClass.getMethod("getOwner");
                getNetworkNickname = wirelessNetworkClass.getMethod("getNickname");
                setNetworkNickname = wirelessNetworkClass.getMethod("setNickname", String.class);
                getMaxOutputs = wirelessNetworkClass.getMethod("getMaxOutputsPerInput");
                setMaxOutputs = wirelessNetworkClass.getMethod("setMaxOutputsPerInput", int.class);
                getInputCount = wirelessNetworkClass.getMethod("getInputCount");
                getOutputCount = wirelessNetworkClass.getMethod("getOutputCount");
                getTotalCapacity = wirelessNetworkClass.getMethod("getTotalCapacity");
                getInputNodes = wirelessNetworkClass.getMethod("getInputNodes");
                getOutputNodes = wirelessNetworkClass.getMethod("getOutputNodes");
                getNodeInfoTable = wirelessNetworkClass.getMethod("getNodeInfoTable");
                getNodeInfoPos = nodeInfoClass.getMethod("getPos");
                getNodeInfoLevel = nodeInfoClass.getMethod("getLevel");
                setNodeInfoNodeType = nodeInfoClass.getMethod("setNodeType", nodeTypeClass);
                setNeedsRefresh = wirelessNetworkClass.getMethod("setNeedsRefresh", boolean.class);
                getMainNode = mePartClass.getDeclaredMethod("getMainNode");
                getWirelessConnectMainNode = meWirelessConnectClass.getMethod("getMainNode");
                getConnectedNetworkId = wirelessMachineClass.getMethod("getConnectedNetworkId");
                getNodeType = wirelessMachineClass.getMethod("getNodeType");
                setNodeType = wirelessMachineClass.getMethod("setNodeType", nodeTypeClass);
                switchNodeType = wirelessMachineClass.getMethod("switchNodeType", nodeTypeClass);
                getNetworkListCache = wirelessMachineClass.getMethod("getNetworkListCache");
                getNodeTypeSync = wirelessMachineClass.getMethod("getNodeTypeSync");
                Class<?> syncedFieldClass = getNetworkListCache.getReturnType();
                syncedFieldGet = syncedFieldClass.getMethod("get");
                syncedFieldSetAndSyncToClient = syncedFieldClass.getMethod("setAndSyncToClient", Object.class);
                Class<?> intSyncedFieldClass = getNodeTypeSync.getReturnType();
                intSyncedFieldSetAndSyncToClient = intSyncedFieldClass.getMethod("setAndSyncToClient", int.class);
                summaryGetId = networkSummaryClass.getMethod("getId");
                summaryGetNickname = networkSummaryClass.getMethod("getNickname");
                summaryIsDefault = networkSummaryClass.getMethod("isDefault");
                summaryGetInputCount = networkSummaryClass.getMethod("getInputCount");
                summaryGetOutputCount = networkSummaryClass.getMethod("getOutputCount");
                summaryGetCapacity = networkSummaryClass.getMethod("getCapacity");
                summaryGetUnassignedCount = networkSummaryClass.getMethod("getUnassignedCount");
                summaryIsConnected = networkSummaryClass.getMethod("isConnected");
                refreshNetworkListOnServer = wirelessMachineClass.getMethod("refreshNetworkListOnServer");
                leaveNetwork = wirelessMachineClass.getMethod("leaveNetwork");
                setOnlineField = mePartClass.getMethod("setOnlineField", boolean.class);

                // GTCEu 1.8.0 exposes this as a public final field.  Reading the
                // exact base-class field is dedicated-server safe and, unlike
                // blockEntity.getClass().getMethod(...), does not enumerate a
                // transformed runtime class with client-only method signatures.
                metaMachineField = metaMachineBlockEntityClass.getDeclaredField("metaMachine");
                metaMachineField.setAccessible(true);

                internalBufferBus = findField(outputBusClass, "internalBuffer");
                internalBufferHatch = findField(outputHatchClass, "internalBuffer");
                if (internalBufferBus != null) {
                    internalBufferBus.setAccessible(true);
                    bufferIterator = internalBufferBus.getType().getMethod("iterator");
                }
                if (internalBufferHatch != null) {
                    internalBufferHatch.setAccessible(true);
                    if (bufferIterator == null) {
                        bufferIterator = internalBufferHatch.getType().getMethod("iterator");
                    }
                }
                available = true;
                LOGGER.info("GTOCore 0.5.x native Wireless ME bridge enabled");
            } catch (Throwable throwable) {
                available = false;
                LOGGER.warn("GTO native Wireless ME API is unavailable: {}", throwable.getMessage());
            }
        }

        private Object metaMachine(BlockEntity blockEntity) {
            if (!available() || blockEntity == null
                    || !metaMachineBlockEntityClass.isInstance(blockEntity)
                    || metaMachineField == null) {
                return null;
            }
            try {
                return metaMachineField.get(blockEntity);
            } catch (IllegalAccessException | RuntimeException exception) {
                LOGGER.debug("Could not read GTCEu MetaMachine safely: {}", exception.getMessage());
                return null;
            }
        }

        private boolean isMePart(Object machine) {
            return available() && machine != null && mePartClass.isInstance(machine);
        }

        private boolean isWirelessMachine(Object machine) {
            return available() && machine != null && wirelessMachineClass.isInstance(machine);
        }

        private boolean isWirelessNetwork(Object network) {
            return available() && network != null && wirelessNetworkClass.isInstance(network);
        }

        private EndpointType endpointType(Object machine) {
            if (!available() || machine == null) {
                return EndpointType.OTHER;
            }
            if (outputBusClass.isInstance(machine)) {
                return EndpointType.OUTPUT_BUS;
            }
            if (outputHatchClass.isInstance(machine)) {
                return EndpointType.OUTPUT_HATCH;
            }
            return EndpointType.OTHER;
        }

        @SuppressWarnings("unchecked")
        private Map<Object, Object> networkPool() {
            if (!available()) {
                return null;
            }
            try {
                Object data = savedDataGet.invoke(null);
                if (data == null) {
                    return null;
                }
                Object pool = getNetworkPool.invoke(data);
                return pool instanceof Map<?, ?> map ? (Map<Object, Object>) map : null;
            } catch (ReflectiveOperationException exception) {
                return null;
            }
        }

        private List<Object> inputNodes(Object network) {
            if (!isWirelessNetwork(network)) {
                return List.of();
            }
            Object value = invoke(getInputNodes, network);
            if (!(value instanceof Iterable<?> iterable)) {
                return List.of();
            }
            List<Object> result = new ArrayList<>();
            for (Object machine : iterable) {
                if (machine != null && wirelessMachineClass.isInstance(machine)) {
                    result.add(machine);
                }
            }
            return result;
        }

        private List<Object> outputNodes(Object network) {
            if (!isWirelessNetwork(network)) {
                return List.of();
            }
            Object value = invoke(getOutputNodes, network);
            if (!(value instanceof Iterable<?> iterable)) {
                return List.of();
            }
            List<Object> result = new ArrayList<>();
            for (Object machine : iterable) {
                if (machine != null && wirelessMachineClass.isInstance(machine)) {
                    result.add(machine);
                }
            }
            return result;
        }

        @SuppressWarnings("unchecked")
        private EndpointKey endpointKey(Object network, Object machine) {
            if (!isWirelessNetwork(network) || machine == null) {
                return null;
            }
            try {
                Object rawTable = getNodeInfoTable.invoke(network);
                if (!(rawTable instanceof Map<?, ?> table)) {
                    return null;
                }
                Object info = table.get(machine);
                if (info == null || !nodeInfoClass.isInstance(info)) {
                    return null;
                }
                Object rawPos = getNodeInfoPos.invoke(info);
                Object rawLevel = getNodeInfoLevel.invoke(info);
                if (!(rawPos instanceof BlockPos pos) || !(rawLevel instanceof ResourceKey<?> levelKey)) {
                    return null;
                }
                return new EndpointKey((ResourceKey<Level>) levelKey, pos.immutable());
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.debug("Could not resolve native GTO wireless endpoint position: {}", exception.getMessage());
                return null;
            }
        }

        private Map<String, Object> networksById() {
            Map<Object, Object> pool = networkPool();
            if (pool == null || pool.isEmpty()) {
                return Map.of();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (Object network : pool.values()) {
                if (!isWirelessNetwork(network)) {
                    continue;
                }
                String id = string(invoke(getNetworkId, network));
                if (!id.isBlank()) {
                    result.put(id, network);
                }
            }
            return result;
        }

        private List<SupplyBufferDatabase.GtoWirelessNetworkSnapshot> snapshotNetworks(Collection<Object> networks) {
            List<SupplyBufferDatabase.GtoWirelessNetworkSnapshot> result = new ArrayList<>();
            if (!available() || networks == null) {
                return result;
            }
            for (Object network : networks) {
                if (!isWirelessNetwork(network)) {
                    continue;
                }
                try {
                    String id = string(getNetworkId.invoke(network));
                    UUID owner = (UUID) getNetworkOwner.invoke(network);
                    String nickname = string(getNetworkNickname.invoke(network));
                    int maxOutputs = number(getMaxOutputs.invoke(network));
                    int inputs = number(getInputCount.invoke(network));
                    int outputs = number(getOutputCount.invoke(network));
                    int capacity = number(getTotalCapacity.invoke(network));
                    if (!id.isBlank() && owner != null) {
                        result.add(new SupplyBufferDatabase.GtoWirelessNetworkSnapshot(
                                id, owner, nickname, maxOutputs, inputs, outputs, capacity));
                    }
                } catch (ReflectiveOperationException exception) {
                    LOGGER.debug("Could not snapshot native GTO wireless network: {}", exception.getMessage());
                }
            }
            return result;
        }

        private Object createWirelessNetwork(SupplyBufferDatabase.GtoWirelessNetworkSnapshot snapshot) {
            if (!available() || snapshot == null) {
                return null;
            }
            try {
                return wirelessNetworkConstructor.newInstance(
                        snapshot.networkId(),
                        snapshot.ownerUuid(),
                        snapshot.nickname(),
                        Math.max(1, snapshot.maxOutputsPerInput())
                );
            } catch (ReflectiveOperationException exception) {
                LOGGER.warn("Could not create mirrored GTO wireless network {}: {}",
                        snapshot.networkId(), exception.getMessage());
                return null;
            }
        }

        private boolean updateWirelessNetwork(
                Object network,
                SupplyBufferDatabase.GtoWirelessNetworkSnapshot snapshot
        ) {
            if (!isWirelessNetwork(network) || snapshot == null) {
                return false;
            }
            boolean changed = false;
            try {
                String oldName = string(getNetworkNickname.invoke(network));
                if (!oldName.equals(snapshot.nickname())) {
                    setNetworkNickname.invoke(network, snapshot.nickname());
                    changed = true;
                }
                int oldMax = number(getMaxOutputs.invoke(network));
                int newMax = Math.max(1, snapshot.maxOutputsPerInput());
                if (oldMax != newMax) {
                    setMaxOutputs.invoke(network, newMax);
                    changed = true;
                }
            } catch (ReflectiveOperationException exception) {
                LOGGER.debug("Could not update mirrored GTO wireless network: {}", exception.getMessage());
            }
            return changed;
        }

        private IGrid resolveSourceGrid(Object network) {
            if (!isWirelessNetwork(network)) {
                return null;
            }
            try {
                Object inputNodes = getInputNodes.invoke(network);
                if (!(inputNodes instanceof Iterable<?> iterable)) {
                    return null;
                }
                for (Object machine : iterable) {
                    Object nodeObject;
                    if (mePartClass.isInstance(machine)) {
                        // ME Output Bus/Hatch and the other multiblock ME parts.
                        nodeObject = invoke(getMainNode, machine);
                    } else if (meWirelessConnectClass.isInstance(machine)) {
                        // GTO's standalone "Wireless ME I/O Hub". This is the
                        // normal SOURCE used by the MAIN network in this cluster.
                        // It is a WirelessMachine, but it does NOT extend
                        // MEPartMachine, so the old resolver silently skipped it
                        // and advertised the cross-server provider as OFFLINE.
                        nodeObject = invoke(getWirelessConnectMainNode, machine);
                    } else {
                        continue;
                    }

                    if (!(nodeObject instanceof IManagedGridNode node)) {
                        continue;
                    }
                    IGrid grid = node.getGrid();
                    if (grid != null && node.isOnline()) {
                        return grid;
                    }
                }
            } catch (ReflectiveOperationException exception) {
                LOGGER.debug("Could not resolve GTO wireless SOURCE grid: {}", exception.getMessage());
            }
            return null;
        }

        private String connectedNetworkId(Object machine) {
            if (!available() || machine == null || !wirelessMachineClass.isInstance(machine)) {
                return "";
            }
            Object value = invoke(getConnectedNetworkId, machine);
            return value == null ? "" : value.toString();
        }

        @SuppressWarnings("unchecked")
        private boolean enforceMirrorChild(Object network, Object machine) {
            if (!available() || !isWirelessNetwork(network) || machine == null
                    || !wirelessMachineClass.isInstance(machine) || childNodeType == null
                    || getNodeType == null || setNodeType == null) {
                return false;
            }
            boolean changed = false;
            try {
                Object current = getNodeType.invoke(machine);
                if (!childNodeType.equals(current)) {
                    // Do NOT call WirelessMachine.switchNodeType here. GTO's native
                    // WirelessNetwork refresh sees a mirror without a physical SOURCE
                    // and immediately promotes the only CHILD back to SOURCE. Change the
                    // role and membership atomically, then freeze native AE assignment on
                    // this synthetic mirror. Cross-JVM transport is handled by CointCoreGTO.
                    setNodeType.invoke(machine, childNodeType);
                    changed = true;
                }

                Object rawInputs = getInputNodes.invoke(network);
                if (rawInputs instanceof Collection<?> inputs) {
                    changed |= inputs.remove(machine);
                }
                Object rawOutputs = getOutputNodes.invoke(network);
                if (rawOutputs instanceof Collection<?> outputs) {
                    Collection<Object> mutableOutputs = (Collection<Object>) outputs;
                    if (!mutableOutputs.contains(machine)) {
                        changed |= mutableOutputs.add(machine);
                    }
                }

                Object rawTable = getNodeInfoTable.invoke(network);
                if (rawTable instanceof Map<?, ?> table) {
                    Object info = table.get(machine);
                    if (info != null && nodeInfoClass.isInstance(info) && setNodeInfoNodeType != null) {
                        setNodeInfoNodeType.invoke(info, childNodeType);
                    }
                }

                if (getNodeTypeSync != null && intSyncedFieldSetAndSyncToClient != null) {
                    Object syncField = getNodeTypeSync.invoke(machine);
                    if (syncField != null) {
                        intSyncedFieldSetAndSyncToClient.invoke(syncField, ((Enum<?>) childNodeType).ordinal());
                    }
                }
                suppressMirrorRefresh(network);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.debug("Could not normalize native GTO mirror node to CHILD: {}", exception.getMessage());
            }
            return changed;
        }

        private void suppressMirrorRefresh(Object network) {
            if (!available() || !isWirelessNetwork(network) || setNeedsRefresh == null) {
                return;
            }
            try {
                setNeedsRefresh.invoke(network, false);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.debug("Could not suppress native GTO mirror refresh: {}", exception.getMessage());
            }
        }

        private void patchNetworkSummaryChildCounts(Object machine, Map<String, Integer> extraChildren) {
            if (!available() || machine == null || !wirelessMachineClass.isInstance(machine)
                    || extraChildren == null || getNetworkListCache == null
                    || syncedFieldGet == null || syncedFieldSetAndSyncToClient == null) {
                return;
            }
            try {
                Object syncedField = getNetworkListCache.invoke(machine);
                if (syncedField == null) {
                    return;
                }
                Object raw = syncedFieldGet.invoke(syncedField);
                if (!(raw instanceof List<?> summaries) || summaries.isEmpty()) {
                    return;
                }

                Map<Object, Object> pool = networkPool();
                if (pool == null) {
                    return;
                }

                List<Object> updated = new ArrayList<>(summaries.size());
                boolean changed = false;
                for (Object summary : summaries) {
                    if (summary == null || !networkSummaryClass.isInstance(summary)) {
                        updated.add(summary);
                        continue;
                    }

                    String id = string(summaryGetId.invoke(summary));
                    Object network = pool.get(id);
                    if (!isWirelessNetwork(network)) {
                        updated.add(summary);
                        continue;
                    }

                    int localInputs = number(getInputCount.invoke(network));
                    int localOutputs = number(getOutputCount.invoke(network));
                    int extra = Math.max(0, extraChildren.getOrDefault(id, 0));
                    int displayOutputs = (int) Math.min(Integer.MAX_VALUE, (long) localOutputs + extra);
                    int displayCapacity = number(getTotalCapacity.invoke(network));
                    int oldInputs = number(summaryGetInputCount.invoke(summary));
                    int oldOutputs = number(summaryGetOutputCount.invoke(summary));
                    int oldCapacity = number(summaryGetCapacity.invoke(summary));

                    if (oldInputs == localInputs
                            && oldOutputs == displayOutputs
                            && oldCapacity == displayCapacity) {
                        updated.add(summary);
                        continue;
                    }

                    Object replacement = networkSummaryConstructor.newInstance(
                            id,
                            string(summaryGetNickname.invoke(summary)),
                            Boolean.TRUE.equals(summaryIsDefault.invoke(summary)),
                            localInputs,
                            displayOutputs,
                            displayCapacity,
                            number(summaryGetUnassignedCount.invoke(summary)),
                            Boolean.TRUE.equals(summaryIsConnected.invoke(summary))
                    );
                    updated.add(replacement);
                    changed = true;
                }

                if (changed) {
                    syncedFieldSetAndSyncToClient.invoke(syncedField, List.copyOf(updated));
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.debug("Could not patch native GTO network summary CHILD counts: {}", exception.getMessage());
            }
        }

        private void patchRemoteMirrorSummaryCounts(
                Object machine,
                Map<String, SupplyBufferDatabase.GtoWirelessNetworkSnapshot> snapshots,
                Map<String, Integer> remoteChildren
        ) {
            if (!available() || machine == null || !wirelessMachineClass.isInstance(machine)
                    || snapshots == null || snapshots.isEmpty() || getNetworkListCache == null
                    || syncedFieldGet == null || syncedFieldSetAndSyncToClient == null) {
                return;
            }
            try {
                Object syncedField = getNetworkListCache.invoke(machine);
                if (syncedField == null) {
                    return;
                }
                Object raw = syncedFieldGet.invoke(syncedField);
                if (!(raw instanceof List<?> summaries) || summaries.isEmpty()) {
                    return;
                }

                List<Object> updated = new ArrayList<>(summaries.size());
                boolean changed = false;
                for (Object summary : summaries) {
                    if (summary == null || !networkSummaryClass.isInstance(summary)) {
                        updated.add(summary);
                        continue;
                    }

                    String id = string(summaryGetId.invoke(summary));
                    SupplyBufferDatabase.GtoWirelessNetworkSnapshot snapshot = snapshots.get(id);
                    if (snapshot == null) {
                        updated.add(summary);
                        continue;
                    }

                    int displayInputs = Math.max(0, snapshot.inputCount());
                    int extra = remoteChildren == null ? 0 : Math.max(0, remoteChildren.getOrDefault(id, 0));
                    int displayOutputs = (int) Math.min(
                            Integer.MAX_VALUE,
                            (long) Math.max(0, snapshot.outputCount()) + extra
                    );
                    int displayCapacity = Math.max(0, snapshot.totalCapacity());
                    int oldInputs = number(summaryGetInputCount.invoke(summary));
                    int oldOutputs = number(summaryGetOutputCount.invoke(summary));
                    int oldCapacity = number(summaryGetCapacity.invoke(summary));

                    if (oldInputs == displayInputs
                            && oldOutputs == displayOutputs
                            && oldCapacity == displayCapacity) {
                        updated.add(summary);
                        continue;
                    }

                    Object replacement = networkSummaryConstructor.newInstance(
                            id,
                            string(summaryGetNickname.invoke(summary)),
                            Boolean.TRUE.equals(summaryIsDefault.invoke(summary)),
                            displayInputs,
                            displayOutputs,
                            displayCapacity,
                            number(summaryGetUnassignedCount.invoke(summary)),
                            Boolean.TRUE.equals(summaryIsConnected.invoke(summary))
                    );
                    updated.add(replacement);
                    changed = true;
                }

                if (changed) {
                    syncedFieldSetAndSyncToClient.invoke(syncedField, List.copyOf(updated));
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.debug("Could not patch REMOTE native GTO mirror summary counts: {}", exception.getMessage());
            }
        }

        private void refreshNetworkList(Object machine) {
            if (available() && machine != null && wirelessMachineClass.isInstance(machine)) {
                invoke(refreshNetworkListOnServer, machine);
            }
        }

        private void leaveNetwork(Object machine) {
            if (available() && machine != null && wirelessMachineClass.isInstance(machine)) {
                invoke(leaveNetwork, machine);
            }
        }

        private void setOnlineField(Object machine, boolean online) {
            if (!available() || machine == null || !mePartClass.isInstance(machine) || setOnlineField == null) {
                return;
            }
            try {
                setOnlineField.invoke(machine, online);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.debug("Could not update native GTO ME visual online state: {}", exception.getMessage());
            }
        }

        private void markNativeSavedDataDirty() {
            if (!available()) {
                return;
            }
            try {
                Object data = savedDataGet.invoke(null);
                // setDirty() belongs to vanilla SavedData. Looking it up through
                // reflection on the GTO subclass fails on a dedicated Forge server
                // because Minecraft method names are runtime-remapped. A normal
                // typed call is remapped by Forge correctly.
                if (data instanceof net.minecraft.world.level.saveddata.SavedData savedData) {
                    savedData.setDirty();
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }

        private void requireWriteToAll() {
            if (!available()) {
                return;
            }
            try {
                savedDataRequireWriteToAll.invoke(null);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }

        private List<BufferEntry> readInternalBuffer(Object machine) {
            Field field = internalBufferField(machine);
            if (field == null) {
                return List.of();
            }
            List<BufferEntry> result = new ArrayList<>();
            try {
                Object buffer = field.get(machine);
                if (buffer == null) {
                    return result;
                }
                Object iteratorObject = invoke(bufferIterator, buffer);
                if (!(iteratorObject instanceof Iterator<?> iterator)) {
                    return result;
                }
                while (iterator.hasNext()) {
                    Object rawEntry = iterator.next();
                    if (!(rawEntry instanceof Reference2LongMap.Entry<?> entry)) {
                        continue;
                    }
                    Object rawKey = entry.getKey();
                    if (!(rawKey instanceof AEKey key) || !isSupported(key)) {
                        continue;
                    }
                    long amount = Math.max(0L, entry.getLongValue());
                    if (amount <= 0L) {
                        continue;
                    }
                    result.add(new BufferEntry(key, SupplyKeyCodec.encode(key), amount));
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.debug("Could not read native GTO ME output buffer: {}", exception.getMessage());
            }
            return result;
        }

        private boolean removeFromInternalBuffer(Object machine, String payload, long amount) {
            if (amount <= 0L || payload == null || payload.isBlank()) {
                return true;
            }
            Field field = internalBufferField(machine);
            if (field == null) {
                return false;
            }
            try {
                Object buffer = field.get(machine);
                if (buffer == null) {
                    return false;
                }
                Object iteratorObject = invoke(bufferIterator, buffer);
                if (!(iteratorObject instanceof Iterator<?> iterator)) {
                    return false;
                }
                while (iterator.hasNext()) {
                    Object rawEntry = iterator.next();
                    if (!(rawEntry instanceof Reference2LongMap.Entry<?> entry)) {
                        continue;
                    }
                    Object rawKey = entry.getKey();
                    if (!(rawKey instanceof AEKey key) || !isSupported(key)) {
                        continue;
                    }
                    if (!payload.equals(SupplyKeyCodec.encode(key))) {
                        continue;
                    }
                    long current = Math.max(0L, entry.getLongValue());
                    if (current < amount) {
                        return false;
                    }
                    long remaining = current - amount;
                    if (remaining <= 0L) {
                        try {
                            iterator.remove();
                        } catch (UnsupportedOperationException ignored) {
                            @SuppressWarnings("unchecked")
                            Reference2LongMap.Entry<Object> mutable = (Reference2LongMap.Entry<Object>) entry;
                            mutable.setValue(0L);
                        }
                    } else {
                        @SuppressWarnings("unchecked")
                        Reference2LongMap.Entry<Object> mutable = (Reference2LongMap.Entry<Object>) entry;
                        mutable.setValue(remaining);
                    }
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                LOGGER.warn("Could not decrement native GTO ME output buffer: {}", exception.getMessage());
            }
            return false;
        }

        private Field internalBufferField(Object machine) {
            if (!available() || machine == null) {
                return null;
            }
            if (outputBusClass.isInstance(machine)) {
                return internalBufferBus;
            }
            if (outputHatchClass.isInstance(machine)) {
                return internalBufferHatch;
            }
            return null;
        }

        private static Field findField(Class<?> type, String name) {
            Class<?> current = type;
            while (current != null) {
                try {
                    return current.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                    current = current.getSuperclass();
                }
            }
            return null;
        }

        private static Object invoke(Method method, Object target) {
            try {
                return method.invoke(target);
            } catch (IllegalAccessException | InvocationTargetException exception) {
                return null;
            }
        }

        private static String string(Object value) {
            return value == null ? "" : value.toString();
        }

        private static int number(Object value) {
            return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
        }
    }
}
