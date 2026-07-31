package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.server.MinecraftServer;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class ClusterDatabase {
    private static final String SHADED_MYSQL_DRIVER =
            "crazer.cubeofinterest.cointcoregto.shadow.mysql.cj.jdbc.Driver";

    private static final String DEVELOPMENT_MYSQL_DRIVER =
            "com.mysql.cj.jdbc.Driver";

    private static volatile boolean driverLoaded;
    private static volatile String initializedSchemaKey;

    private ClusterDatabase() {
    }

    public static TestResult initializeAndTest(
            ClusterConfig config,
            MinecraftServer server
    ) throws SQLException {
        return test(config, server, Map.of());
    }

    public static TestResult initializeAndTest(
            ClusterConfig config,
            MinecraftServer server,
            Map<String, Integer> dimensionPlayerCounts
    ) throws SQLException {
        return test(config, server, dimensionPlayerCounts);
    }

    public static TestResult test(
            ClusterConfig config,
            MinecraftServer server
    ) throws SQLException {
        return test(config, server, Map.of());
    }

    public static TestResult test(
            ClusterConfig config,
            MinecraftServer server,
            Map<String, Integer> dimensionPlayerCounts
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                expireTransfers(connection);
                recoverStaleClaims(connection, config);
                cleanupExpiredBackups(connection, config);
                upsertNode(connection, config, server);
                abortReadyDimensionFailoversForReturnedSource(
                        connection,
                        config.nodeId()
                );
                refreshNodeDrainStates(connection);
                refreshPlayerSessionLeases(connection, config, server);
                refreshDimensionActivity(
                        connection,
                        config,
                        dimensionPlayerCounts
                );

                TestResult result = readTestResult(connection, config);
                connection.commit();
                return result;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static void heartbeat(
            ClusterConfig config,
            MinecraftServer server
    ) throws SQLException {
        heartbeat(config, server, Map.of());
    }

    public static void heartbeat(
            ClusterConfig config,
            MinecraftServer server,
            Map<String, Integer> dimensionPlayerCounts
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                expireTransfers(connection);
                recoverStaleClaims(connection, config);
                cleanupExpiredBackups(connection, config);
                upsertNode(connection, config, server);
                abortReadyDimensionFailoversForReturnedSource(
                        connection,
                        config.nodeId()
                );
                refreshNodeDrainStates(connection);
                refreshPlayerSessionLeases(connection, config, server);
                refreshDimensionActivity(
                        connection,
                        config,
                        dimensionPlayerCounts
                );
                connection.commit();
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    private static void abortReadyDimensionFailoversForReturnedSource(
            Connection connection,
            String sourceNode
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE cluster_dimension_failovers
                SET status = 'ABORTED',
                    error_text = 'source node returned online',
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE source_node = ? AND status = 'READY'
                """)) {
            statement.setString(1, sourceNode);
            statement.executeUpdate();
        }
    }

    public static void updateDimensionActivity(
            ClusterConfig config,
            Map<String, Integer> dimensionPlayerCounts
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                refreshDimensionActivity(
                        connection,
                        config,
                        dimensionPlayerCounts
                );
                connection.commit();
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static String findOnlineRedirectAddress(
            ClusterConfig config,
            String nodeId
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            return findOnlineNodeRedirectAddress(
                    connection,
                    nodeId,
                    config.nodeTimeoutSeconds()
            );
        }
    }

    public static List<ClusterNodeStatus> listNodes(
            ClusterConfig config
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            String sql = """
                SELECT
                    nodes.node_id,
                    nodes.redirect_address,
                    nodes.player_count,
                    nodes.last_seen,
                                      GREATEST(
                                          0,
                                          TIMESTAMPDIFF(
                                              SECOND,
                                              nodes.last_seen,
                                              CURRENT_TIMESTAMP(3)
                                          )
                                      ) AS heartbeat_age_seconds,
                                      CASE
                                          WHEN nodes.stopped_at IS NULL
                                           AND nodes.last_seen >= TIMESTAMPADD(
                                              SECOND,
                                              -?,
                                              CURRENT_TIMESTAMP(3)
                                          )
                                          THEN 1
                                          ELSE 0
                                      END AS online,
                    COUNT(assignments.dimension_id)
                        AS dimension_count
                FROM cluster_nodes AS nodes
                LEFT JOIN dimension_assignments AS assignments
                    ON assignments.node_id = nodes.node_id
                GROUP BY
                    nodes.node_id,
                    nodes.redirect_address,
                    nodes.player_count,
                    nodes.last_seen,
                    nodes.stopped_at
                ORDER BY nodes.node_id
                """;

            List<ClusterNodeStatus> nodes =
                    new ArrayList<>();

            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {

                statement.setInt(
                        1,
                        config.nodeTimeoutSeconds()
                );

                try (ResultSet resultSet =
                             statement.executeQuery()) {

                    while (resultSet.next()) {
                        nodes.add(
                                new ClusterNodeStatus(
                                        resultSet.getString(
                                                "node_id"
                                        ),
                                        resultSet.getString(
                                                "redirect_address"
                                        ),
                                        resultSet.getInt(
                                                "player_count"
                                        ),
                                        resultSet.getInt(
                                                "dimension_count"
                                        ),
                                        resultSet.getBoolean(
                                                "online"
                                        ),
                                        resultSet.getLong(
                                                "heartbeat_age_seconds"
                                        ),
                                        resultSet.getTimestamp(
                                                "last_seen"
                                        ).toInstant()
                                )
                        );
                    }
                }
            }

            return List.copyOf(nodes);
        }
    }

    public static List<DimensionSnapshotCoverage> listDimensionSnapshotCoverage(
            ClusterConfig config
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT
                         assignments.dimension_id,
                         assignments.node_id,
                         MAX(snapshots.ready_at) AS latest_ready_at
                     FROM dimension_assignments AS assignments
                     LEFT JOIN cluster_dimension_snapshots AS snapshots
                       ON snapshots.dimension_id = assignments.dimension_id
                      AND snapshots.source_node = assignments.node_id
                      AND snapshots.status = 'READY'
                     WHERE assignments.dimension_id <> 'minecraft:overworld'
                     GROUP BY assignments.dimension_id, assignments.node_id
                     ORDER BY assignments.dimension_id
                     """)) {
            List<DimensionSnapshotCoverage> coverage = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    java.sql.Timestamp latestReady = resultSet.getTimestamp("latest_ready_at");
                    coverage.add(new DimensionSnapshotCoverage(
                            resultSet.getString("dimension_id"),
                            resultSet.getString("node_id"),
                            latestReady == null ? null : latestReady.toInstant()
                    ));
                }
            }
            return List.copyOf(coverage);
        }
    }

    public static OperationalHealth readOperationalHealth(
            ClusterConfig config
    ) throws SQLException {
        ensureSchema(config);

        String sql = """
                SELECT
                    (SELECT COUNT(*)
                       FROM pending_transfers
                      WHERE status IN ('READY', 'CLAIMED')
                        AND expires_at >= CURRENT_TIMESTAMP(3)) AS active_transfers,
                    (SELECT COUNT(*)
                       FROM pending_transfers
                      WHERE status = 'CLAIMED'
                        AND claimed_at IS NOT NULL
                        AND claimed_at < TIMESTAMPADD(SECOND, -?, CURRENT_TIMESTAMP(3))
                        AND expires_at >= CURRENT_TIMESTAMP(3)) AS stale_claimed_transfers,
                    (SELECT COUNT(*)
                       FROM cluster_player_sessions
                      WHERE lease_expires_at < CURRENT_TIMESTAMP(3)) AS expired_player_sessions,
                    (SELECT COUNT(*)
                       FROM cluster_dimension_migrations
                      WHERE status IN (
                          'PREPARING',
                          'READY',
                          'APPLYING',
                          'FINALIZE_READY',
                          'ROLLBACK_PREPARING',
                          'ROLLBACK_READY',
                          'ROLLBACK_APPLYING'
                      )) AS active_migrations,
                    (SELECT COUNT(*)
                       FROM cluster_dimension_snapshots
                      WHERE status = 'PREPARING') AS active_snapshots,
                    (SELECT COUNT(*)
                       FROM cluster_dimension_failovers
                      WHERE status IN ('READY', 'APPLYING')) AS active_failovers,
                    (SELECT COUNT(*)
                       FROM cluster_dimension_failbacks
                      WHERE status IN ('PREPARING', 'READY', 'APPLYING')) AS active_failbacks,
                    (SELECT COUNT(*)
                       FROM cluster_node_drains
                      WHERE operation_type = 'DRAIN'
                        AND status IN ('PREPARING', 'READY', 'APPLYING', 'DRAINED', 'PARTIAL', 'FAILED')) AS active_drains,
                    (SELECT COUNT(*)
                       FROM cluster_node_drains
                      WHERE operation_type = 'REBALANCE'
                        AND status IN ('PREPARING', 'READY', 'APPLYING')) AS active_rebalances,
                    (SELECT COUNT(*)
                       FROM cluster_dimension_migrations
                      WHERE status = 'FAILED'
                        AND updated_at >= TIMESTAMPADD(DAY, -1, CURRENT_TIMESTAMP(3)))
                    +
                    (SELECT COUNT(*)
                       FROM cluster_dimension_snapshots
                      WHERE status = 'FAILED'
                        AND updated_at >= TIMESTAMPADD(DAY, -1, CURRENT_TIMESTAMP(3)))
                    +
                    (SELECT COUNT(*)
                       FROM cluster_dimension_failovers
                      WHERE status = 'FAILED'
                        AND updated_at >= TIMESTAMPADD(DAY, -1, CURRENT_TIMESTAMP(3)))
                    +
                    (SELECT COUNT(*)
                       FROM cluster_dimension_failbacks
                      WHERE status = 'FAILED'
                        AND updated_at >= TIMESTAMPADD(DAY, -1, CURRENT_TIMESTAMP(3)))
                    +
                    (SELECT COUNT(*)
                       FROM cluster_node_drains
                      WHERE status = 'FAILED'
                        AND updated_at >= TIMESTAMPADD(DAY, -1, CURRENT_TIMESTAMP(3)))
                    AS recent_failed_operations,
                    (SELECT COUNT(*)
                       FROM cluster_dimension_migrations
                      WHERE status IN (
                          'PREPARING',
                          'APPLYING',
                          'ROLLBACK_PREPARING',
                          'ROLLBACK_APPLYING'
                      )
                        AND updated_at < TIMESTAMPADD(MINUTE, -10, CURRENT_TIMESTAMP(3)))
                    +
                    (SELECT COUNT(*)
                       FROM cluster_dimension_snapshots
                      WHERE status = 'PREPARING'
                        AND updated_at < TIMESTAMPADD(MINUTE, -10, CURRENT_TIMESTAMP(3)))
                    +
                    (SELECT COUNT(*)
                       FROM cluster_dimension_failovers
                      WHERE status = 'APPLYING'
                        AND updated_at < TIMESTAMPADD(MINUTE, -10, CURRENT_TIMESTAMP(3)))
                    +
                    (SELECT COUNT(*)
                       FROM cluster_dimension_failbacks
                      WHERE status IN ('PREPARING', 'APPLYING')
                        AND updated_at < TIMESTAMPADD(MINUTE, -10, CURRENT_TIMESTAMP(3)))
                    +
                    (SELECT COUNT(*)
                       FROM cluster_node_drains
                      WHERE status IN ('PREPARING', 'APPLYING')
                        AND updated_at < TIMESTAMPADD(MINUTE, -10, CURRENT_TIMESTAMP(3)))
                    AS stuck_operations,
                    (SELECT COUNT(*)
                       FROM cluster_dimension_failovers AS failovers
                       JOIN cluster_nodes AS nodes
                         ON nodes.node_id = failovers.source_node
                      WHERE failovers.status = 'READY'
                        AND nodes.stopped_at IS NULL
                        AND nodes.last_seen >= TIMESTAMPADD(
                            SECOND,
                            -?,
                            CURRENT_TIMESTAMP(3)
                        )) AS ready_failovers_with_online_source,
                    (SELECT COUNT(*)
                       FROM cluster_operation_leases
                      WHERE lease_until >= CURRENT_TIMESTAMP(3)) AS active_operation_leases
                """;

        try (Connection connection = open(config)) {
            refreshNodeDrainStates(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, config.staleClaimSeconds());
                statement.setInt(2, config.nodeTimeoutSeconds());
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return new OperationalHealth(
                        resultSet.getInt("active_transfers"),
                        resultSet.getInt("stale_claimed_transfers"),
                        resultSet.getInt("expired_player_sessions"),
                        resultSet.getInt("active_migrations"),
                        resultSet.getInt("active_snapshots"),
                        resultSet.getInt("active_failovers"),
                        resultSet.getInt("active_failbacks"),
                        resultSet.getInt("active_drains"),
                        resultSet.getInt("active_rebalances"),
                        resultSet.getInt("recent_failed_operations"),
                        resultSet.getInt("stuck_operations"),
                        resultSet.getInt("ready_failovers_with_online_source"),
                        resultSet.getInt("active_operation_leases"),
                        Instant.now()
                    );
                }
            }
        }
    }

    public static Map<String, String> listDimensionOwners(
            ClusterConfig config
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            String sql = """
                    SELECT
                        dimension_id,
                        node_id
                    FROM dimension_assignments
                    ORDER BY dimension_id
                    """;

            Map<String, String> owners =
                    new LinkedHashMap<>();

            try (PreparedStatement statement =
                         connection.prepareStatement(sql);
                 ResultSet resultSet =
                         statement.executeQuery()) {

                while (resultSet.next()) {
                    owners.put(
                            resultSet.getString("dimension_id"),
                            resultSet.getString("node_id")
                    );
                }
            }

            return Map.copyOf(owners);
        }
    }

    public static void markNodeOffline(
            ClusterConfig config
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                String nodeSql = """
                        UPDATE cluster_nodes
                        SET
                            player_count = 0,
                            stopped_at = CURRENT_TIMESTAMP(3)
                        WHERE node_id = ?
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(nodeSql)) {
                    statement.setString(1, config.nodeId());
                    statement.executeUpdate();
                }

                String sessionsSql = """
                        DELETE FROM cluster_player_sessions
                        WHERE owner_node = ?
                          AND state = 'ONLINE'
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(sessionsSql)) {
                    statement.setString(1, config.nodeId());
                    statement.executeUpdate();
                }

                String activitySql = """
                        DELETE FROM cluster_dimension_activity
                        WHERE node_id = ?
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(activitySql)) {
                    statement.setString(1, config.nodeId());
                    statement.executeUpdate();
                }

                connection.commit();
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static List<DimensionReassignment>
    failoverOfflineDimensions(
            ClusterConfig config
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                lockClusterNodes(connection);
                lockDimensionAssignments(connection);

                List<OfflineDimensionAssignment> offlineAssignments =
                        findOfflineDimensionAssignments(
                                connection,
                                config.nodeTimeoutSeconds()
                        );

                List<DimensionReassignment> reassignments =
                        new ArrayList<>();

                for (OfflineDimensionAssignment assignment
                        : offlineAssignments) {
                    LeastAssignedNode selectedNode =
                            findLeastAssignedNode(
                                    connection,
                                    config.nodeTimeoutSeconds()
                            );

                    if (selectedNode == null) {
                        break;
                    }

                    String updateSql = """
                            UPDATE dimension_assignments
                            SET
                                node_id = ?,
                                updated_at = CURRENT_TIMESTAMP(3)
                            WHERE dimension_id = ?
                              AND node_id = ?
                            """;

                    try (PreparedStatement statement =
                                 connection.prepareStatement(
                                         updateSql
                                 )) {

                        statement.setString(
                                1,
                                selectedNode.nodeId()
                        );
                        statement.setString(
                                2,
                                assignment.dimensionId()
                        );
                        statement.setString(
                                3,
                                assignment.previousNodeId()
                        );

                        if (statement.executeUpdate() != 1) {
                            continue;
                        }
                    }

                    reassignments.add(
                            new DimensionReassignment(
                                    assignment.dimensionId(),
                                    assignment.previousNodeId(),
                                    selectedNode.nodeId(),
                                    Instant.now()
                            )
                    );
                }

                connection.commit();
                return List.copyOf(reassignments);
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static DimensionAssignment assignDimension(
            ClusterConfig config,
            String dimensionId,
            String nodeId
    ) throws SQLException {
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new SQLException("Dimension id is empty");
        }

        if (nodeId == null || nodeId.isBlank()) {
            throw new SQLException("Node id is empty");
        }

        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                if (findNodeRedirectAddress(connection, nodeId) == null) {
                    throw new SQLException(
                            "Узел " + nodeId
                                    + " не найден в cluster_nodes"
                    );
                }

                if (hasActiveNodeDrain(connection, nodeId)) {
                    throw new SQLException(
                            "Узел " + nodeId + " находится в drain-режиме"
                    );
                }

                if (hasActiveDimensionMigration(connection, dimensionId)) {
                    throw new SQLException(
                            "Dimension " + dimensionId + " находится в процессе migration"
                    );
                }

                String previousNode =
                        findDimensionOwner(connection, dimensionId);

                DimensionActivity activeDimension =
                        loadDimensionActivity(
                                connection,
                                config.nodeTimeoutSeconds()
                        ).get(dimensionId);

                if (activeDimension != null
                        && activeDimension.playerCount() > 0
                        && (previousNode == null
                        || !previousNode.equalsIgnoreCase(nodeId))) {
                    throw new SQLException(
                            "Dimension " + dimensionId
                                    + " сейчас содержит игроков на узлах "
                                    + activeDimension.nodeIds()
                                    + "; переназначение запрещено"
                    );
                }

                String sql = """
                        INSERT INTO dimension_assignments (
                            dimension_id,
                            node_id,
                            assigned_at,
                            updated_at
                        )
                        VALUES (
                            ?, ?,
                            CURRENT_TIMESTAMP(3),
                            CURRENT_TIMESTAMP(3)
                        )
                        ON DUPLICATE KEY UPDATE
                            node_id = VALUES(node_id),
                            updated_at = CURRENT_TIMESTAMP(3)
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(sql)) {

                    statement.setString(1, dimensionId);
                    statement.setString(2, nodeId);
                    statement.executeUpdate();
                }

                connection.commit();

                return new DimensionAssignment(
                        dimensionId,
                        nodeId,
                        previousNode,
                        Instant.now()
                );
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static String findDimensionOwner(
            ClusterConfig config,
            String dimensionId
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            return findDimensionOwner(connection, dimensionId);
        }
    }

    public static AutomaticDimensionAssignment assignDimensionAutomatically(
            ClusterConfig config,
            String dimensionId
    ) throws SQLException {
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new SQLException("Dimension id is empty");
        }

        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                lockClusterNodes(connection);
                lockDimensionAssignments(connection);
                lockDimensionActivity(connection);

                if (hasActiveDimensionMigration(connection, dimensionId)) {
                    throw new SQLException(
                            "Dimension " + dimensionId + " находится в процессе migration"
                    );
                }

                String existingOwner =
                        findDimensionOwner(connection, dimensionId);

                if (existingOwner != null) {
                    if (isNodeOnline(
                            connection,
                            existingOwner,
                            config.nodeTimeoutSeconds()
                    )) {
                        int currentAssignments =
                                countAssignmentsForNode(
                                        connection,
                                        existingOwner
                                );

                        connection.commit();

                        return new AutomaticDimensionAssignment(
                                dimensionId,
                                existingOwner,
                                false,
                                currentAssignments,
                                0,
                                Instant.now()
                        );
                    }
                }

                DimensionActivity activeDimension =
                        loadDimensionActivity(
                                connection,
                                config.nodeTimeoutSeconds()
                        ).get(dimensionId);

                LeastAssignedNode selectedNode;

                if (activeDimension != null
                        && activeDimension.playerCount() > 0) {
                    String activeNode = singleActiveNode(activeDimension);

                    if (activeNode == null) {
                        throw new SQLException(
                                "Dimension " + dimensionId
                                        + " одновременно содержит игроков на узлах "
                                        + activeDimension.nodeIds()
                                        + "; автоматическое назначение запрещено"
                        );
                    }

                    PlanningNode activePlanningNode =
                            findOnlinePlanningNodes(
                                    connection,
                                    config.nodeTimeoutSeconds()
                            ).stream()
                                    .filter(node -> node.nodeId()
                                            .equalsIgnoreCase(activeNode))
                                    .findFirst()
                                    .orElseThrow(
                                            () -> new SQLException(
                                                    "Активный узел "
                                                            + activeNode
                                                            + " не находится ONLINE"
                                            )
                                    );

                    selectedNode = new LeastAssignedNode(
                            activePlanningNode.nodeId(),
                            countAssignmentsForNode(
                                    connection,
                                    activePlanningNode.nodeId()
                            ),
                            activePlanningNode.playerCount()
                    );
                } else {
                    selectedNode = findLeastAssignedNode(
                            connection,
                            config.nodeTimeoutSeconds()
                    );
                }

                if (selectedNode == null) {
                    throw new SQLException(
                            "Нет доступных ONLINE-узлов с heartbeat не старше "
                                    + config.nodeTimeoutSeconds()
                                    + " секунд"
                    );
                }

                String sql = """
                        INSERT INTO dimension_assignments (
                            dimension_id,
                            node_id,
                            assigned_at,
                            updated_at
                        )
                        VALUES (
                            ?, ?,
                            CURRENT_TIMESTAMP(3),
                            CURRENT_TIMESTAMP(3)
                        )
                        ON DUPLICATE KEY UPDATE
                            node_id = VALUES(node_id),
                            updated_at = CURRENT_TIMESTAMP(3)
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(sql)) {

                    statement.setString(1, dimensionId);
                    statement.setString(
                            2,
                            selectedNode.nodeId()
                    );

                    statement.executeUpdate();
                }

                connection.commit();

                return new AutomaticDimensionAssignment(
                        dimensionId,
                        selectedNode.nodeId(),
                        true,
                        selectedNode.assignmentCount(),
                        selectedNode.playerCount(),
                        Instant.now()
                );
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static DimensionPinResult pinDimension(
            ClusterConfig config,
            String dimensionId,
            String nodeId
    ) throws SQLException {
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new SQLException("Dimension id is empty");
        }

        if (nodeId == null || nodeId.isBlank()) {
            throw new SQLException("Node id is empty");
        }

        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                lockClusterNodes(connection);
                lockDimensionAssignments(connection);

                if (findNodeRedirectAddress(connection, nodeId) == null) {
                    throw new SQLException(
                            "Узел " + nodeId
                                    + " не найден в cluster_nodes"
                    );
                }

                if (hasActiveNodeDrain(connection, nodeId)) {
                    throw new SQLException(
                            "Узел " + nodeId + " находится в drain-режиме"
                    );
                }

                if (hasActiveDimensionMigration(connection, dimensionId)) {
                    throw new SQLException(
                            "Dimension " + dimensionId + " находится в процессе migration"
                    );
                }

                DimensionAssignmentRow previous =
                        findDimensionAssignmentRow(
                                connection,
                                dimensionId
                        );

                DimensionActivity activeDimension =
                        loadDimensionActivity(
                                connection,
                                config.nodeTimeoutSeconds()
                        ).get(dimensionId);

                if (activeDimension != null
                        && activeDimension.playerCount() > 0) {
                    String activeNode = singleActiveNode(activeDimension);
                    String currentOwner = previous == null
                            ? activeNode
                            : previous.nodeId();

                    if (currentOwner == null
                            || !currentOwner.equalsIgnoreCase(nodeId)) {
                        throw new SQLException(
                                "Dimension " + dimensionId
                                        + " сейчас содержит игроков на узлах "
                                        + activeDimension.nodeIds()
                                        + "; закрепление за другим узлом запрещено"
                        );
                    }
                }

                String sql = """
                        INSERT INTO dimension_assignments (
                            dimension_id,
                            node_id,
                            pinned,
                            assigned_at,
                            updated_at
                        )
                        VALUES (
                            ?, ?, 1,
                            CURRENT_TIMESTAMP(3),
                            CURRENT_TIMESTAMP(3)
                        )
                        ON DUPLICATE KEY UPDATE
                            node_id = VALUES(node_id),
                            pinned = 1,
                            updated_at = CURRENT_TIMESTAMP(3)
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(sql)) {
                    statement.setString(1, dimensionId);
                    statement.setString(2, nodeId);
                    statement.executeUpdate();
                }

                connection.commit();

                return new DimensionPinResult(
                        dimensionId,
                        nodeId,
                        previous == null ? null : previous.nodeId(),
                        previous != null && previous.pinned(),
                        true,
                        Instant.now()
                );
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static DimensionPinResult unpinDimension(
            ClusterConfig config,
            String dimensionId
    ) throws SQLException {
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new SQLException("Dimension id is empty");
        }

        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                lockDimensionAssignments(connection);

                if (hasActiveDimensionMigration(connection, dimensionId)) {
                    throw new SQLException(
                            "Dimension " + dimensionId + " находится в процессе migration"
                    );
                }

                DimensionAssignmentRow previous =
                        findDimensionAssignmentRow(
                                connection,
                                dimensionId
                        );

                if (previous == null) {
                    throw new SQLException(
                            "Для dimension " + dimensionId
                                    + " владелец не назначен"
                    );
                }

                String sql = """
                        UPDATE dimension_assignments
                        SET
                            pinned = 0,
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE dimension_id = ?
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(sql)) {
                    statement.setString(1, dimensionId);
                    statement.executeUpdate();
                }

                connection.commit();

                return new DimensionPinResult(
                        dimensionId,
                        previous.nodeId(),
                        previous.nodeId(),
                        previous.pinned(),
                        false,
                        Instant.now()
                );
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static DimensionAssignmentInfo findDimensionAssignmentInfo(
            ClusterConfig config,
            String dimensionId
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            DimensionAssignmentRow assignment =
                    findDimensionAssignmentRow(
                            connection,
                            dimensionId
                    );

            if (assignment == null) {
                return null;
            }

            DimensionActivity activity =
                    loadDimensionActivity(
                            connection,
                            config.nodeTimeoutSeconds()
                    ).get(dimensionId);

            return new DimensionAssignmentInfo(
                    dimensionId,
                    assignment.nodeId(),
                    assignment.pinned(),
                    activity == null ? 0 : activity.playerCount(),
                    activity == null
                            ? List.of()
                            : activity.nodeIds()
            );
        }
    }

    public static List<DimensionAssignmentInfo> listDimensionAssignments(
            ClusterConfig config,
            Collection<String> registeredDimensions
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            Map<String, DimensionAssignmentRow> assignments =
                    loadDimensionAssignments(connection);
            Map<String, DimensionActivity> activity =
                    loadDimensionActivity(
                            connection,
                            config.nodeTimeoutSeconds()
                    );

            Set<String> dimensions = new TreeSet<>();
            if (registeredDimensions != null) {
                for (String dimensionId : registeredDimensions) {
                    if (dimensionId != null && !dimensionId.isBlank()) {
                        dimensions.add(dimensionId);
                    }
                }
            }
            dimensions.addAll(assignments.keySet());
            dimensions.addAll(activity.keySet());

            List<DimensionAssignmentInfo> result =
                    new ArrayList<>();

            for (String dimensionId : dimensions) {
                DimensionAssignmentRow assignment =
                        assignments.get(dimensionId);
                DimensionActivity dimensionActivity =
                        activity.get(dimensionId);

                result.add(
                        new DimensionAssignmentInfo(
                                dimensionId,
                                assignment == null
                                        ? null
                                        : assignment.nodeId(),
                                assignment != null
                                        && assignment.pinned(),
                                dimensionActivity == null
                                        ? 0
                                        : dimensionActivity.playerCount(),
                                dimensionActivity == null
                                        ? List.of()
                                        : dimensionActivity.nodeIds()
                        )
                );
            }

            return List.copyOf(result);
        }
    }

    public static DimensionPlanResult planDimensionAssignments(
            ClusterConfig config,
            Collection<String> registeredDimensions,
            boolean rebalance,
            boolean apply
    ) throws SQLException {
        ensureSchema(config);

        Set<String> knownDimensions = new TreeSet<>();
        if (registeredDimensions != null) {
            for (String dimensionId : registeredDimensions) {
                if (dimensionId != null && !dimensionId.isBlank()) {
                    knownDimensions.add(dimensionId);
                }
            }
        }

        if (knownDimensions.isEmpty()) {
            throw new SQLException(
                    "Список зарегистрированных измерений пуст"
            );
        }

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                lockClusterNodes(connection);
                lockDimensionAssignments(connection);
                lockDimensionActivity(connection);

                List<PlanningNode> onlineNodes =
                        findOnlinePlanningNodes(
                                connection,
                                config.nodeTimeoutSeconds()
                        );

                if (onlineNodes.isEmpty()) {
                    throw new SQLException(
                            "Нет доступных ONLINE-узлов с heartbeat не старше "
                                    + config.nodeTimeoutSeconds()
                                    + " секунд"
                    );
                }

                Map<String, PlanningNode> nodesById =
                        new LinkedHashMap<>();
                Map<String, Integer> assignedCounts =
                        new HashMap<>();

                for (PlanningNode node : onlineNodes) {
                    nodesById.put(node.nodeId(), node);
                    assignedCounts.put(node.nodeId(), 0);
                }

                Map<String, DimensionAssignmentRow> assignments =
                        loadDimensionAssignments(connection);
                Map<String, DimensionActivity> activity =
                        loadDimensionActivity(
                                connection,
                                config.nodeTimeoutSeconds()
                        );
                Set<String> migratingDimensions =
                        loadActiveMigrationDimensions(connection);

                List<DimensionPlanEntry> entries =
                        new ArrayList<>();

                if (!rebalance) {
                    for (DimensionAssignmentRow assignment
                            : assignments.values()) {
                        if (nodesById.containsKey(
                                assignment.nodeId()
                        )) {
                            assignedCounts.computeIfPresent(
                                    assignment.nodeId(),
                                    (ignored, count) -> count + 1
                            );
                        }
                    }

                    for (String dimensionId : knownDimensions) {
                        DimensionAssignmentRow assignment =
                                assignments.get(dimensionId);
                        DimensionActivity dimensionActivity =
                                activity.get(dimensionId);

                        if (assignment != null) {
                            entries.add(
                                    createPlanEntry(
                                            dimensionId,
                                            assignment,
                                            assignment.nodeId(),
                                            dimensionActivity,
                                            migratingDimensions.contains(dimensionId)
                                                    ? DimensionPlanAction.SKIP_MIGRATING
                                                    : DimensionPlanAction.KEEP
                                    )
                            );
                            continue;
                        }

                        String activeNode =
                                singleActiveNode(dimensionActivity);

                        if (dimensionActivity != null
                                && dimensionActivity.playerCount() > 0
                                && activeNode == null) {
                            entries.add(
                                    createPlanEntry(
                                            dimensionId,
                                            null,
                                            null,
                                            dimensionActivity,
                                            DimensionPlanAction.CONFLICT_ACTIVE
                                    )
                            );
                            continue;
                        }

                        String targetNode = activeNode != null
                                && nodesById.containsKey(activeNode)
                                ? activeNode
                                : selectPlanningNode(
                                        onlineNodes,
                                        assignedCounts,
                                        null
                                );

                        assignedCounts.computeIfPresent(
                                targetNode,
                                (ignored, count) -> count + 1
                        );

                        entries.add(
                                createPlanEntry(
                                        dimensionId,
                                        null,
                                        targetNode,
                                        dimensionActivity,
                                        DimensionPlanAction.ASSIGN
                                )
                        );
                    }
                } else {
                    for (Map.Entry<String, DimensionAssignmentRow> entry
                            : assignments.entrySet()) {
                        if (knownDimensions.contains(entry.getKey())) {
                            continue;
                        }

                        String ownerNode = entry.getValue().nodeId();
                        if (nodesById.containsKey(ownerNode)) {
                            assignedCounts.computeIfPresent(
                                    ownerNode,
                                    (ignored, count) -> count + 1
                            );
                        }
                    }

                    List<String> movableDimensions =
                            new ArrayList<>();

                    for (String dimensionId : knownDimensions) {
                        DimensionAssignmentRow assignment =
                                assignments.get(dimensionId);
                        DimensionActivity dimensionActivity =
                                activity.get(dimensionId);

                        if (migratingDimensions.contains(dimensionId)) {
                            if (assignment != null
                                    && nodesById.containsKey(assignment.nodeId())) {
                                assignedCounts.computeIfPresent(
                                        assignment.nodeId(),
                                        (ignored, count) -> count + 1
                                );
                            }
                            entries.add(
                                    createPlanEntry(
                                            dimensionId,
                                            assignment,
                                            assignment == null ? null : assignment.nodeId(),
                                            dimensionActivity,
                                            DimensionPlanAction.SKIP_MIGRATING
                                    )
                            );
                            continue;
                        }

                        if (assignment != null && assignment.pinned()) {
                            if (nodesById.containsKey(
                                    assignment.nodeId()
                            )) {
                                assignedCounts.computeIfPresent(
                                        assignment.nodeId(),
                                        (ignored, count) -> count + 1
                                );
                            }

                            entries.add(
                                    createPlanEntry(
                                            dimensionId,
                                            assignment,
                                            assignment.nodeId(),
                                            dimensionActivity,
                                            DimensionPlanAction.SKIP_PINNED
                                    )
                            );
                            continue;
                        }

                        if (dimensionActivity != null
                                && dimensionActivity.playerCount() > 0) {
                            if (assignment != null) {
                                if (nodesById.containsKey(
                                        assignment.nodeId()
                                )) {
                                    assignedCounts.computeIfPresent(
                                            assignment.nodeId(),
                                            (ignored, count) -> count + 1
                                    );
                                }

                                entries.add(
                                        createPlanEntry(
                                                dimensionId,
                                                assignment,
                                                assignment.nodeId(),
                                                dimensionActivity,
                                                DimensionPlanAction.SKIP_ACTIVE
                                        )
                                );
                                continue;
                            }

                            String activeNode =
                                    singleActiveNode(dimensionActivity);

                            if (activeNode == null
                                    || !nodesById.containsKey(activeNode)) {
                                entries.add(
                                        createPlanEntry(
                                                dimensionId,
                                                null,
                                                null,
                                                dimensionActivity,
                                                DimensionPlanAction.CONFLICT_ACTIVE
                                        )
                                );
                                continue;
                            }

                            assignedCounts.computeIfPresent(
                                    activeNode,
                                    (ignored, count) -> count + 1
                            );

                            entries.add(
                                    createPlanEntry(
                                            dimensionId,
                                            null,
                                            activeNode,
                                            dimensionActivity,
                                            DimensionPlanAction.ASSIGN
                                    )
                            );
                            continue;
                        }

                        movableDimensions.add(dimensionId);
                    }

                    movableDimensions.sort(String::compareTo);

                    for (String dimensionId : movableDimensions) {
                        DimensionAssignmentRow assignment =
                                assignments.get(dimensionId);
                        String previousNode = assignment == null
                                ? null
                                : assignment.nodeId();

                        String targetNode = selectPlanningNode(
                                onlineNodes,
                                assignedCounts,
                                previousNode
                        );

                        assignedCounts.computeIfPresent(
                                targetNode,
                                (ignored, count) -> count + 1
                        );

                        DimensionPlanAction action;
                        if (previousNode == null) {
                            action = DimensionPlanAction.ASSIGN;
                        } else if (previousNode.equalsIgnoreCase(
                                targetNode
                        )) {
                            action = DimensionPlanAction.KEEP;
                        } else {
                            action = DimensionPlanAction.MOVE;
                        }

                        entries.add(
                                createPlanEntry(
                                        dimensionId,
                                        assignment,
                                        targetNode,
                                        activity.get(dimensionId),
                                        action
                                )
                        );
                    }
                }

                entries.sort(
                        Comparator.comparing(
                                DimensionPlanEntry::dimensionId
                        )
                );

                int changed = 0;

                for (DimensionPlanEntry entry : entries) {
                    if (entry.action() == DimensionPlanAction.ASSIGN
                            || entry.action() == DimensionPlanAction.MOVE) {
                        changed++;
                    }
                }

                if (apply) {
                    String upsertSql = """
                            INSERT INTO dimension_assignments (
                                dimension_id,
                                node_id,
                                pinned,
                                assigned_at,
                                updated_at
                            )
                            VALUES (
                                ?, ?, 0,
                                CURRENT_TIMESTAMP(3),
                                CURRENT_TIMESTAMP(3)
                            )
                            ON DUPLICATE KEY UPDATE
                                node_id = VALUES(node_id),
                                updated_at = CURRENT_TIMESTAMP(3)
                            """;

                    try (PreparedStatement statement =
                                 connection.prepareStatement(upsertSql)) {
                        for (DimensionPlanEntry entry : entries) {
                            if (entry.action()
                                    != DimensionPlanAction.ASSIGN
                                    && entry.action()
                                    != DimensionPlanAction.MOVE) {
                                continue;
                            }

                            if (entry.targetNodeId() == null) {
                                continue;
                            }

                            statement.setString(
                                    1,
                                    entry.dimensionId()
                            );
                            statement.setString(
                                    2,
                                    entry.targetNodeId()
                            );
                            statement.addBatch();
                        }

                        if (changed > 0) {
                            statement.executeBatch();
                        }
                    }
                }

                connection.commit();

                List<PlanningNodeStatus> finalNodes =
                        new ArrayList<>();

                for (PlanningNode node : onlineNodes) {
                    finalNodes.add(
                            new PlanningNodeStatus(
                                    node.nodeId(),
                                    node.playerCount(),
                                    assignedCounts.getOrDefault(
                                            node.nodeId(),
                                            0
                                    )
                            )
                    );
                }

                return new DimensionPlanResult(
                        apply,
                        rebalance,
                        changed,
                        List.copyOf(entries),
                        List.copyOf(finalNodes),
                        Instant.now()
                );
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static DimensionMigration requestDimensionMigration(
            ClusterConfig config,
            String dimensionId,
            String targetNode
    ) throws SQLException {
        if (dimensionId == null || dimensionId.isBlank()) {
            throw new SQLException("Dimension id is empty");
        }
        if (targetNode == null || targetNode.isBlank()) {
            throw new SQLException("Target node is empty");
        }
        if (targetNode.equalsIgnoreCase(config.nodeId())) {
            throw new SQLException("Целевой узел совпадает с текущим узлом");
        }

        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                lockClusterNodes(connection);
                lockDimensionAssignments(connection);
                lockDimensionActivity(connection);

                if (!isNodeOnline(
                        connection,
                        targetNode,
                        config.nodeTimeoutSeconds()
                )) {
                    throw new SQLException(
                            "Целевой узел " + targetNode + " не находится ONLINE"
                    );
                }

                if (hasActiveNodeDrain(connection, targetNode)) {
                    throw new SQLException(
                            "Целевой узел " + targetNode + " находится в drain-режиме"
                    );
                }

                DimensionAssignmentRow assignment =
                        findDimensionAssignmentRow(connection, dimensionId);

                if (assignment == null) {
                    throw new SQLException(
                            "Для dimension " + dimensionId + " владелец не назначен"
                    );
                }

                if (!assignment.nodeId().equalsIgnoreCase(config.nodeId())) {
                    throw new SQLException(
                            "Dimension " + dimensionId
                                    + " принадлежит узлу "
                                    + assignment.nodeId()
                                    + ", подготовка должна выполняться на владельце"
                    );
                }

                DimensionActivity activity =
                        loadDimensionActivity(
                                connection,
                                config.nodeTimeoutSeconds()
                        ).get(dimensionId);

                if (activity != null && activity.playerCount() > 0) {
                    throw new SQLException(
                            "Dimension " + dimensionId
                                    + " содержит игроков на узлах "
                                    + activity.nodeIds()
                    );
                }

                DimensionMigration active =
                        findActiveDimensionMigration(connection, dimensionId, true);

                if (active != null) {
                    throw new SQLException(
                            "Для dimension " + dimensionId
                                    + " уже выполняется migration "
                                    + active.migrationId()
                                    + " (" + active.status() + ")"
                    );
                }

                String migrationId = UUID.randomUUID().toString();
                String sql = """
                        INSERT INTO cluster_dimension_migrations (
                            migration_id,
                            dimension_id,
                            source_node,
                            target_node,
                            status,
                            created_at,
                            updated_at
                        )
                        VALUES (
                            ?, ?, ?, ?, 'PREPARING',
                            CURRENT_TIMESTAMP(3),
                            CURRENT_TIMESTAMP(3)
                        )
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(sql)) {
                    statement.setString(1, migrationId);
                    statement.setString(2, dimensionId);
                    statement.setString(3, config.nodeId());
                    statement.setString(4, targetNode);
                    statement.executeUpdate();
                }

                connection.commit();

                return new DimensionMigration(
                        migrationId,
                        dimensionId,
                        config.nodeId(),
                        targetNode,
                        "PREPARING",
                        null,
                        null,
                        null,
                        0,
                        null,
                        Instant.now(),
                        Instant.now(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        null,
                        null,
                        null,
                        null
                );
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static DimensionMigration markDimensionMigrationReady(
            ClusterConfig config,
            String migrationId,
            String archiveName,
            String archiveSha256,
            String contentSha256,
            long archiveSize
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            String sql = """
                    UPDATE cluster_dimension_migrations
                    SET
                        status = 'READY',
                        archive_name = ?,
                        archive_sha256 = ?,
                        content_sha256 = ?,
                        archive_size = ?,
                        error_text = NULL,
                        ready_at = CURRENT_TIMESTAMP(3),
                        updated_at = CURRENT_TIMESTAMP(3)
                    WHERE migration_id = ?
                      AND source_node = ?
                      AND status = 'PREPARING'
                    """;

            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {
                statement.setString(1, archiveName);
                statement.setString(2, archiveSha256);
                statement.setString(3, contentSha256);
                statement.setLong(4, archiveSize);
                statement.setString(5, migrationId);
                statement.setString(6, config.nodeId());

                if (statement.executeUpdate() != 1) {
                    throw new SQLException(
                            "Не удалось перевести migration "
                                    + migrationId
                                    + " в READY"
                    );
                }
            }
        }

        return findDimensionMigration(config, migrationId);
    }

    public static DimensionMigration findPendingDimensionMigrationForTarget(
            ClusterConfig config
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            String sql = """
                    SELECT
                        migration_id,
                        dimension_id,
                        source_node,
                        target_node,
                        status,
                        archive_name,
                        archive_sha256,
                        content_sha256,
                        archive_size,
                        error_text,
                        created_at,
                        updated_at,
                        ready_at,
                        applying_at,
                        applied_at,
                        verified_at,
                        finalize_ready_at,
                        finalized_at,
                        rollback_previous_status,
                        rollback_archive_name,
                        rollback_archive_sha256,
                        rollback_content_sha256,
                        rollback_archive_size,
                        rollback_ready_at,
                        rollback_applying_at,
                        rolled_back_at,
                        source_backup_deleted_at
                    FROM cluster_dimension_migrations
                    WHERE target_node = ?
                      AND status IN ('READY', 'APPLYING')
                    ORDER BY created_at
                    LIMIT 1
                    """;

            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {
                statement.setString(1, config.nodeId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        return null;
                    }
                    return readDimensionMigration(resultSet);
                }
            }
        }
    }

    public static DimensionMigration markDimensionMigrationApplying(
            ClusterConfig config,
            String migrationId
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            String sql = """
                    UPDATE cluster_dimension_migrations
                    SET
                        status = 'APPLYING',
                        applying_at = COALESCE(
                            applying_at,
                            CURRENT_TIMESTAMP(3)
                        ),
                        updated_at = CURRENT_TIMESTAMP(3)
                    WHERE migration_id = ?
                      AND target_node = ?
                      AND status IN ('READY', 'APPLYING')
                    """;

            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {
                statement.setString(1, migrationId);
                statement.setString(2, config.nodeId());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException(
                            "Не удалось захватить migration " + migrationId
                    );
                }
            }
        }

        return findDimensionMigration(config, migrationId);
    }

    public static DimensionMigration completeDimensionMigration(
            ClusterConfig config,
            String migrationId
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                DimensionMigration migration =
                        findDimensionMigration(connection, migrationId, true);

                if (migration == null) {
                    throw new SQLException("Migration не найден: " + migrationId);
                }

                if (!migration.targetNode().equalsIgnoreCase(config.nodeId())) {
                    throw new SQLException(
                            "Migration " + migrationId
                                    + " предназначен для узла "
                                    + migration.targetNode()
                    );
                }

                if (!migration.status().equals("APPLYING")) {
                    throw new SQLException(
                            "Migration " + migrationId
                                    + " имеет status="
                                    + migration.status()
                    );
                }

                String assignmentSql = """
                        UPDATE dimension_assignments
                        SET
                            node_id = ?,
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE dimension_id = ?
                          AND node_id = ?
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(assignmentSql)) {
                    statement.setString(1, migration.targetNode());
                    statement.setString(2, migration.dimensionId());
                    statement.setString(3, migration.sourceNode());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException(
                                "Владелец dimension "
                                        + migration.dimensionId()
                                        + " изменился во время migration"
                        );
                    }
                }

                String migrationSql = """
                        UPDATE cluster_dimension_migrations
                        SET
                            status = 'APPLIED',
                            error_text = NULL,
                            applied_at = CURRENT_TIMESTAMP(3),
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE migration_id = ?
                          AND status = 'APPLYING'
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(migrationSql)) {
                    statement.setString(1, migrationId);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException(
                                "Не удалось завершить migration " + migrationId
                        );
                    }
                }

                boolean managedFailback;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT 1
                        FROM cluster_dimension_failbacks
                        WHERE migration_id = ?
                        LIMIT 1
                        """)) {
                    statement.setString(1, migrationId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        managedFailback = resultSet.next();
                    }
                }

                if (managedFailback) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE cluster_dimension_failbacks
                            SET status = 'APPLIED', error_text = NULL,
                                applied_at = COALESCE(applied_at, CURRENT_TIMESTAMP(3)),
                                updated_at = CURRENT_TIMESTAMP(3)
                            WHERE migration_id = ?
                              AND status IN ('READY', 'APPLYING', 'APPLIED')
                            """)) {
                        statement.setString(1, migrationId);
                        statement.executeUpdate();
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE cluster_dimension_migrations
                            SET status = 'COMPLETED', error_text = NULL,
                                updated_at = CURRENT_TIMESTAMP(3)
                            WHERE migration_id = ? AND status = 'APPLIED'
                            """)) {
                        statement.setString(1, migrationId);
                        if (statement.executeUpdate() != 1) {
                            throw new SQLException(
                                    "Не удалось закрыть failback migration " + migrationId
                            );
                        }
                    }
                }

                connection.commit();
                return findDimensionMigration(config, migrationId);
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }


    public static DimensionMigration validateDimensionMigrationVerification(
            ClusterConfig config,
            String migrationId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                lockDimensionAssignments(connection);
                lockDimensionActivity(connection);
                DimensionMigration migration = findDimensionMigration(connection, migrationId, true);
                if (migration == null) {
                    throw new SQLException("Migration не найден: " + migrationId);
                }
                if (!migration.targetNode().equalsIgnoreCase(config.nodeId())) {
                    throw new SQLException("Verify должен выполняться на target node " + migration.targetNode());
                }
                if (!migration.status().equals("APPLIED") && !migration.status().equals("VERIFIED")) {
                    throw new SQLException("Verify недоступен для status=" + migration.status());
                }
                DimensionAssignmentRow assignment = findDimensionAssignmentRow(connection, migration.dimensionId());
                if (assignment == null || !assignment.nodeId().equalsIgnoreCase(migration.targetNode())) {
                    throw new SQLException("Target node больше не владеет dimension " + migration.dimensionId());
                }
                DimensionActivity activity = loadDimensionActivity(connection, config.nodeTimeoutSeconds()).get(migration.dimensionId());
                if (activity != null && activity.playerCount() > 0) {
                    throw new SQLException("Dimension содержит игроков на узлах " + activity.nodeIds());
                }
                connection.commit();
                return migration;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static DimensionMigration markDimensionMigrationVerified(
            ClusterConfig config,
            String migrationId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            String sql = """
                    UPDATE cluster_dimension_migrations
                    SET status = 'VERIFIED', verified_at = CURRENT_TIMESTAMP(3), error_text = NULL, updated_at = CURRENT_TIMESTAMP(3)
                    WHERE migration_id = ? AND target_node = ? AND status IN ('APPLIED', 'VERIFIED')
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, migrationId);
                statement.setString(2, config.nodeId());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Не удалось подтвердить migration " + migrationId);
                }
            }
        }
        return findDimensionMigration(config, migrationId);
    }

    public static DimensionMigration requestDimensionMigrationFinalization(
            ClusterConfig config,
            String migrationId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                lockDimensionAssignments(connection);
                lockDimensionActivity(connection);
                DimensionMigration migration = findDimensionMigration(connection, migrationId, true);
                if (migration == null) {
                    throw new SQLException("Migration не найден: " + migrationId);
                }
                if (!migration.sourceNode().equalsIgnoreCase(config.nodeId())) {
                    throw new SQLException("Finalize должен выполняться на source node " + migration.sourceNode());
                }
                if (!migration.status().equals("VERIFIED") && !migration.status().equals("FINALIZE_READY")) {
                    throw new SQLException("Finalize недоступен для status=" + migration.status());
                }
                DimensionAssignmentRow assignment = findDimensionAssignmentRow(connection, migration.dimensionId());
                if (assignment == null || !assignment.nodeId().equalsIgnoreCase(migration.targetNode())) {
                    throw new SQLException("Target node больше не владеет dimension " + migration.dimensionId());
                }
                DimensionActivity activity = loadDimensionActivity(connection, config.nodeTimeoutSeconds()).get(migration.dimensionId());
                if (activity != null && activity.playerCount() > 0) {
                    throw new SQLException("Dimension содержит игроков на узлах " + activity.nodeIds());
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE cluster_dimension_migrations
                        SET status = 'FINALIZE_READY', finalize_ready_at = COALESCE(finalize_ready_at, CURRENT_TIMESTAMP(3)), updated_at = CURRENT_TIMESTAMP(3)
                        WHERE migration_id = ? AND status IN ('VERIFIED', 'FINALIZE_READY')
                        """)) {
                    statement.setString(1, migrationId);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Не удалось подготовить finalize " + migrationId);
                    }
                }
                connection.commit();
                return findDimensionMigration(config, migrationId);
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static DimensionMigration findPendingDimensionFinalizationForSource(
            ClusterConfig config
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT
                        migration_id, dimension_id, source_node, target_node, status,
                        archive_name, archive_sha256, content_sha256, archive_size, error_text,
                        created_at, updated_at, ready_at, applying_at, applied_at,
                        verified_at, finalize_ready_at, finalized_at, rollback_previous_status,
                        rollback_archive_name, rollback_archive_sha256, rollback_content_sha256,
                        rollback_archive_size, rollback_ready_at, rollback_applying_at,
                        rolled_back_at, source_backup_deleted_at
                    FROM cluster_dimension_migrations
                    WHERE source_node = ? AND status = 'FINALIZE_READY'
                    ORDER BY created_at
                    LIMIT 1
                    """)) {
                statement.setString(1, config.nodeId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? readDimensionMigration(resultSet) : null;
                }
            }
        }
    }

    public static DimensionMigration completeDimensionMigrationFinalization(
            ClusterConfig config,
            String migrationId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE cluster_dimension_migrations
                    SET status = 'FINALIZED', finalized_at = CURRENT_TIMESTAMP(3), error_text = NULL, updated_at = CURRENT_TIMESTAMP(3)
                    WHERE migration_id = ? AND source_node = ? AND status = 'FINALIZE_READY'
                    """)) {
                statement.setString(1, migrationId);
                statement.setString(2, config.nodeId());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Не удалось завершить finalize " + migrationId);
                }
            }
        }
        return findDimensionMigration(config, migrationId);
    }

    public static DimensionMigration requestDimensionRollback(
            ClusterConfig config,
            String migrationId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                lockDimensionAssignments(connection);
                lockDimensionActivity(connection);
                DimensionMigration migration = findDimensionMigration(connection, migrationId, true);
                if (migration == null) {
                    throw new SQLException("Migration не найден: " + migrationId);
                }
                if (!migration.targetNode().equalsIgnoreCase(config.nodeId())) {
                    throw new SQLException("Rollback должен выполняться на target node " + migration.targetNode());
                }
                boolean alreadyPreparing = migration.status().equals("ROLLBACK_PREPARING");
                if (!migration.status().equals("APPLIED")
                        && !migration.status().equals("VERIFIED")
                        && !migration.status().equals("FINALIZED")
                        && !alreadyPreparing) {
                    throw new SQLException("Rollback недоступен для status=" + migration.status());
                }
                DimensionAssignmentRow assignment = findDimensionAssignmentRow(connection, migration.dimensionId());
                if (assignment == null || !assignment.nodeId().equalsIgnoreCase(migration.targetNode())) {
                    throw new SQLException("Target node больше не владеет dimension " + migration.dimensionId());
                }
                DimensionActivity activity = loadDimensionActivity(connection, config.nodeTimeoutSeconds()).get(migration.dimensionId());
                if (activity != null && activity.playerCount() > 0) {
                    throw new SQLException("Dimension содержит игроков на узлах " + activity.nodeIds());
                }
                if (!alreadyPreparing) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE cluster_dimension_migrations
                            SET status = 'ROLLBACK_PREPARING', rollback_previous_status = status,
                                rollback_archive_name = NULL, rollback_archive_sha256 = NULL,
                                rollback_content_sha256 = NULL, rollback_archive_size = 0,
                                rollback_ready_at = NULL, rollback_applying_at = NULL,
                                rolled_back_at = NULL, error_text = NULL, updated_at = CURRENT_TIMESTAMP(3)
                            WHERE migration_id = ? AND status IN ('APPLIED', 'VERIFIED', 'FINALIZED')
                            """)) {
                        statement.setString(1, migrationId);
                        if (statement.executeUpdate() != 1) {
                            throw new SQLException("Не удалось начать rollback " + migrationId);
                        }
                    }
                }
                connection.commit();
                return findDimensionMigration(config, migrationId);
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static DimensionMigration markDimensionRollbackReady(
            ClusterConfig config,
            String migrationId,
            String archiveName,
            String archiveSha256,
            String contentSha256,
            long archiveSize
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE cluster_dimension_migrations
                    SET status = 'ROLLBACK_READY', rollback_archive_name = ?, rollback_archive_sha256 = ?,
                        rollback_content_sha256 = ?, rollback_archive_size = ?, rollback_ready_at = CURRENT_TIMESTAMP(3),
                        error_text = NULL, updated_at = CURRENT_TIMESTAMP(3)
                    WHERE migration_id = ? AND target_node = ? AND status = 'ROLLBACK_PREPARING'
                    """)) {
                statement.setString(1, archiveName);
                statement.setString(2, archiveSha256);
                statement.setString(3, contentSha256);
                statement.setLong(4, archiveSize);
                statement.setString(5, migrationId);
                statement.setString(6, config.nodeId());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Не удалось подготовить rollback " + migrationId);
                }
            }
        }
        return findDimensionMigration(config, migrationId);
    }

    public static DimensionMigration failDimensionRollback(
            ClusterConfig config,
            String migrationId,
            String errorText
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE cluster_dimension_migrations
                    SET status = COALESCE(rollback_previous_status, 'APPLIED'), error_text = ?, updated_at = CURRENT_TIMESTAMP(3)
                    WHERE migration_id = ? AND status = 'ROLLBACK_PREPARING'
                    """)) {
                statement.setString(1, truncate(errorText, 4000));
                statement.setString(2, migrationId);
                statement.executeUpdate();
            }
        }
        return findDimensionMigration(config, migrationId);
    }

    public static DimensionMigration findPendingDimensionRollbackForSource(
            ClusterConfig config
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT
                        migration_id, dimension_id, source_node, target_node, status,
                        archive_name, archive_sha256, content_sha256, archive_size, error_text,
                        created_at, updated_at, ready_at, applying_at, applied_at,
                        verified_at, finalize_ready_at, finalized_at, rollback_previous_status,
                        rollback_archive_name, rollback_archive_sha256, rollback_content_sha256,
                        rollback_archive_size, rollback_ready_at, rollback_applying_at,
                        rolled_back_at, source_backup_deleted_at
                    FROM cluster_dimension_migrations
                    WHERE source_node = ? AND status IN ('ROLLBACK_READY', 'ROLLBACK_APPLYING')
                    ORDER BY created_at
                    LIMIT 1
                    """)) {
                statement.setString(1, config.nodeId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? readDimensionMigration(resultSet) : null;
                }
            }
        }
    }

    public static DimensionMigration markDimensionRollbackApplying(
            ClusterConfig config,
            String migrationId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE cluster_dimension_migrations
                    SET status = 'ROLLBACK_APPLYING', rollback_applying_at = COALESCE(rollback_applying_at, CURRENT_TIMESTAMP(3)), updated_at = CURRENT_TIMESTAMP(3)
                    WHERE migration_id = ? AND source_node = ? AND status IN ('ROLLBACK_READY', 'ROLLBACK_APPLYING')
                    """)) {
                statement.setString(1, migrationId);
                statement.setString(2, config.nodeId());
                if (statement.executeUpdate() != 1) {
                    throw new SQLException("Не удалось захватить rollback " + migrationId);
                }
            }
        }
        return findDimensionMigration(config, migrationId);
    }

    public static DimensionMigration completeDimensionRollback(
            ClusterConfig config,
            String migrationId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                lockDimensionAssignments(connection);
                DimensionMigration migration = findDimensionMigration(connection, migrationId, true);
                if (migration == null) {
                    throw new SQLException("Migration не найден: " + migrationId);
                }
                if (!migration.sourceNode().equalsIgnoreCase(config.nodeId()) || !migration.status().equals("ROLLBACK_APPLYING")) {
                    throw new SQLException("Rollback не принадлежит этому source node или имеет неверный status");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE dimension_assignments
                        SET node_id = ?, updated_at = CURRENT_TIMESTAMP(3)
                        WHERE dimension_id = ? AND node_id = ?
                        """)) {
                    statement.setString(1, migration.sourceNode());
                    statement.setString(2, migration.dimensionId());
                    statement.setString(3, migration.targetNode());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Владелец dimension изменился во время rollback");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE cluster_dimension_migrations
                        SET status = 'ROLLED_BACK', rolled_back_at = CURRENT_TIMESTAMP(3), error_text = NULL, updated_at = CURRENT_TIMESTAMP(3)
                        WHERE migration_id = ? AND status = 'ROLLBACK_APPLYING'
                        """)) {
                    statement.setString(1, migrationId);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Не удалось завершить rollback " + migrationId);
                    }
                }
                connection.commit();
                return findDimensionMigration(config, migrationId);
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static List<DimensionMigration> listExpiredFinalizedMigrationBackups(
            ClusterConfig config,
            int retentionDays
    ) throws SQLException {
        ensureSchema(config);
        int safeDays = Math.max(1, retentionDays);
        try (Connection connection = open(config)) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT
                        migration_id, dimension_id, source_node, target_node, status,
                        archive_name, archive_sha256, content_sha256, archive_size, error_text,
                        created_at, updated_at, ready_at, applying_at, applied_at,
                        verified_at, finalize_ready_at, finalized_at, rollback_previous_status,
                        rollback_archive_name, rollback_archive_sha256, rollback_content_sha256,
                        rollback_archive_size, rollback_ready_at, rollback_applying_at,
                        rolled_back_at, source_backup_deleted_at
                    FROM cluster_dimension_migrations
                    WHERE source_node = ? AND status IN ('FINALIZED', 'ROLLED_BACK') AND source_backup_deleted_at IS NULL
                      AND finalized_at < TIMESTAMPADD(DAY, -?, CURRENT_TIMESTAMP(3))
                    ORDER BY finalized_at
                    """)) {
                statement.setString(1, config.nodeId());
                statement.setInt(2, safeDays);
                List<DimensionMigration> result = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        result.add(readDimensionMigration(resultSet));
                    }
                }
                return List.copyOf(result);
            }
        }
    }

    public static void markFinalizedMigrationBackupDeleted(
            ClusterConfig config,
            String migrationId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE cluster_dimension_migrations
                     SET source_backup_deleted_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3)
                     WHERE migration_id = ? AND source_node = ? AND status IN ('FINALIZED', 'ROLLED_BACK')
                     """)) {
            statement.setString(1, migrationId);
            statement.setString(2, config.nodeId());
            statement.executeUpdate();
        }
    }
    public static List<String> listOfflineDimensionOwnerNodes(
            ClusterConfig config
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT DISTINCT assignments.node_id
                     FROM dimension_assignments AS assignments
                     LEFT JOIN cluster_nodes AS nodes ON nodes.node_id = assignments.node_id
                     WHERE nodes.node_id IS NULL
                        OR nodes.stopped_at IS NOT NULL
                        OR nodes.last_seen < TIMESTAMPADD(SECOND, -?, CURRENT_TIMESTAMP(3))
                     ORDER BY assignments.node_id
                     """)) {
            statement.setInt(1, Math.max(1, config.nodeTimeoutSeconds()));
            List<String> nodes = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    nodes.add(resultSet.getString(1));
                }
            }
            return List.copyOf(nodes);
        }
    }

    public static List<AutomaticFailoverCandidate> listAutomaticFailoverCandidates(
            ClusterConfig config
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT assignments.node_id, nodes.last_seen, nodes.stopped_at,
                            COUNT(assignments.dimension_id) AS dimension_count,
                            CASE
                                WHEN nodes.last_seen IS NULL THEN -1
                                ELSE GREATEST(0, TIMESTAMPDIFF(SECOND, nodes.last_seen, CURRENT_TIMESTAMP(3)))
                            END AS heartbeat_age_seconds
                     FROM dimension_assignments AS assignments
                     LEFT JOIN cluster_nodes AS nodes ON nodes.node_id = assignments.node_id
                     WHERE assignments.node_id <> ?
                     GROUP BY assignments.node_id, nodes.last_seen, nodes.stopped_at
                     ORDER BY assignments.node_id
                     """)) {
            statement.setString(1, config.nodeId());
            int confirmationSeconds = Math.max(
                    config.nodeTimeoutSeconds(),
                    config.automaticFailoverConfirmationSeconds()
            );
            List<AutomaticFailoverCandidate> candidates = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String nodeId = resultSet.getString("node_id");
                    long heartbeatAgeSeconds = resultSet.getLong("heartbeat_age_seconds");
                    boolean registered = resultSet.getTimestamp("last_seen") != null;
                    boolean cleanStop = resultSet.getTimestamp("stopped_at") != null;
                    int dimensionCount = resultSet.getInt("dimension_count");
                    boolean eligible = false;
                    long secondsRemaining = 0L;
                    String reason;
                    if (!registered) {
                        reason = "узел не зарегистрирован; требуется ручной failover";
                    } else if (cleanStop && !config.automaticFailoverIncludeCleanStops()) {
                        reason = "чистая остановка; automatic failover запрещён";
                    } else if (heartbeatAgeSeconds < config.nodeTimeoutSeconds()) {
                        reason = "узел ONLINE";
                    } else if (heartbeatAgeSeconds < confirmationSeconds) {
                        secondsRemaining = confirmationSeconds - heartbeatAgeSeconds;
                        reason = "ожидание подтверждения сбоя";
                    } else {
                        eligible = true;
                        reason = "готов к automatic failover";
                    }
                    candidates.add(new AutomaticFailoverCandidate(
                            nodeId,
                            heartbeatAgeSeconds,
                            cleanStop,
                            dimensionCount,
                            eligible,
                            secondsRemaining,
                            reason
                    ));
                }
            }
            return List.copyOf(candidates);
        }
    }

    public static boolean tryAcquireOperationLease(
            ClusterConfig config,
            String leaseName,
            int leaseSeconds
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT IGNORE INTO cluster_operation_leases (
                            lease_name, owner_node, lease_until, updated_at
                        ) VALUES (?, '', TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(3)), CURRENT_TIMESTAMP(3))
                        """)) {
                    statement.setString(1, leaseName);
                    statement.executeUpdate();
                }
                String ownerNode = null;
                Instant leaseUntil = null;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT owner_node, lease_until
                        FROM cluster_operation_leases
                        WHERE lease_name = ?
                        FOR UPDATE
                        """)) {
                    statement.setString(1, leaseName);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (resultSet.next()) {
                            ownerNode = resultSet.getString("owner_node");
                            leaseUntil = resultSet.getTimestamp("lease_until").toInstant();
                        }
                    }
                }
                Instant now = Instant.now();
                boolean acquired = ownerNode == null
                        || ownerNode.isBlank()
                        || ownerNode.equalsIgnoreCase(config.nodeId())
                        || leaseUntil == null
                        || !leaseUntil.isAfter(now);
                if (!acquired) {
                    connection.commit();
                    return false;
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE cluster_operation_leases
                        SET owner_node = ?,
                            lease_until = TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(3)),
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE lease_name = ?
                        """)) {
                    int safeLeaseSeconds = Math.max(1, leaseSeconds);
                    statement.setString(1, config.nodeId());
                    statement.setInt(2, safeLeaseSeconds);
                    statement.setString(3, leaseName);
                    statement.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static void releaseOperationLease(
            ClusterConfig config,
            String leaseName
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM cluster_operation_leases
                     WHERE lease_name = ? AND owner_node = ?
                     """)) {
            statement.setString(1, leaseName);
            statement.setString(2, config.nodeId());
            statement.executeUpdate();
        }
    }

    public static DimensionSnapshot requestDimensionSnapshot(
            ClusterConfig config,
            String dimensionId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                lockDimensionAssignments(connection);
                String owner = findDimensionOwner(connection, dimensionId);
                if (owner == null) {
                    throw new SQLException("У dimension нет владельца: " + dimensionId);
                }
                if (!owner.equalsIgnoreCase(config.nodeId())) {
                    throw new SQLException("Dimension принадлежит узлу " + owner);
                }
                if (hasActiveDimensionMigration(connection, dimensionId)) {
                    throw new SQLException("Для dimension уже выполняется migration");
                }
                if (hasActiveDimensionFailover(connection, dimensionId)) {
                    throw new SQLException("Для dimension уже выполняется failover");
                }
                failStalePreparingDimensionSnapshots(
                        connection,
                        config.nodeId(),
                        Math.max(10, config.dimensionSnapshotIntervalMinutes() * 2)
                );
                if (hasPreparingDimensionSnapshot(connection, dimensionId, config.nodeId())) {
                    throw new SQLException("Для dimension уже создаётся snapshot");
                }
                if (hasActiveDimensionPlayers(connection, dimensionId, config.nodeTimeoutSeconds())) {
                    throw new SQLException("В dimension находятся игроки");
                }
                String snapshotId = UUID.randomUUID().toString();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO cluster_dimension_snapshots (
                            snapshot_id, dimension_id, source_node, status
                        ) VALUES (?, ?, ?, 'PREPARING')
                        """)) {
                    statement.setString(1, snapshotId);
                    statement.setString(2, dimensionId);
                    statement.setString(3, config.nodeId());
                    statement.executeUpdate();
                }
                connection.commit();
                return findDimensionSnapshot(config, snapshotId);
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static DimensionSnapshot markDimensionSnapshotReady(
            ClusterConfig config,
            String snapshotId,
            String archiveName,
            String archiveSha256,
            String contentSha256,
            long archiveSize
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE cluster_dimension_snapshots
                     SET status = 'READY', archive_name = ?, archive_sha256 = ?,
                         content_sha256 = ?, archive_size = ?, error_text = NULL,
                         ready_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3)
                     WHERE snapshot_id = ? AND source_node = ? AND status = 'PREPARING'
                     """)) {
            statement.setString(1, archiveName);
            statement.setString(2, archiveSha256);
            statement.setString(3, contentSha256);
            statement.setLong(4, archiveSize);
            statement.setString(5, snapshotId);
            statement.setString(6, config.nodeId());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Не удалось завершить snapshot " + snapshotId);
            }
        }
        return findDimensionSnapshot(config, snapshotId);
    }

    public static void failDimensionSnapshot(
            ClusterConfig config,
            String snapshotId,
            String errorText
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE cluster_dimension_snapshots
                     SET status = 'FAILED', error_text = ?, updated_at = CURRENT_TIMESTAMP(3)
                     WHERE snapshot_id = ? AND status = 'PREPARING'
                     """)) {
            statement.setString(1, truncate(errorText, 8000));
            statement.setString(2, snapshotId);
            statement.executeUpdate();
        }
    }

    public static DimensionSnapshot findDimensionSnapshot(
            ClusterConfig config,
            String snapshotId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            return findDimensionSnapshot(connection, snapshotId, false);
        }
    }

    public static DimensionSnapshot findLatestReadyDimensionSnapshot(
            ClusterConfig config,
            String dimensionId,
            String sourceNode
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            return findLatestReadyDimensionSnapshot(connection, dimensionId, sourceNode);
        }
    }

    public static List<DimensionSnapshot> listDimensionSnapshots(
            ClusterConfig config,
            int limit
    ) throws SQLException {
        ensureSchema(config);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT snapshot_id, dimension_id, source_node, status,
                            archive_name, archive_sha256, content_sha256, archive_size,
                            error_text, created_at, updated_at, ready_at
                     FROM cluster_dimension_snapshots
                     ORDER BY created_at DESC
                     LIMIT ?
                     """)) {
            statement.setInt(1, safeLimit);
            List<DimensionSnapshot> snapshots = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    snapshots.add(readDimensionSnapshot(resultSet));
                }
            }
            return List.copyOf(snapshots);
        }
    }

    public static List<DimensionSnapshot> listDimensionSnapshotCleanupCandidates(
            ClusterConfig config,
            int retentionDays,
            int maxPerDimension
    ) throws SQLException {
        ensureSchema(config);
        Instant cutoff = Instant.now().minusSeconds(Math.max(1, retentionDays) * 86400L);
        int safeMax = Math.max(1, maxPerDimension);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT snapshots.snapshot_id, snapshots.dimension_id, snapshots.source_node, snapshots.status,
                            snapshots.archive_name, snapshots.archive_sha256, snapshots.content_sha256,
                            snapshots.archive_size, snapshots.error_text, snapshots.created_at,
                            snapshots.updated_at, snapshots.ready_at
                     FROM cluster_dimension_snapshots AS snapshots
                     LEFT JOIN cluster_dimension_failovers AS failovers
                       ON failovers.snapshot_id = snapshots.snapshot_id
                      AND failovers.status IN ('READY', 'APPLYING')
                     WHERE snapshots.source_node = ?
                       AND snapshots.status = 'READY'
                       AND failovers.failover_id IS NULL
                     ORDER BY snapshots.dimension_id, snapshots.ready_at DESC, snapshots.created_at DESC
                     """)) {
            statement.setString(1, config.nodeId());
            Map<String, Integer> ranks = new HashMap<>();
            List<DimensionSnapshot> result = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    DimensionSnapshot snapshot = readDimensionSnapshot(resultSet);
                    int rank = ranks.merge(snapshot.dimensionId(), 1, Integer::sum);
                    boolean beyondLimit = rank > safeMax;
                    boolean expired = rank > 1
                            && snapshot.readyAt() != null
                            && snapshot.readyAt().isBefore(cutoff);
                    if (beyondLimit || expired) {
                        result.add(snapshot);
                    }
                }
            }
            return List.copyOf(result);
        }
    }

    public static void markDimensionSnapshotDeleted(
            ClusterConfig config,
            String snapshotId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE cluster_dimension_snapshots
                     SET status = 'DELETED', updated_at = CURRENT_TIMESTAMP(3), error_text = NULL
                     WHERE snapshot_id = ? AND source_node = ? AND status = 'READY'
                     """)) {
            statement.setString(1, snapshotId);
            statement.setString(2, config.nodeId());
            statement.executeUpdate();
        }
    }

    public static List<FailoverPreviewEntry> previewDimensionFailover(
            ClusterConfig config,
            String sourceNode
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            if (isNodeOnline(connection, sourceNode, config.nodeTimeoutSeconds())) {
                throw new SQLException("Узел " + sourceNode + " находится ONLINE");
            }
            List<String> dimensions = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT dimension_id
                    FROM dimension_assignments
                    WHERE node_id = ?
                    ORDER BY dimension_id
                    """)) {
                statement.setString(1, sourceNode);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        dimensions.add(resultSet.getString("dimension_id"));
                    }
                }
            }
            List<FailoverPreviewEntry> entries = new ArrayList<>();
            for (String dimensionId : dimensions) {
                DimensionSnapshot snapshot = findLatestReadyDimensionSnapshot(
                        connection,
                        dimensionId,
                        sourceNode
                );
                LeastAssignedNode target = findLeastAssignedNode(
                        connection,
                        config.nodeTimeoutSeconds()
                );
                String reason = null;
                if (snapshot == null) {
                    reason = "нет READY snapshot";
                } else if (!isSnapshotFresh(snapshot, config.dimensionSnapshotMaxAgeMinutes())) {
                    reason = "snapshot устарел";
                } else if (target == null) {
                    reason = "нет ONLINE target node";
                } else if (hasActiveDimensionMigration(connection, dimensionId)) {
                    reason = "активна migration";
                } else if (hasActiveDimensionFailover(connection, dimensionId)) {
                    reason = "активен failover";
                }
                entries.add(new FailoverPreviewEntry(
                        dimensionId,
                        sourceNode,
                        target == null ? null : target.nodeId(),
                        snapshot == null ? null : snapshot.snapshotId(),
                        snapshot == null ? null : snapshot.readyAt(),
                        reason == null,
                        reason
                ));
            }
            return List.copyOf(entries);
        }
    }

    public static List<DimensionFailover> prepareDimensionFailover(
            ClusterConfig config,
            String sourceNode
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                lockClusterNodes(connection);
                lockDimensionAssignments(connection);
                if (isNodeOnline(connection, sourceNode, config.nodeTimeoutSeconds())) {
                    throw new SQLException("Узел " + sourceNode + " находится ONLINE");
                }
                List<String> dimensions = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT dimension_id
                        FROM dimension_assignments
                        WHERE node_id = ?
                        ORDER BY dimension_id
                        FOR UPDATE
                        """)) {
                    statement.setString(1, sourceNode);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            dimensions.add(resultSet.getString("dimension_id"));
                        }
                    }
                }
                List<DimensionFailover> failovers = new ArrayList<>();
                for (String dimensionId : dimensions) {
                    if (hasActiveDimensionMigration(connection, dimensionId)
                            || hasActiveDimensionFailover(connection, dimensionId)) {
                        continue;
                    }
                    DimensionSnapshot snapshot = findLatestReadyDimensionSnapshot(
                            connection,
                            dimensionId,
                            sourceNode
                    );
                    LeastAssignedNode target = findLeastAssignedNode(
                            connection,
                            config.nodeTimeoutSeconds()
                    );
                    if (snapshot == null
                            || !isSnapshotFresh(snapshot, config.dimensionSnapshotMaxAgeMinutes())
                            || target == null) {
                        continue;
                    }
                    String failoverId = UUID.randomUUID().toString();
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO cluster_dimension_failovers (
                                failover_id, dimension_id, source_node, target_node,
                                snapshot_id, status
                            ) VALUES (?, ?, ?, ?, ?, 'READY')
                            """)) {
                        statement.setString(1, failoverId);
                        statement.setString(2, dimensionId);
                        statement.setString(3, sourceNode);
                        statement.setString(4, target.nodeId());
                        statement.setString(5, snapshot.snapshotId());
                        statement.executeUpdate();
                    }
                    failovers.add(new DimensionFailover(
                            failoverId,
                            dimensionId,
                            sourceNode,
                            target.nodeId(),
                            snapshot.snapshotId(),
                            "READY",
                            null,
                            Instant.now(),
                            Instant.now(),
                            null,
                            null
                    ));
                }
                connection.commit();
                return List.copyOf(failovers);
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static NodeDrainPreview previewNodeDrain(
            ClusterConfig config,
            String targetNode
    ) throws SQLException {
        if (targetNode == null || targetNode.isBlank()) {
            throw new SQLException("Target node is empty");
        }
        if (targetNode.equalsIgnoreCase(config.nodeId())) {
            throw new SQLException("Нельзя выполнить drain на текущий узел");
        }
        ensureSchema(config);
        try (Connection connection = open(config)) {
            refreshNodeDrainStates(connection);
            if (hasActiveNodeOperation(connection, config.nodeId())) {
                throw new SQLException("Для source node уже существует активный drain");
            }
            boolean targetReady = isNodeOnline(
                    connection,
                    targetNode,
                    config.nodeTimeoutSeconds()
            ) && !hasActiveNodeOperation(connection, targetNode);
            int sourcePlayers = readNodePlayerCount(connection, config.nodeId());
            Map<String, DimensionActivity> activity = loadDimensionActivity(
                    connection,
                    config.nodeTimeoutSeconds()
            );
            List<NodeDrainPreviewEntry> entries = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT dimension_id, node_id, pinned
                    FROM dimension_assignments
                    WHERE node_id = ?
                    ORDER BY dimension_id
                    """)) {
                statement.setString(1, config.nodeId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String dimensionId = resultSet.getString("dimension_id");
                        DimensionAssignmentRow assignment = new DimensionAssignmentRow(
                                resultSet.getString("node_id"),
                                resultSet.getBoolean("pinned")
                        );
                        String reason = validateNodeDrainCandidate(
                                connection,
                                dimensionId,
                                assignment,
                                targetReady,
                                activity
                        );
                        entries.add(new NodeDrainPreviewEntry(
                                dimensionId,
                                config.nodeId(),
                                targetNode,
                                assignment.pinned(),
                                activity.containsKey(dimensionId)
                                        ? activity.get(dimensionId).playerCount()
                                        : 0,
                                activity.containsKey(dimensionId)
                                        ? activity.get(dimensionId).nodeIds()
                                        : List.of(),
                                reason == null,
                                reason
                        ));
                    }
                }
            }
            return new NodeDrainPreview(
                    config.nodeId(),
                    targetNode,
                    sourcePlayers,
                    targetReady,
                    List.copyOf(entries),
                    Instant.now()
            );
        }
    }

    public static NodeDrainPreparationResult prepareNodeDrain(
            ClusterConfig config,
            String targetNode,
            Set<String> locallyAvailableDimensions
    ) throws SQLException {
        if (targetNode == null || targetNode.isBlank()) {
            throw new SQLException("Target node is empty");
        }
        if (targetNode.equalsIgnoreCase(config.nodeId())) {
            throw new SQLException("Нельзя выполнить drain на текущий узел");
        }
        Set<String> availableDimensions = locallyAvailableDimensions == null
                ? Set.of()
                : Set.copyOf(locallyAvailableDimensions);
        if (availableDimensions.isEmpty()) {
            throw new SQLException("Нет локально загруженных измерений для drain");
        }
        ensureSchema(config);
        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                refreshNodeDrainStates(connection);
                lockClusterNodes(connection);
                lockDimensionAssignments(connection);
                lockDimensionActivity(connection);
                if (readNodePlayerCount(connection, config.nodeId()) > 0) {
                    throw new SQLException("На source node ещё находятся игроки");
                }
                if (hasActiveNodeOperation(connection, config.nodeId())) {
                    throw new SQLException("Для source node уже существует активный drain");
                }
                boolean targetReady = isNodeOnline(
                        connection,
                        targetNode,
                        config.nodeTimeoutSeconds()
                ) && !hasActiveNodeOperation(connection, targetNode);
                if (!targetReady) {
                    throw new SQLException("Target node OFFLINE или находится в drain-режиме: " + targetNode);
                }
                Map<String, DimensionActivity> activity = loadDimensionActivity(
                        connection,
                        config.nodeTimeoutSeconds()
                );
                List<String> candidates = new ArrayList<>();
                int skipped = 0;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT dimension_id, node_id, pinned
                        FROM dimension_assignments
                        WHERE node_id = ?
                        ORDER BY dimension_id
                        FOR UPDATE
                        """)) {
                    statement.setString(1, config.nodeId());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            String dimensionId = resultSet.getString("dimension_id");
                            if (!availableDimensions.contains(dimensionId)) {
                                skipped++;
                                continue;
                            }
                            DimensionAssignmentRow assignment = new DimensionAssignmentRow(
                                    resultSet.getString("node_id"),
                                    resultSet.getBoolean("pinned")
                            );
                            String reason = validateNodeDrainCandidate(
                                    connection,
                                    dimensionId,
                                    assignment,
                                    true,
                                    activity
                            );
                            if (reason == null) {
                                candidates.add(dimensionId);
                            } else {
                                skipped++;
                            }
                        }
                    }
                }
                if (candidates.isEmpty()) {
                    throw new SQLException("Нет доступных измерений для drain");
                }
                String drainId = UUID.randomUUID().toString();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO cluster_node_drains (
                            drain_id, operation_type, source_node, target_node, status,
                            created_at, updated_at
                        ) VALUES (?, 'DRAIN', ?, ?, 'PREPARING', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                        """)) {
                    statement.setString(1, drainId);
                    statement.setString(2, config.nodeId());
                    statement.setString(3, targetNode);
                    statement.executeUpdate();
                }
                List<DimensionDrainItem> items = new ArrayList<>();
                for (String dimensionId : candidates) {
                    String migrationId = UUID.randomUUID().toString();
                    String itemId = UUID.randomUUID().toString();
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO cluster_dimension_migrations (
                                migration_id, dimension_id, source_node, target_node,
                                status, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, 'PREPARING', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                            """)) {
                        statement.setString(1, migrationId);
                        statement.setString(2, dimensionId);
                        statement.setString(3, config.nodeId());
                        statement.setString(4, targetNode);
                        statement.executeUpdate();
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO cluster_node_drain_items (
                                drain_item_id, drain_id, migration_id, dimension_id,
                                source_node, target_node, status,
                                created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, 'PREPARING', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                            """)) {
                        statement.setString(1, itemId);
                        statement.setString(2, drainId);
                        statement.setString(3, migrationId);
                        statement.setString(4, dimensionId);
                        statement.setString(5, config.nodeId());
                        statement.setString(6, targetNode);
                        statement.executeUpdate();
                    }
                    Instant now = Instant.now();
                    items.add(new DimensionDrainItem(
                            itemId,
                            drainId,
                            migrationId,
                            dimensionId,
                            config.nodeId(),
                            targetNode,
                            "PREPARING",
                            null,
                            now,
                            now,
                            null
                    ));
                }
                connection.commit();
                Instant now = Instant.now();
                NodeDrain drain = new NodeDrain(
                        drainId,
                        "DRAIN",
                        config.nodeId(),
                        targetNode,
                        "PREPARING",
                        null,
                        items.size(),
                        items.size(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        now,
                        now,
                        null
                );
                return new NodeDrainPreparationResult(
                        drain,
                        List.copyOf(items),
                        skipped
                );
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static NodeDrainPreparationResult prepareSafeRebalance(
            ClusterConfig config,
            String targetNode,
            Collection<String> selectedDimensions,
            Set<String> locallyAvailableDimensions
    ) throws SQLException {
        if (targetNode == null || targetNode.isBlank()) {
            throw new SQLException("Target node is empty");
        }
        if (targetNode.equalsIgnoreCase(config.nodeId())) {
            throw new SQLException("Нельзя балансировать измерения на текущий узел");
        }
        Set<String> selected = new TreeSet<>();
        if (selectedDimensions != null) {
            for (String dimensionId : selectedDimensions) {
                if (dimensionId != null && !dimensionId.isBlank()) {
                    selected.add(dimensionId);
                }
            }
        }
        if (selected.isEmpty()) {
            throw new SQLException("План не содержит файловых перемещений на " + targetNode);
        }
        Set<String> available = locallyAvailableDimensions == null
                ? Set.of()
                : Set.copyOf(locallyAvailableDimensions);
        if (available.isEmpty()) {
            throw new SQLException("Нет локально загруженных измерений для безопасной балансировки");
        }
        ensureSchema(config);
        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                refreshNodeDrainStates(connection);
                lockClusterNodes(connection);
                lockDimensionAssignments(connection);
                lockDimensionActivity(connection);
                if (hasActiveNodeOperation(connection, config.nodeId())) {
                    throw new SQLException("Для source node уже существует активная кластерная операция");
                }
                boolean targetReady = isNodeOnline(
                        connection,
                        targetNode,
                        config.nodeTimeoutSeconds()
                ) && !hasActiveNodeOperation(connection, targetNode);
                if (!targetReady) {
                    throw new SQLException("Target node OFFLINE или участвует в другой операции: " + targetNode);
                }
                Map<String, DimensionActivity> activity = loadDimensionActivity(
                        connection,
                        config.nodeTimeoutSeconds()
                );
                List<String> candidates = new ArrayList<>();
                List<String> blocked = new ArrayList<>();
                Set<String> found = new HashSet<>();
                int skipped = 0;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT dimension_id, node_id, pinned
                        FROM dimension_assignments
                        WHERE node_id = ?
                        ORDER BY dimension_id
                        FOR UPDATE
                        """)) {
                    statement.setString(1, config.nodeId());
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            String dimensionId = resultSet.getString("dimension_id");
                            if (!selected.contains(dimensionId)) {
                                continue;
                            }
                            found.add(dimensionId);
                            if (!available.contains(dimensionId)) {
                                blocked.add(dimensionId + " (измерение не загружено)");
                                continue;
                            }
                            DimensionAssignmentRow assignment = new DimensionAssignmentRow(
                                    resultSet.getString("node_id"),
                                    resultSet.getBoolean("pinned")
                            );
                            String reason = validateNodeDrainCandidate(
                                    connection,
                                    dimensionId,
                                    assignment,
                                    true,
                                    activity
                            );
                            if (reason == null) {
                                candidates.add(dimensionId);
                            } else {
                                blocked.add(dimensionId + " (" + reason + ")");
                            }
                        }
                    }
                }
                for (String dimensionId : selected) {
                    if (!found.contains(dimensionId)) {
                        blocked.add(dimensionId + " (владелец или план изменился)");
                    }
                }
                if (!blocked.isEmpty()) {
                    int limit = Math.min(5, blocked.size());
                    throw new SQLException(
                            "Безопасная балансировка остановлена: недоступно "
                                    + blocked.size() + " измерений: "
                                    + String.join(", ", blocked.subList(0, limit))
                    );
                }
                if (candidates.isEmpty()) {
                    throw new SQLException("Нет доступных измерений для безопасной балансировки");
                }
                String operationId = UUID.randomUUID().toString();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO cluster_node_drains (
                            drain_id, operation_type, source_node, target_node, status,
                            created_at, updated_at
                        ) VALUES (?, 'REBALANCE', ?, ?, 'PREPARING', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                        """)) {
                    statement.setString(1, operationId);
                    statement.setString(2, config.nodeId());
                    statement.setString(3, targetNode);
                    statement.executeUpdate();
                }
                List<DimensionDrainItem> items = new ArrayList<>();
                for (String dimensionId : candidates) {
                    String migrationId = UUID.randomUUID().toString();
                    String itemId = UUID.randomUUID().toString();
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO cluster_dimension_migrations (
                                migration_id, dimension_id, source_node, target_node,
                                status, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, 'PREPARING', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                            """)) {
                        statement.setString(1, migrationId);
                        statement.setString(2, dimensionId);
                        statement.setString(3, config.nodeId());
                        statement.setString(4, targetNode);
                        statement.executeUpdate();
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO cluster_node_drain_items (
                                drain_item_id, drain_id, migration_id, dimension_id,
                                source_node, target_node, status,
                                created_at, updated_at
                            ) VALUES (?, ?, ?, ?, ?, ?, 'PREPARING', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                            """)) {
                        statement.setString(1, itemId);
                        statement.setString(2, operationId);
                        statement.setString(3, migrationId);
                        statement.setString(4, dimensionId);
                        statement.setString(5, config.nodeId());
                        statement.setString(6, targetNode);
                        statement.executeUpdate();
                    }
                    Instant now = Instant.now();
                    items.add(new DimensionDrainItem(
                            itemId,
                            operationId,
                            migrationId,
                            dimensionId,
                            config.nodeId(),
                            targetNode,
                            "PREPARING",
                            null,
                            now,
                            now,
                            null
                    ));
                }
                connection.commit();
                Instant now = Instant.now();
                NodeDrain operation = new NodeDrain(
                        operationId,
                        "REBALANCE",
                        config.nodeId(),
                        targetNode,
                        "PREPARING",
                        null,
                        items.size(),
                        items.size(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        now,
                        now,
                        null
                );
                return new NodeDrainPreparationResult(
                        operation,
                        List.copyOf(items),
                        skipped
                );
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static NodeOperationRecoveryResult prepareNodeOperationRecovery(
            ClusterConfig config,
            String operationId,
            String expectedOperationType,
            Set<String> locallyAvailableDimensions
    ) throws SQLException {
        if (operationId == null || operationId.isBlank()) {
            throw new SQLException("Operation ID is empty");
        }
        String operationType = expectedOperationType == null
                ? ""
                : expectedOperationType.trim().toUpperCase(Locale.ROOT);
        if (!operationType.equals("DRAIN") && !operationType.equals("REBALANCE")) {
            throw new SQLException("Unsupported operation type: " + expectedOperationType);
        }
        Set<String> availableDimensions = locallyAvailableDimensions == null
                ? Set.of()
                : Set.copyOf(locallyAvailableDimensions);
        ensureSchema(config);
        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                refreshNodeDrainStates(connection);
                lockClusterNodes(connection);
                lockDimensionAssignments(connection);
                lockDimensionActivity(connection);

                String sourceNode;
                String targetNode;
                String status;
                String actualOperationType;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT operation_type, source_node, target_node, status
                        FROM cluster_node_drains
                        WHERE drain_id = ?
                        FOR UPDATE
                        """)) {
                    statement.setString(1, operationId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            throw new SQLException("Кластерная операция не найдена: " + operationId);
                        }
                        actualOperationType = resultSet.getString("operation_type");
                        sourceNode = resultSet.getString("source_node");
                        targetNode = resultSet.getString("target_node");
                        status = resultSet.getString("status");
                    }
                }
                if (!operationType.equalsIgnoreCase(actualOperationType)) {
                    throw new SQLException(
                            "Operation " + operationId + " имеет тип " + actualOperationType
                                    + ", ожидался " + operationType
                    );
                }
                if (!sourceNode.equalsIgnoreCase(config.nodeId())) {
                    throw new SQLException(
                            "Recovery можно запускать только на source node " + sourceNode
                    );
                }
                if (!status.equals("PREPARING")
                        && !status.equals("READY")
                        && !status.equals("FAILED")) {
                    throw new SQLException(
                            "Operation со status=" + status + " нельзя восстановить подготовкой"
                    );
                }
                if (operationType.equals("DRAIN")
                        && readNodePlayerCount(connection, sourceNode) > 0) {
                    throw new SQLException("На source node ещё находятся игроки");
                }
                boolean targetReady = isNodeOnline(
                        connection,
                        targetNode,
                        config.nodeTimeoutSeconds()
                ) && !hasOtherActiveNodeOperation(
                        connection,
                        targetNode,
                        operationId
                );
                if (!targetReady) {
                    throw new SQLException(
                            "Target node OFFLINE или участвует в другой операции: " + targetNode
                    );
                }
                if (hasOtherActiveNodeOperation(connection, sourceNode, operationId)) {
                    throw new SQLException(
                            "Source node участвует в другой активной операции: " + sourceNode
                    );
                }

                Map<String, DimensionActivity> activity = loadDimensionActivity(
                        connection,
                        config.nodeTimeoutSeconds()
                );
                List<DimensionDrainItem> retryItems = new ArrayList<>();
                int alreadyReady = 0;
                int alreadyApplied = 0;
                int skipped = 0;
                List<String> blocked = new ArrayList<>();

                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT drain_item_id
                        FROM cluster_node_drain_items
                        WHERE drain_id = ?
                        ORDER BY dimension_id
                        FOR UPDATE
                        """)) {
                    statement.setString(1, operationId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            String drainItemId = resultSet.getString("drain_item_id");
                            DimensionDrainItem item = findDimensionDrainItem(
                                    connection,
                                    drainItemId,
                                    false
                            );
                            if (item == null) {
                                blocked.add(drainItemId + " (item исчез из базы)");
                                continue;
                            }
                            DimensionMigration migration = findDimensionMigration(
                                    connection,
                                    item.migrationId(),
                                    true
                            );
                            if (migration == null) {
                                blocked.add(item.dimensionId() + " (migration не найдена)");
                                continue;
                            }
                            if (!migration.sourceNode().equalsIgnoreCase(sourceNode)
                                    || !migration.targetNode().equalsIgnoreCase(targetNode)
                                    || !migration.dimensionId().equals(item.dimensionId())) {
                                blocked.add(item.dimensionId() + " (данные migration не совпадают с operation)");
                                continue;
                            }

                            boolean alreadyAppliedState = item.status().equals("APPLIED")
                                    && (migration.status().equals("APPLIED")
                                    || migration.status().equals("COMPLETED")
                                    || migration.status().equals("VERIFIED")
                                    || migration.status().equals("FINALIZE_READY")
                                    || migration.status().equals("FINALIZED"));
                            if (alreadyAppliedState) {
                                DimensionAssignmentRow assignment = findDimensionAssignmentRow(
                                        connection,
                                        item.dimensionId()
                                );
                                if (assignment == null
                                        || !assignment.nodeId().equalsIgnoreCase(targetNode)) {
                                    blocked.add(
                                            item.dimensionId()
                                                    + " (APPLIED item больше не принадлежит target node)"
                                    );
                                    continue;
                                }
                                alreadyApplied++;
                                continue;
                            }
                            if (item.status().equals("READY")
                                    && migration.status().equals("READY")) {
                                alreadyReady++;
                                continue;
                            }
                            if (item.status().equals("PREPARING")
                                    && migration.status().equals("READY")) {
                                try (PreparedStatement readyStatement = connection.prepareStatement("""
                                        UPDATE cluster_node_drain_items
                                        SET status = 'READY', error_text = NULL,
                                            updated_at = CURRENT_TIMESTAMP(3)
                                        WHERE drain_item_id = ?
                                          AND status = 'PREPARING'
                                        """)) {
                                    readyStatement.setString(1, item.drainItemId());
                                    if (readyStatement.executeUpdate() != 1) {
                                        blocked.add(
                                                item.dimensionId()
                                                        + " (item изменился при восстановлении READY)"
                                        );
                                        continue;
                                    }
                                }
                                alreadyReady++;
                                continue;
                            }
                            if (item.status().equals("CANCELLED")
                                    && migration.status().equals("CANCELLED")) {
                                skipped++;
                                continue;
                            }
                            boolean preparing = item.status().equals("PREPARING")
                                    && migration.status().equals("PREPARING");
                            boolean retryableFailure = item.status().equals("FAILED")
                                    && migration.status().equals("FAILED")
                                    && migration.appliedAt() == null;
                            if (!preparing && !retryableFailure) {
                                blocked.add(
                                        item.dimensionId() + " (item=" + item.status()
                                                + ", migration=" + migration.status() + ")"
                                );
                                continue;
                            }

                            String reason = validateNodeOperationRecoveryCandidate(
                                    connection,
                                    item,
                                    migration,
                                    availableDimensions,
                                    activity
                            );
                            if (reason != null) {
                                blocked.add(item.dimensionId() + " (" + reason + ")");
                                continue;
                            }
                            retryItems.add(item);
                        }
                    }
                }

                if (!blocked.isEmpty()) {
                    int limit = Math.min(5, blocked.size());
                    throw new SQLException(
                            "Recovery остановлен: заблокировано " + blocked.size()
                                    + " элементов: "
                                    + String.join(", ", blocked.subList(0, limit))
                    );
                }

                for (DimensionDrainItem item : retryItems) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE cluster_dimension_migrations
                            SET status = 'PREPARING',
                                archive_name = NULL,
                                archive_sha256 = NULL,
                                content_sha256 = NULL,
                                archive_size = 0,
                                error_text = NULL,
                                ready_at = NULL,
                                applying_at = NULL,
                                applied_at = NULL,
                                updated_at = CURRENT_TIMESTAMP(3)
                            WHERE migration_id = ?
                              AND status IN ('PREPARING', 'FAILED')
                            """)) {
                        statement.setString(1, item.migrationId());
                        if (statement.executeUpdate() != 1) {
                            throw new SQLException(
                                    "Migration изменилась во время recovery: " + item.migrationId()
                            );
                        }
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE cluster_node_drain_items
                            SET status = 'PREPARING',
                                error_text = NULL,
                                applied_at = NULL,
                                updated_at = CURRENT_TIMESTAMP(3)
                            WHERE drain_item_id = ?
                              AND status IN ('PREPARING', 'FAILED')
                            """)) {
                        statement.setString(1, item.drainItemId());
                        if (statement.executeUpdate() != 1) {
                            throw new SQLException(
                                    "Operation item изменился во время recovery: " + item.drainItemId()
                            );
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE cluster_node_drains
                        SET error_text = NULL,
                            completed_at = NULL,
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE drain_id = ?
                        """)) {
                    statement.setString(1, operationId);
                    statement.executeUpdate();
                }
                refreshNodeDrainStates(connection);

                List<DimensionDrainItem> refreshedItems = new ArrayList<>();
                for (DimensionDrainItem item : retryItems) {
                    DimensionDrainItem refreshed = findDimensionDrainItem(
                            connection,
                            item.drainItemId(),
                            false
                    );
                    if (refreshed != null) {
                        refreshedItems.add(refreshed);
                    }
                }
                NodeDrain operation = findNodeDrain(connection, operationId);
                connection.commit();
                return new NodeOperationRecoveryResult(
                        operation,
                        List.copyOf(refreshedItems),
                        alreadyReady,
                        alreadyApplied,
                        skipped
                );
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static DimensionDrainItem markNodeDrainItemReady(
            ClusterConfig config,
            String drainItemId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE cluster_node_drain_items
                     SET status = 'READY', error_text = NULL,
                         updated_at = CURRENT_TIMESTAMP(3)
                     WHERE drain_item_id = ?
                       AND source_node = ?
                       AND status = 'PREPARING'
                     """)) {
            statement.setString(1, drainItemId);
            statement.setString(2, config.nodeId());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Drain item уже не PREPARING: " + drainItemId);
            }
        }
        return findDimensionDrainItem(config, drainItemId);
    }

    public static void skipNodeDrainItem(
            ClusterConfig config,
            String drainItemId,
            String reason
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                DimensionDrainItem item = findDimensionDrainItem(
                        connection,
                        drainItemId,
                        true
                );
                if (item == null) {
                    throw new SQLException("Drain item не найден: " + drainItemId);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE cluster_node_drain_items
                        SET status = 'CANCELLED', error_text = ?,
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE drain_item_id = ?
                          AND status IN ('PREPARING', 'READY')
                        """)) {
                    statement.setString(1, truncate(reason, 8000));
                    statement.setString(2, drainItemId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE cluster_dimension_migrations
                        SET status = 'CANCELLED', error_text = ?,
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE migration_id = ?
                          AND status IN ('PREPARING', 'READY')
                        """)) {
                    statement.setString(1, truncate(reason, 8000));
                    statement.setString(2, item.migrationId());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static void failNodeDrainItem(
            ClusterConfig config,
            String drainItemId,
            String errorText
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                DimensionDrainItem item = findDimensionDrainItem(
                        connection,
                        drainItemId,
                        true
                );
                if (item == null) {
                    throw new SQLException("Drain item не найден: " + drainItemId);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE cluster_node_drain_items
                        SET status = 'FAILED', error_text = ?,
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE drain_item_id = ?
                          AND status IN ('PREPARING', 'READY', 'APPLYING')
                        """)) {
                    statement.setString(1, truncate(errorText, 8000));
                    statement.setString(2, drainItemId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE cluster_dimension_migrations
                        SET status = 'FAILED', error_text = ?,
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE migration_id = ?
                          AND status IN ('PREPARING', 'READY', 'APPLYING')
                        """)) {
                    statement.setString(1, truncate(errorText, 8000));
                    statement.setString(2, item.migrationId());
                    statement.executeUpdate();
                }
                refreshNodeDrainStates(connection);
                connection.commit();
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static NodeDrain findNodeDrain(
            ClusterConfig config,
            String drainId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            refreshNodeDrainStates(connection);
            return findNodeDrain(connection, drainId);
        }
    }

    public static DimensionDrainItem findDimensionDrainItem(
            ClusterConfig config,
            String drainItemId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            refreshNodeDrainStates(connection);
            return findDimensionDrainItem(connection, drainItemId, false);
        }
    }

    public static List<NodeDrain> listNodeDrains(
            ClusterConfig config,
            int limit
    ) throws SQLException {
        ensureSchema(config);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        try (Connection connection = open(config)) {
            refreshNodeDrainStates(connection);
            try (PreparedStatement statement = connection.prepareStatement(nodeDrainSelectSql() + """
                    HAVING drains.operation_type = 'DRAIN'
                    ORDER BY drains.created_at DESC
                    LIMIT ?
                    """)) {
                statement.setInt(1, safeLimit);
                List<NodeDrain> drains = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        drains.add(readNodeDrain(resultSet));
                    }
                }
                return List.copyOf(drains);
            }
        }
    }

    public static List<NodeDrain> listSafeRebalances(
            ClusterConfig config,
            int limit
    ) throws SQLException {
        ensureSchema(config);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        try (Connection connection = open(config)) {
            refreshNodeDrainStates(connection);
            try (PreparedStatement statement = connection.prepareStatement(nodeDrainSelectSql() + """
                    HAVING drains.operation_type = 'REBALANCE'
                    ORDER BY drains.created_at DESC
                    LIMIT ?
                    """)) {
                statement.setInt(1, safeLimit);
                List<NodeDrain> operations = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        operations.add(readNodeDrain(resultSet));
                    }
                }
                return List.copyOf(operations);
            }
        }
    }

    public static List<NodeDrain> listStartupNodeOperationRecoveryCandidates(
            ClusterConfig config,
            int limit
    ) throws SQLException {
        ensureSchema(config);
        int safeLimit = Math.max(1, Math.min(limit, 20));
        try (Connection connection = open(config)) {
            refreshNodeDrainStates(connection);
            try (PreparedStatement statement = connection.prepareStatement(nodeDrainSelectSql() + """
                    HAVING drains.source_node = ?
                       AND drains.operation_type IN ('DRAIN', 'REBALANCE')
                       AND drains.status IN ('PREPARING', 'READY')
                       AND SUM(CASE WHEN items.status = 'PREPARING' THEN 1 ELSE 0 END) > 0
                    ORDER BY drains.updated_at ASC, drains.created_at ASC
                    LIMIT ?
                    """)) {
                statement.setString(1, config.nodeId());
                statement.setInt(2, safeLimit);
                List<NodeDrain> operations = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        operations.add(readNodeDrain(resultSet));
                    }
                }
                return List.copyOf(operations);
            }
        }
    }

    public static List<DimensionDrainItem> listNodeDrainItems(
            ClusterConfig config,
            String drainId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            refreshNodeDrainStates(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT drain_item_id, drain_id, migration_id, dimension_id,
                           source_node, target_node, status, error_text,
                           created_at, updated_at, applied_at
                    FROM cluster_node_drain_items
                    WHERE drain_id = ?
                    ORDER BY dimension_id
                    """)) {
                statement.setString(1, drainId);
                List<DimensionDrainItem> items = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        items.add(readDimensionDrainItem(resultSet));
                    }
                }
                return List.copyOf(items);
            }
        }
    }

    public static NodeDrainCancellationResult cancelNodeDrain(
            ClusterConfig config,
            String drainId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                refreshNodeDrainStates(connection);
                NodeDrain drain = findNodeDrain(connection, drainId);
                if (drain == null) {
                    throw new SQLException("Drain не найден: " + drainId);
                }
                if (!drain.sourceNode().equalsIgnoreCase(config.nodeId())) {
                    throw new SQLException("Drain можно отменить только на source node " + drain.sourceNode());
                }
                if (!drain.status().equals("PREPARING")
                        && !drain.status().equals("READY")
                        && !drain.status().equals("FAILED")) {
                    throw new SQLException("Drain со status=" + drain.status() + " нельзя отменить");
                }
                List<DimensionMigration> migrations = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT migration_id
                        FROM cluster_node_drain_items
                        WHERE drain_id = ?
                        ORDER BY dimension_id
                        FOR UPDATE
                        """)) {
                    statement.setString(1, drainId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            DimensionMigration migration = findDimensionMigration(
                                    connection,
                                    resultSet.getString("migration_id"),
                                    true
                            );
                            if (migration != null) {
                                if (!migration.status().equals("PREPARING")
                                        && !migration.status().equals("READY")
                                        && !migration.status().equals("FAILED")
                                        && !migration.status().equals("CANCELLED")) {
                                    throw new SQLException(
                                            "Target уже начал или завершил применение migration "
                                                    + migration.migrationId()
                                    );
                                }
                                migrations.add(migration);
                            }
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE cluster_dimension_migrations AS migrations
                        JOIN cluster_node_drain_items AS items
                          ON items.migration_id = migrations.migration_id
                        SET migrations.status = 'CANCELLED',
                            migrations.updated_at = CURRENT_TIMESTAMP(3)
                        WHERE items.drain_id = ?
                          AND migrations.status IN ('PREPARING', 'READY', 'FAILED')
                        """)) {
                    statement.setString(1, drainId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE cluster_node_drain_items
                        SET status = 'CANCELLED', updated_at = CURRENT_TIMESTAMP(3)
                        WHERE drain_id = ?
                          AND status IN ('PREPARING', 'READY', 'FAILED')
                        """)) {
                    statement.setString(1, drainId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE cluster_node_drains
                        SET status = 'CANCELLED', error_text = NULL,
                            updated_at = CURRENT_TIMESTAMP(3),
                            completed_at = CURRENT_TIMESTAMP(3)
                        WHERE drain_id = ?
                        """)) {
                    statement.setString(1, drainId);
                    statement.executeUpdate();
                }
                connection.commit();
                return new NodeDrainCancellationResult(
                        findNodeDrain(config, drainId),
                        List.copyOf(migrations)
                );
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static NodeDrain resumeNodeDrain(
            ClusterConfig config,
            String drainId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                refreshNodeDrainStates(connection);
                NodeDrain drain = findNodeDrain(connection, drainId);
                if (drain == null) {
                    throw new SQLException("Drain не найден: " + drainId);
                }
                if (!drain.sourceNode().equalsIgnoreCase(config.nodeId())) {
                    throw new SQLException("Drain можно завершить только на source node " + drain.sourceNode());
                }
                if (!"DRAIN".equals(drain.operationType())) {
                    throw new SQLException("Operation " + drainId + " не является node drain");
                }
                if (!drain.status().equals("DRAINED")
                        && !drain.status().equals("PARTIAL")
                        && !drain.status().equals("FAILED")) {
                    throw new SQLException("Drain со status=" + drain.status() + " нельзя перевести в RESUMED");
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE cluster_node_drains
                        SET status = 'RESUMED', error_text = NULL,
                            updated_at = CURRENT_TIMESTAMP(3),
                            completed_at = COALESCE(completed_at, CURRENT_TIMESTAMP(3))
                        WHERE drain_id = ?
                        """)) {
                    statement.setString(1, drainId);
                    statement.executeUpdate();
                }
                connection.commit();
                return findNodeDrain(config, drainId);
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static NodeDrainReadiness readNodeDrainReadiness(
            ClusterConfig config,
            String nodeId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            refreshNodeDrainStates(connection);
            int playerCount = readNodePlayerCount(connection, nodeId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT
                        COUNT(*) AS assignment_count,
                        SUM(CASE WHEN pinned = 1 THEN 1 ELSE 0 END) AS pinned_count,
                        SUM(CASE WHEN dimension_id = 'minecraft:overworld' THEN 1 ELSE 0 END) AS unsupported_count,
                        SUM(CASE WHEN pinned = 0 AND dimension_id <> 'minecraft:overworld' THEN 1 ELSE 0 END) AS migratable_count
                    FROM dimension_assignments
                    WHERE node_id = ?
                    """)) {
                statement.setString(1, nodeId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    int assignments = resultSet.getInt("assignment_count");
                    int pinned = resultSet.getInt("pinned_count");
                    int unsupported = resultSet.getInt("unsupported_count");
                    int migratable = resultSet.getInt("migratable_count");
                    int activeDrains;
                    try (PreparedStatement drainStatement = connection.prepareStatement("""
                            SELECT COUNT(*)
                            FROM cluster_node_drains
                            WHERE operation_type = 'DRAIN'
                              AND source_node = ?
                              AND status IN ('PREPARING', 'READY', 'APPLYING', 'DRAINED', 'PARTIAL', 'FAILED')
                            """)) {
                        drainStatement.setString(1, nodeId);
                        try (ResultSet drainResult = drainStatement.executeQuery()) {
                            drainResult.next();
                            activeDrains = drainResult.getInt(1);
                        }
                    }
                    return new NodeDrainReadiness(
                            nodeId,
                            playerCount,
                            assignments,
                            pinned,
                            unsupported,
                            migratable,
                            activeDrains,
                            playerCount == 0 && assignments == 0,
                            Instant.now()
                    );
                }
            }
        }
    }

    public static List<FailbackPreviewEntry> previewDimensionFailback(
            ClusterConfig config,
            String recoveredNode
    ) throws SQLException {
        if (recoveredNode == null || recoveredNode.isBlank()) {
            throw new SQLException("Recovered node is empty");
        }
        if (recoveredNode.equalsIgnoreCase(config.nodeId())) {
            throw new SQLException("Failback должен запускаться на текущем владельце измерений");
        }
        ensureSchema(config);
        try (Connection connection = open(config)) {
            boolean recoveredOnline = isNodeOnline(
                    connection,
                    recoveredNode,
                    config.nodeTimeoutSeconds()
            );
            Map<String, DimensionActivity> activity = loadDimensionActivity(
                    connection,
                    config.nodeTimeoutSeconds()
            );
            List<DimensionFailover> candidates = listDimensionFailbackCandidates(
                    connection,
                    config.nodeId(),
                    recoveredNode,
                    false
            );
            List<FailbackPreviewEntry> entries = new ArrayList<>();
            for (DimensionFailover failover : candidates) {
                String reason = validateDimensionFailbackCandidate(
                        connection,
                        config,
                        failover,
                        recoveredOnline,
                        activity
                );
                entries.add(new FailbackPreviewEntry(
                        failover.failoverId(),
                        failover.dimensionId(),
                        config.nodeId(),
                        recoveredNode,
                        reason == null,
                        reason
                ));
            }
            return List.copyOf(entries);
        }
    }

    public static FailbackPreparationResult prepareDimensionFailback(
            ClusterConfig config,
            String recoveredNode
    ) throws SQLException {
        if (recoveredNode == null || recoveredNode.isBlank()) {
            throw new SQLException("Recovered node is empty");
        }
        if (recoveredNode.equalsIgnoreCase(config.nodeId())) {
            throw new SQLException("Failback должен запускаться на текущем владельце измерений");
        }
        ensureSchema(config);
        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                lockClusterNodes(connection);
                lockDimensionAssignments(connection);
                lockDimensionActivity(connection);
                boolean recoveredOnline = isNodeOnline(
                        connection,
                        recoveredNode,
                        config.nodeTimeoutSeconds()
                );
                if (!recoveredOnline) {
                    throw new SQLException("Восстановленный узел " + recoveredNode + " не находится ONLINE");
                }
                Map<String, DimensionActivity> activity = loadDimensionActivity(
                        connection,
                        config.nodeTimeoutSeconds()
                );
                List<DimensionFailover> candidates = listDimensionFailbackCandidates(
                        connection,
                        config.nodeId(),
                        recoveredNode,
                        true
                );
                List<DimensionFailback> created = new ArrayList<>();
                int skipped = 0;
                for (DimensionFailover failover : candidates) {
                    String reason = validateDimensionFailbackCandidate(
                            connection,
                            config,
                            failover,
                            true,
                            activity
                    );
                    if (reason != null) {
                        skipped++;
                        continue;
                    }
                    String migrationId = UUID.randomUUID().toString();
                    String failbackId = UUID.randomUUID().toString();
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO cluster_dimension_migrations (
                                migration_id, dimension_id, source_node, target_node,
                                status, created_at, updated_at
                            ) VALUES (?, ?, ?, ?, 'PREPARING', CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                            """)) {
                        statement.setString(1, migrationId);
                        statement.setString(2, failover.dimensionId());
                        statement.setString(3, config.nodeId());
                        statement.setString(4, recoveredNode);
                        statement.executeUpdate();
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO cluster_dimension_failbacks (
                                failback_id, failover_id, migration_id, dimension_id,
                                source_node, target_node, status
                            ) VALUES (?, ?, ?, ?, ?, ?, 'PREPARING')
                            """)) {
                        statement.setString(1, failbackId);
                        statement.setString(2, failover.failoverId());
                        statement.setString(3, migrationId);
                        statement.setString(4, failover.dimensionId());
                        statement.setString(5, config.nodeId());
                        statement.setString(6, recoveredNode);
                        statement.executeUpdate();
                    }
                    Instant now = Instant.now();
                    created.add(new DimensionFailback(
                            failbackId,
                            failover.failoverId(),
                            migrationId,
                            failover.dimensionId(),
                            config.nodeId(),
                            recoveredNode,
                            "PREPARING",
                            null,
                            now,
                            now,
                            null
                    ));
                }
                connection.commit();
                return new FailbackPreparationResult(List.copyOf(created), skipped);
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static DimensionFailback markDimensionFailbackReady(
            ClusterConfig config,
            String failbackId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE cluster_dimension_failbacks
                     SET status = 'READY', error_text = NULL, updated_at = CURRENT_TIMESTAMP(3)
                     WHERE failback_id = ? AND source_node = ? AND status = 'PREPARING'
                     """)) {
            statement.setString(1, failbackId);
            statement.setString(2, config.nodeId());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Failback уже не PREPARING: " + failbackId);
            }
        }
        return findDimensionFailback(config, failbackId);
    }

    public static void failDimensionFailback(
            ClusterConfig config,
            String failbackId,
            String errorText
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                DimensionFailback failback = findDimensionFailback(connection, failbackId, true);
                if (failback == null) {
                    throw new SQLException("Failback не найден: " + failbackId);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE cluster_dimension_failbacks
                        SET status = 'FAILED', error_text = ?, updated_at = CURRENT_TIMESTAMP(3)
                        WHERE failback_id = ? AND status IN ('PREPARING', 'READY', 'APPLYING')
                        """)) {
                    statement.setString(1, truncate(errorText, 8000));
                    statement.setString(2, failbackId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE cluster_dimension_migrations
                        SET status = 'FAILED', error_text = ?, updated_at = CURRENT_TIMESTAMP(3)
                        WHERE migration_id = ? AND status IN ('PREPARING', 'READY', 'APPLYING')
                        """)) {
                    statement.setString(1, truncate(errorText, 8000));
                    statement.setString(2, failback.migrationId());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static DimensionFailback findDimensionFailback(
            ClusterConfig config,
            String failbackId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            refreshDimensionFailbackStates(connection);
            return findDimensionFailback(connection, failbackId, false);
        }
    }

    public static List<DimensionFailback> listDimensionFailbacks(
            ClusterConfig config,
            int limit
    ) throws SQLException {
        ensureSchema(config);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        try (Connection connection = open(config)) {
            refreshDimensionFailbackStates(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT failback_id, failover_id, migration_id, dimension_id,
                           source_node, target_node, status, error_text,
                           created_at, updated_at, applied_at
                    FROM cluster_dimension_failbacks
                    ORDER BY created_at DESC
                    LIMIT ?
                    """)) {
                statement.setInt(1, safeLimit);
                List<DimensionFailback> result = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        result.add(readDimensionFailback(resultSet));
                    }
                }
                return List.copyOf(result);
            }
        }
    }

    public static DimensionFailover findPendingDimensionFailoverForTarget(
            ClusterConfig config
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT failover_id, dimension_id, source_node, target_node,
                            snapshot_id, status, error_text, created_at, updated_at,
                            applying_at, applied_at
                     FROM cluster_dimension_failovers
                     WHERE target_node = ? AND status = 'READY'
                     ORDER BY created_at
                     LIMIT 1
                     """)) {
            statement.setString(1, config.nodeId());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readDimensionFailover(resultSet) : null;
            }
        }
    }

    public static DimensionFailover markDimensionFailoverApplying(
            ClusterConfig config,
            String failoverId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE cluster_dimension_failovers
                     SET status = 'APPLYING', applying_at = CURRENT_TIMESTAMP(3),
                         updated_at = CURRENT_TIMESTAMP(3), error_text = NULL
                     WHERE failover_id = ? AND target_node = ? AND status = 'READY'
                     """)) {
            statement.setString(1, failoverId);
            statement.setString(2, config.nodeId());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Failover уже не READY: " + failoverId);
            }
        }
        return findDimensionFailover(config, failoverId);
    }

    public static DimensionFailover completeDimensionFailover(
            ClusterConfig config,
            String failoverId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);
            try {
                DimensionFailover failover = findDimensionFailover(connection, failoverId, true);
                if (failover == null || !"APPLYING".equals(failover.status())) {
                    throw new SQLException("Failover не находится в APPLYING: " + failoverId);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE dimension_assignments
                        SET node_id = ?, updated_at = CURRENT_TIMESTAMP(3)
                        WHERE dimension_id = ? AND node_id = ?
                        """)) {
                    statement.setString(1, failover.targetNode());
                    statement.setString(2, failover.dimensionId());
                    statement.setString(3, failover.sourceNode());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Владелец dimension изменился во время failover");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE cluster_dimension_failovers
                        SET status = 'APPLIED', applied_at = CURRENT_TIMESTAMP(3),
                            updated_at = CURRENT_TIMESTAMP(3), error_text = NULL
                        WHERE failover_id = ? AND status = 'APPLYING'
                        """)) {
                    statement.setString(1, failoverId);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Не удалось завершить failover " + failoverId);
                    }
                }
                connection.commit();
                return findDimensionFailover(config, failoverId);
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static void failDimensionFailover(
            ClusterConfig config,
            String failoverId,
            String errorText
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE cluster_dimension_failovers
                     SET status = 'FAILED', error_text = ?, updated_at = CURRENT_TIMESTAMP(3)
                     WHERE failover_id = ? AND status IN ('READY', 'APPLYING')
                     """)) {
            statement.setString(1, truncate(errorText, 8000));
            statement.setString(2, failoverId);
            statement.executeUpdate();
        }
    }

    public static DimensionFailover findDimensionFailover(
            ClusterConfig config,
            String failoverId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            return findDimensionFailover(connection, failoverId, false);
        }
    }

    public static List<DimensionFailover> listDimensionFailovers(
            ClusterConfig config,
            int limit
    ) throws SQLException {
        ensureSchema(config);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        try (Connection connection = open(config);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT failover_id, dimension_id, source_node, target_node,
                            snapshot_id, status, error_text, created_at, updated_at,
                            applying_at, applied_at
                     FROM cluster_dimension_failovers
                     ORDER BY created_at DESC
                     LIMIT ?
                     """)) {
            statement.setInt(1, safeLimit);
            List<DimensionFailover> failovers = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    failovers.add(readDimensionFailover(resultSet));
                }
            }
            return List.copyOf(failovers);
        }
    }

    public static void failDimensionMigration(
            ClusterConfig config,
            String migrationId,
            String errorText
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            String sql = """
                    UPDATE cluster_dimension_migrations
                    SET
                        status = 'FAILED',
                        error_text = ?,
                        updated_at = CURRENT_TIMESTAMP(3)
                    WHERE migration_id = ?
                      AND status IN ('PREPARING', 'READY', 'APPLYING')
                    """;

            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {
                statement.setString(1, truncate(errorText, 4000));
                statement.setString(2, migrationId);
                statement.executeUpdate();
            }
        }
    }

    public static DimensionMigration cancelDimensionMigration(
            ClusterConfig config,
            String migrationId
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                DimensionMigration migration =
                        findDimensionMigration(connection, migrationId, true);

                if (migration == null) {
                    throw new SQLException("Migration не найден: " + migrationId);
                }

                if (!migration.sourceNode().equalsIgnoreCase(config.nodeId())) {
                    throw new SQLException(
                            "Migration можно отменить только на source node "
                                    + migration.sourceNode()
                    );
                }

                if (!migration.status().equals("PREPARING")
                        && !migration.status().equals("READY")
                        && !migration.status().equals("FAILED")) {
                    throw new SQLException(
                            "Migration со status="
                                    + migration.status()
                                    + " нельзя отменить"
                    );
                }

                String sql = """
                        UPDATE cluster_dimension_migrations
                        SET
                            status = 'CANCELLED',
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE migration_id = ?
                          AND status IN ('PREPARING', 'READY', 'FAILED')
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(sql)) {
                    statement.setString(1, migrationId);
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException(
                                "Не удалось отменить migration " + migrationId
                        );
                    }
                }

                connection.commit();
                return findDimensionMigration(config, migrationId);
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static DimensionMigration findDimensionMigration(
            ClusterConfig config,
            String migrationId
    ) throws SQLException {
        ensureSchema(config);
        try (Connection connection = open(config)) {
            return findDimensionMigration(connection, migrationId, false);
        }
    }

    public static List<DimensionMigration> listDimensionMigrations(
            ClusterConfig config,
            int limit
    ) throws SQLException {
        ensureSchema(config);
        int safeLimit = Math.max(1, Math.min(limit, 100));

        try (Connection connection = open(config)) {
            String sql = """
                    SELECT
                        migration_id,
                        dimension_id,
                        source_node,
                        target_node,
                        status,
                        archive_name,
                        archive_sha256,
                        content_sha256,
                        archive_size,
                        error_text,
                        created_at,
                        updated_at,
                        ready_at,
                        applying_at,
                        applied_at,
                        verified_at,
                        finalize_ready_at,
                        finalized_at,
                        rollback_previous_status,
                        rollback_archive_name,
                        rollback_archive_sha256,
                        rollback_content_sha256,
                        rollback_archive_size,
                        rollback_ready_at,
                        rollback_applying_at,
                        rolled_back_at,
                        source_backup_deleted_at
                    FROM cluster_dimension_migrations
                    ORDER BY created_at DESC
                    LIMIT ?
                    """;

            List<DimensionMigration> migrations = new ArrayList<>();
            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {
                statement.setInt(1, safeLimit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        migrations.add(readDimensionMigration(resultSet));
                    }
                }
            }
            return List.copyOf(migrations);
        }
    }

    public static Set<String> listFrozenMigrationDimensions(
            ClusterConfig config
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            refreshDimensionFailbackStates(connection);
            String sql = """
                    SELECT migrations.dimension_id
                    FROM cluster_dimension_migrations AS migrations
                    INNER JOIN dimension_assignments AS assignments
                        ON assignments.dimension_id = migrations.dimension_id
                    WHERE (
                            migrations.source_node = ?
                        AND (
                               migrations.status IN ('PREPARING', 'READY', 'APPLYING')
                            OR (
                                   migrations.status IN ('APPLIED', 'VERIFIED', 'FINALIZE_READY', 'FINALIZED', 'ROLLBACK_PREPARING', 'ROLLBACK_READY', 'ROLLBACK_APPLYING')
                               AND assignments.node_id <> ?
                            )
                        )
                    ) OR (
                            migrations.target_node = ?
                        AND (
                               migrations.status IN ('ROLLBACK_PREPARING', 'ROLLBACK_READY', 'ROLLBACK_APPLYING')
                            OR (migrations.status = 'ROLLED_BACK' AND assignments.node_id <> ?)
                        )
                    )
                    ORDER BY migrations.dimension_id
                    """;

            Set<String> dimensions = new TreeSet<>();
            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {
                statement.setString(1, config.nodeId());
                statement.setString(2, config.nodeId());
                statement.setString(3, config.nodeId());
                statement.setString(4, config.nodeId());
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        dimensions.add(resultSet.getString("dimension_id"));
                    }
                }
            }
            return Set.copyOf(dimensions);
        }
    }

    public static Set<String> listActiveMigrationDimensions(
            ClusterConfig config
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            refreshDimensionFailbackStates(connection);
            String sql = """
                    SELECT DISTINCT dimension_id
                    FROM cluster_dimension_migrations
                    WHERE status IN ('PREPARING', 'READY', 'APPLYING', 'ROLLBACK_PREPARING', 'ROLLBACK_READY', 'ROLLBACK_APPLYING')
                    ORDER BY dimension_id
                    """;

            Set<String> dimensions = new TreeSet<>();
            try (PreparedStatement statement =
                         connection.prepareStatement(sql);
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    dimensions.add(resultSet.getString("dimension_id"));
                }
            }
            return Set.copyOf(dimensions);
        }
    }

    public static CreatedTransfer createTransfer(
            ClusterConfig config,
            UUID playerUuid,
            String targetNode,
            String dimensionId,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            ClusterPlayerDataCodec.Snapshot playerData
    ) throws SQLException {
        if (targetNode == null || targetNode.isBlank()) {
            throw new SQLException("Target node is empty");
        }

        if (targetNode.equalsIgnoreCase(config.nodeId())) {
            throw new SQLException(
                    "Игрок уже находится на узле " + config.nodeId()
            );
        }

        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                expireTransfers(connection);
                recoverStaleClaims(connection, config);
                cleanupExpiredBackups(connection, config);

                String redirectAddress =
                        findOnlineNodeRedirectAddress(
                                connection,
                                targetNode,
                                config.nodeTimeoutSeconds()
                        );

                if (redirectAddress == null) {
                    if (findNodeRedirectAddress(
                            connection,
                            targetNode
                    ) == null) {
                        throw new SQLException(
                                "Узел " + targetNode
                                        + " не найден в cluster_nodes"
                        );
                    }

                    throw new SQLException(
                            "Узел " + targetNode
                                    + " сейчас OFFLINE: heartbeat старше "
                                    + config.nodeTimeoutSeconds()
                                    + " секунд или узел штатно остановлен"
                    );
                }

                if (hasBlockingDimensionMigration(connection, dimensionId)) {
                    throw new SQLException(
                            "Dimension " + dimensionId
                                    + " временно недоступен: выполняется migration"
                    );
                }

                cancelReadyTransfers(
                        connection,
                        config,
                        playerUuid
                );

                ensureSourceSessionForTransfer(
                        connection,
                        config,
                        playerUuid
                );

                String transferId = UUID.randomUUID().toString();

                String sql = """
                        INSERT INTO pending_transfers (
                            transfer_id,
                            player_uuid,
                            source_node,
                            target_node,
                            dimension_id,
                            x,
                            y,
                            z,
                            yaw,
                            pitch,
                            player_data,
                            player_data_sha256,
                            player_data_codec,
                            player_data_size,
                            status,
                            created_at,
                            expires_at
                        )
                        VALUES (
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?,
                            'READY',
                            CURRENT_TIMESTAMP(3),
                            TIMESTAMPADD(
                                SECOND,
                                ?,
                                CURRENT_TIMESTAMP(3)
                            )
                        )
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(sql)) {

                    statement.setString(1, transferId);
                    statement.setString(2, playerUuid.toString());
                    statement.setString(3, config.nodeId());
                    statement.setString(4, targetNode);
                    statement.setString(5, dimensionId);
                    statement.setDouble(6, x);
                    statement.setDouble(7, y);
                    statement.setDouble(8, z);
                    statement.setFloat(9, yaw);
                    statement.setFloat(10, pitch);

                    bindPlayerData(statement, 11, playerData);

                    statement.setInt(
                            15,
                            config.transferTtlSeconds()
                    );
                    statement.executeUpdate();
                }

                if (playerData != null) {
                    insertPlayerDataBackup(
                            connection,
                            config,
                            playerUuid,
                            transferId,
                            playerData
                    );
                }

                markSessionTransferring(
                        connection,
                        config,
                        playerUuid,
                        transferId,
                        targetNode
                );

                connection.commit();

                return new CreatedTransfer(
                        transferId,
                        playerUuid,
                        config.nodeId(),
                        targetNode,
                        redirectAddress,
                        dimensionId,
                        x,
                        y,
                        z,
                        yaw,
                        pitch,
                        playerData == null
                                ? 0
                                : playerData.compressedSize(),
                        playerData == null
                                ? null
                                : playerData.sha256()
                );
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static PendingTransfer claimPendingTransfer(
            ClusterConfig config,
            UUID playerUuid
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                expireTransfers(connection);
                recoverStaleClaims(connection, config);

                PendingTransfer transfer = null;

                String selectSql = """
                        SELECT
                            transfer_id,
                            player_uuid,
                            source_node,
                            target_node,
                            dimension_id,
                            x,
                            y,
                            z,
                            yaw,
                            pitch,
                            player_data,
                            player_data_sha256,
                            player_data_codec,
                            player_data_size,
                            created_at,
                            expires_at
                        FROM pending_transfers
                        WHERE player_uuid = ?
                          AND target_node = ?
                          AND status = 'READY'
                          AND expires_at > CURRENT_TIMESTAMP(3)
                        ORDER BY created_at DESC
                        LIMIT 1
                        FOR UPDATE
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(selectSql)) {

                    statement.setString(1, playerUuid.toString());
                    statement.setString(2, config.nodeId());

                    try (ResultSet resultSet =
                                 statement.executeQuery()) {

                        if (resultSet.next()) {
                            transfer = readPendingTransfer(resultSet);
                        }
                    }
                }

                if (transfer == null) {
                    connection.commit();
                    return null;
                }

                claimSessionForTransfer(
                        connection,
                        config,
                        transfer
                );

                String claimSql = """
                        UPDATE pending_transfers
                        SET
                            status = 'CLAIMED',
                            claimed_at = CURRENT_TIMESTAMP(3)
                        WHERE transfer_id = ?
                          AND status = 'READY'
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(claimSql)) {

                    statement.setString(
                            1,
                            transfer.transferId()
                    );

                    int updated = statement.executeUpdate();

                    if (updated != 1) {
                        throw new SQLException(
                                "Не удалось захватить transfer "
                                        + transfer.transferId()
                        );
                    }
                }

                connection.commit();
                return transfer;
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static PlayerSessionAcquireResult acquirePlayerSession(
            ClusterConfig config,
            UUID playerUuid
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                expireTransfers(connection);
                recoverStaleClaims(connection, config);

                PlayerSessionRow session = selectPlayerSessionForUpdate(
                        connection,
                        config,
                        playerUuid
                );

                if (session == null) {
                    insertOnlinePlayerSession(
                            connection,
                            config,
                            playerUuid
                    );

                    connection.commit();
                    return PlayerSessionAcquireResult.acquired(
                            config.nodeId()
                    );
                }

                boolean ownedByCurrentNode =
                        session.ownerNode().equalsIgnoreCase(
                                config.nodeId()
                        );

                if (ownedByCurrentNode
                        && "ONLINE".equals(session.state())) {
                    activateOnlinePlayerSession(
                            connection,
                            config,
                            playerUuid
                    );

                    connection.commit();
                    return PlayerSessionAcquireResult.acquired(
                            config.nodeId()
                    );
                }

                if (!session.leaseActive()
                        || !session.ownerNodeOnline()) {
                    activateOnlinePlayerSession(
                            connection,
                            config,
                            playerUuid
                    );

                    connection.commit();
                    return PlayerSessionAcquireResult.recovered(
                            config.nodeId(),
                            session.ownerNode(),
                            session.state()
                    );
                }

                connection.commit();
                return PlayerSessionAcquireResult.denied(
                        session.ownerNode(),
                        session.state(),
                        session.transferId(),
                        session.targetNode(),
                        session.leaseExpiresAt()
                );
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static void releasePlayerSession(
            ClusterConfig config,
            UUID playerUuid
    ) throws SQLException {
        ensureSchema(config);

        String sql = """
                DELETE FROM cluster_player_sessions
                WHERE player_uuid = ?
                  AND owner_node = ?
                  AND state = 'ONLINE'
                """;

        try (Connection connection = open(config);
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, playerUuid.toString());
            statement.setString(2, config.nodeId());
            statement.executeUpdate();
        }
    }

    public static RecoveryBackup findRecoveryBackup(
            ClusterConfig config,
            UUID playerUuid
    ) throws SQLException {
        ensureSchema(config);

        String sql = """
                SELECT
                    backups.backup_id,
                    backups.player_uuid,
                    backups.transfer_id,
                    backups.source_node,
                    backups.player_data,
                    backups.player_data_sha256,
                    backups.player_data_codec,
                    backups.player_data_size,
                    backups.created_at,
                    backups.expires_at
                FROM cluster_player_backups AS backups
                INNER JOIN pending_transfers AS transfers
                    ON transfers.transfer_id = backups.transfer_id
                WHERE backups.player_uuid = ?
                  AND backups.restored_at IS NULL
                  AND backups.expires_at > CURRENT_TIMESTAMP(3)
                  AND transfers.status IN ('FAILED', 'EXPIRED')
                ORDER BY backups.created_at DESC
                LIMIT 1
                """;

        try (Connection connection = open(config);
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return new RecoveryBackup(
                        resultSet.getLong("backup_id"),
                        UUID.fromString(
                                resultSet.getString("player_uuid")
                        ),
                        resultSet.getString("transfer_id"),
                        resultSet.getString("source_node"),
                        resultSet.getBytes("player_data"),
                        resultSet.getString("player_data_sha256"),
                        resultSet.getInt("player_data_codec"),
                        resultSet.getInt("player_data_size"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getTimestamp("expires_at").toInstant()
                );
            }
        }
    }

    public static void markRecoveryBackupRestored(
            ClusterConfig config,
            long backupId,
            UUID playerUuid
    ) throws SQLException {
        ensureSchema(config);

        String sql = """
                UPDATE cluster_player_backups
                SET
                    restored_at = CURRENT_TIMESTAMP(3),
                    restore_node = ?
                WHERE backup_id = ?
                  AND player_uuid = ?
                  AND restored_at IS NULL
                """;

        try (Connection connection = open(config);
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setString(1, config.nodeId());
            statement.setLong(2, backupId);
            statement.setString(3, playerUuid.toString());

            int updated = statement.executeUpdate();

            if (updated != 1) {
                throw new SQLException(
                        "Не удалось отметить backup "
                                + backupId
                                + " как восстановленный"
                );
            }
        }
    }

    public static void cancelReadyTransfer(
            ClusterConfig config,
            String transferId,
            UUID playerUuid
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                String transferSql = """
                        UPDATE pending_transfers
                        SET
                            status = 'CANCELLED',
                            claimed_at = NULL,
                            player_data = NULL
                        WHERE transfer_id = ?
                          AND player_uuid = ?
                          AND source_node = ?
                          AND status = 'READY'
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(transferSql)) {
                    statement.setString(1, transferId);
                    statement.setString(2, playerUuid.toString());
                    statement.setString(3, config.nodeId());
                    statement.executeUpdate();
                }

                String sessionSql = """
                        UPDATE cluster_player_sessions
                        SET
                            state = 'ONLINE',
                            transfer_id = NULL,
                            target_node = NULL,
                            lease_expires_at = TIMESTAMPADD(
                                SECOND,
                                ?,
                                CURRENT_TIMESTAMP(3)
                            ),
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE player_uuid = ?
                          AND owner_node = ?
                          AND transfer_id = ?
                          AND state = 'TRANSFERRING'
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(sessionSql)) {
                    statement.setInt(
                            1,
                            config.playerSessionLeaseSeconds()
                    );
                    statement.setString(2, playerUuid.toString());
                    statement.setString(3, config.nodeId());
                    statement.setString(4, transferId);
                    statement.executeUpdate();
                }

                connection.commit();
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static void markConsumed(
            ClusterConfig config,
            String transferId,
            UUID playerUuid
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                String transferSql = """
                        UPDATE pending_transfers
                        SET
                            status = 'CONSUMED',
                            claimed_at = NULL,
                            player_data = NULL,
                            applied_at = CURRENT_TIMESTAMP(3)
                        WHERE transfer_id = ?
                          AND player_uuid = ?
                          AND target_node = ?
                          AND status = 'CLAIMED'
                        """;

                int updated;

                try (PreparedStatement statement =
                             connection.prepareStatement(transferSql)) {
                    statement.setString(1, transferId);
                    statement.setString(2, playerUuid.toString());
                    statement.setString(3, config.nodeId());
                    updated = statement.executeUpdate();
                }

                if (updated != 1
                        && !isTransferAlreadyConsumed(
                        connection,
                        transferId,
                        playerUuid,
                        config.nodeId()
                )) {
                    throw new SQLException(
                            "Не удалось завершить transfer " + transferId
                    );
                }

                activateOnlinePlayerSessionAfterTransfer(
                        connection,
                        config,
                        playerUuid,
                        transferId
                );

                connection.commit();
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    public static void markFailed(
            ClusterConfig config,
            String transferId,
            UUID playerUuid
    ) throws SQLException {
        ensureSchema(config);

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                String transferSql = """
                        UPDATE pending_transfers
                        SET
                            status = 'FAILED',
                            claimed_at = NULL,
                            player_data = NULL
                        WHERE transfer_id = ?
                          AND player_uuid = ?
                          AND status = 'CLAIMED'
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(transferSql)) {
                    statement.setString(1, transferId);
                    statement.setString(2, playerUuid.toString());
                    statement.executeUpdate();
                }

                String sessionSql = """
                        DELETE FROM cluster_player_sessions
                        WHERE player_uuid = ?
                          AND transfer_id = ?
                        """;

                try (PreparedStatement statement =
                             connection.prepareStatement(sessionSql)) {
                    statement.setString(1, playerUuid.toString());
                    statement.setString(2, transferId);
                    statement.executeUpdate();
                }

                connection.commit();
            } catch (SQLException exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }


    private static void bindPlayerData(
            PreparedStatement statement,
            int firstParameter,
            ClusterPlayerDataCodec.Snapshot playerData
    ) throws SQLException {
        if (playerData == null) {
            statement.setNull(firstParameter, Types.LONGVARBINARY);
            statement.setNull(firstParameter + 1, Types.CHAR);
            statement.setInt(firstParameter + 2, 0);
            statement.setInt(firstParameter + 3, 0);
            return;
        }

        statement.setBytes(
                firstParameter,
                playerData.compressedNbt()
        );
        statement.setString(
                firstParameter + 1,
                playerData.sha256()
        );
        statement.setInt(
                firstParameter + 2,
                playerData.codecVersion()
        );
        statement.setInt(
                firstParameter + 3,
                playerData.compressedSize()
        );
    }

    private static void insertPlayerDataBackup(
            Connection connection,
            ClusterConfig config,
            UUID playerUuid,
            String transferId,
            ClusterPlayerDataCodec.Snapshot playerData
    ) throws SQLException {
        String sql = """
                INSERT IGNORE INTO cluster_player_backups (
                    player_uuid,
                    transfer_id,
                    source_node,
                    player_data,
                    player_data_sha256,
                    player_data_codec,
                    player_data_size,
                    created_at,
                    expires_at
                )
                VALUES (
                    ?, ?, ?, ?, ?, ?, ?,
                    CURRENT_TIMESTAMP(3),
                    TIMESTAMPADD(
                        DAY,
                        ?,
                        CURRENT_TIMESTAMP(3)
                    )
                )
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, transferId);
            statement.setString(3, config.nodeId());
            statement.setBytes(4, playerData.compressedNbt());
            statement.setString(5, playerData.sha256());
            statement.setInt(6, playerData.codecVersion());
            statement.setInt(7, playerData.compressedSize());
            statement.setInt(8, config.playerBackupRetentionDays());
            statement.executeUpdate();
        }
    }

    private static void cleanupExpiredBackups(
            Connection connection,
            ClusterConfig config
    ) throws SQLException {
        String sql = """
                DELETE FROM cluster_player_backups
                WHERE expires_at <= CURRENT_TIMESTAMP(3)
                   OR created_at <= TIMESTAMPADD(
                        DAY,
                        -?,
                        CURRENT_TIMESTAMP(3)
                   )
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setInt(1, config.playerBackupRetentionDays());
            statement.executeUpdate();
        }
    }

    private static void refreshPlayerSessionLeases(
            Connection connection,
            ClusterConfig config,
            MinecraftServer server
    ) throws SQLException {
        String sql = """
                UPDATE cluster_player_sessions
                SET
                    lease_expires_at = TIMESTAMPADD(
                        SECOND,
                        ?,
                        CURRENT_TIMESTAMP(3)
                    ),
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE player_uuid = ?
                  AND owner_node = ?
                  AND state IN ('ONLINE', 'CLAIMING')
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            for (var player : server.getPlayerList().getPlayers()) {
                statement.setInt(
                        1,
                        config.playerSessionLeaseSeconds()
                );
                statement.setString(
                        2,
                        player.getUUID().toString()
                );
                statement.setString(3, config.nodeId());
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private static void ensureSourceSessionForTransfer(
            Connection connection,
            ClusterConfig config,
            UUID playerUuid
    ) throws SQLException {
        PlayerSessionRow session = selectPlayerSessionForUpdate(
                connection,
                config,
                playerUuid
        );

        if (session == null) {
            insertOnlinePlayerSession(
                    connection,
                    config,
                    playerUuid
            );
            return;
        }

        boolean ownedByCurrentNode =
                session.ownerNode().equalsIgnoreCase(
                        config.nodeId()
                );

        if (ownedByCurrentNode
                && "ONLINE".equals(session.state())) {
            return;
        }

        if (!session.leaseActive()
                || !session.ownerNodeOnline()) {
            activateOnlinePlayerSession(
                    connection,
                    config,
                    playerUuid
            );
            return;
        }

        throw new SQLException(
                "Состояние игрока заблокировано узлом "
                        + session.ownerNode()
                        + " (state="
                        + session.state()
                        + ", transfer="
                        + session.transferId()
                        + ")"
        );
    }

    private static PlayerSessionRow selectPlayerSessionForUpdate(
            Connection connection,
            ClusterConfig config,
            UUID playerUuid
    ) throws SQLException {
        String sql = """
                SELECT
                    sessions.owner_node,
                    sessions.state,
                    sessions.transfer_id,
                    sessions.target_node,
                    sessions.lease_expires_at,
                    CASE
                        WHEN sessions.lease_expires_at
                             > CURRENT_TIMESTAMP(3)
                        THEN 1
                        ELSE 0
                    END AS lease_active,
                    CASE
                        WHEN nodes.node_id IS NOT NULL
                         AND nodes.stopped_at IS NULL
                         AND nodes.last_seen >= TIMESTAMPADD(
                            SECOND,
                            -?,
                            CURRENT_TIMESTAMP(3)
                         )
                        THEN 1
                        ELSE 0
                    END AS owner_online
                FROM cluster_player_sessions AS sessions
                LEFT JOIN cluster_nodes AS nodes
                    ON nodes.node_id = sessions.owner_node
                WHERE sessions.player_uuid = ?
                FOR UPDATE
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setInt(1, config.nodeTimeoutSeconds());
            statement.setString(2, playerUuid.toString());

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return new PlayerSessionRow(
                        resultSet.getString("owner_node"),
                        resultSet.getString("state"),
                        resultSet.getString("transfer_id"),
                        resultSet.getString("target_node"),
                        resultSet.getTimestamp(
                                "lease_expires_at"
                        ).toInstant(),
                        resultSet.getBoolean("lease_active"),
                        resultSet.getBoolean("owner_online")
                );
            }
        }
    }

    private static void insertOnlinePlayerSession(
            Connection connection,
            ClusterConfig config,
            UUID playerUuid
    ) throws SQLException {
        String sql = """
                INSERT INTO cluster_player_sessions (
                    player_uuid,
                    owner_node,
                    state,
                    transfer_id,
                    target_node,
                    lease_expires_at,
                    updated_at
                )
                VALUES (
                    ?, ?, 'ONLINE', NULL, NULL,
                    TIMESTAMPADD(
                        SECOND,
                        ?,
                        CURRENT_TIMESTAMP(3)
                    ),
                    CURRENT_TIMESTAMP(3)
                )
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, config.nodeId());
            statement.setInt(
                    3,
                    config.playerSessionLeaseSeconds()
            );
            statement.executeUpdate();
        }
    }

    private static void activateOnlinePlayerSession(
            Connection connection,
            ClusterConfig config,
            UUID playerUuid
    ) throws SQLException {
        String sql = """
                INSERT INTO cluster_player_sessions (
                    player_uuid,
                    owner_node,
                    state,
                    transfer_id,
                    target_node,
                    lease_expires_at,
                    updated_at
                )
                VALUES (
                    ?, ?, 'ONLINE', NULL, NULL,
                    TIMESTAMPADD(
                        SECOND,
                        ?,
                        CURRENT_TIMESTAMP(3)
                    ),
                    CURRENT_TIMESTAMP(3)
                )
                ON DUPLICATE KEY UPDATE
                    owner_node = VALUES(owner_node),
                    state = 'ONLINE',
                    transfer_id = NULL,
                    target_node = NULL,
                    lease_expires_at = VALUES(lease_expires_at),
                    updated_at = CURRENT_TIMESTAMP(3)
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, config.nodeId());
            statement.setInt(
                    3,
                    config.playerSessionLeaseSeconds()
            );
            statement.executeUpdate();
        }
    }

    private static void markSessionTransferring(
            Connection connection,
            ClusterConfig config,
            UUID playerUuid,
            String transferId,
            String targetNode
    ) throws SQLException {
        String sql = """
                UPDATE cluster_player_sessions
                SET
                    state = 'TRANSFERRING',
                    transfer_id = ?,
                    target_node = ?,
                    lease_expires_at = TIMESTAMPADD(
                        SECOND,
                        ?,
                        CURRENT_TIMESTAMP(3)
                    ),
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE player_uuid = ?
                  AND owner_node = ?
                  AND state = 'ONLINE'
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setString(1, transferId);
            statement.setString(2, targetNode);
            statement.setInt(3, config.transferTtlSeconds());
            statement.setString(4, playerUuid.toString());
            statement.setString(5, config.nodeId());

            if (statement.executeUpdate() != 1) {
                throw new SQLException(
                        "Не удалось заблокировать сессию игрока для transfer "
                                + transferId
                );
            }
        }
    }

    private static void claimSessionForTransfer(
            Connection connection,
            ClusterConfig config,
            PendingTransfer transfer
    ) throws SQLException {
        PlayerSessionRow session = selectPlayerSessionForUpdate(
                connection,
                config,
                transfer.playerUuid()
        );

        boolean matchingTransfer = session != null
                && transfer.transferId().equals(
                session.transferId()
        )
                && transfer.targetNode().equalsIgnoreCase(
                config.nodeId()
        )
                && ("TRANSFERRING".equals(session.state())
                || "CLAIMING".equals(session.state()));

        if (session != null
                && !matchingTransfer
                && session.leaseActive()
                && session.ownerNodeOnline()) {
            throw new SQLException(
                    "Игрок уже активен на узле "
                            + session.ownerNode()
                            + " (state="
                            + session.state()
                            + ")"
            );
        }

        String sql = """
                INSERT INTO cluster_player_sessions (
                    player_uuid,
                    owner_node,
                    state,
                    transfer_id,
                    target_node,
                    lease_expires_at,
                    updated_at
                )
                VALUES (
                    ?, ?, 'CLAIMING', ?, ?,
                    TIMESTAMPADD(
                        SECOND,
                        ?,
                        CURRENT_TIMESTAMP(3)
                    ),
                    CURRENT_TIMESTAMP(3)
                )
                ON DUPLICATE KEY UPDATE
                    owner_node = VALUES(owner_node),
                    state = 'CLAIMING',
                    transfer_id = VALUES(transfer_id),
                    target_node = VALUES(target_node),
                    lease_expires_at = VALUES(lease_expires_at),
                    updated_at = CURRENT_TIMESTAMP(3)
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setString(
                    1,
                    transfer.playerUuid().toString()
            );
            statement.setString(2, config.nodeId());
            statement.setString(3, transfer.transferId());
            statement.setString(4, transfer.targetNode());
            statement.setInt(
                    5,
                    config.playerSessionLeaseSeconds()
            );
            statement.executeUpdate();
        }
    }

    private static void activateOnlinePlayerSessionAfterTransfer(
            Connection connection,
            ClusterConfig config,
            UUID playerUuid,
            String transferId
    ) throws SQLException {
        String sql = """
                UPDATE cluster_player_sessions
                SET
                    state = 'ONLINE',
                    transfer_id = NULL,
                    target_node = NULL,
                    lease_expires_at = TIMESTAMPADD(
                        SECOND,
                        ?,
                        CURRENT_TIMESTAMP(3)
                    ),
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE player_uuid = ?
                  AND owner_node = ?
                  AND transfer_id = ?
                  AND state = 'CLAIMING'
                """;

        int updated;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setInt(
                    1,
                    config.playerSessionLeaseSeconds()
            );
            statement.setString(2, playerUuid.toString());
            statement.setString(3, config.nodeId());
            statement.setString(4, transferId);
            updated = statement.executeUpdate();
        }

        if (updated == 1) {
            return;
        }

        PlayerSessionRow session = selectPlayerSessionForUpdate(
                connection,
                config,
                playerUuid
        );

        if (session != null
                && session.ownerNode().equalsIgnoreCase(
                config.nodeId()
        )
                && "ONLINE".equals(session.state())) {
            return;
        }

        throw new SQLException(
                "Не удалось активировать сессию после transfer "
                        + transferId
        );
    }

    private static boolean isTransferAlreadyConsumed(
            Connection connection,
            String transferId,
            UUID playerUuid,
            String targetNode
    ) throws SQLException {
        String sql = """
                SELECT 1
                FROM pending_transfers
                WHERE transfer_id = ?
                  AND player_uuid = ?
                  AND target_node = ?
                  AND status = 'CONSUMED'
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setString(1, transferId);
            statement.setString(2, playerUuid.toString());
            statement.setString(3, targetNode);

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }



    private static Set<String> loadActiveMigrationDimensions(
            Connection connection
    ) throws SQLException {
        String sql = """
                SELECT dimension_id
                FROM cluster_dimension_migrations
                WHERE status IN ('PREPARING', 'READY', 'APPLYING', 'APPLIED', 'VERIFIED', 'FINALIZE_READY', 'ROLLBACK_PREPARING', 'ROLLBACK_READY', 'ROLLBACK_APPLYING')
                ORDER BY dimension_id
                """;

        Set<String> dimensions = new TreeSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                dimensions.add(resultSet.getString("dimension_id"));
            }
        }
        return Set.copyOf(dimensions);
    }

    private static DimensionMigration findDimensionMigration(
            Connection connection,
            String migrationId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT
                    migration_id,
                    dimension_id,
                    source_node,
                    target_node,
                    status,
                    archive_name,
                    archive_sha256,
                    content_sha256,
                    archive_size,
                    error_text,
                    created_at,
                    updated_at,
                    ready_at,
                    applying_at,
                    applied_at,
                    verified_at,
                    finalize_ready_at,
                    finalized_at,
                    rollback_previous_status,
                    rollback_archive_name,
                    rollback_archive_sha256,
                    rollback_content_sha256,
                    rollback_archive_size,
                    rollback_ready_at,
                    rollback_applying_at,
                    rolled_back_at,
                    source_backup_deleted_at
                FROM cluster_dimension_migrations
                WHERE migration_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setString(1, migrationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return readDimensionMigration(resultSet);
            }
        }
    }

    private static DimensionMigration findActiveDimensionMigration(
            Connection connection,
            String dimensionId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT
                    migration_id,
                    dimension_id,
                    source_node,
                    target_node,
                    status,
                    archive_name,
                    archive_sha256,
                    content_sha256,
                    archive_size,
                    error_text,
                    created_at,
                    updated_at,
                    ready_at,
                    applying_at,
                    applied_at,
                    verified_at,
                    finalize_ready_at,
                    finalized_at,
                    rollback_previous_status,
                    rollback_archive_name,
                    rollback_archive_sha256,
                    rollback_content_sha256,
                    rollback_archive_size,
                    rollback_ready_at,
                    rollback_applying_at,
                    rolled_back_at,
                    source_backup_deleted_at
                FROM cluster_dimension_migrations
                WHERE dimension_id = ?
                  AND status IN ('PREPARING', 'READY', 'APPLYING', 'APPLIED', 'VERIFIED', 'FINALIZE_READY', 'ROLLBACK_PREPARING', 'ROLLBACK_READY', 'ROLLBACK_APPLYING')
                ORDER BY created_at DESC
                LIMIT 1
                """ + (forUpdate ? " FOR UPDATE" : "");

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setString(1, dimensionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return readDimensionMigration(resultSet);
            }
        }
    }

    private static boolean hasActiveDimensionMigration(
            Connection connection,
            String dimensionId
    ) throws SQLException {
        return findActiveDimensionMigration(
                connection,
                dimensionId,
                false
        ) != null;
    }

    private static boolean hasBlockingDimensionMigration(
            Connection connection,
            String dimensionId
    ) throws SQLException {
        String sql = """
                SELECT 1
                FROM cluster_dimension_migrations
                WHERE dimension_id = ?
                  AND status IN ('PREPARING', 'READY', 'APPLYING', 'ROLLBACK_PREPARING', 'ROLLBACK_READY', 'ROLLBACK_APPLYING')
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, dimensionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static boolean hasActiveDimensionPlayers(
            Connection connection,
            String dimensionId,
            int timeoutSeconds
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(player_count), 0)
                FROM cluster_dimension_activity
                WHERE dimension_id = ?
                  AND last_seen >= TIMESTAMPADD(SECOND, -?, CURRENT_TIMESTAMP(3))
                """)) {
            statement.setString(1, dimensionId);
            statement.setInt(2, Math.max(1, timeoutSeconds));
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    private static void failStalePreparingDimensionSnapshots(
            Connection connection,
            String sourceNode,
            int staleMinutes
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE cluster_dimension_snapshots
                SET status = 'FAILED',
                    error_text = 'stale PREPARING recovered',
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE source_node = ?
                  AND status = 'PREPARING'
                  AND updated_at < TIMESTAMPADD(MINUTE, -?, CURRENT_TIMESTAMP(3))
                """)) {
            statement.setString(1, sourceNode);
            statement.setInt(2, Math.max(1, staleMinutes));
            statement.executeUpdate();
        }
    }

    private static boolean hasPreparingDimensionSnapshot(
            Connection connection,
            String dimensionId,
            String sourceNode
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM cluster_dimension_snapshots
                WHERE dimension_id = ? AND source_node = ? AND status = 'PREPARING'
                LIMIT 1
                """)) {
            statement.setString(1, dimensionId);
            statement.setString(2, sourceNode);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static boolean isSnapshotFresh(
            DimensionSnapshot snapshot,
            int maxAgeMinutes
    ) {
        return snapshot != null
                && snapshot.readyAt() != null
                && !snapshot.readyAt().isBefore(
                        Instant.now().minusSeconds(Math.max(1, maxAgeMinutes) * 60L)
                );
    }

    private static boolean hasActiveDimensionFailover(
            Connection connection,
            String dimensionId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM cluster_dimension_failovers
                WHERE dimension_id = ? AND status IN ('READY', 'APPLYING')
                LIMIT 1
                """)) {
            statement.setString(1, dimensionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static DimensionSnapshot findDimensionSnapshot(
            Connection connection,
            String snapshotId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT snapshot_id, dimension_id, source_node, status,
                       archive_name, archive_sha256, content_sha256, archive_size,
                       error_text, created_at, updated_at, ready_at
                FROM cluster_dimension_snapshots
                WHERE snapshot_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, snapshotId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readDimensionSnapshot(resultSet) : null;
            }
        }
    }

    private static DimensionSnapshot findLatestReadyDimensionSnapshot(
            Connection connection,
            String dimensionId,
            String sourceNode
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT snapshot_id, dimension_id, source_node, status,
                       archive_name, archive_sha256, content_sha256, archive_size,
                       error_text, created_at, updated_at, ready_at
                FROM cluster_dimension_snapshots
                WHERE dimension_id = ? AND source_node = ? AND status = 'READY'
                ORDER BY ready_at DESC
                LIMIT 1
                """)) {
            statement.setString(1, dimensionId);
            statement.setString(2, sourceNode);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readDimensionSnapshot(resultSet) : null;
            }
        }
    }

    private static DimensionFailover findDimensionFailover(
            Connection connection,
            String failoverId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT failover_id, dimension_id, source_node, target_node,
                       snapshot_id, status, error_text, created_at, updated_at,
                       applying_at, applied_at
                FROM cluster_dimension_failovers
                WHERE failover_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, failoverId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readDimensionFailover(resultSet) : null;
            }
        }
    }

    private static List<DimensionFailover> listDimensionFailbackCandidates(
            Connection connection,
            String currentNode,
            String recoveredNode,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT failover_id, dimension_id, source_node, target_node,
                       snapshot_id, status, error_text, created_at, updated_at,
                       applying_at, applied_at
                FROM cluster_dimension_failovers AS failovers
                WHERE failovers.source_node = ?
                  AND failovers.target_node = ?
                  AND failovers.status = 'APPLIED'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM cluster_dimension_failbacks AS failbacks
                      WHERE failbacks.failover_id = failovers.failover_id
                        AND failbacks.status IN ('PREPARING', 'READY', 'APPLYING', 'APPLIED')
                  )
                ORDER BY failovers.applied_at, failovers.dimension_id
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, recoveredNode);
            statement.setString(2, currentNode);
            List<DimensionFailover> result = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(readDimensionFailover(resultSet));
                }
            }
            return List.copyOf(result);
        }
    }

    private static String validateDimensionFailbackCandidate(
            Connection connection,
            ClusterConfig config,
            DimensionFailover failover,
            boolean recoveredOnline,
            Map<String, DimensionActivity> activity
    ) throws SQLException {
        if (!recoveredOnline) {
            return "recovered node OFFLINE";
        }
        if ("minecraft:overworld".equals(failover.dimensionId())) {
            return "minecraft:overworld не поддерживается";
        }
        DimensionAssignmentRow assignment = findDimensionAssignmentRow(
                connection,
                failover.dimensionId()
        );
        if (assignment == null || !assignment.nodeId().equalsIgnoreCase(config.nodeId())) {
            return "текущий узел больше не владеет dimension";
        }
        DimensionActivity dimensionActivity = activity.get(failover.dimensionId());
        if (dimensionActivity != null && dimensionActivity.playerCount() > 0) {
            return "в dimension находятся игроки на узлах " + dimensionActivity.nodeIds();
        }
        if (hasActiveDimensionMigration(connection, failover.dimensionId())) {
            return "активна migration";
        }
        if (hasActiveDimensionFailover(connection, failover.dimensionId())) {
            return "активен failover";
        }
        return null;
    }

    private static String validateNodeDrainCandidate(
            Connection connection,
            String dimensionId,
            DimensionAssignmentRow assignment,
            boolean targetReady,
            Map<String, DimensionActivity> activity
    ) throws SQLException {
        if (!targetReady) {
            return "target node OFFLINE или находится в drain-режиме";
        }
        if ("minecraft:overworld".equals(dimensionId)) {
            return "minecraft:overworld не поддерживается файловой migration";
        }
        if (assignment.pinned()) {
            return "измерение PINNED";
        }
        DimensionActivity currentActivity = activity.get(dimensionId);
        if (currentActivity != null && currentActivity.playerCount() > 0) {
            return "в dimension находятся игроки на узлах " + currentActivity.nodeIds();
        }
        if (hasActiveDimensionMigration(connection, dimensionId)) {
            return "активна migration";
        }
        if (hasActiveDimensionFailover(connection, dimensionId)) {
            return "активен failover";
        }
        if (hasPreparingDimensionSnapshot(connection, dimensionId)) {
            return "создаётся snapshot";
        }
        return null;
    }

    private static boolean hasPreparingDimensionSnapshot(
            Connection connection,
            String dimensionId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM cluster_dimension_snapshots
                WHERE dimension_id = ?
                  AND status = 'PREPARING'
                LIMIT 1
                """)) {
            statement.setString(1, dimensionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static int readNodePlayerCount(
            Connection connection,
            String nodeId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_count
                FROM cluster_nodes
                WHERE node_id = ?
                """)) {
            statement.setString(1, nodeId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt("player_count") : 0;
            }
        }
    }

    private static boolean hasActiveNodeDrain(
            Connection connection,
            String nodeId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM cluster_node_drains
                WHERE operation_type = 'DRAIN'
                  AND source_node = ?
                  AND status IN ('PREPARING', 'READY', 'APPLYING', 'DRAINED', 'PARTIAL', 'FAILED')
                LIMIT 1
                """)) {
            statement.setString(1, nodeId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static boolean hasActiveNodeOperation(
            Connection connection,
            String nodeId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM cluster_node_drains
                WHERE (source_node = ? OR target_node = ?)
                  AND status IN ('PREPARING', 'READY', 'APPLYING', 'DRAINED', 'PARTIAL', 'FAILED')
                LIMIT 1
                """)) {
            statement.setString(1, nodeId);
            statement.setString(2, nodeId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static boolean hasOtherActiveNodeOperation(
            Connection connection,
            String nodeId,
            String excludedOperationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM cluster_node_drains
                WHERE (source_node = ? OR target_node = ?)
                  AND drain_id <> ?
                  AND status IN ('PREPARING', 'READY', 'APPLYING', 'DRAINED', 'PARTIAL', 'FAILED')
                LIMIT 1
                """)) {
            statement.setString(1, nodeId);
            statement.setString(2, nodeId);
            statement.setString(3, excludedOperationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static String validateNodeOperationRecoveryCandidate(
            Connection connection,
            DimensionDrainItem item,
            DimensionMigration migration,
            Set<String> locallyAvailableDimensions,
            Map<String, DimensionActivity> activity
    ) throws SQLException {
        String dimensionId = item.dimensionId();
        if (!locallyAvailableDimensions.contains(dimensionId)) {
            return "измерение не загружено, содержит игроков или папка недоступна";
        }
        if ("minecraft:overworld".equals(dimensionId)) {
            return "minecraft:overworld не поддерживается файловой migration";
        }
        DimensionAssignmentRow assignment = findDimensionAssignmentRow(
                connection,
                dimensionId
        );
        if (assignment == null
                || !assignment.nodeId().equalsIgnoreCase(item.sourceNode())) {
            return "source node больше не владеет измерением";
        }
        if (assignment.pinned()) {
            return "измерение PINNED";
        }
        DimensionActivity currentActivity = activity.get(dimensionId);
        if (currentActivity != null && currentActivity.playerCount() > 0) {
            return "в dimension находятся игроки на узлах " + currentActivity.nodeIds();
        }
        if (hasOtherActiveDimensionMigration(
                connection,
                dimensionId,
                migration.migrationId()
        )) {
            return "активна другая migration";
        }
        if (hasActiveDimensionFailover(connection, dimensionId)) {
            return "активен failover";
        }
        if (hasPreparingDimensionSnapshot(connection, dimensionId)) {
            return "создаётся snapshot";
        }
        return null;
    }

    private static boolean hasOtherActiveDimensionMigration(
            Connection connection,
            String dimensionId,
            String excludedMigrationId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM cluster_dimension_migrations
                WHERE dimension_id = ?
                  AND migration_id <> ?
                  AND status IN ('PREPARING', 'READY', 'APPLYING', 'APPLIED', 'VERIFIED', 'FINALIZE_READY', 'ROLLBACK_PREPARING', 'ROLLBACK_READY', 'ROLLBACK_APPLYING')
                LIMIT 1
                """)) {
            statement.setString(1, dimensionId);
            statement.setString(2, excludedMigrationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static void refreshNodeDrainStates(
            Connection connection
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE cluster_dimension_migrations AS migrations
                JOIN cluster_node_drain_items AS items
                  ON items.migration_id = migrations.migration_id
                SET migrations.status = 'CANCELLED',
                    migrations.error_text = 'Пропущено: измерение не загружено',
                    migrations.updated_at = CURRENT_TIMESTAMP(3),
                    items.status = 'CANCELLED',
                    items.error_text = 'Пропущено: измерение не загружено',
                    items.updated_at = CURRENT_TIMESTAMP(3)
                WHERE migrations.status = 'FAILED'
                  AND items.status = 'FAILED'
                  AND migrations.archive_name IS NULL
                  AND migrations.archive_size = 0
                  AND TRIM(COALESCE(migrations.error_text, '')) = 'Измерение не загружено'
                  AND TRIM(COALESCE(items.error_text, '')) = 'Измерение не загружено'
                """)) {
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE cluster_dimension_migrations AS migrations
                JOIN cluster_node_drain_items AS items
                  ON items.migration_id = migrations.migration_id
                JOIN dimension_assignments AS assignments
                  ON assignments.dimension_id = migrations.dimension_id
                 AND assignments.node_id = migrations.target_node
                SET migrations.status = 'COMPLETED',
                    migrations.error_text = NULL,
                    migrations.updated_at = CURRENT_TIMESTAMP(3)
                WHERE migrations.status = 'APPLIED'
                """)) {
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE cluster_node_drain_items AS items
                JOIN cluster_dimension_migrations AS migrations
                  ON migrations.migration_id = items.migration_id
                SET items.status = CASE
                        WHEN migrations.status IN ('APPLIED', 'COMPLETED', 'VERIFIED', 'FINALIZE_READY', 'FINALIZED') THEN 'APPLIED'
                        WHEN migrations.status = 'CANCELLED' THEN 'CANCELLED'
                        WHEN migrations.status = 'FAILED' THEN 'FAILED'
                        ELSE migrations.status
                    END,
                    items.error_text = migrations.error_text,
                    items.applied_at = CASE
                        WHEN migrations.status IN ('APPLIED', 'COMPLETED', 'VERIFIED', 'FINALIZE_READY', 'FINALIZED')
                        THEN COALESCE(items.applied_at, migrations.applied_at, CURRENT_TIMESTAMP(3))
                        ELSE items.applied_at
                    END,
                    items.updated_at = CURRENT_TIMESTAMP(3)
                WHERE items.status <> CASE
                        WHEN migrations.status IN ('APPLIED', 'COMPLETED', 'VERIFIED', 'FINALIZE_READY', 'FINALIZED') THEN 'APPLIED'
                        WHEN migrations.status = 'CANCELLED' THEN 'CANCELLED'
                        WHEN migrations.status = 'FAILED' THEN 'FAILED'
                        ELSE migrations.status
                    END
                   OR NOT (items.error_text <=> migrations.error_text)
                """)) {
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE cluster_node_drains AS drains
                JOIN (
                    SELECT
                        drain_id,
                        COUNT(*) AS total_count,
                        SUM(CASE WHEN status = 'PREPARING' THEN 1 ELSE 0 END) AS preparing_count,
                        SUM(CASE WHEN status = 'READY' THEN 1 ELSE 0 END) AS ready_count,
                        SUM(CASE WHEN status = 'APPLYING' THEN 1 ELSE 0 END) AS applying_count,
                        SUM(CASE WHEN status = 'APPLIED' THEN 1 ELSE 0 END) AS applied_count,
                        SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed_count,
                        SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled_count
                    FROM cluster_node_drain_items
                    GROUP BY drain_id
                ) AS stats ON stats.drain_id = drains.drain_id
                SET drains.status = CASE
                        WHEN drains.status IN ('CANCELLED', 'RESUMED') THEN drains.status
                        WHEN stats.applied_count = stats.total_count THEN CASE WHEN drains.operation_type = 'REBALANCE' THEN 'COMPLETED' ELSE 'DRAINED' END
                        WHEN stats.applying_count > 0 THEN 'APPLYING'
                        WHEN stats.preparing_count > 0 THEN 'PREPARING'
                        WHEN stats.ready_count > 0 THEN 'READY'
                        WHEN stats.applied_count > 0
                         AND stats.applied_count + stats.failed_count + stats.cancelled_count = stats.total_count
                        THEN 'PARTIAL'
                        WHEN stats.failed_count > 0
                         AND stats.failed_count + stats.cancelled_count = stats.total_count
                        THEN 'FAILED'
                        ELSE drains.status
                    END,
                    drains.completed_at = CASE
                        WHEN stats.applied_count = stats.total_count
                          OR stats.applied_count + stats.failed_count + stats.cancelled_count = stats.total_count
                        THEN COALESCE(drains.completed_at, CURRENT_TIMESTAMP(3))
                        ELSE drains.completed_at
                    END,
                    drains.updated_at = CURRENT_TIMESTAMP(3)
                WHERE drains.status NOT IN ('CANCELLED', 'RESUMED')
                  AND (
                      drains.status <> CASE
                          WHEN stats.applied_count = stats.total_count THEN CASE WHEN drains.operation_type = 'REBALANCE' THEN 'COMPLETED' ELSE 'DRAINED' END
                          WHEN stats.applying_count > 0 THEN 'APPLYING'
                          WHEN stats.preparing_count > 0 THEN 'PREPARING'
                          WHEN stats.ready_count > 0 THEN 'READY'
                          WHEN stats.applied_count > 0
                           AND stats.applied_count + stats.failed_count + stats.cancelled_count = stats.total_count
                          THEN 'PARTIAL'
                          WHEN stats.failed_count > 0
                           AND stats.failed_count + stats.cancelled_count = stats.total_count
                          THEN 'FAILED'
                          ELSE drains.status
                      END
                      OR (
                          drains.completed_at IS NULL
                          AND (
                              stats.applied_count = stats.total_count
                              OR stats.applied_count + stats.failed_count + stats.cancelled_count = stats.total_count
                          )
                      )
                  )
                """)) {
            statement.executeUpdate();
        }
    }

    private static String nodeDrainSelectSql() {
        return """
                SELECT
                    drains.drain_id,
                    drains.operation_type,
                    drains.source_node,
                    drains.target_node,
                    drains.status,
                    drains.error_text,
                    drains.created_at,
                    drains.updated_at,
                    drains.completed_at,
                    COUNT(items.drain_item_id) AS total_items,
                    SUM(CASE WHEN items.status = 'PREPARING' THEN 1 ELSE 0 END) AS preparing_items,
                    SUM(CASE WHEN items.status = 'READY' THEN 1 ELSE 0 END) AS ready_items,
                    SUM(CASE WHEN items.status = 'APPLYING' THEN 1 ELSE 0 END) AS applying_items,
                    SUM(CASE WHEN items.status = 'APPLIED' THEN 1 ELSE 0 END) AS applied_items,
                    SUM(CASE WHEN items.status = 'FAILED' THEN 1 ELSE 0 END) AS failed_items,
                    SUM(CASE WHEN items.status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled_items
                FROM cluster_node_drains AS drains
                LEFT JOIN cluster_node_drain_items AS items
                  ON items.drain_id = drains.drain_id
                GROUP BY
                    drains.drain_id,
                    drains.operation_type,
                    drains.source_node,
                    drains.target_node,
                    drains.status,
                    drains.error_text,
                    drains.created_at,
                    drains.updated_at,
                    drains.completed_at
                """;
    }

    private static NodeDrain findNodeDrain(
            Connection connection,
            String drainId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                nodeDrainSelectSql() + " HAVING drains.drain_id = ?"
        )) {
            statement.setString(1, drainId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readNodeDrain(resultSet) : null;
            }
        }
    }

    private static NodeDrain readNodeDrain(
            ResultSet resultSet
    ) throws SQLException {
        return new NodeDrain(
                resultSet.getString("drain_id"),
                resultSet.getString("operation_type"),
                resultSet.getString("source_node"),
                resultSet.getString("target_node"),
                resultSet.getString("status"),
                resultSet.getString("error_text"),
                resultSet.getInt("total_items"),
                resultSet.getInt("preparing_items"),
                resultSet.getInt("ready_items"),
                resultSet.getInt("applying_items"),
                resultSet.getInt("applied_items"),
                resultSet.getInt("failed_items"),
                resultSet.getInt("cancelled_items"),
                instantOrNull(resultSet, "created_at"),
                instantOrNull(resultSet, "updated_at"),
                instantOrNull(resultSet, "completed_at")
        );
    }

    private static DimensionDrainItem findDimensionDrainItem(
            Connection connection,
            String drainItemId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT drain_item_id, drain_id, migration_id, dimension_id,
                       source_node, target_node, status, error_text,
                       created_at, updated_at, applied_at
                FROM cluster_node_drain_items
                WHERE drain_item_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, drainItemId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readDimensionDrainItem(resultSet) : null;
            }
        }
    }

    private static DimensionDrainItem readDimensionDrainItem(
            ResultSet resultSet
    ) throws SQLException {
        return new DimensionDrainItem(
                resultSet.getString("drain_item_id"),
                resultSet.getString("drain_id"),
                resultSet.getString("migration_id"),
                resultSet.getString("dimension_id"),
                resultSet.getString("source_node"),
                resultSet.getString("target_node"),
                resultSet.getString("status"),
                resultSet.getString("error_text"),
                instantOrNull(resultSet, "created_at"),
                instantOrNull(resultSet, "updated_at"),
                instantOrNull(resultSet, "applied_at")
        );
    }

    private static void refreshDimensionFailbackStates(
            Connection connection
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE cluster_dimension_migrations AS migrations
                JOIN cluster_dimension_failbacks AS failbacks
                  ON failbacks.migration_id = migrations.migration_id
                JOIN dimension_assignments AS assignments
                  ON assignments.dimension_id = migrations.dimension_id
                 AND assignments.node_id = migrations.target_node
                SET migrations.status = 'COMPLETED',
                    migrations.error_text = NULL,
                    migrations.updated_at = CURRENT_TIMESTAMP(3)
                WHERE migrations.status = 'APPLIED'
                  AND failbacks.status IN ('READY', 'APPLYING', 'APPLIED')
                """)) {
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE cluster_dimension_failbacks AS failbacks
                JOIN cluster_dimension_migrations AS migrations
                  ON migrations.migration_id = failbacks.migration_id
                SET failbacks.status = CASE
                        WHEN migrations.status IN ('APPLIED', 'COMPLETED', 'VERIFIED', 'FINALIZE_READY', 'FINALIZED') THEN 'APPLIED'
                        WHEN migrations.status IN ('FAILED', 'CANCELLED') THEN 'FAILED'
                        ELSE migrations.status
                    END,
                    failbacks.error_text = migrations.error_text,
                    failbacks.applied_at = CASE
                        WHEN migrations.status IN ('APPLIED', 'COMPLETED', 'VERIFIED', 'FINALIZE_READY', 'FINALIZED')
                        THEN COALESCE(failbacks.applied_at, migrations.applied_at, CURRENT_TIMESTAMP(3))
                        ELSE failbacks.applied_at
                    END,
                    failbacks.updated_at = CURRENT_TIMESTAMP(3)
                WHERE failbacks.status <> CASE
                        WHEN migrations.status IN ('APPLIED', 'COMPLETED', 'VERIFIED', 'FINALIZE_READY', 'FINALIZED') THEN 'APPLIED'
                        WHEN migrations.status IN ('FAILED', 'CANCELLED') THEN 'FAILED'
                        ELSE migrations.status
                    END
                   OR NOT (failbacks.error_text <=> migrations.error_text)
                """)) {
            statement.executeUpdate();
        }
    }

    private static DimensionFailback findDimensionFailback(
            Connection connection,
            String failbackId,
            boolean forUpdate
    ) throws SQLException {
        String sql = """
                SELECT failback_id, failover_id, migration_id, dimension_id,
                       source_node, target_node, status, error_text,
                       created_at, updated_at, applied_at
                FROM cluster_dimension_failbacks
                WHERE failback_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, failbackId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? readDimensionFailback(resultSet) : null;
            }
        }
    }

    private static DimensionFailback readDimensionFailback(
            ResultSet resultSet
    ) throws SQLException {
        return new DimensionFailback(
                resultSet.getString("failback_id"),
                resultSet.getString("failover_id"),
                resultSet.getString("migration_id"),
                resultSet.getString("dimension_id"),
                resultSet.getString("source_node"),
                resultSet.getString("target_node"),
                resultSet.getString("status"),
                resultSet.getString("error_text"),
                instantOrNull(resultSet, "created_at"),
                instantOrNull(resultSet, "updated_at"),
                instantOrNull(resultSet, "applied_at")
        );
    }

    private static DimensionSnapshot readDimensionSnapshot(
            ResultSet resultSet
    ) throws SQLException {
        return new DimensionSnapshot(
                resultSet.getString("snapshot_id"),
                resultSet.getString("dimension_id"),
                resultSet.getString("source_node"),
                resultSet.getString("status"),
                resultSet.getString("archive_name"),
                resultSet.getString("archive_sha256"),
                resultSet.getString("content_sha256"),
                resultSet.getLong("archive_size"),
                resultSet.getString("error_text"),
                instantOrNull(resultSet, "created_at"),
                instantOrNull(resultSet, "updated_at"),
                instantOrNull(resultSet, "ready_at")
        );
    }

    private static DimensionFailover readDimensionFailover(
            ResultSet resultSet
    ) throws SQLException {
        return new DimensionFailover(
                resultSet.getString("failover_id"),
                resultSet.getString("dimension_id"),
                resultSet.getString("source_node"),
                resultSet.getString("target_node"),
                resultSet.getString("snapshot_id"),
                resultSet.getString("status"),
                resultSet.getString("error_text"),
                instantOrNull(resultSet, "created_at"),
                instantOrNull(resultSet, "updated_at"),
                instantOrNull(resultSet, "applying_at"),
                instantOrNull(resultSet, "applied_at")
        );
    }

    private static DimensionMigration readDimensionMigration(
            ResultSet resultSet
    ) throws SQLException {
        return new DimensionMigration(
                resultSet.getString("migration_id"),
                resultSet.getString("dimension_id"),
                resultSet.getString("source_node"),
                resultSet.getString("target_node"),
                resultSet.getString("status"),
                resultSet.getString("archive_name"),
                resultSet.getString("archive_sha256"),
                resultSet.getString("content_sha256"),
                resultSet.getLong("archive_size"),
                resultSet.getString("error_text"),
                instantOrNull(resultSet, "created_at"),
                instantOrNull(resultSet, "updated_at"),
                instantOrNull(resultSet, "ready_at"),
                instantOrNull(resultSet, "applying_at"),
                instantOrNull(resultSet, "applied_at"),
                instantOrNull(resultSet, "verified_at"),
                instantOrNull(resultSet, "finalize_ready_at"),
                instantOrNull(resultSet, "finalized_at"),
                resultSet.getString("rollback_previous_status"),
                resultSet.getString("rollback_archive_name"),
                resultSet.getString("rollback_archive_sha256"),
                resultSet.getString("rollback_content_sha256"),
                resultSet.getLong("rollback_archive_size"),
                instantOrNull(resultSet, "rollback_ready_at"),
                instantOrNull(resultSet, "rollback_applying_at"),
                instantOrNull(resultSet, "rolled_back_at"),
                instantOrNull(resultSet, "source_backup_deleted_at")
        );
    }

    private static Instant instantOrNull(
            ResultSet resultSet,
            String column
    ) throws SQLException {
        java.sql.Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String truncate(
            String value,
            int maxLength
    ) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static Connection open(
            ClusterConfig config
    ) throws SQLException {
        ensureDriverLoaded();

        return DriverManager.getConnection(
                config.jdbcUrl(),
                config.username(),
                config.password()
        );
    }

    private static void ensureSchema(
            ClusterConfig config
    ) throws SQLException {
        String schemaKey =
                config.jdbcUrl()
                        + "\n"
                        + config.username();

        if (schemaKey.equals(initializedSchemaKey)) {
            return;
        }

        synchronized (ClusterDatabase.class) {
            if (schemaKey.equals(initializedSchemaKey)) {
                return;
            }

            try (Connection connection = open(config)) {
                createTables(connection);
            }

            initializedSchemaKey = schemaKey;
        }
    }

    private static void ensureDriverLoaded()
            throws SQLException {
        if (driverLoaded) {
            return;
        }

        synchronized (ClusterDatabase.class) {
            if (driverLoaded) {
                return;
            }

            ClassLoader modClassLoader =
                    ClusterDatabase.class.getClassLoader();

            try {
                try {
                    Class.forName(
                            SHADED_MYSQL_DRIVER,
                            true,
                            modClassLoader
                    );
                } catch (ClassNotFoundException ignored) {
                    Class.forName(
                            DEVELOPMENT_MYSQL_DRIVER,
                            true,
                            modClassLoader
                    );
                }

                driverLoaded = true;
            } catch (ClassNotFoundException exception) {
                throw new SQLException(
                        "MySQL JDBC driver was not found in CointCoreGTO",
                        exception
                );
            }
        }
    }

    private static void createTables(
            Connection connection
    ) throws SQLException {
        try (Statement statement =
                     connection.createStatement()) {

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cluster_nodes (
                    node_id VARCHAR(64) NOT NULL PRIMARY KEY,
                    redirect_address VARCHAR(255) NOT NULL,
                    minecraft_version VARCHAR(32) NOT NULL,
                    player_count INT NOT NULL DEFAULT 0,
                    last_seen TIMESTAMP(3) NOT NULL
                        DEFAULT CURRENT_TIMESTAMP(3),
                    stopped_at TIMESTAMP(3) NULL,
                    started_at TIMESTAMP(3) NOT NULL
                        DEFAULT CURRENT_TIMESTAMP(3)
                )
                ENGINE=InnoDB
                DEFAULT CHARSET=utf8mb4
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS dimension_assignments (
                    dimension_id VARCHAR(255) NOT NULL PRIMARY KEY,
                    node_id VARCHAR(64) NOT NULL,
                    pinned TINYINT(1) NOT NULL DEFAULT 0,
                    assigned_at TIMESTAMP(3) NOT NULL
                        DEFAULT CURRENT_TIMESTAMP(3),
                    updated_at TIMESTAMP(3) NOT NULL
                        DEFAULT CURRENT_TIMESTAMP(3),

                    INDEX idx_dimension_node (
                        node_id
                    )
                )
                ENGINE=InnoDB
                DEFAULT CHARSET=utf8mb4
                """);


            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cluster_dimension_activity (
                    node_id VARCHAR(64) NOT NULL,
                    dimension_id VARCHAR(255) NOT NULL,
                    player_count INT NOT NULL DEFAULT 0,
                    last_seen TIMESTAMP(3) NOT NULL
                        DEFAULT CURRENT_TIMESTAMP(3),

                    PRIMARY KEY (
                        node_id,
                        dimension_id
                    ),

                    INDEX idx_dimension_activity_dimension (
                        dimension_id,
                        player_count
                    ),

                    INDEX idx_dimension_activity_seen (
                        last_seen
                    )
                )
                ENGINE=InnoDB
                DEFAULT CHARSET=utf8mb4
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cluster_dimension_migrations (
                    migration_id CHAR(36) NOT NULL PRIMARY KEY,
                    dimension_id VARCHAR(255) NOT NULL,
                    source_node VARCHAR(64) NOT NULL,
                    target_node VARCHAR(64) NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    archive_name VARCHAR(255) NULL,
                    archive_sha256 CHAR(64) NULL,
                    content_sha256 CHAR(64) NULL,
                    archive_size BIGINT NOT NULL DEFAULT 0,
                    error_text TEXT NULL,
                    created_at TIMESTAMP(3) NOT NULL
                        DEFAULT CURRENT_TIMESTAMP(3),
                    updated_at TIMESTAMP(3) NOT NULL
                        DEFAULT CURRENT_TIMESTAMP(3),
                    ready_at TIMESTAMP(3) NULL,
                    applying_at TIMESTAMP(3) NULL,
                    applied_at TIMESTAMP(3) NULL,
                    verified_at TIMESTAMP(3) NULL,
                    finalize_ready_at TIMESTAMP(3) NULL,
                    finalized_at TIMESTAMP(3) NULL,
                    rollback_previous_status VARCHAR(24) NULL,
                    rollback_archive_name VARCHAR(255) NULL,
                    rollback_archive_sha256 CHAR(64) NULL,
                    rollback_content_sha256 CHAR(64) NULL,
                    rollback_archive_size BIGINT NOT NULL DEFAULT 0,
                    rollback_ready_at TIMESTAMP(3) NULL,
                    rollback_applying_at TIMESTAMP(3) NULL,
                    rolled_back_at TIMESTAMP(3) NULL,
                    source_backup_deleted_at TIMESTAMP(3) NULL,

                    INDEX idx_dimension_migration_dimension_status (
                        dimension_id,
                        status
                    ),

                    INDEX idx_dimension_migration_target_status (
                        target_node,
                        status
                    ),

                    INDEX idx_dimension_migration_source_status (
                        source_node,
                        status
                    )
                )
                ENGINE=InnoDB
                DEFAULT CHARSET=utf8mb4
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cluster_dimension_snapshots (
                    snapshot_id CHAR(36) NOT NULL PRIMARY KEY,
                    dimension_id VARCHAR(255) NOT NULL,
                    source_node VARCHAR(64) NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    archive_name VARCHAR(255) NULL,
                    archive_sha256 CHAR(64) NULL,
                    content_sha256 CHAR(64) NULL,
                    archive_size BIGINT NOT NULL DEFAULT 0,
                    error_text TEXT NULL,
                    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    ready_at TIMESTAMP(3) NULL,

                    INDEX idx_dimension_snapshot_dimension (dimension_id, status, ready_at),
                    INDEX idx_dimension_snapshot_source (source_node, status, ready_at)
                )
                ENGINE=InnoDB
                DEFAULT CHARSET=utf8mb4
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cluster_dimension_failovers (
                    failover_id CHAR(36) NOT NULL PRIMARY KEY,
                    dimension_id VARCHAR(255) NOT NULL,
                    source_node VARCHAR(64) NOT NULL,
                    target_node VARCHAR(64) NOT NULL,
                    snapshot_id CHAR(36) NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    error_text TEXT NULL,
                    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    applying_at TIMESTAMP(3) NULL,
                    applied_at TIMESTAMP(3) NULL,

                    INDEX idx_dimension_failover_source (source_node, status),
                    INDEX idx_dimension_failover_target (target_node, status),
                    INDEX idx_dimension_failover_dimension (dimension_id, status)
                )
                ENGINE=InnoDB
                DEFAULT CHARSET=utf8mb4
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cluster_dimension_failbacks (
                    failback_id CHAR(36) NOT NULL PRIMARY KEY,
                    failover_id CHAR(36) NOT NULL,
                    migration_id CHAR(36) NOT NULL UNIQUE,
                    dimension_id VARCHAR(255) NOT NULL,
                    source_node VARCHAR(64) NOT NULL,
                    target_node VARCHAR(64) NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    error_text TEXT NULL,
                    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    applied_at TIMESTAMP(3) NULL,

                    INDEX idx_dimension_failback_failover (failover_id, status),
                    INDEX idx_dimension_failback_source (source_node, status),
                    INDEX idx_dimension_failback_target (target_node, status),
                    INDEX idx_dimension_failback_dimension (dimension_id, status)
                )
                ENGINE=InnoDB
                DEFAULT CHARSET=utf8mb4
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cluster_node_drains (
                    drain_id CHAR(36) NOT NULL PRIMARY KEY,
                    operation_type VARCHAR(24) NOT NULL DEFAULT 'DRAIN',
                    source_node VARCHAR(64) NOT NULL,
                    target_node VARCHAR(64) NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    error_text TEXT NULL,
                    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    completed_at TIMESTAMP(3) NULL,

                    INDEX idx_node_drain_source (source_node, status),
                    INDEX idx_node_drain_target (target_node, status)
                )
                ENGINE=InnoDB
                DEFAULT CHARSET=utf8mb4
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cluster_node_drain_items (
                    drain_item_id CHAR(36) NOT NULL PRIMARY KEY,
                    drain_id CHAR(36) NOT NULL,
                    migration_id CHAR(36) NOT NULL UNIQUE,
                    dimension_id VARCHAR(255) NOT NULL,
                    source_node VARCHAR(64) NOT NULL,
                    target_node VARCHAR(64) NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    error_text TEXT NULL,
                    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    applied_at TIMESTAMP(3) NULL,

                    INDEX idx_node_drain_item_drain (drain_id, status),
                    INDEX idx_node_drain_item_dimension (dimension_id, status),
                    INDEX idx_node_drain_item_source (source_node, status),
                    INDEX idx_node_drain_item_target (target_node, status)
                )
                ENGINE=InnoDB
                DEFAULT CHARSET=utf8mb4
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cluster_operation_leases (
                    lease_name VARCHAR(64) NOT NULL PRIMARY KEY,
                    owner_node VARCHAR(64) NOT NULL,
                    lease_until TIMESTAMP(3) NOT NULL,
                    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

                    INDEX idx_cluster_operation_lease_until (lease_until)
                )
                ENGINE=InnoDB
                DEFAULT CHARSET=utf8mb4
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS pending_transfers (
                    transfer_id CHAR(36) NOT NULL PRIMARY KEY,
                    player_uuid CHAR(36) NOT NULL,
                    source_node VARCHAR(64) NOT NULL,
                    target_node VARCHAR(64) NOT NULL,
                    dimension_id VARCHAR(255) NOT NULL,
                    x DOUBLE NOT NULL,
                    y DOUBLE NOT NULL,
                    z DOUBLE NOT NULL,
                    yaw FLOAT NOT NULL,
                    pitch FLOAT NOT NULL,
                    status VARCHAR(24) NOT NULL,
                    claimed_at TIMESTAMP(3) NULL,
                    applied_at TIMESTAMP(3) NULL,
                    player_data LONGBLOB NULL,
                    player_data_sha256 CHAR(64) NULL,
                    player_data_codec INT NOT NULL DEFAULT 0,
                    player_data_size INT NOT NULL DEFAULT 0,
                    created_at TIMESTAMP(3) NOT NULL
                        DEFAULT CURRENT_TIMESTAMP(3),
                    expires_at TIMESTAMP(3) NOT NULL,

                    INDEX idx_pending_player_status (
                        player_uuid,
                        status
                    ),

                    INDEX idx_pending_target_status (
                        target_node,
                        status
                    )
                )
                ENGINE=InnoDB
                DEFAULT CHARSET=utf8mb4
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cluster_player_sessions (
                    player_uuid CHAR(36) NOT NULL PRIMARY KEY,
                    owner_node VARCHAR(64) NOT NULL,
                    state VARCHAR(24) NOT NULL,
                    transfer_id CHAR(36) NULL,
                    target_node VARCHAR(64) NULL,
                    lease_expires_at TIMESTAMP(3) NOT NULL,
                    updated_at TIMESTAMP(3) NOT NULL
                        DEFAULT CURRENT_TIMESTAMP(3),

                    INDEX idx_player_sessions_owner_state (
                        owner_node,
                        state
                    ),

                    INDEX idx_player_sessions_transfer (
                        transfer_id
                    )
                )
                ENGINE=InnoDB
                DEFAULT CHARSET=utf8mb4
                """);

            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS cluster_player_backups (
                    backup_id BIGINT NOT NULL AUTO_INCREMENT
                        PRIMARY KEY,
                    player_uuid CHAR(36) NOT NULL,
                    transfer_id CHAR(36) NOT NULL,
                    source_node VARCHAR(64) NOT NULL,
                    player_data LONGBLOB NOT NULL,
                    player_data_sha256 CHAR(64) NOT NULL,
                    player_data_codec INT NOT NULL,
                    player_data_size INT NOT NULL,
                    created_at TIMESTAMP(3) NOT NULL
                        DEFAULT CURRENT_TIMESTAMP(3),
                    expires_at TIMESTAMP(3) NOT NULL,
                    restored_at TIMESTAMP(3) NULL,
                    restore_node VARCHAR(64) NULL,

                    UNIQUE INDEX uq_player_backup_transfer (
                        transfer_id
                    ),

                    INDEX idx_player_backup_player_created (
                        player_uuid,
                        created_at
                    ),

                    INDEX idx_player_backup_expires (
                        expires_at
                    )
                )
                ENGINE=InnoDB
                DEFAULT CHARSET=utf8mb4
                """);
        }

        ensureColumnExists(
                connection,
                "dimension_assignments",
                "pinned",
                "TINYINT(1) NOT NULL DEFAULT 0"
        );

        ensureColumnExists(
                connection,
                "cluster_nodes",
                "player_count",
                "INT NOT NULL DEFAULT 0"
        );

        ensureColumnExists(
                connection,
                "cluster_nodes",
                "stopped_at",
                "TIMESTAMP(3) NULL"
        );

        ensureColumnExists(
                connection,
                "pending_transfers",
                "claimed_at",
                "TIMESTAMP(3) NULL"
        );

        ensureColumnExists(
                connection,
                "pending_transfers",
                "applied_at",
                "TIMESTAMP(3) NULL"
        );

        ensureColumnExists(
                connection,
                "pending_transfers",
                "player_data",
                "LONGBLOB NULL"
        );

        ensureColumnExists(
                connection,
                "pending_transfers",
                "player_data_sha256",
                "CHAR(64) NULL"
        );

        ensureColumnExists(
                connection,
                "pending_transfers",
                "player_data_codec",
                "INT NOT NULL DEFAULT 0"
        );

        ensureColumnExists(
                connection,
                "pending_transfers",
                "player_data_size",
                "INT NOT NULL DEFAULT 0"
        );

        ensureColumnExists(
                connection,
                "cluster_player_backups",
                "restored_at",
                "TIMESTAMP(3) NULL"
        );

        ensureColumnExists(
                connection,
                "cluster_player_backups",
                "restore_node",
                "VARCHAR(64) NULL"
        );

        ensureColumnExists(connection, "cluster_dimension_migrations", "verified_at", "TIMESTAMP(3) NULL");
        ensureColumnExists(connection, "cluster_dimension_migrations", "finalize_ready_at", "TIMESTAMP(3) NULL");
        ensureColumnExists(connection, "cluster_dimension_migrations", "finalized_at", "TIMESTAMP(3) NULL");
        ensureColumnExists(connection, "cluster_dimension_migrations", "rollback_previous_status", "VARCHAR(24) NULL");
        ensureColumnExists(connection, "cluster_dimension_migrations", "rollback_archive_name", "VARCHAR(255) NULL");
        ensureColumnExists(connection, "cluster_dimension_migrations", "rollback_archive_sha256", "CHAR(64) NULL");
        ensureColumnExists(connection, "cluster_dimension_migrations", "rollback_content_sha256", "CHAR(64) NULL");
        ensureColumnExists(connection, "cluster_dimension_migrations", "rollback_archive_size", "BIGINT NOT NULL DEFAULT 0");
        ensureColumnExists(connection, "cluster_dimension_migrations", "rollback_ready_at", "TIMESTAMP(3) NULL");
        ensureColumnExists(connection, "cluster_dimension_migrations", "rollback_applying_at", "TIMESTAMP(3) NULL");
        ensureColumnExists(connection, "cluster_dimension_migrations", "rolled_back_at", "TIMESTAMP(3) NULL");
        ensureColumnExists(connection, "cluster_dimension_migrations", "source_backup_deleted_at", "TIMESTAMP(3) NULL");
        ensureColumnExists(connection, "cluster_node_drains", "operation_type", "VARCHAR(24) NOT NULL DEFAULT 'DRAIN'");
    }

    private static void refreshDimensionActivity(
            Connection connection,
            ClusterConfig config,
            Map<String, Integer> dimensionPlayerCounts
    ) throws SQLException {
        String deleteSql = """
                DELETE FROM cluster_dimension_activity
                WHERE node_id = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(deleteSql)) {
            statement.setString(1, config.nodeId());
            statement.executeUpdate();
        }

        if (dimensionPlayerCounts == null
                || dimensionPlayerCounts.isEmpty()) {
            return;
        }

        String insertSql = """
                INSERT INTO cluster_dimension_activity (
                    node_id,
                    dimension_id,
                    player_count,
                    last_seen
                )
                VALUES (
                    ?, ?, ?, CURRENT_TIMESTAMP(3)
                )
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(insertSql)) {
            for (Map.Entry<String, Integer> entry
                    : dimensionPlayerCounts.entrySet()) {
                String dimensionId = entry.getKey();
                int playerCount = entry.getValue() == null
                        ? 0
                        : Math.max(0, entry.getValue());

                if (dimensionId == null
                        || dimensionId.isBlank()
                        || playerCount <= 0) {
                    continue;
                }

                statement.setString(1, config.nodeId());
                statement.setString(2, dimensionId);
                statement.setInt(3, playerCount);
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private static void upsertNode(
            Connection connection,
            ClusterConfig config,
            MinecraftServer server
    ) throws SQLException {
        String sql = """
            INSERT INTO cluster_nodes (
                node_id,
                redirect_address,
                minecraft_version,
                player_count,
                last_seen,
                stopped_at,
                started_at
            )
            VALUES (
                ?, ?, ?, ?,
                CURRENT_TIMESTAMP(3),
                NULL,
                CURRENT_TIMESTAMP(3)
            )
            ON DUPLICATE KEY UPDATE
                redirect_address = VALUES(redirect_address),
                minecraft_version = VALUES(minecraft_version),
                player_count = VALUES(player_count),
                last_seen = CURRENT_TIMESTAMP(3),
                stopped_at = NULL
            """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, config.nodeId());
            statement.setString(
                    2,
                    config.redirectAddress()
            );
            statement.setString(
                    3,
                    server.getServerVersion()
            );
            statement.setInt(
                    4,
                    server.getPlayerList()
                            .getPlayerCount()
            );

            statement.executeUpdate();
        }
    }

    private static void ensureColumnExists(
            Connection connection,
            String tableName,
            String columnName,
            String definition
    ) throws SQLException {
        String checkSql = """
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = ?
              AND column_name = ?
            """;

        boolean exists;

        try (PreparedStatement statement =
                     connection.prepareStatement(checkSql)) {

            statement.setString(1, tableName);
            statement.setString(2, columnName);

            try (ResultSet resultSet =
                         statement.executeQuery()) {

                resultSet.next();
                exists = resultSet.getInt(1) > 0;
            }
        }

        if (exists) {
            return;
        }

        String alterSql =
                "ALTER TABLE "
                        + tableName
                        + " ADD COLUMN "
                        + columnName
                        + " "
                        + definition;

        try (Statement statement =
                     connection.createStatement()) {

            statement.executeUpdate(alterSql);
        } catch (SQLException exception) {
            if (exception.getErrorCode() != 1060) {
                throw exception;
            }
        }
    }

    private static String findNodeRedirectAddress(
            Connection connection,
            String targetNode
    ) throws SQLException {
        String sql = """
                SELECT redirect_address
                FROM cluster_nodes
                WHERE node_id = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, targetNode);

            try (ResultSet resultSet =
                         statement.executeQuery()) {

                if (!resultSet.next()) {
                    return null;
                }

                return resultSet.getString(
                        "redirect_address"
                );
            }
        }
    }

    private static String findOnlineNodeRedirectAddress(
            Connection connection,
            String targetNode,
            int timeoutSeconds
    ) throws SQLException {
        String sql = """
            SELECT redirect_address
            FROM cluster_nodes
            WHERE node_id = ?
              AND stopped_at IS NULL
              AND last_seen >= TIMESTAMPADD(
                    SECOND,
                    -?,
                    CURRENT_TIMESTAMP(3)
              )
            """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, targetNode);
            statement.setInt(2, timeoutSeconds);

            try (ResultSet resultSet =
                         statement.executeQuery()) {

                if (!resultSet.next()) {
                    return null;
                }

                return resultSet.getString(
                        "redirect_address"
                );
            }
        }
    }

    private static void lockClusterNodes(
            Connection connection
    ) throws SQLException {
        String sql = """
                SELECT node_id
                FROM cluster_nodes
                ORDER BY node_id
                FOR UPDATE
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql);
             ResultSet resultSet =
                     statement.executeQuery()) {

            while (resultSet.next()) {
                resultSet.getString("node_id");
            }
        }
    }

    private static void lockDimensionAssignments(
            Connection connection
    ) throws SQLException {
        String sql = """
                SELECT dimension_id
                FROM dimension_assignments
                ORDER BY dimension_id
                FOR UPDATE
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql);
             ResultSet resultSet =
                     statement.executeQuery()) {

            while (resultSet.next()) {
                resultSet.getString("dimension_id");
            }
        }
    }

    private static List<OfflineDimensionAssignment>
    findOfflineDimensionAssignments(
            Connection connection,
            int timeoutSeconds
    ) throws SQLException {
        String sql = """
                SELECT
                    assignments.dimension_id,
                    assignments.node_id
                FROM dimension_assignments AS assignments
                LEFT JOIN cluster_nodes AS nodes
                    ON nodes.node_id = assignments.node_id
                WHERE assignments.pinned = 0
                  AND NOT EXISTS (
                        SELECT 1
                        FROM cluster_dimension_migrations AS migrations
                        WHERE migrations.dimension_id = assignments.dimension_id
                          AND migrations.status IN ('PREPARING', 'READY', 'APPLYING', 'APPLIED', 'VERIFIED', 'FINALIZE_READY', 'ROLLBACK_PREPARING', 'ROLLBACK_READY', 'ROLLBACK_APPLYING')
                  )
                  AND (nodes.node_id IS NULL
                   OR nodes.stopped_at IS NOT NULL
                   OR nodes.last_seen < TIMESTAMPADD(
                        SECOND,
                        -?,
                        CURRENT_TIMESTAMP(3)
                   ))
                ORDER BY assignments.dimension_id
                """;

        List<OfflineDimensionAssignment> assignments =
                new ArrayList<>();

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setInt(1, timeoutSeconds);

            try (ResultSet resultSet =
                         statement.executeQuery()) {

                while (resultSet.next()) {
                    assignments.add(
                            new OfflineDimensionAssignment(
                                    resultSet.getString(
                                            "dimension_id"
                                    ),
                                    resultSet.getString(
                                            "node_id"
                                    )
                            )
                    );
                }
            }
        }

        return assignments;
    }

    private static LeastAssignedNode findLeastAssignedNode(
            Connection connection,
            int timeoutSeconds
    ) throws SQLException {
        String sql = """
            SELECT
                nodes.node_id,
                nodes.player_count,
                COUNT(assignments.dimension_id)
                    AS assignment_count
            FROM cluster_nodes AS nodes
            LEFT JOIN dimension_assignments AS assignments
                ON assignments.node_id = nodes.node_id
            WHERE nodes.stopped_at IS NULL
              AND nodes.last_seen >= TIMESTAMPADD(
                SECOND,
                -?,
                CURRENT_TIMESTAMP(3)
            )
              AND NOT EXISTS (
                  SELECT 1
                  FROM cluster_node_drains AS drains
                  WHERE drains.source_node = nodes.node_id
                    AND drains.status IN ('PREPARING', 'READY', 'APPLYING', 'DRAINED', 'PARTIAL', 'FAILED')
              )
            GROUP BY
                nodes.node_id,
                nodes.player_count
            ORDER BY
                assignment_count ASC,
                nodes.player_count ASC,
                nodes.node_id ASC
            LIMIT 1
            """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setInt(1, timeoutSeconds);

            try (ResultSet resultSet =
                         statement.executeQuery()) {

                if (!resultSet.next()) {
                    return null;
                }

                return new LeastAssignedNode(
                        resultSet.getString("node_id"),
                        resultSet.getInt(
                                "assignment_count"
                        ),
                        resultSet.getInt(
                                "player_count"
                        )
                );
            }
        }
    }

    private static boolean isNodeOnline(
            Connection connection,
            String nodeId,
            int timeoutSeconds
    ) throws SQLException {
        String sql = """
                SELECT 1
                FROM cluster_nodes
                WHERE node_id = ?
                  AND stopped_at IS NULL
                  AND last_seen >= TIMESTAMPADD(
                        SECOND,
                        -?,
                        CURRENT_TIMESTAMP(3)
                  )
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, nodeId);
            statement.setInt(2, timeoutSeconds);

            try (ResultSet resultSet =
                         statement.executeQuery()) {

                return resultSet.next();
            }
        }
    }

    private static int countAssignmentsForNode(
            Connection connection,
            String nodeId
    ) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM dimension_assignments
                WHERE node_id = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, nodeId);

            try (ResultSet resultSet =
                         statement.executeQuery()) {

                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static String findDimensionOwner(
            Connection connection,
            String dimensionId
    ) throws SQLException {
        String sql = """
                SELECT node_id
                FROM dimension_assignments
                WHERE dimension_id = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, dimensionId);

            try (ResultSet resultSet =
                         statement.executeQuery()) {

                if (!resultSet.next()) {
                    return null;
                }

                return resultSet.getString("node_id");
            }
        }
    }

    private static void lockDimensionActivity(
            Connection connection
    ) throws SQLException {
        String sql = """
                SELECT node_id, dimension_id
                FROM cluster_dimension_activity
                ORDER BY node_id, dimension_id
                FOR UPDATE
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                resultSet.getString("node_id");
                resultSet.getString("dimension_id");
            }
        }
    }

    private static List<PlanningNode> findOnlinePlanningNodes(
            Connection connection,
            int timeoutSeconds
    ) throws SQLException {
        String sql = """
                SELECT
                    node_id,
                    player_count
                FROM cluster_nodes
                WHERE stopped_at IS NULL
                  AND last_seen >= TIMESTAMPADD(
                        SECOND,
                        -?,
                        CURRENT_TIMESTAMP(3)
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM cluster_node_drains AS drains
                      WHERE drains.source_node = cluster_nodes.node_id
                        AND drains.status IN ('PREPARING', 'READY', 'APPLYING', 'DRAINED', 'PARTIAL', 'FAILED')
                  )
                ORDER BY node_id
                """;

        List<PlanningNode> nodes = new ArrayList<>();

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setInt(1, timeoutSeconds);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    nodes.add(
                            new PlanningNode(
                                    resultSet.getString("node_id"),
                                    resultSet.getInt("player_count")
                            )
                    );
                }
            }
        }

        return List.copyOf(nodes);
    }

    private static Map<String, DimensionAssignmentRow>
    loadDimensionAssignments(
            Connection connection
    ) throws SQLException {
        String sql = """
                SELECT
                    dimension_id,
                    node_id,
                    pinned
                FROM dimension_assignments
                ORDER BY dimension_id
                """;

        Map<String, DimensionAssignmentRow> assignments =
                new LinkedHashMap<>();

        try (PreparedStatement statement =
                     connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                assignments.put(
                        resultSet.getString("dimension_id"),
                        new DimensionAssignmentRow(
                                resultSet.getString("node_id"),
                                resultSet.getBoolean("pinned")
                        )
                );
            }
        }

        return assignments;
    }

    private static DimensionAssignmentRow findDimensionAssignmentRow(
            Connection connection,
            String dimensionId
    ) throws SQLException {
        String sql = """
                SELECT
                    node_id,
                    pinned
                FROM dimension_assignments
                WHERE dimension_id = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setString(1, dimensionId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                return new DimensionAssignmentRow(
                        resultSet.getString("node_id"),
                        resultSet.getBoolean("pinned")
                );
            }
        }
    }

    private static Map<String, DimensionActivity>
    loadDimensionActivity(
            Connection connection,
            int timeoutSeconds
    ) throws SQLException {
        String sql = """
                SELECT
                    activity.dimension_id,
                    activity.node_id,
                    activity.player_count
                FROM cluster_dimension_activity AS activity
                INNER JOIN cluster_nodes AS nodes
                    ON nodes.node_id = activity.node_id
                WHERE activity.player_count > 0
                  AND activity.last_seen >= TIMESTAMPADD(
                        SECOND,
                        -?,
                        CURRENT_TIMESTAMP(3)
                  )
                  AND nodes.stopped_at IS NULL
                  AND nodes.last_seen >= TIMESTAMPADD(
                        SECOND,
                        -?,
                        CURRENT_TIMESTAMP(3)
                  )
                ORDER BY
                    activity.dimension_id,
                    activity.node_id
                """;

        Map<String, Integer> playerCounts = new LinkedHashMap<>();
        Map<String, Set<String>> activeNodes = new LinkedHashMap<>();

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setInt(1, timeoutSeconds);
            statement.setInt(2, timeoutSeconds);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String dimensionId =
                            resultSet.getString("dimension_id");
                    String nodeId =
                            resultSet.getString("node_id");
                    int playerCount =
                            resultSet.getInt("player_count");

                    playerCounts.merge(
                            dimensionId,
                            playerCount,
                            Integer::sum
                    );
                    activeNodes.computeIfAbsent(
                            dimensionId,
                            ignored -> new LinkedHashSet<>()
                    ).add(nodeId);
                }
            }
        }

        Map<String, DimensionActivity> result =
                new LinkedHashMap<>();

        for (Map.Entry<String, Integer> entry
                : playerCounts.entrySet()) {
            result.put(
                    entry.getKey(),
                    new DimensionActivity(
                            entry.getValue(),
                            List.copyOf(
                                    activeNodes.getOrDefault(
                                            entry.getKey(),
                                            Set.of()
                                    )
                            )
                    )
            );
        }

        return result;
    }

    private static String singleActiveNode(
            DimensionActivity activity
    ) {
        if (activity == null
                || activity.playerCount() <= 0
                || activity.nodeIds().size() != 1) {
            return null;
        }

        return activity.nodeIds().get(0);
    }

    private static String selectPlanningNode(
            List<PlanningNode> onlineNodes,
            Map<String, Integer> assignedCounts,
            String preferredNode
    ) throws SQLException {
        return onlineNodes.stream()
                .min(
                        Comparator
                                .comparingInt(
                                        (PlanningNode node) -> assignedCounts.getOrDefault(
                                                node.nodeId(),
                                                0
                                        )
                                )
                                .thenComparingInt(
                                        PlanningNode::playerCount
                                )
                                .thenComparingInt(
                                        node -> preferredNode != null
                                                && preferredNode.equalsIgnoreCase(
                                                        node.nodeId()
                                                )
                                                ? 0
                                                : 1
                                )
                                .thenComparing(PlanningNode::nodeId)
                )
                .map(PlanningNode::nodeId)
                .orElseThrow(
                        () -> new SQLException(
                                "Нет ONLINE-узлов для назначения измерения"
                        )
                );
    }

    private static DimensionPlanEntry createPlanEntry(
            String dimensionId,
            DimensionAssignmentRow assignment,
            String targetNode,
            DimensionActivity activity,
            DimensionPlanAction action
    ) {
        return new DimensionPlanEntry(
                dimensionId,
                assignment == null ? null : assignment.nodeId(),
                targetNode,
                assignment != null && assignment.pinned(),
                activity == null ? 0 : activity.playerCount(),
                activity == null ? List.of() : activity.nodeIds(),
                action
        );
    }

    private static void cancelReadyTransfers(
            Connection connection,
            ClusterConfig config,
            UUID playerUuid
    ) throws SQLException {
        String sessionSql = """
                UPDATE cluster_player_sessions AS sessions
                INNER JOIN pending_transfers AS transfers
                    ON transfers.transfer_id = sessions.transfer_id
                SET
                    sessions.state = 'ONLINE',
                    sessions.transfer_id = NULL,
                    sessions.target_node = NULL,
                    sessions.lease_expires_at = TIMESTAMPADD(
                        SECOND,
                        ?,
                        CURRENT_TIMESTAMP(3)
                    ),
                    sessions.updated_at = CURRENT_TIMESTAMP(3)
                WHERE transfers.player_uuid = ?
                  AND transfers.source_node = ?
                  AND transfers.status = 'READY'
                  AND sessions.owner_node = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sessionSql)) {
            statement.setInt(
                    1,
                    config.playerSessionLeaseSeconds()
            );
            statement.setString(2, playerUuid.toString());
            statement.setString(3, config.nodeId());
            statement.setString(4, config.nodeId());
            statement.executeUpdate();
        }

        String transferSql = """
                UPDATE pending_transfers
                SET
                    status = 'CANCELLED',
                    player_data = NULL
                WHERE player_uuid = ?
                  AND source_node = ?
                  AND status = 'READY'
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(transferSql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, config.nodeId());
            statement.executeUpdate();
        }
    }

    private static void expireTransfers(
            Connection connection
    ) throws SQLException {
        String sessionSql = """
                DELETE sessions
                FROM cluster_player_sessions AS sessions
                INNER JOIN pending_transfers AS transfers
                    ON transfers.transfer_id = sessions.transfer_id
                WHERE transfers.status IN ('READY', 'CLAIMED')
                  AND transfers.expires_at <= CURRENT_TIMESTAMP(3)
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sessionSql)) {
            statement.executeUpdate();
        }

        String transferSql = """
                UPDATE pending_transfers
                SET
                    status = 'EXPIRED',
                    claimed_at = NULL,
                    player_data = NULL
                WHERE status IN ('READY', 'CLAIMED')
                  AND expires_at <= CURRENT_TIMESTAMP(3)
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(transferSql)) {
            statement.executeUpdate();
        }
    }

    private static void recoverStaleClaims(
            Connection connection,
            ClusterConfig config
    ) throws SQLException {
        String sessionSql = """
                UPDATE cluster_player_sessions AS sessions
                INNER JOIN pending_transfers AS transfers
                    ON transfers.transfer_id = sessions.transfer_id
                SET
                    sessions.owner_node = transfers.source_node,
                    sessions.state = 'TRANSFERRING',
                    sessions.target_node = transfers.target_node,
                    sessions.lease_expires_at = transfers.expires_at,
                    sessions.updated_at = CURRENT_TIMESTAMP(3)
                WHERE transfers.status = 'CLAIMED'
                  AND transfers.claimed_at IS NOT NULL
                  AND transfers.claimed_at <= TIMESTAMPADD(
                        SECOND,
                        -?,
                        CURRENT_TIMESTAMP(3)
                  )
                  AND transfers.expires_at > CURRENT_TIMESTAMP(3)
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sessionSql)) {
            statement.setInt(1, config.staleClaimSeconds());
            statement.executeUpdate();
        }

        String transferSql = """
                UPDATE pending_transfers
                SET
                    status = 'READY',
                    claimed_at = NULL
                WHERE status = 'CLAIMED'
                  AND claimed_at IS NOT NULL
                  AND claimed_at <= TIMESTAMPADD(
                        SECOND,
                        -?,
                        CURRENT_TIMESTAMP(3)
                  )
                  AND expires_at > CURRENT_TIMESTAMP(3)
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(transferSql)) {
            statement.setInt(1, config.staleClaimSeconds());
            statement.executeUpdate();
        }
    }

    private static PendingTransfer readPendingTransfer(
            ResultSet resultSet
    ) throws SQLException {
        return new PendingTransfer(
                resultSet.getString("transfer_id"),
                UUID.fromString(
                        resultSet.getString("player_uuid")
                ),
                resultSet.getString("source_node"),
                resultSet.getString("target_node"),
                resultSet.getString("dimension_id"),
                resultSet.getDouble("x"),
                resultSet.getDouble("y"),
                resultSet.getDouble("z"),
                resultSet.getFloat("yaw"),
                resultSet.getFloat("pitch"),
                resultSet.getBytes("player_data"),
                resultSet.getString("player_data_sha256"),
                resultSet.getInt("player_data_codec"),
                resultSet.getInt("player_data_size"),
                resultSet
                        .getTimestamp("created_at")
                        .toInstant(),
                resultSet
                        .getTimestamp("expires_at")
                        .toInstant()
        );
    }

    private static TestResult readTestResult(
            Connection connection,
            ClusterConfig config
    ) throws SQLException {
        DatabaseMetaData metadata =
                connection.getMetaData();

        int nodeCount;

        try (PreparedStatement statement =
                     connection.prepareStatement(
                             "SELECT COUNT(*) FROM cluster_nodes"
                     );
             ResultSet resultSet =
                     statement.executeQuery()) {

            resultSet.next();
            nodeCount = resultSet.getInt(1);
        }

        return new TestResult(
                metadata.getDatabaseProductName(),
                metadata.getDatabaseProductVersion(),
                connection.getCatalog(),
                config.nodeId(),
                nodeCount,
                Instant.now()
        );
    }

    private static void rollbackQuietly(
            Connection connection
    ) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
        }
    }

    private static void restoreAutoCommit(
            Connection connection
    ) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {
        }
    }

    public record TestResult(
            String databaseName,
            String databaseVersion,
            String catalog,
            String nodeId,
            int registeredNodes,
            Instant checkedAt
    ) {
    }

    public record DimensionAssignment(
            String dimensionId,
            String nodeId,
            String previousNodeId,
            Instant assignedAt
    ) {
    }

    public record AutomaticDimensionAssignment(
            String dimensionId,
            String nodeId,
            boolean created,
            int assignmentCountBefore,
            int playerCountBefore,
            Instant assignedAt
    ) {
    }

    public record DimensionAssignmentInfo(
            String dimensionId,
            String nodeId,
            boolean pinned,
            int activePlayers,
            List<String> activeNodes
    ) {
    }

    public record DimensionPinResult(
            String dimensionId,
            String nodeId,
            String previousNodeId,
            boolean previouslyPinned,
            boolean pinned,
            Instant updatedAt
    ) {
    }

    public enum DimensionPlanAction {
        KEEP,
        ASSIGN,
        MOVE,
        SKIP_PINNED,
        SKIP_MIGRATING,
        SKIP_ACTIVE,
        CONFLICT_ACTIVE
    }

    public record DimensionPlanEntry(
            String dimensionId,
            String previousNodeId,
            String targetNodeId,
            boolean pinned,
            int activePlayers,
            List<String> activeNodes,
            DimensionPlanAction action
    ) {
    }

    public record PlanningNodeStatus(
            String nodeId,
            int playerCount,
            int plannedDimensionCount
    ) {
    }

    public record DimensionPlanResult(
            boolean applied,
            boolean rebalance,
            int changedCount,
            List<DimensionPlanEntry> entries,
            List<PlanningNodeStatus> nodes,
            Instant createdAt
    ) {
    }

    public record DimensionMigration(
            String migrationId,
            String dimensionId,
            String sourceNode,
            String targetNode,
            String status,
            String archiveName,
            String archiveSha256,
            String contentSha256,
            long archiveSize,
            String errorText,
            Instant createdAt,
            Instant updatedAt,
            Instant readyAt,
            Instant applyingAt,
            Instant appliedAt,
            Instant verifiedAt,
            Instant finalizeReadyAt,
            Instant finalizedAt,
            String rollbackPreviousStatus,
            String rollbackArchiveName,
            String rollbackArchiveSha256,
            String rollbackContentSha256,
            long rollbackArchiveSize,
            Instant rollbackReadyAt,
            Instant rollbackApplyingAt,
            Instant rolledBackAt,
            Instant sourceBackupDeletedAt
    ) {
    }

    public record NodeDrainPreviewEntry(
            String dimensionId,
            String sourceNode,
            String targetNode,
            boolean pinned,
            int activePlayers,
            List<String> activeNodes,
            boolean executable,
            String reason
    ) {
    }

    public record NodeDrainPreview(
            String sourceNode,
            String targetNode,
            int sourcePlayers,
            boolean targetReady,
            List<NodeDrainPreviewEntry> entries,
            Instant createdAt
    ) {
    }

    public record DimensionDrainItem(
            String drainItemId,
            String drainId,
            String migrationId,
            String dimensionId,
            String sourceNode,
            String targetNode,
            String status,
            String errorText,
            Instant createdAt,
            Instant updatedAt,
            Instant appliedAt
    ) {
    }

    public record NodeDrain(
            String drainId,
            String operationType,
            String sourceNode,
            String targetNode,
            String status,
            String errorText,
            int totalItems,
            int preparingItems,
            int readyItems,
            int applyingItems,
            int appliedItems,
            int failedItems,
            int cancelledItems,
            Instant createdAt,
            Instant updatedAt,
            Instant completedAt
    ) {
    }

    public record NodeDrainPreparationResult(
            NodeDrain drain,
            List<DimensionDrainItem> items,
            int skipped
    ) {
    }

    public record NodeOperationRecoveryResult(
            NodeDrain operation,
            List<DimensionDrainItem> items,
            int alreadyReady,
            int alreadyApplied,
            int skipped
    ) {
    }

    public record NodeDrainCancellationResult(
            NodeDrain drain,
            List<DimensionMigration> migrations
    ) {
    }

    public record NodeDrainReadiness(
            String nodeId,
            int playerCount,
            int assignmentCount,
            int pinnedCount,
            int unsupportedCount,
            int migratableCount,
            int activeDrainCount,
            boolean safeToStop,
            Instant checkedAt
    ) {
    }

    public record DimensionSnapshotCoverage(
            String dimensionId,
            String ownerNode,
            Instant latestReadyAt
    ) {
    }

    public record OperationalHealth(
            int activeTransfers,
            int staleClaimedTransfers,
            int expiredPlayerSessions,
            int activeMigrations,
            int activeSnapshots,
            int activeFailovers,
            int activeFailbacks,
            int activeDrains,
            int activeRebalances,
            int recentFailedOperations,
            int stuckOperations,
            int readyFailoversWithOnlineSource,
            int activeOperationLeases,
            Instant checkedAt
    ) {
    }

    public record DimensionSnapshot(
            String snapshotId,
            String dimensionId,
            String sourceNode,
            String status,
            String archiveName,
            String archiveSha256,
            String contentSha256,
            long archiveSize,
            String errorText,
            Instant createdAt,
            Instant updatedAt,
            Instant readyAt
    ) {
    }

    public record AutomaticFailoverCandidate(
            String nodeId,
            long heartbeatAgeSeconds,
            boolean cleanStop,
            int dimensionCount,
            boolean eligible,
            long secondsRemaining,
            String reason
    ) {
    }

    public record FailoverPreviewEntry(
            String dimensionId,
            String sourceNode,
            String targetNode,
            String snapshotId,
            Instant snapshotReadyAt,
            boolean executable,
            String reason
    ) {
    }

    public record DimensionFailover(
            String failoverId,
            String dimensionId,
            String sourceNode,
            String targetNode,
            String snapshotId,
            String status,
            String errorText,
            Instant createdAt,
            Instant updatedAt,
            Instant applyingAt,
            Instant appliedAt
    ) {
    }

    public record FailbackPreviewEntry(
            String failoverId,
            String dimensionId,
            String sourceNode,
            String targetNode,
            boolean executable,
            String reason
    ) {
    }

    public record DimensionFailback(
            String failbackId,
            String failoverId,
            String migrationId,
            String dimensionId,
            String sourceNode,
            String targetNode,
            String status,
            String errorText,
            Instant createdAt,
            Instant updatedAt,
            Instant appliedAt
    ) {
    }

    public record FailbackPreparationResult(
            List<DimensionFailback> failbacks,
            int skipped
    ) {
    }

    public record ClusterNodeStatus(
            String nodeId,
            String redirectAddress,
            int playerCount,
            int dimensionCount,
            boolean online,
            long heartbeatAgeSeconds,
            Instant lastSeen
    ) {
    }

    private record DimensionAssignmentRow(
            String nodeId,
            boolean pinned
    ) {
    }

    private record DimensionActivity(
            int playerCount,
            List<String> nodeIds
    ) {
    }

    private record PlanningNode(
            String nodeId,
            int playerCount
    ) {
    }

    private record LeastAssignedNode(
            String nodeId,
            int assignmentCount,
            int playerCount
    ) {
    }

    private record OfflineDimensionAssignment(
            String dimensionId,
            String previousNodeId
    ) {
    }

    public record DimensionReassignment(
            String dimensionId,
            String previousNodeId,
            String newNodeId,
            Instant reassignedAt
    ) {
    }

    private record PlayerSessionRow(
            String ownerNode,
            String state,
            String transferId,
            String targetNode,
            Instant leaseExpiresAt,
            boolean leaseActive,
            boolean ownerNodeOnline
    ) {
    }

    public record PlayerSessionAcquireResult(
            boolean acquired,
            boolean recovered,
            String ownerNode,
            String previousOwnerNode,
            String state,
            String transferId,
            String targetNode,
            Instant leaseExpiresAt
    ) {
        private static PlayerSessionAcquireResult acquired(
                String ownerNode
        ) {
            return new PlayerSessionAcquireResult(
                    true,
                    false,
                    ownerNode,
                    null,
                    "ONLINE",
                    null,
                    null,
                    null
            );
        }

        private static PlayerSessionAcquireResult recovered(
                String ownerNode,
                String previousOwnerNode,
                String previousState
        ) {
            return new PlayerSessionAcquireResult(
                    true,
                    true,
                    ownerNode,
                    previousOwnerNode,
                    previousState,
                    null,
                    null,
                    null
            );
        }

        private static PlayerSessionAcquireResult denied(
                String ownerNode,
                String state,
                String transferId,
                String targetNode,
                Instant leaseExpiresAt
        ) {
            return new PlayerSessionAcquireResult(
                    false,
                    false,
                    ownerNode,
                    null,
                    state,
                    transferId,
                    targetNode,
                    leaseExpiresAt
            );
        }
    }

    public record RecoveryBackup(
            long backupId,
            UUID playerUuid,
            String transferId,
            String sourceNode,
            byte[] playerData,
            String playerDataSha256,
            int playerDataCodec,
            int playerDataSize,
            Instant createdAt,
            Instant expiresAt
    ) {
    }

    public record CreatedTransfer(
            String transferId,
            UUID playerUuid,
            String sourceNode,
            String targetNode,
            String redirectAddress,
            String dimensionId,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            int playerDataSize,
            String playerDataSha256
    ) {
    }

    public record PendingTransfer(
            String transferId,
            UUID playerUuid,
            String sourceNode,
            String targetNode,
            String dimensionId,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            byte[] playerData,
            String playerDataSha256,
            int playerDataCodec,
            int playerDataSize,
            Instant createdAt,
            Instant expiresAt
    ) {
    }
}