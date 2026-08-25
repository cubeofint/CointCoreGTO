package Crazer.cubeofinterest.cointcoregto.monitor;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record ClusterMonitorSnapshotPacket(ClusterMonitorSnapshot snapshot) {
    private static final int MAX_NODES = 256;
    private static final int MAX_BUFFERS = 1024;
    private static final int MAX_RESOURCES = 18;
    private static final int MAX_OPERATIONS = 50;

    public static void encode(ClusterMonitorSnapshotPacket packet, FriendlyByteBuf buffer) {
        ClusterMonitorSnapshot snapshot = packet.snapshot();
        buffer.writeBoolean(snapshot.clusterEnabled());
        buffer.writeUtf(snapshot.currentNodeId(), 64);
        buffer.writeLong(snapshot.generatedAtMillis());
        buffer.writeVarInt(Math.max(0, snapshot.activeOperations()));
        buffer.writeUtf(snapshot.error(), 1024);

        int nodeCount = Math.min(MAX_NODES, snapshot.nodes().size());
        buffer.writeVarInt(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            ClusterMonitorSnapshot.NodeEntry node = snapshot.nodes().get(i);
            buffer.writeUtf(node.nodeId(), 64);
            buffer.writeUtf(node.role(), 64);
            buffer.writeVarInt(Math.max(0, node.playerCount()));
            buffer.writeVarInt(Math.max(0, node.dimensionCount()));
            buffer.writeBoolean(node.online());
            buffer.writeVarLong(Math.max(0L, node.heartbeatAgeSeconds()));
        }

        int bufferCount = Math.min(MAX_BUFFERS, snapshot.buffers().size());
        buffer.writeVarInt(bufferCount);
        for (int i = 0; i < bufferCount; i++) {
            ClusterMonitorSnapshot.BufferEntry entry = snapshot.buffers().get(i);
            buffer.writeUtf(entry.endpointId(), 64);
            buffer.writeUtf(entry.linkId(), 64);
            buffer.writeUtf(entry.role(), 24);
            buffer.writeUtf(entry.nodeId(), 64);
            buffer.writeUtf(entry.providerNode(), 64);
            buffer.writeUtf(entry.dimensionId(), 160);
            buffer.writeUtf(entry.blockPosition(), 64);
            buffer.writeUtf(entry.ownerName(), 64);
            buffer.writeBoolean(entry.endpointOnline());
            buffer.writeBoolean(entry.aeOnline());
            buffer.writeBoolean(entry.linkOnline());
            buffer.writeVarInt(Math.max(0, entry.pendingCount()));
            buffer.writeVarLong(Math.max(0L, entry.heartbeatAgeSeconds()));

            int resourceCount = Math.min(MAX_RESOURCES, entry.resources().size());
            buffer.writeVarInt(resourceCount);
            for (int resourceIndex = 0; resourceIndex < resourceCount; resourceIndex++) {
                ClusterMonitorSnapshot.ResourceEntry resource = entry.resources().get(resourceIndex);
                buffer.writeUtf(resource.type(), 16);
                buffer.writeVarInt(Math.max(0, resource.filterIndex()));
                buffer.writeUtf(resource.displayName(), 256);
                buffer.writeUtf(resource.resourceKey(), 256);
                buffer.writeVarLong(Math.max(0L, resource.amount()));
                buffer.writeVarLong(Math.max(0L, resource.capacity()));
                buffer.writeVarInt(Math.max(0, Math.min(100, resource.refillBelowPercent())));
                buffer.writeVarInt(Math.max(0, Math.min(100, resource.refillToPercent())));
            }
        }

        int operationCount = Math.min(MAX_OPERATIONS, snapshot.operations().size());
        buffer.writeVarInt(operationCount);
        for (int i = 0; i < operationCount; i++) {
            ClusterMonitorSnapshot.OperationEntry operation = snapshot.operations().get(i);
            buffer.writeUtf(operation.operationId(), 64);
            buffer.writeUtf(operation.linkId(), 64);
            buffer.writeUtf(operation.sourceNode(), 64);
            buffer.writeUtf(operation.providerNode(), 64);
            buffer.writeUtf(operation.direction(), 24);
            buffer.writeUtf(operation.resourceType(), 16);
            buffer.writeUtf(operation.displayName(), 256);
            buffer.writeUtf(operation.resourceKey(), 256);
            buffer.writeVarLong(Math.max(0L, operation.requestedAmount()));
            buffer.writeVarLong(Math.max(0L, operation.deliveredAmount()));
            buffer.writeUtf(operation.status(), 16);
            buffer.writeUtf(operation.errorText(), 512);
            buffer.writeVarLong(Math.max(0L, operation.createdAgeSeconds()));
            buffer.writeVarLong(Math.max(0L, operation.updatedAgeSeconds()));
        }
    }

    public static ClusterMonitorSnapshotPacket decode(FriendlyByteBuf buffer) {
        boolean clusterEnabled = buffer.readBoolean();
        String currentNodeId = buffer.readUtf(64);
        long generatedAtMillis = buffer.readLong();
        int activeOperations = buffer.readVarInt();
        String error = buffer.readUtf(1024);

        int nodeCount = Math.min(MAX_NODES, Math.max(0, buffer.readVarInt()));
        List<ClusterMonitorSnapshot.NodeEntry> nodes = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            nodes.add(new ClusterMonitorSnapshot.NodeEntry(
                    buffer.readUtf(64),
                    buffer.readUtf(64),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readBoolean(),
                    buffer.readVarLong()
            ));
        }

        int bufferCount = Math.min(MAX_BUFFERS, Math.max(0, buffer.readVarInt()));
        List<ClusterMonitorSnapshot.BufferEntry> buffers = new ArrayList<>(bufferCount);
        for (int i = 0; i < bufferCount; i++) {
            String endpointId = buffer.readUtf(64);
            String linkId = buffer.readUtf(64);
            String role = buffer.readUtf(24);
            String nodeId = buffer.readUtf(64);
            String providerNode = buffer.readUtf(64);
            String dimensionId = buffer.readUtf(160);
            String blockPosition = buffer.readUtf(64);
            String ownerName = buffer.readUtf(64);
            boolean endpointOnline = buffer.readBoolean();
            boolean aeOnline = buffer.readBoolean();
            boolean linkOnline = buffer.readBoolean();
            int pendingCount = buffer.readVarInt();
            long heartbeatAgeSeconds = buffer.readVarLong();

            int resourceCount = Math.min(MAX_RESOURCES, Math.max(0, buffer.readVarInt()));
            List<ClusterMonitorSnapshot.ResourceEntry> resources = new ArrayList<>(resourceCount);
            for (int resourceIndex = 0; resourceIndex < resourceCount; resourceIndex++) {
                resources.add(new ClusterMonitorSnapshot.ResourceEntry(
                        buffer.readUtf(16),
                        buffer.readVarInt(),
                        buffer.readUtf(256),
                        buffer.readUtf(256),
                        buffer.readVarLong(),
                        buffer.readVarLong(),
                        buffer.readVarInt(),
                        buffer.readVarInt()
                ));
            }

            buffers.add(new ClusterMonitorSnapshot.BufferEntry(
                    endpointId,
                    linkId,
                    role,
                    nodeId,
                    providerNode,
                    dimensionId,
                    blockPosition,
                    ownerName,
                    endpointOnline,
                    aeOnline,
                    linkOnline,
                    pendingCount,
                    heartbeatAgeSeconds,
                    resources
            ));
        }

        int operationCount = Math.min(MAX_OPERATIONS, Math.max(0, buffer.readVarInt()));
        List<ClusterMonitorSnapshot.OperationEntry> operations = new ArrayList<>(operationCount);
        for (int i = 0; i < operationCount; i++) {
            operations.add(new ClusterMonitorSnapshot.OperationEntry(
                    buffer.readUtf(64),
                    buffer.readUtf(64),
                    buffer.readUtf(64),
                    buffer.readUtf(64),
                    buffer.readUtf(24),
                    buffer.readUtf(16),
                    buffer.readUtf(256),
                    buffer.readUtf(256),
                    buffer.readVarLong(),
                    buffer.readVarLong(),
                    buffer.readUtf(16),
                    buffer.readUtf(512),
                    buffer.readVarLong(),
                    buffer.readVarLong()
            ));
        }

        return new ClusterMonitorSnapshotPacket(new ClusterMonitorSnapshot(
                clusterEnabled,
                currentNodeId,
                generatedAtMillis,
                activeOperations,
                nodes,
                buffers,
                operations,
                error
        ));
    }

    public static void handle(
            ClusterMonitorSnapshotPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> ClusterMonitorClient.handleSnapshot(packet.snapshot())
        ));
        context.setPacketHandled(true);
    }
}
