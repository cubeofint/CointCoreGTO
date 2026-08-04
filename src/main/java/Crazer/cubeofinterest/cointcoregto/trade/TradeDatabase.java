package Crazer.cubeofinterest.cointcoregto.trade;

import Crazer.cubeofinterest.cointcoregto.ClusterConfig;
import net.minecraft.world.item.ItemStack;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

final class TradeDatabase {
    private static final String SHADED_DRIVER = "crazer.cubeofinterest.cointcoregto.shadow.mysql.cj.jdbc.Driver";
    private static final String DEV_DRIVER = "com.mysql.cj.jdbc.Driver";
    private static volatile boolean driverLoaded;

    private final ClusterConfig config;

    TradeDatabase(ClusterConfig config) {
        this.config = config;
    }

    void initialize() throws Exception {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS cluster_trade_players (
                        player_uuid CHAR(36) NOT NULL PRIMARY KEY,
                        player_name VARCHAR(64) NOT NULL,
                        node_id VARCHAR(64) NOT NULL,
                        tier_index INT NOT NULL DEFAULT -1,
                        online TINYINT(1) NOT NULL DEFAULT 1,
                        heartbeat_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                        KEY idx_trade_players_name (player_name),
                        KEY idx_trade_players_online (online, heartbeat_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS cluster_trades (
                        trade_id CHAR(36) NOT NULL PRIMARY KEY,
                        initiator_uuid CHAR(36) NOT NULL,
                        initiator_name VARCHAR(64) NOT NULL,
                        initiator_node VARCHAR(64) NOT NULL,
                        target_uuid CHAR(36) NOT NULL,
                        target_name VARCHAR(64) NOT NULL,
                        target_node VARCHAR(64) NOT NULL,
                        status VARCHAR(24) NOT NULL,
                        initiator_offer LONGTEXT NOT NULL,
                        target_offer LONGTEXT NOT NULL,
                        initiator_currency BIGINT NOT NULL DEFAULT 0,
                        target_currency BIGINT NOT NULL DEFAULT 0,
                        initiator_ready TINYINT(1) NOT NULL DEFAULT 0,
                        target_ready TINYINT(1) NOT NULL DEFAULT 0,
                        initiator_prepared TINYINT(1) NOT NULL DEFAULT 0,
                        target_prepared TINYINT(1) NOT NULL DEFAULT 0,
                        initiator_delivered TINYINT(1) NOT NULL DEFAULT 0,
                        target_delivered TINYINT(1) NOT NULL DEFAULT 0,
                        initiator_returned TINYINT(1) NOT NULL DEFAULT 0,
                        target_returned TINYINT(1) NOT NULL DEFAULT 0,
                        error_text TEXT NOT NULL,
                        expires_at TIMESTAMP(3) NOT NULL,
                        created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                        KEY idx_trades_initiator (initiator_uuid, status),
                        KEY idx_trades_target (target_uuid, status),
                        KEY idx_trades_status (status, updated_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS cluster_trade_audit (
                        audit_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        trade_id CHAR(36) NOT NULL,
                        event_type VARCHAR(32) NOT NULL,
                        node_id VARCHAR(64) NOT NULL,
                        actor_uuid CHAR(36) NULL,
                        actor_name VARCHAR(64) NULL,
                        details LONGTEXT NOT NULL,
                        suspicious TINYINT(1) NOT NULL DEFAULT 0,
                        created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        KEY idx_trade_audit_trade (trade_id, audit_id),
                        KEY idx_trade_audit_actor (actor_uuid, audit_id)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        }
    }

    void heartbeat(UUID uuid, String name, String nodeId, int tierIndex, boolean online) throws SQLException {
        if (!online) {
            try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                    UPDATE cluster_trade_players
                    SET online = 0, heartbeat_at = CURRENT_TIMESTAMP(3)
                    WHERE player_uuid = ? AND node_id = ?
                    """)) {
                statement.setString(1, uuid.toString());
                statement.setString(2, truncate(nodeId, 64));
                statement.executeUpdate();
            }
            return;
        }
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO cluster_trade_players (
                    player_uuid, player_name, node_id, tier_index, online, heartbeat_at
                ) VALUES (?, ?, ?, ?, 1, CURRENT_TIMESTAMP(3))
                ON DUPLICATE KEY UPDATE
                    player_name = VALUES(player_name),
                    node_id = VALUES(node_id),
                    tier_index = VALUES(tier_index),
                    online = 1,
                    heartbeat_at = CURRENT_TIMESTAMP(3)
                """)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, truncate(name, 64));
            statement.setString(3, truncate(nodeId, 64));
            statement.setInt(4, tierIndex);
            statement.executeUpdate();
        }
    }

    Optional<PlayerPresence> findOnlinePlayer(String name) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT player_uuid, player_name, node_id, tier_index
                FROM cluster_trade_players
                WHERE LOWER(player_name) = LOWER(?)
                  AND online = 1
                  AND heartbeat_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 45 SECOND)
                ORDER BY heartbeat_at DESC
                LIMIT 1
                """)) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PlayerPresence(
                        UUID.fromString(resultSet.getString("player_uuid")),
                        resultSet.getString("player_name"),
                        resultSet.getString("node_id"),
                        resultSet.getInt("tier_index")
                ));
            }
        }
    }

    Optional<PlayerPresence> findPlayerByName(String name) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT player_uuid, player_name, node_id, tier_index
                FROM cluster_trade_players
                WHERE LOWER(player_name) = LOWER(?)
                ORDER BY heartbeat_at DESC LIMIT 1
                """)) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PlayerPresence(
                        UUID.fromString(resultSet.getString("player_uuid")),
                        resultSet.getString("player_name"),
                        resultSet.getString("node_id"),
                        resultSet.getInt("tier_index")
                ));
            }
        }
    }

    Optional<PlayerPresence> findPlayer(UUID uuid) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT player_uuid, player_name, node_id, tier_index
                FROM cluster_trade_players WHERE player_uuid = ? LIMIT 1
                """)) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PlayerPresence(
                        UUID.fromString(resultSet.getString("player_uuid")),
                        resultSet.getString("player_name"),
                        resultSet.getString("node_id"),
                        resultSet.getInt("tier_index")
                ));
            }
        }
    }

    UUID createInvite(PlayerPresence initiator, PlayerPresence target, int ttlSeconds) throws SQLException {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                if (hasActiveTrade(connection, initiator.uuid()) || hasActiveTrade(connection, target.uuid())) {
                    throw new SQLException("Один из игроков уже участвует в другой сделке");
                }
                UUID tradeId = UUID.randomUUID();
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO cluster_trades (
                            trade_id, initiator_uuid, initiator_name, initiator_node,
                            target_uuid, target_name, target_node, status,
                            initiator_offer, target_offer, expires_at, error_text
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 'INVITED', ?, ?, ?, '')
                        """)) {
                    statement.setString(1, tradeId.toString());
                    statement.setString(2, initiator.uuid().toString());
                    statement.setString(3, truncate(initiator.name(), 64));
                    statement.setString(4, truncate(initiator.nodeId(), 64));
                    statement.setString(5, target.uuid().toString());
                    statement.setString(6, truncate(target.name(), 64));
                    statement.setString(7, truncate(target.nodeId(), 64));
                    statement.setString(8, TradeItemCodec.encode(List.of()));
                    statement.setString(9, TradeItemCodec.encode(List.of()));
                    statement.setTimestamp(10, Timestamp.from(Instant.now().plusSeconds(Math.max(30, ttlSeconds))));
                    statement.executeUpdate();
                }
                connection.commit();
                return tradeId;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    Optional<TradeRecord> pendingInvite(UUID targetUuid, String initiatorName) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM cluster_trades
                WHERE target_uuid = ? AND status = 'INVITED'
                  AND expires_at > CURRENT_TIMESTAMP(3)
                  AND LOWER(initiator_name) = LOWER(?)
                ORDER BY created_at DESC LIMIT 1
                """)) {
            statement.setString(1, targetUuid.toString());
            statement.setString(2, initiatorName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(read(resultSet)) : Optional.empty();
            }
        }
    }

    boolean accept(UUID tradeId, UUID targetUuid, String targetNode) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE cluster_trades
                SET status = 'OPEN', target_node = ?, expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)
                WHERE trade_id = ? AND target_uuid = ? AND status = 'INVITED'
                  AND expires_at > CURRENT_TIMESTAMP(3)
                """)) {
            statement.setString(1, truncate(targetNode, 64));
            statement.setString(2, tradeId.toString());
            statement.setString(3, targetUuid.toString());
            return statement.executeUpdate() == 1;
        }
    }

    boolean deny(UUID tradeId, UUID targetUuid) throws SQLException {
        return terminalUpdate(tradeId, targetUuid, "DENIED", "Приглашение отклонено");
    }

    boolean cancel(UUID tradeId, UUID actorUuid, String reason) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE cluster_trades SET status = 'CANCELLED', error_text = ?
                WHERE trade_id = ? AND (initiator_uuid = ? OR target_uuid = ?)
                  AND status IN ('INVITED', 'OPEN')
                """)) {
            statement.setString(1, truncate(reason, 1000));
            statement.setString(2, tradeId.toString());
            statement.setString(3, actorUuid.toString());
            statement.setString(4, actorUuid.toString());
            return statement.executeUpdate() == 1;
        }
    }

    private boolean terminalUpdate(UUID tradeId, UUID actorUuid, String status, String reason) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE cluster_trades SET status = ?, error_text = ?
                WHERE trade_id = ? AND target_uuid = ? AND status = 'INVITED'
                """)) {
            statement.setString(1, status);
            statement.setString(2, reason);
            statement.setString(3, tradeId.toString());
            statement.setString(4, actorUuid.toString());
            return statement.executeUpdate() == 1;
        }
    }

    Optional<TradeRecord> findActive(UUID playerUuid) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT * FROM cluster_trades
                WHERE (initiator_uuid = ? OR target_uuid = ?)
                  AND status IN ('INVITED','OPEN','PREPARING','SETTLING','COMMITTING','CANCELLED')
                ORDER BY updated_at DESC LIMIT 1
                """)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(read(resultSet)) : Optional.empty();
            }
        }
    }

    Optional<TradeRecord> find(UUID tradeId) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM cluster_trades WHERE trade_id = ? LIMIT 1")) {
            statement.setString(1, tradeId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(read(resultSet)) : Optional.empty();
            }
        }
    }

    boolean updateOffer(UUID tradeId, TradeSide side, List<ItemStack> items, long currency) throws SQLException {
        String offerColumn = side == TradeSide.INITIATOR ? "initiator_offer" : "target_offer";
        String currencyColumn = side == TradeSide.INITIATOR ? "initiator_currency" : "target_currency";
        String sql = "UPDATE cluster_trades SET " + offerColumn + " = ?, " + currencyColumn
                + " = ?, initiator_ready = 0, target_ready = 0 WHERE trade_id = ? AND status = 'OPEN'";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, TradeItemCodec.encode(items));
            statement.setLong(2, Math.max(0L, currency));
            statement.setString(3, tradeId.toString());
            return statement.executeUpdate() == 1;
        }
    }

    boolean setReady(UUID tradeId, TradeSide side, boolean ready) throws SQLException {
        String column = side == TradeSide.INITIATOR ? "initiator_ready" : "target_ready";
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE cluster_trades SET " + column + " = ? WHERE trade_id = ? AND status = 'OPEN'")) {
                    statement.setBoolean(1, ready);
                    statement.setString(2, tradeId.toString());
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE cluster_trades SET status = 'PREPARING'
                        WHERE trade_id = ? AND status = 'OPEN'
                          AND initiator_ready = 1 AND target_ready = 1
                        """)) {
                    statement.setString(1, tradeId.toString());
                    statement.executeUpdate();
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    boolean markPrepared(UUID tradeId, TradeSide side) throws SQLException {
        String column = side == TradeSide.INITIATOR ? "initiator_prepared" : "target_prepared";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE cluster_trades SET " + column + " = 1 WHERE trade_id = ? AND status IN ('PREPARING','CANCELLED') AND " + column + " = 0")) {
            statement.setString(1, tradeId.toString());
            return statement.executeUpdate() == 1;
        }
    }

    boolean claimSettlement(UUID tradeId) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE cluster_trades SET status = 'SETTLING'
                WHERE trade_id = ? AND status = 'PREPARING'
                  AND initiator_prepared = 1 AND target_prepared = 1
                """)) {
            statement.setString(1, tradeId.toString());
            return statement.executeUpdate() == 1;
        }
    }

    void markCommitting(UUID tradeId) throws SQLException {
        updateStatus(tradeId, "COMMITTING", "");
    }

    void markCancelled(UUID tradeId, String error) throws SQLException {
        updateStatus(tradeId, "CANCELLED", error);
    }

    boolean markDelivered(UUID tradeId, TradeSide side) throws SQLException {
        String column = side == TradeSide.INITIATOR ? "initiator_delivered" : "target_delivered";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE cluster_trades SET " + column + " = 1 WHERE trade_id = ? AND status = 'COMMITTING' AND " + column + " = 0")) {
            statement.setString(1, tradeId.toString());
            return statement.executeUpdate() == 1;
        }
    }

    boolean markReturned(UUID tradeId, TradeSide side) throws SQLException {
        String column = side == TradeSide.INITIATOR ? "initiator_returned" : "target_returned";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE cluster_trades SET " + column + " = 1 WHERE trade_id = ? AND status = 'CANCELLED' AND " + column + " = 0")) {
            statement.setString(1, tradeId.toString());
            return statement.executeUpdate() == 1;
        }
    }

    void finishIfComplete(UUID tradeId) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE cluster_trades SET status = 'COMPLETED'
                WHERE trade_id = ? AND status = 'COMMITTING'
                  AND initiator_delivered = 1 AND target_delivered = 1
                """)) {
            statement.setString(1, tradeId.toString());
            statement.executeUpdate();
        }
    }

    void finishCancelledIfReturned(UUID tradeId) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE cluster_trades SET status = 'EXPIRED'
                WHERE trade_id = ? AND status = 'CANCELLED'
                  AND (initiator_prepared = 0 OR initiator_returned = 1)
                  AND (target_prepared = 0 OR target_returned = 1)
                """)) {
            statement.setString(1, tradeId.toString());
            statement.executeUpdate();
        }
    }

    int expireInvites() throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE cluster_trades SET status = 'EXPIRED', error_text = 'Срок приглашения истёк'
                WHERE status = 'INVITED' AND expires_at <= CURRENT_TIMESTAMP(3)
                """)) {
            return statement.executeUpdate();
        }
    }

    void audit(UUID tradeId, String eventType, String nodeId, UUID actorUuid, String actorName, String details, boolean suspicious) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO cluster_trade_audit (
                    trade_id, event_type, node_id, actor_uuid, actor_name, details, suspicious
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, tradeId.toString());
            statement.setString(2, truncate(eventType, 32));
            statement.setString(3, truncate(nodeId, 64));
            if (actorUuid == null) {
                statement.setNull(4, java.sql.Types.CHAR);
            } else {
                statement.setString(4, actorUuid.toString());
            }
            if (actorName == null || actorName.isBlank()) {
                statement.setNull(5, java.sql.Types.VARCHAR);
            } else {
                statement.setString(5, truncate(actorName, 64));
            }
            statement.setString(6, details == null ? "" : details);
            statement.setBoolean(7, suspicious);
            statement.executeUpdate();
        }
    }

    List<String> history(UUID playerUuid, int limit) throws SQLException {
        ArrayList<String> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT trade_id, initiator_name, target_name, status, updated_at
                FROM cluster_trades
                WHERE initiator_uuid = ? OR target_uuid = ?
                ORDER BY updated_at DESC LIMIT ?
                """)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerUuid.toString());
            statement.setInt(3, Math.max(1, Math.min(50, limit)));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getString("trade_id") + " | " + rs.getString("initiator_name")
                            + " <-> " + rs.getString("target_name") + " | " + rs.getString("status")
                            + " | " + rs.getTimestamp("updated_at"));
                }
            }
        }
        return result;
    }

    private void updateStatus(UUID tradeId, String status, String error) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE cluster_trades SET status = ?, error_text = ? WHERE trade_id = ?")) {
            statement.setString(1, status);
            statement.setString(2, truncate(error, 1000));
            statement.setString(3, tradeId.toString());
            statement.executeUpdate();
        }
    }

    private boolean hasActiveTrade(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM cluster_trades
                WHERE (initiator_uuid = ? OR target_uuid = ?)
                  AND status IN ('INVITED','OPEN','PREPARING','SETTLING','COMMITTING','CANCELLED')
                LIMIT 1
                """)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private TradeRecord read(ResultSet rs) throws SQLException {
        return new TradeRecord(
                UUID.fromString(rs.getString("trade_id")),
                UUID.fromString(rs.getString("initiator_uuid")),
                rs.getString("initiator_name"),
                rs.getString("initiator_node"),
                UUID.fromString(rs.getString("target_uuid")),
                rs.getString("target_name"),
                rs.getString("target_node"),
                TradeStatus.valueOf(rs.getString("status").toUpperCase(Locale.ROOT)),
                TradeItemCodec.decode(rs.getString("initiator_offer"), TradeService.OFFER_SLOTS),
                TradeItemCodec.decode(rs.getString("target_offer"), TradeService.OFFER_SLOTS),
                rs.getLong("initiator_currency"),
                rs.getLong("target_currency"),
                rs.getBoolean("initiator_ready"),
                rs.getBoolean("target_ready"),
                rs.getBoolean("initiator_prepared"),
                rs.getBoolean("target_prepared"),
                rs.getBoolean("initiator_delivered"),
                rs.getBoolean("target_delivered"),
                rs.getBoolean("initiator_returned"),
                rs.getBoolean("target_returned"),
                rs.getString("error_text"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at"))
        );
    }

    private Connection open() throws SQLException {
        ensureDriver();
        return DriverManager.getConnection(config.jdbcUrl(), config.username(), config.password());
    }

    private static synchronized void ensureDriver() throws SQLException {
        if (driverLoaded) {
            return;
        }
        try {
            Class.forName(SHADED_DRIVER);
        } catch (ClassNotFoundException shadedMissing) {
            try {
                Class.forName(DEV_DRIVER);
            } catch (ClassNotFoundException devMissing) {
                throw new SQLException("MySQL JDBC driver is unavailable", devMissing);
            }
        }
        driverLoaded = true;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.EPOCH : timestamp.toInstant();
    }

    private static String truncate(String value, int maximum) {
        if (value == null) {
            return "";
        }
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    record PlayerPresence(UUID uuid, String name, String nodeId, int tierIndex) {
    }
}
