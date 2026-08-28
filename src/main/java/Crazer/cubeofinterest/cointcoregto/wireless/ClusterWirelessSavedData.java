package Crazer.cubeofinterest.cointcoregto.wireless;

import Crazer.cubeofinterest.cointcoregto.supply.SupplyBufferDatabase;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ClusterWirelessSavedData extends SavedData {
    private static final String DATA_NAME = "cointcoregto_cluster_wireless";
    private static final int MAX_RESOURCE_ENTRIES = 4096;
    private static final int MAX_PENDING_ENTRIES = 256;

    private final Map<String, EndpointState> endpoints = new LinkedHashMap<>();
    private final Set<String> nativeMirrorIds = new LinkedHashSet<>();
    private final Map<String, NativeProviderJournal> nativeProviderJournals = new LinkedHashMap<>();
    private UUID providerJournalOperationId;
    private long providerJournalDelivered;

    public static ClusterWirelessSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                ClusterWirelessSavedData::load,
                ClusterWirelessSavedData::new,
                DATA_NAME
        );
    }

    public static ClusterWirelessSavedData load(CompoundTag tag) {
        ClusterWirelessSavedData data = new ClusterWirelessSavedData();
        if (tag == null) {
            return data;
        }

        ListTag endpointList = tag.getList("Endpoints", Tag.TAG_COMPOUND);
        for (int index = 0; index < endpointList.size(); index++) {
            CompoundTag endpointTag = endpointList.getCompound(index);
            String id = endpointTag.getString("Id");
            if (id.isBlank()) {
                continue;
            }
            data.endpoints.put(id, EndpointState.load(endpointTag));
        }

        ListTag mirrorList = tag.getList("NativeMirrors", Tag.TAG_COMPOUND);
        for (int index = 0; index < mirrorList.size(); index++) {
            String id = mirrorList.getCompound(index).getString("Id");
            if (!id.isBlank()) {
                data.nativeMirrorIds.add(id);
            }
        }

        ListTag nativeJournalList = tag.getList("NativeProviderJournals", Tag.TAG_COMPOUND);
        for (int index = 0; index < nativeJournalList.size(); index++) {
            CompoundTag journalTag = nativeJournalList.getCompound(index);
            String linkId = journalTag.getString("LinkId");
            if (linkId.isBlank() || !journalTag.hasUUID("OperationId")) {
                continue;
            }
            data.nativeProviderJournals.put(linkId, new NativeProviderJournal(
                    journalTag.getUUID("OperationId"),
                    Math.max(0L, journalTag.getLong("Delivered"))
            ));
        }

        if (tag.hasUUID("ProviderJournalOperation")) {
            data.providerJournalOperationId = tag.getUUID("ProviderJournalOperation");
            data.providerJournalDelivered = Math.max(0L, tag.getLong("ProviderJournalDelivered"));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag endpointList = new ListTag();
        for (Map.Entry<String, EndpointState> entry : endpoints.entrySet()) {
            CompoundTag endpointTag = entry.getValue().save();
            endpointTag.putString("Id", entry.getKey());
            endpointList.add(endpointTag);
        }
        tag.put("Endpoints", endpointList);

        ListTag mirrorList = new ListTag();
        for (String id : nativeMirrorIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            CompoundTag mirrorTag = new CompoundTag();
            mirrorTag.putString("Id", id);
            mirrorList.add(mirrorTag);
        }
        tag.put("NativeMirrors", mirrorList);

        ListTag nativeJournalList = new ListTag();
        for (Map.Entry<String, NativeProviderJournal> entry : nativeProviderJournals.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            CompoundTag journalTag = new CompoundTag();
            journalTag.putString("LinkId", entry.getKey());
            journalTag.putUUID("OperationId", entry.getValue().operationId());
            journalTag.putLong("Delivered", Math.max(0L, entry.getValue().delivered()));
            nativeJournalList.add(journalTag);
        }
        tag.put("NativeProviderJournals", nativeJournalList);

        if (providerJournalOperationId != null) {
            tag.putUUID("ProviderJournalOperation", providerJournalOperationId);
            tag.putLong("ProviderJournalDelivered", Math.max(0L, providerJournalDelivered));
        }
        return tag;
    }

    public EndpointState endpoint(String endpointId) {
        return endpoints.computeIfAbsent(endpointId, ignored -> new EndpointState());
    }

    public boolean hasEndpoint(String endpointId) {
        return endpoints.containsKey(endpointId);
    }

    public void removeEndpoint(String endpointId) {
        if (endpoints.remove(endpointId) != null) {
            setDirty();
        }
    }

    public Set<String> nativeMirrorIds() {
        return nativeMirrorIds;
    }

    public void replaceNativeMirrorIds(Collection<String> ids) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isBlank()) {
                    normalized.add(id);
                }
            }
        }
        if (!nativeMirrorIds.equals(normalized)) {
            nativeMirrorIds.clear();
            nativeMirrorIds.addAll(normalized);
            setDirty();
        }
    }

    public NativeProviderJournal nativeProviderJournal(String linkId) {
        return linkId == null ? null : nativeProviderJournals.get(linkId);
    }

    public void setNativeProviderJournal(String linkId, UUID operationId, long delivered) {
        if (linkId == null || linkId.isBlank() || operationId == null) {
            return;
        }
        nativeProviderJournals.put(linkId, new NativeProviderJournal(operationId, Math.max(0L, delivered)));
        setDirty();
    }

    public void clearNativeProviderJournal(String linkId) {
        if (linkId != null && nativeProviderJournals.remove(linkId) != null) {
            setDirty();
        }
    }

    public UUID providerJournalOperationId() {
        return providerJournalOperationId;
    }

    public long providerJournalDelivered() {
        return Math.max(0L, providerJournalDelivered);
    }

    public void setProviderJournal(UUID operationId, long delivered) {
        providerJournalOperationId = operationId;
        providerJournalDelivered = Math.max(0L, delivered);
        setDirty();
    }

    public void clearProviderJournal() {
        if (providerJournalOperationId != null || providerJournalDelivered != 0L) {
            providerJournalOperationId = null;
            providerJournalDelivered = 0L;
            setDirty();
        }
    }

    public static final class EndpointState {
        private final Map<String, Long> outgoing = new LinkedHashMap<>();
        private final Map<String, Long> incoming = new LinkedHashMap<>();
        private final Map<String, Long> wanted = new LinkedHashMap<>();
        private final Map<UUID, PendingTransfer> pending = new LinkedHashMap<>();
        private final Set<UUID> acknowledgements = new LinkedHashSet<>();
        private String activeLinkId = "";

        private CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.put("Outgoing", saveAmounts(outgoing));
            tag.put("Incoming", saveAmounts(incoming));
            tag.put("Wanted", saveAmounts(wanted));
            if (!activeLinkId.isBlank()) {
                tag.putString("ActiveLinkId", activeLinkId);
            }

            ListTag pendingList = new ListTag();
            int written = 0;
            for (PendingTransfer transfer : pending.values()) {
                if (written++ >= MAX_PENDING_ENTRIES) {
                    break;
                }
                CompoundTag operation = new CompoundTag();
                operation.putUUID("OperationId", transfer.operationId());
                operation.putString("Direction", transfer.direction().name());
                operation.putString("Type", transfer.resourceType().name());
                operation.putString("Key", transfer.keyPayload());
                operation.putLong("Amount", Math.max(1L, transfer.amount()));
                operation.putInt("Priority", Math.max(0, transfer.priority()));
                pendingList.add(operation);
            }
            tag.put("Pending", pendingList);

            ListTag ackList = new ListTag();
            int ackWritten = 0;
            for (UUID operationId : acknowledgements) {
                if (ackWritten++ >= MAX_PENDING_ENTRIES) {
                    break;
                }
                CompoundTag ack = new CompoundTag();
                ack.putUUID("OperationId", operationId);
                ackList.add(ack);
            }
            tag.put("Acknowledgements", ackList);
            return tag;
        }

        private static EndpointState load(CompoundTag tag) {
            EndpointState state = new EndpointState();
            loadAmounts(tag.getList("Outgoing", Tag.TAG_COMPOUND), state.outgoing);
            loadAmounts(tag.getList("Incoming", Tag.TAG_COMPOUND), state.incoming);
            loadAmounts(tag.getList("Wanted", Tag.TAG_COMPOUND), state.wanted);
            state.activeLinkId = tag.getString("ActiveLinkId");

            ListTag pendingList = tag.getList("Pending", Tag.TAG_COMPOUND);
            for (int index = 0; index < pendingList.size() && state.pending.size() < MAX_PENDING_ENTRIES; index++) {
                CompoundTag operation = pendingList.getCompound(index);
                if (!operation.hasUUID("OperationId")) {
                    continue;
                }
                try {
                    PendingTransfer transfer = new PendingTransfer(
                            operation.getUUID("OperationId"),
                            SupplyBufferDatabase.TransferDirection.valueOf(operation.getString("Direction")),
                            SupplyBufferDatabase.ResourceType.valueOf(operation.getString("Type")),
                            operation.getString("Key"),
                            Math.max(1L, operation.getLong("Amount")),
                            Math.max(0, operation.getInt("Priority"))
                    );
                    if (!transfer.keyPayload().isBlank()) {
                        state.pending.put(transfer.operationId(), transfer);
                    }
                } catch (RuntimeException ignored) {
                }
            }

            ListTag ackList = tag.getList("Acknowledgements", Tag.TAG_COMPOUND);
            for (int index = 0; index < ackList.size() && state.acknowledgements.size() < MAX_PENDING_ENTRIES; index++) {
                CompoundTag ack = ackList.getCompound(index);
                if (ack.hasUUID("OperationId")) {
                    state.acknowledgements.add(ack.getUUID("OperationId"));
                }
            }
            return state;
        }

        private static ListTag saveAmounts(Map<String, Long> values) {
            ListTag result = new ListTag();
            int written = 0;
            for (Map.Entry<String, Long> entry : values.entrySet()) {
                long amount = Math.max(0L, entry.getValue() == null ? 0L : entry.getValue());
                if (amount <= 0L || entry.getKey() == null || entry.getKey().isBlank()) {
                    continue;
                }
                if (written++ >= MAX_RESOURCE_ENTRIES) {
                    break;
                }
                CompoundTag resource = new CompoundTag();
                resource.putString("Key", entry.getKey());
                resource.putLong("Amount", amount);
                result.add(resource);
            }
            return result;
        }

        private static void loadAmounts(ListTag list, Map<String, Long> target) {
            for (int index = 0; index < list.size() && target.size() < MAX_RESOURCE_ENTRIES; index++) {
                CompoundTag resource = list.getCompound(index);
                String key = resource.getString("Key");
                long amount = Math.max(0L, resource.getLong("Amount"));
                if (!key.isBlank() && amount > 0L) {
                    target.put(key, amount);
                }
            }
        }

        public Map<String, Long> outgoing() {
            return outgoing;
        }

        public Map<String, Long> incoming() {
            return incoming;
        }

        public Map<String, Long> wanted() {
            return wanted;
        }

        public Map<UUID, PendingTransfer> pending() {
            return pending;
        }

        public Set<UUID> acknowledgements() {
            return acknowledgements;
        }

        public String activeLinkId() {
            return activeLinkId == null ? "" : activeLinkId;
        }

        public void setActiveLinkId(String activeLinkId) {
            this.activeLinkId = activeLinkId == null ? "" : activeLinkId;
        }

        public boolean hasResources() {
            return hasPositive(outgoing.values())
                    || hasPositive(incoming.values())
                    || !pending.isEmpty();
        }

        public boolean isEmpty() {
            return !hasResources() && !hasPositive(wanted.values()) && acknowledgements.isEmpty();
        }

        private static boolean hasPositive(Collection<Long> values) {
            for (Long value : values) {
                if (value != null && value > 0L) {
                    return true;
                }
            }
            return false;
        }
    }

    public record NativeProviderJournal(UUID operationId, long delivered) {
        public NativeProviderJournal {
            if (operationId == null) {
                throw new IllegalArgumentException("operationId");
            }
            delivered = Math.max(0L, delivered);
        }
    }

    public record PendingTransfer(
            UUID operationId,
            SupplyBufferDatabase.TransferDirection direction,
            SupplyBufferDatabase.ResourceType resourceType,
            String keyPayload,
            long amount,
            int priority
    ) {
        public PendingTransfer {
            if (operationId == null) {
                throw new IllegalArgumentException("operationId");
            }
            direction = direction == null
                    ? SupplyBufferDatabase.TransferDirection.MAIN_TO_REMOTE
                    : direction;
            resourceType = resourceType == null
                    ? SupplyBufferDatabase.ResourceType.ITEM
                    : resourceType;
            keyPayload = keyPayload == null ? "" : keyPayload;
            amount = Math.max(1L, amount);
            priority = Math.max(0, priority);
        }

        public SupplyBufferDatabase.PendingDescriptor descriptor() {
            return new SupplyBufferDatabase.PendingDescriptor(
                    operationId,
                    direction,
                    resourceType,
                    keyPayload,
                    amount,
                    priority
            );
        }
    }
}
