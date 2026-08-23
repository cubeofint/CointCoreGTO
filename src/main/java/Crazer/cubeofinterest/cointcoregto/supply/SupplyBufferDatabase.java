package Crazer.cubeofinterest.cointcoregto.supply;

import Crazer.cubeofinterest.cointcoregto.ClusterConfig;

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
