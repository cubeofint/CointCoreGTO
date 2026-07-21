package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.server.MinecraftServer;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ClusterDatabase {
    private static final String SHADED_MYSQL_DRIVER =
            "crazer.cubeofinterest.cointcoregto.shadow.mysql.cj.jdbc.Driver";

    private static final String DEVELOPMENT_MYSQL_DRIVER =
            "com.mysql.cj.jdbc.Driver";

    private static volatile boolean driverLoaded;

    private ClusterDatabase() {
    }

    public static TestResult initializeAndTest(
            ClusterConfig config,
            MinecraftServer server
    ) throws SQLException {
        return test(config, server);
    }

    public static TestResult test(
            ClusterConfig config,
            MinecraftServer server
    ) throws SQLException {
        try (Connection connection = open(config)) {
            createTables(connection);
            expireTransfers(connection);
            upsertNode(connection, config, server);

            return readTestResult(connection, config);
        }
    }

    public static void heartbeat(
            ClusterConfig config,
            MinecraftServer server
    ) throws SQLException {
        try (Connection connection = open(config)) {
            upsertNode(connection, config, server);
        }
    }

    public static List<ClusterNodeStatus> listNodes(
            ClusterConfig config
    ) throws SQLException {
        try (Connection connection = open(config)) {
            createTables(connection);

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
                                          WHEN nodes.last_seen >= DATE_SUB(
                                              CURRENT_TIMESTAMP(3),
                                              INTERVAL 15 SECOND
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
                    nodes.last_seen
                ORDER BY nodes.node_id
                """;

            List<ClusterNodeStatus> nodes =
                    new ArrayList<>();

            try (PreparedStatement statement =
                         connection.prepareStatement(sql);
                 ResultSet resultSet =
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

            return List.copyOf(nodes);
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

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                createTables(connection);

                if (findNodeRedirectAddress(connection, nodeId) == null) {
                    throw new SQLException(
                            "Узел " + nodeId
                                    + " не найден в cluster_nodes"
                    );
                }

                String previousNode =
                        findDimensionOwner(connection, dimensionId);

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
        try (Connection connection = open(config)) {
            createTables(connection);
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

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                createTables(connection);
                lockClusterNodes(connection);

                String existingOwner =
                        findDimensionOwner(connection, dimensionId);

                if (existingOwner != null) {
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

                LeastAssignedNode selectedNode =
                        findLeastAssignedNode(connection);

                if (selectedNode == null) {
                    throw new SQLException(
                            "Нет доступных ONLINE-узлов с heartbeat не старше 15 секунд"
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

    public static CreatedTransfer createTransfer(
            ClusterConfig config,
            UUID playerUuid,
            String targetNode,
            String dimensionId,
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) throws SQLException {
        if (targetNode == null || targetNode.isBlank()) {
            throw new SQLException("Target node is empty");
        }

        if (targetNode.equalsIgnoreCase(config.nodeId())) {
            throw new SQLException(
                    "Игрок уже находится на узле " + config.nodeId()
            );
        }

        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                createTables(connection);
                expireTransfers(connection);

                String redirectAddress =
                        findOnlineNodeRedirectAddress(
                                connection,
                                targetNode
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
                                    + " сейчас OFFLINE: heartbeat старше 15 секунд"
                    );
                }

                cancelReadyTransfers(connection, playerUuid);

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
                            status,
                            created_at,
                            expires_at
                        )
                        VALUES (
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            'READY',
                            CURRENT_TIMESTAMP(3),
                            DATE_ADD(
                                CURRENT_TIMESTAMP(3),
                                INTERVAL 5 MINUTE
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
                    statement.executeUpdate();
                }

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
                        pitch
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
        try (Connection connection = open(config)) {
            connection.setAutoCommit(false);

            try {
                createTables(connection);
                expireTransfers(connection);

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

                String claimSql = """
                        UPDATE pending_transfers
                        SET status = 'CLAIMED'
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

    public static void markConsumed(
            ClusterConfig config,
            String transferId
    ) throws SQLException {
        updateClaimedStatus(
                config,
                transferId,
                "CONSUMED"
        );
    }

    public static void markFailed(
            ClusterConfig config,
            String transferId
    ) throws SQLException {
        updateClaimedStatus(
                config,
                transferId,
                "FAILED"
        );
    }

    private static void updateClaimedStatus(
            ClusterConfig config,
            String transferId,
            String newStatus
    ) throws SQLException {
        String sql = """
                UPDATE pending_transfers
                SET status = ?
                WHERE transfer_id = ?
                  AND status = 'CLAIMED'
                """;

        try (Connection connection = open(config);
             PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(1, newStatus);
            statement.setString(2, transferId);
            statement.executeUpdate();
        }
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
        }

        ensureColumnExists(
                connection,
                "cluster_nodes",
                "player_count",
                "INT NOT NULL DEFAULT 0"
        );
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
                started_at
            )
            VALUES (
                ?, ?, ?, ?,
                CURRENT_TIMESTAMP(3),
                CURRENT_TIMESTAMP(3)
            )
            ON DUPLICATE KEY UPDATE
                redirect_address = VALUES(redirect_address),
                minecraft_version = VALUES(minecraft_version),
                player_count = VALUES(player_count),
                last_seen = CURRENT_TIMESTAMP(3)
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
            String targetNode
    ) throws SQLException {
        String sql = """
            SELECT redirect_address
            FROM cluster_nodes
            WHERE node_id = ?
              AND last_seen >= DATE_SUB(
                    CURRENT_TIMESTAMP(3),
                    INTERVAL 15 SECOND
              )
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

    private static LeastAssignedNode findLeastAssignedNode(
            Connection connection
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
            WHERE nodes.last_seen >= DATE_SUB(
                CURRENT_TIMESTAMP(3),
                INTERVAL 15 SECOND
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
                     connection.prepareStatement(sql);
             ResultSet resultSet =
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

    private static void cancelReadyTransfers(
            Connection connection,
            UUID playerUuid
    ) throws SQLException {
        String sql = """
                UPDATE pending_transfers
                SET status = 'CANCELLED'
                WHERE player_uuid = ?
                  AND status = 'READY'
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            statement.setString(
                    1,
                    playerUuid.toString()
            );

            statement.executeUpdate();
        }
    }

    private static void expireTransfers(
            Connection connection
    ) throws SQLException {
        String sql = """
                UPDATE pending_transfers
                SET status = 'EXPIRED'
                WHERE status IN ('READY', 'CLAIMED')
                  AND expires_at <= CURRENT_TIMESTAMP(3)
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

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

    private record LeastAssignedNode(
            String nodeId,
            int assignmentCount,
            int playerCount
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
            float pitch
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
            Instant createdAt,
            Instant expiresAt
    ) {
    }
}