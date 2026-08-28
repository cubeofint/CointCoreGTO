package Crazer.cubeofinterest.cointcoregto.monitor;

import java.util.List;

public record ClusterMonitorSnapshot(
        boolean clusterEnabled,
        String currentNodeId,
        long generatedAtMillis,
        int activeOperations,
        List<NodeEntry> nodes,
        List<BufferEntry> buffers,
        List<OperationEntry> operations,
        String error
) {
    public ClusterMonitorSnapshot {
        currentNodeId = currentNodeId == null ? "" : currentNodeId;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        buffers = buffers == null ? List.of() : List.copyOf(buffers);
        operations = operations == null ? List.of() : List.copyOf(operations);
        error = error == null ? "" : error;
    }

    public static ClusterMonitorSnapshot error(String currentNodeId, String message) {
        return new ClusterMonitorSnapshot(
                false,
                currentNodeId,
                System.currentTimeMillis(),
                0,
                List.of(),
                List.of(),
                List.of(),
                message == null ? "Unknown error" : message
        );
    }

    public record NodeEntry(
            String nodeId,
            String role,
            int playerCount,
            int dimensionCount,
            boolean online,
            long heartbeatAgeSeconds
    ) {
        public NodeEntry {
            nodeId = nodeId == null ? "" : nodeId;
            role = role == null ? "" : role;
        }
    }

    public record ResourceEntry(
            String type,
            int filterIndex,
            String displayName,
            String resourceKey,
            long amount,
            long capacity,
            int refillBelowPercent,
            int refillToPercent
    ) {
        public ResourceEntry {
            type = type == null ? "" : type;
            displayName = displayName == null ? "" : displayName;
            resourceKey = resourceKey == null ? "" : resourceKey;
        }
    }

    public record BufferEntry(
            String endpointId,
            String linkId,
            String role,
            String nodeId,
            String providerNode,
            String dimensionId,
            String blockPosition,
            String ownerName,
            boolean endpointOnline,
            boolean aeOnline,
            boolean linkOnline,
            int pendingCount,
            int priority,
            long heartbeatAgeSeconds,
            List<ResourceEntry> resources
    ) {
        public BufferEntry {
            endpointId = endpointId == null ? "" : endpointId;
            linkId = linkId == null ? "" : linkId;
            role = role == null ? "" : role;
            nodeId = nodeId == null ? "" : nodeId;
            providerNode = providerNode == null ? "" : providerNode;
            dimensionId = dimensionId == null ? "" : dimensionId;
            blockPosition = blockPosition == null ? "" : blockPosition;
            ownerName = ownerName == null ? "" : ownerName;
            priority = Math.max(0, priority);
            resources = resources == null ? List.of() : List.copyOf(resources);
        }
    }

    public record OperationEntry(
            String operationId,
            String linkId,
            String sourceNode,
            String providerNode,
            String direction,
            String resourceType,
            String displayName,
            String resourceKey,
            long requestedAmount,
            long deliveredAmount,
            int priority,
            String status,
            String errorText,
            long createdAgeSeconds,
            long updatedAgeSeconds
    ) {
        public OperationEntry {
            operationId = operationId == null ? "" : operationId;
            linkId = linkId == null ? "" : linkId;
            sourceNode = sourceNode == null ? "" : sourceNode;
            providerNode = providerNode == null ? "" : providerNode;
            direction = direction == null ? "" : direction;
            resourceType = resourceType == null ? "" : resourceType;
            displayName = displayName == null ? "" : displayName;
            resourceKey = resourceKey == null ? "" : resourceKey;
            status = status == null ? "" : status;
            errorText = errorText == null ? "" : errorText;
            requestedAmount = Math.max(0L, requestedAmount);
            deliveredAmount = Math.max(0L, deliveredAmount);
            priority = Math.max(0, priority);
            createdAgeSeconds = Math.max(0L, createdAgeSeconds);
            updatedAgeSeconds = Math.max(0L, updatedAgeSeconds);
        }
    }
}
