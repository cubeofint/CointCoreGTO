package Crazer.cubeofinterest.cointcoregto.supply;

import Crazer.cubeofinterest.cointcoregto.ClusterConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SupplyBufferDatabase {
    private static final String SHADED_MYSQL_DRIVER =
            "crazer.cubeofinterest.cointcoregto.shadow.mysql.cj.jdbc.Driver";
    private static final String DEVELOPMENT_MYSQL_DRIVER =
            "com.mysql.cj.jdbc.Driver";

    private static volatile boolean driverLoaded;
    private static volatile String initializedSchemaKey;

    private SupplyBufferDatabase() {
    }

    public enum TransferDirection {
        REMOTE_TO_MAIN,
        MAIN_TO_REMOTE
    }

    public enum ResourceType {
        ITEM,
        FLUID
    }

    public record PendingDescriptor(
            UUID operationId,
            TransferDirection direction,
            ResourceType resourceType,
            String keyPayload,
            long amount
    ) {
    }

    public record Operation(
            UUID operationId,
            String linkId,
            String sourceNode,
            TransferDirection direction,
            ResourceType resourceType,
            String keyPayload,
            long requestedAmount,
            long deliveredAmount,
            String status,
            String claimedBy,
            String errorText
    ) {
    }

    public record MonitorOperation(
            UUID operationId,
            String linkId,
            String sourceNode,
            String providerNode,
            TransferDirection direction,
            ResourceType resourceType,
            String keyPayload,
            long requestedAmount,
            long deliveredAmount,
            String status,
            String errorText,
            long createdAgeSeconds,
            long updatedAgeSeconds
    ) {
        public MonitorOperation {
            linkId = linkId == null ? "" : linkId;
            sourceNode = sourceNode == null ? "" : sourceNode;
            providerNode = providerNode == null ? "" : providerNode;
            keyPayload = keyPayload == null ? "" : keyPayload;
            status = status == null ? "" : status;
            errorText = errorText == null ? "" : errorText;
            requestedAmount = Math.max(0L, requestedAmount);
            deliveredAmount = Math.max(0L, deliveredAmount);
            createdAgeSeconds = Math.max(0L, createdAgeSeconds);
            updatedAgeSeconds = Math.max(0L, updatedAgeSeconds);
        }
    }

    public record OperationResult(
            String status,
            long deliveredAmount,
            String errorText
    ) {
        public boolean applied() {
            return "APPLIED".equalsIgnoreCase(status)
                    || "CONSUMED".equalsIgnoreCase(status);
        }

        public boolean failed() {
            return "FAILED".equalsIgnoreCase(status);
        }
    }

    public record RemoteSyncResult(
            boolean providerOnline,
            Map<UUID, OperationResult> results,
            List<UUID> acknowledged
    ) {
    }

    /**
     * Result of a remote-side attempt to cancel a request before the provider
     * has applied it. If cancelled is false, result contains the operation's
     * current database state so the block entity can safely finish an already
     * claimed/applied transfer instead of losing resources.
     */
    public record CancelResult(
            boolean cancelled,
            OperationResult result
    ) {
    }

    public record ResourceSnapshot(
            ResourceType resourceType,
            int filterIndex,
            String displayName,
            String resourceKey,
            long amount,
            long capacity,
            int refillBelowPercent,
            int refillToPercent
    ) {
        public ResourceSnapshot {
            resourceType = resourceType == null ? ResourceType.ITEM : resourceType;
            filterIndex = Math.max(0, filterIndex);
            displayName = displayName == null ? "" : displayName;
            resourceKey = resourceKey == null ? "" : resourceKey;
            amount = Math.max(0L, amount);
            capacity = Math.max(0L, capacity);
            refillBelowPercent = Math.max(0, Math.min(100, refillBelowPercent));
            refillToPercent = Math.max(0, Math.min(100, refillToPercent));
        }
    }

    public record EndpointStatus(
            String endpointId,
            String linkId,
            String role,
            String nodeId,
            String providerNode,
            String dimensionId,
            String blockPosition,
            String ownerName,
            boolean online,
            boolean aeOnline,
            boolean linkOnline,
            int pendingCount,
            long heartbeatAgeSeconds,
            List<ResourceSnapshot> resources
    ) {
        public EndpointStatus {
            endpointId = endpointId == null ? "" : endpointId;
            linkId = linkId == null ? "" : linkId;
            role = role == null ? "" : role;
            nodeId = nodeId == null ? "" : nodeId;
            providerNode = providerNode == null ? "" : providerNode;
            dimensionId = dimensionId == null ? "" : dimensionId;
            blockPosition = blockPosition == null ? "" : blockPosition;
            ownerName = ownerName == null ? "" : ownerName;
            pendingCount = Math.max(0, pendingCount);
            heartbeatAgeSeconds = Math.max(0L, heartbeatAgeSeconds);
            resources = resources == null ? List.of() : List.copyOf(resources);
        }
    }

    public static void touchEndpoint(
            ClusterConfig config,
            String endpointId,
            String linkId,
            String role,
            String nodeId,
            String providerNode,
            String dimensionId,
            String blockPosition,
            UUID ownerUuid,
            String ownerName,
            boolean aeOnline,
            boolean linkOnline,
            int pendingCount,
            Collection<ResourceSnapshot> resources
    ) throws SQLException {
        ensureSchema(config);
        String resourceJson = encodeResources(resources);

        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO cluster_supply_endpoints (
                         endpoint_id, link_id, endpoint_role, node_id, provider_node,
                         dimension_id, block_position, owner_uuid, owner_name,
                         ae_online, link_online, pending_count, resource_snapshot, last_seen
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3))
                     ON DUPLICATE KEY UPDATE
                         link_id = VALUES(link_id),
                         endpoint_role = VALUES(endpoint_role),
                         node_id = VALUES(node_id),
                         provider_node = VALUES(provider_node),
                         dimension_id = VALUES(dimension_id),
                         block_position = VALUES(block_position),
                         owner_uuid = VALUES(owner_uuid),
                         owner_name = VALUES(owner_name),
                         ae_online = VALUES(ae_online),
                         link_online = VALUES(link_online),
                         pending_count = VALUES(pending_count),
                         resource_snapshot = VALUES(resource_snapshot),
                         last_seen = CURRENT_TIMESTAMP(3)
                     """)) {
            statement.setString(1, truncate(endpointId, 64));
            statement.setString(2, truncate(linkId, 64));
            statement.setString(3, truncate(role, 24));
            statement.setString(4, truncate(nodeId, 64));
            statement.setString(5, truncate(providerNode, 64));
            statement.setString(6, truncate(dimensionId, 160));
            statement.setString(7, truncate(blockPosition, 64));
            if (ownerUuid == null) {
                statement.setNull(8, java.sql.Types.CHAR);
            } else {
                statement.setString(8, ownerUuid.toString());
            }
            statement.setString(9, truncate(ownerName, 64));
            statement.setBoolean(10, aeOnline);
            statement.setBoolean(11, linkOnline);
            statement.setInt(12, Math.max(0, pendingCount));
            statement.setString(13, resourceJson);
            statement.executeUpdate();
        }
    }

    public static List<EndpointStatus> listEndpoints(ClusterConfig config) throws SQLException {
        ensureSchema(config);
        String sql = """
                SELECT endpoints.endpoint_id, endpoints.link_id, endpoints.endpoint_role,
                       endpoints.node_id, endpoints.provider_node, endpoints.dimension_id,
                       endpoints.block_position, endpoints.owner_name, endpoints.ae_online,
                       endpoints.link_online, endpoints.pending_count, endpoints.resource_snapshot,
                       GREATEST(0, TIMESTAMPDIFF(SECOND, endpoints.last_seen, CURRENT_TIMESTAMP(3))) AS heartbeat_age_seconds,
                       CASE
                           WHEN endpoints.last_seen >= TIMESTAMPADD(SECOND, -?, CURRENT_TIMESTAMP(3))
                            AND nodes.node_id IS NOT NULL
                            AND nodes.stopped_at IS NULL
                            AND nodes.last_seen >= TIMESTAMPADD(SECOND, -?, CURRENT_TIMESTAMP(3))
                           THEN 1 ELSE 0
                       END AS endpoint_online
                FROM cluster_supply_endpoints AS endpoints
                LEFT JOIN cluster_nodes AS nodes ON BINARY nodes.node_id = BINARY endpoints.node_id
                WHERE endpoints.last_seen >= TIMESTAMPADD(MINUTE, -30, CURRENT_TIMESTAMP(3))
                ORDER BY endpoints.node_id, endpoints.endpoint_role, endpoints.owner_name, endpoints.endpoint_id
                """;

        List<EndpointStatus> endpoints = new ArrayList<>();
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int timeout = Math.max(5, config.nodeTimeoutSeconds());
            statement.setInt(1, timeout);
            statement.setInt(2, timeout);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    endpoints.add(new EndpointStatus(
                            resultSet.getString("endpoint_id"),
                            resultSet.getString("link_id"),
                            resultSet.getString("endpoint_role"),
                            resultSet.getString("node_id"),
                            resultSet.getString("provider_node"),
                            resultSet.getString("dimension_id"),
                            resultSet.getString("block_position"),
                            resultSet.getString("owner_name"),
                            resultSet.getBoolean("endpoint_online"),
                            resultSet.getBoolean("ae_online"),
                            resultSet.getBoolean("link_online"),
                            resultSet.getInt("pending_count"),
                            resultSet.getLong("heartbeat_age_seconds"),
                            decodeResources(resultSet.getString("resource_snapshot"))
                    ));
                }
            }
        }
        return List.copyOf(endpoints);
    }

    public static int countActiveOperations(ClusterConfig config) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM cluster_supply_operations
                     WHERE status IN ('PENDING', 'CLAIMED', 'APPLIED')
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    public static List<MonitorOperation> listRecentOperations(
            ClusterConfig config,
            int requestedLimit
    ) throws SQLException {
        ensureSchema(config);
        int limit = Math.max(1, Math.min(50, requestedLimit));
        String sql = """
                SELECT operations.operation_id, operations.link_id, operations.source_node,
                       COALESCE(NULLIF(operations.claimed_by, ''), providers.node_id, '') AS provider_node,
                       operations.direction, operations.resource_type, operations.resource_nbt,
                       operations.requested_amount, operations.delivered_amount, operations.status,
                       COALESCE(operations.error_text, '') AS error_text,
                       GREATEST(0, TIMESTAMPDIFF(SECOND, operations.created_at, CURRENT_TIMESTAMP(3))) AS created_age_seconds,
                       GREATEST(0, TIMESTAMPDIFF(SECOND, operations.updated_at, CURRENT_TIMESTAMP(3))) AS updated_age_seconds
                FROM cluster_supply_operations AS operations
                LEFT JOIN cluster_supply_providers AS providers
                  ON BINARY providers.link_id = BINARY operations.link_id
                ORDER BY operations.updated_at DESC
                LIMIT ?
                """;

        List<MonitorOperation> result = new ArrayList<>(limit);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    TransferDirection direction;
                    ResourceType resourceType;
                    try {
                        direction = TransferDirection.valueOf(resultSet.getString("direction"));
                        resourceType = ResourceType.valueOf(resultSet.getString("resource_type"));
                    } catch (IllegalArgumentException exception) {
                        continue;
                    }

                    UUID operationId;
                    try {
                        operationId = UUID.fromString(resultSet.getString("operation_id"));
                    } catch (IllegalArgumentException exception) {
                        continue;
                    }

                    result.add(new MonitorOperation(
                            operationId,
                            resultSet.getString("link_id"),
                            resultSet.getString("source_node"),
                            resultSet.getString("provider_node"),
                            direction,
                            resourceType,
                            resultSet.getString("resource_nbt"),
                            resultSet.getLong("requested_amount"),
                            resultSet.getLong("delivered_amount"),
                            resultSet.getString("status"),
                            resultSet.getString("error_text"),
                            resultSet.getLong("created_age_seconds"),
                            resultSet.getLong("updated_age_seconds")
                    ));
                }
            }
        }
        return List.copyOf(result);
    }

    public static void touchProvider(
            ClusterConfig config,
            String linkId,
            String nodeId,
            String dimensionId,
            String blockPosition,
            boolean aeOnline
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO cluster_supply_providers (
                         link_id, node_id, dimension_id, block_position, ae_online, last_seen
                     ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3))
                     ON DUPLICATE KEY UPDATE
                         node_id = VALUES(node_id),
                         dimension_id = VALUES(dimension_id),
                         block_position = VALUES(block_position),
                         ae_online = VALUES(ae_online),
                         last_seen = CURRENT_TIMESTAMP(3)
                     """)) {
            statement.setString(1, truncate(linkId, 64));
            statement.setString(2, truncate(nodeId, 64));
            statement.setString(3, truncate(dimensionId, 128));
            statement.setString(4, truncate(blockPosition, 64));
            statement.setBoolean(5, aeOnline);
            statement.executeUpdate();
        }

        cleanupConsumed(config);
    }

    public static Operation claimNext(
            ClusterConfig config,
            String linkId,
            String providerNode
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                recoverStaleClaims(connection, config, linkId);

                Operation selected = null;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT operation_id, link_id, source_node, direction, resource_type,
                               resource_nbt, requested_amount, delivered_amount, status,
                               claimed_by, error_text
                        FROM cluster_supply_operations
                        WHERE link_id = ? AND status = 'PENDING'
                        ORDER BY updated_at ASC, created_at ASC
                        LIMIT 1
                        FOR UPDATE
                        """)) {
                    statement.setString(1, truncate(linkId, 64));
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (resultSet.next()) {
                            selected = readOperation(resultSet);
                        }
                    }
                }

                if (selected == null) {
                    connection.commit();
                    return null;
                }

                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE cluster_supply_operations
                        SET status = 'CLAIMED',
                            claimed_by = ?,
                            claimed_at = CURRENT_TIMESTAMP(3),
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE operation_id = ? AND status = 'PENDING'
                        """)) {
                    statement.setString(1, truncate(providerNode, 64));
                    statement.setString(2, selected.operationId().toString());
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return null;
                    }
                }

                connection.commit();
                return new Operation(
                        selected.operationId(),
                        selected.linkId(),
                        selected.sourceNode(),
                        selected.direction(),
                        selected.resourceType(),
                        selected.keyPayload(),
                        selected.requestedAmount(),
                        selected.deliveredAmount(),
                        "CLAIMED",
                        providerNode,
                        selected.errorText()
                );
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static void markApplied(
            ClusterConfig config,
            UUID operationId,
            String providerNode,
            long deliveredAmount
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE cluster_supply_operations
                     SET status = 'APPLIED',
                         delivered_amount = ?,
                         claimed_by = ?,
                         claimed_at = COALESCE(claimed_at, CURRENT_TIMESTAMP(3)),
                         error_text = NULL,
                         updated_at = CURRENT_TIMESTAMP(3)
                     WHERE operation_id = ?
                       AND (
                           status = 'PENDING'
                           OR (status = 'CLAIMED' AND (claimed_by = ? OR claimed_by IS NULL))
                       )
                     """)) {
            statement.setLong(1, Math.max(0L, deliveredAmount));
            statement.setString(2, truncate(providerNode, 64));
            statement.setString(3, operationId.toString());
            statement.setString(4, truncate(providerNode, 64));
            int updated = statement.executeUpdate();
            if (updated == 0 && !isAlreadyFinal(connection, operationId)) {
                throw new SQLException("Supply operation is no longer owned by this provider: " + operationId);
            }
        }
    }

    public static void releaseClaim(
            ClusterConfig config,
            UUID operationId,
            String providerNode,
            String reason
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE cluster_supply_operations
                     SET status = 'PENDING',
                         claimed_by = NULL,
                         claimed_at = NULL,
                         error_text = ?,
                         updated_at = CURRENT_TIMESTAMP(3)
                     WHERE operation_id = ?
                       AND status = 'CLAIMED'
                       AND (claimed_by = ? OR claimed_by IS NULL)
                     """)) {
            statement.setString(1, truncate(reason, 512));
            statement.setString(2, operationId.toString());
            statement.setString(3, truncate(providerNode, 64));
            statement.executeUpdate();
        }
    }

    public static void markFailed(
            ClusterConfig config,
            UUID operationId,
            String providerNode,
            String error
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE cluster_supply_operations
                     SET status = 'FAILED',
                         claimed_by = ?,
                         error_text = ?,
                         updated_at = CURRENT_TIMESTAMP(3)
                     WHERE operation_id = ?
                       AND status IN ('PENDING', 'CLAIMED')
                     """)) {
            statement.setString(1, truncate(providerNode, 64));
            statement.setString(2, truncate(error, 512));
            statement.setString(3, operationId.toString());
            statement.executeUpdate();
        }
    }

    public static CancelResult tryCancelPending(
            ClusterConfig config,
            UUID operationId,
            String sourceNode
    ) throws SQLException {
        ensureSchema(config);
        if (operationId == null) {
            return new CancelResult(true, null);
        }

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                int deleted;
                try (PreparedStatement statement = connection.prepareStatement("""
                        DELETE FROM cluster_supply_operations
                        WHERE operation_id = ?
                          AND source_node = ?
                          AND status = 'PENDING'
                        """)) {
                    statement.setString(1, operationId.toString());
                    statement.setString(2, truncate(sourceNode, 64));
                    deleted = statement.executeUpdate();
                }

                if (deleted > 0) {
                    connection.commit();
                    return new CancelResult(true, null);
                }

                OperationResult result = readResultForSource(connection, operationId, sourceNode);
                connection.commit();

                // No row means the local PendingTransfer had not reached SQL yet.
                // Treat it as cancelled; the caller stops submitting it afterwards.
                if (result == null) {
                    return new CancelResult(true, null);
                }
                return new CancelResult(false, result);
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    private static OperationResult readResultForSource(
            Connection connection,
            UUID operationId,
            String sourceNode
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status, delivered_amount, error_text
                FROM cluster_supply_operations
                WHERE operation_id = ?
                  AND source_node = ?
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, truncate(sourceNode, 64));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new OperationResult(
                        resultSet.getString("status"),
                        resultSet.getLong("delivered_amount"),
                        resultSet.getString("error_text")
                );
            }
        }
    }

    public static RemoteSyncResult syncRemote(
            ClusterConfig config,
            String linkId,
            String sourceNode,
            Collection<PendingDescriptor> pending,
            Collection<UUID> acknowledgements
    ) throws SQLException {
        ensureSchema(config);

        Map<UUID, OperationResult> results = new LinkedHashMap<>();
        List<UUID> acknowledged = new ArrayList<>();
        boolean providerOnline;

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                if (acknowledgements != null) {
                    for (UUID operationId : acknowledgements) {
                        if (operationId == null) {
                            continue;
                        }
                        acknowledge(connection, operationId, sourceNode);
                        acknowledged.add(operationId);
                    }
                }

                if (pending != null) {
                    for (PendingDescriptor descriptor : pending) {
                        if (descriptor == null
                                || descriptor.operationId() == null
                                || descriptor.amount() <= 0L) {
                            continue;
                        }
                        submitOperation(connection, linkId, sourceNode, descriptor);
                    }

                    for (PendingDescriptor descriptor : pending) {
                        if (descriptor == null || descriptor.operationId() == null) {
                            continue;
                        }
                        OperationResult result = readResult(connection, descriptor.operationId());
                        if (result != null) {
                            results.put(descriptor.operationId(), result);
                        }
                    }
                }

                providerOnline = isProviderOnline(connection, linkId, config.nodeTimeoutSeconds());
                connection.commit();
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }

        return new RemoteSyncResult(providerOnline, Map.copyOf(results), List.copyOf(acknowledged));
    }

    private static void submitOperation(
            Connection connection,
            String linkId,
            String sourceNode,
            PendingDescriptor descriptor
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO cluster_supply_operations (
                    operation_id, link_id, source_node, direction, resource_type,
                    resource_nbt, requested_amount, delivered_amount, status,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'PENDING', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                ON DUPLICATE KEY UPDATE operation_id = operation_id
                """)) {
            statement.setString(1, descriptor.operationId().toString());
            statement.setString(2, truncate(linkId, 64));
            statement.setString(3, truncate(sourceNode, 64));
            statement.setString(4, descriptor.direction().name());
            statement.setString(5, descriptor.resourceType().name());
            statement.setString(6, descriptor.keyPayload());
            statement.setLong(7, Math.max(1L, descriptor.amount()));
            statement.executeUpdate();
        }
    }

    private static OperationResult readResult(
            Connection connection,
            UUID operationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status, delivered_amount, error_text
                FROM cluster_supply_operations
                WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new OperationResult(
                        resultSet.getString("status"),
                        resultSet.getLong("delivered_amount"),
                        resultSet.getString("error_text")
                );
            }
        }
    }

    private static void acknowledge(
            Connection connection,
            UUID operationId,
            String sourceNode
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE cluster_supply_operations
                SET status = 'CONSUMED',
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE operation_id = ?
                  AND source_node = ?
                  AND status IN ('APPLIED', 'FAILED', 'CONSUMED')
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, truncate(sourceNode, 64));
            statement.executeUpdate();
        }
    }

    private static boolean isProviderOnline(
            Connection connection,
            String linkId,
            int timeoutSeconds
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT ae_online,
                       (last_seen >= TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(3))) AS fresh
                FROM cluster_supply_providers
                WHERE link_id = ?
                """)) {
            statement.setInt(1, -Math.max(5, timeoutSeconds));
            statement.setString(2, truncate(linkId, 64));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        && resultSet.getBoolean("ae_online")
                        && resultSet.getBoolean("fresh");
            }
        }
    }

    private static void recoverStaleClaims(
            Connection connection,
            ClusterConfig config,
            String linkId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE cluster_supply_operations
                SET status = 'PENDING',
                    claimed_by = NULL,
                    claimed_at = NULL,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE link_id = ?
                  AND status = 'CLAIMED'
                  AND claimed_at < TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(3))
                """)) {
            statement.setString(1, truncate(linkId, 64));
            statement.setInt(2, -Math.max(10, config.staleClaimSeconds()));
            statement.executeUpdate();
        }
    }

    private static boolean isAlreadyFinal(Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status
                FROM cluster_supply_operations
                WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                String status = resultSet.getString(1);
                return "APPLIED".equalsIgnoreCase(status)
                        || "CONSUMED".equalsIgnoreCase(status);
            }
        }
    }

    private static Operation readOperation(ResultSet resultSet) throws SQLException {
        return new Operation(
                UUID.fromString(resultSet.getString("operation_id")),
                resultSet.getString("link_id"),
                resultSet.getString("source_node"),
                TransferDirection.valueOf(resultSet.getString("direction")),
                ResourceType.valueOf(resultSet.getString("resource_type")),
                resultSet.getString("resource_nbt"),
                resultSet.getLong("requested_amount"),
                resultSet.getLong("delivered_amount"),
                resultSet.getString("status"),
                resultSet.getString("claimed_by"),
                resultSet.getString("error_text")
        );
    }

    private static void cleanupConsumed(ClusterConfig config) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM cluster_supply_operations
                     WHERE status = 'CONSUMED'
                       AND updated_at < TIMESTAMPADD(DAY, -1, CURRENT_TIMESTAMP(3))
                     LIMIT 500
                     """)) {
            statement.executeUpdate();
        }
    }

    private static Connection open(ClusterConfig config) throws SQLException {
        ensureDriverLoaded();
        return DriverManager.getConnection(
                config.jdbcUrl(),
                config.username(),
                config.password()
        );
    }

    private static synchronized void ensureDriverLoaded() throws SQLException {
        if (driverLoaded) {
            return;
        }

        SQLException failure = null;
        for (String driverName : List.of(SHADED_MYSQL_DRIVER, DEVELOPMENT_MYSQL_DRIVER)) {
            try {
                Class.forName(driverName);
                driverLoaded = true;
                return;
            } catch (ClassNotFoundException exception) {
                failure = new SQLException("MySQL driver not found: " + driverName, exception);
            }
        }
        throw failure == null ? new SQLException("MySQL driver not found") : failure;
    }

    private static void ensureSchema(ClusterConfig config) throws SQLException {
        String schemaKey = config.jdbcUrl() + "|" + config.username();
        if (schemaKey.equals(initializedSchemaKey)) {
            return;
        }

        synchronized (SupplyBufferDatabase.class) {
            if (schemaKey.equals(initializedSchemaKey)) {
                return;
            }

            try (Connection connection = open(config);
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS cluster_supply_providers (
                            link_id VARCHAR(64) NOT NULL PRIMARY KEY,
                            node_id VARCHAR(64) NOT NULL,
                            dimension_id VARCHAR(128) NOT NULL,
                            block_position VARCHAR(64) NOT NULL,
                            ae_online BOOLEAN NOT NULL DEFAULT FALSE,
                            last_seen TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                            INDEX idx_supply_provider_seen (last_seen)
                        ) ENGINE=InnoDB
                        """);

                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS cluster_supply_endpoints (
                            endpoint_id VARCHAR(64) NOT NULL PRIMARY KEY,
                            link_id VARCHAR(64) NOT NULL,
                            endpoint_role VARCHAR(24) NOT NULL,
                            node_id VARCHAR(64) NOT NULL,
                            provider_node VARCHAR(64) NOT NULL DEFAULT '',
                            dimension_id VARCHAR(160) NOT NULL,
                            block_position VARCHAR(64) NOT NULL,
                            owner_uuid CHAR(36) NULL,
                            owner_name VARCHAR(64) NOT NULL DEFAULT '',
                            ae_online BOOLEAN NOT NULL DEFAULT FALSE,
                            link_online BOOLEAN NOT NULL DEFAULT FALSE,
                            pending_count INT NOT NULL DEFAULT 0,
                            resource_snapshot LONGTEXT NOT NULL,
                            last_seen TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                            INDEX idx_supply_endpoint_link (link_id, endpoint_role),
                            INDEX idx_supply_endpoint_node (node_id, last_seen),
                            INDEX idx_supply_endpoint_seen (last_seen)
                        ) ENGINE=InnoDB
                        """);

                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS cluster_supply_operations (
                            operation_id CHAR(36) NOT NULL PRIMARY KEY,
                            link_id VARCHAR(64) NOT NULL,
                            source_node VARCHAR(64) NOT NULL,
                            direction VARCHAR(24) NOT NULL,
                            resource_type VARCHAR(16) NOT NULL,
                            resource_nbt LONGTEXT NOT NULL,
                            requested_amount BIGINT NOT NULL,
                            delivered_amount BIGINT NOT NULL DEFAULT 0,
                            status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                            claimed_by VARCHAR(64) NULL,
                            claimed_at TIMESTAMP(3) NULL,
                            error_text VARCHAR(512) NULL,
                            created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                            updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                            INDEX idx_supply_link_status (link_id, status, created_at),
                            INDEX idx_supply_claimed (status, claimed_at),
                            INDEX idx_supply_source (source_node, status)
                        ) ENGINE=InnoDB
                        """);
            }

            initializedSchemaKey = schemaKey;
        }
    }

    private static String encodeResources(Collection<ResourceSnapshot> resources) {
        JsonArray array = new JsonArray();
        if (resources != null) {
            for (ResourceSnapshot resource : resources) {
                if (resource == null || resource.displayName().isBlank()) {
                    continue;
                }
                JsonObject object = new JsonObject();
                object.addProperty("type", resource.resourceType().name());
                object.addProperty("index", resource.filterIndex());
                object.addProperty("name", truncate(resource.displayName(), 256));
                object.addProperty("key", truncate(resource.resourceKey(), 256));
                object.addProperty("amount", Math.max(0L, resource.amount()));
                object.addProperty("capacity", Math.max(0L, resource.capacity()));
                object.addProperty("below", Math.max(0, Math.min(100, resource.refillBelowPercent())));
                object.addProperty("target", Math.max(0, Math.min(100, resource.refillToPercent())));
                array.add(object);
            }
        }
        return array.toString();
    }

    private static List<ResourceSnapshot> decodeResources(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }

        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                return List.of();
            }

            List<ResourceSnapshot> result = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                ResourceType type;
                try {
                    type = ResourceType.valueOf(readString(object, "type", "ITEM"));
                } catch (IllegalArgumentException ignored) {
                    type = ResourceType.ITEM;
                }
                result.add(new ResourceSnapshot(
                        type,
                        readInt(object, "index", 0),
                        readString(object, "name", ""),
                        readString(object, "key", ""),
                        readLong(object, "amount", 0L),
                        readLong(object, "capacity", 0L),
                        readInt(object, "below", 0),
                        readInt(object, "target", 0)
                ));
            }
            return List.copyOf(result);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static String readString(JsonObject object, String key, String fallback) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? fallback : element.getAsString();
    }

    private static int readInt(JsonObject object, String key, int fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static long readLong(JsonObject object, String key, long fallback) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static void restoreAutoCommit(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
        }
    }
}
