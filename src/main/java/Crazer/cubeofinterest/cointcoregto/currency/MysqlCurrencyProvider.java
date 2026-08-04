package Crazer.cubeofinterest.cointcoregto.currency;

import Crazer.cubeofinterest.cointcoregto.ClusterConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

public final class MysqlCurrencyProvider implements CurrencyProvider {
    public static final String ID = "cointcoregto:mysql";

    private static final String SHADED_MYSQL_DRIVER =
            "crazer.cubeofinterest.cointcoregto.shadow.mysql.cj.jdbc.Driver";
    private static final String DEVELOPMENT_MYSQL_DRIVER =
            "com.mysql.cj.jdbc.Driver";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private volatile ClusterConfig clusterConfig;
    private volatile CurrencyDescriptor descriptor;
    private volatile boolean initialized;

    @Override
    public String providerId() {
        return ID;
    }

    @Override
    public CurrencyDescriptor descriptor() {
        CurrencyDescriptor current = descriptor;
        return current == null ? CurrencyConfig.descriptor() : current;
    }

    @Override
    public synchronized void initialize() throws Exception {
        ensureDriverLoaded();
        clusterConfig = ClusterConfig.load();
        descriptor = CurrencyConfig.descriptor();
        try (Connection connection = open()) {
            createTables(connection);
        }
        initialized = true;
    }

    @Override
    public synchronized void close() {
        initialized = false;
        clusterConfig = null;
    }

    @Override
    public CurrencyBalance getBalance(UUID playerUuid) throws Exception {
        requireReady();
        Objects.requireNonNull(playerUuid, "playerUuid");
        try (Connection connection = open()) {
            ensureAccount(connection, playerUuid);
            return CurrencyBalance.success(readBalance(connection, playerUuid, false));
        }
    }

    @Override
    public CurrencyOperationResult credit(
            UUID playerUuid,
            long amount,
            UUID operationId,
            CurrencyContext context
    ) throws Exception {
        validateAmount(amount);
        return executeBalanceOperation(
                "CREDIT",
                null,
                playerUuid,
                null,
                amount,
                operationId,
                context
        );
    }

    @Override
    public CurrencyOperationResult debit(
            UUID playerUuid,
            long amount,
            UUID operationId,
            CurrencyContext context
    ) throws Exception {
        validateAmount(amount);
        return executeBalanceOperation(
                "DEBIT",
                playerUuid,
                null,
                null,
                amount,
                operationId,
                context
        );
    }

    @Override
    public CurrencyOperationResult transfer(
            UUID sourcePlayerUuid,
            UUID targetPlayerUuid,
            long amount,
            UUID operationId,
            CurrencyContext context
    ) throws Exception {
        validateAmount(amount);
        if (sourcePlayerUuid.equals(targetPlayerUuid)) {
            return CurrencyOperationResult.failure(operationId, "SAME_ACCOUNT", "Источник и получатель совпадают");
        }
        return executeBalanceOperation(
                "TRANSFER",
                sourcePlayerUuid,
                targetPlayerUuid,
                null,
                amount,
                operationId,
                context
        );
    }

    @Override
    public CurrencyOperationResult hold(
            UUID playerUuid,
            long amount,
            UUID holdId,
            Instant expiresAt,
            CurrencyContext context
    ) throws Exception {
        requireReady();
        validateAmount(amount);
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(expiresAt, "expiresAt");

        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                CurrencyOperationResult existing = readOperation(
                        connection,
                        holdId,
                        "HOLD",
                        playerUuid,
                        null,
                        holdId,
                        amount
                );
                if (existing != null) {
                    connection.commit();
                    return existing;
                }

                insertOperation(
                        connection,
                        holdId,
                        "HOLD",
                        playerUuid,
                        null,
                        holdId,
                        amount,
                        context
                );
                ensureAccount(connection, playerUuid);
                long balance = readBalance(connection, playerUuid, true);
                if (balance < amount) {
                    updateOperation(
                            connection,
                            holdId,
                            "REJECTED",
                            "INSUFFICIENT_FUNDS",
                            "Недостаточно средств",
                            balance,
                            0L
                    );
                    connection.commit();
                    return new CurrencyOperationResult(
                            false,
                            false,
                            "INSUFFICIENT_FUNDS",
                            "Недостаточно средств",
                            holdId,
                            balance,
                            0L
                    );
                }

                long newBalance = balance - amount;
                updateBalance(connection, playerUuid, newBalance);
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO coint_currency_holds (
                            hold_id, provider_id, currency_id, owner_uuid, amount,
                            status, expires_at, context_json, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, ?, 'RESERVED', ?, ?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                        """)) {
                    statement.setString(1, holdId.toString());
                    statement.setString(2, providerId());
                    statement.setString(3, descriptor().currencyId());
                    statement.setString(4, playerUuid.toString());
                    statement.setLong(5, amount);
                    statement.setTimestamp(6, Timestamp.from(expiresAt));
                    statement.setString(7, contextJson(context));
                    statement.executeUpdate();
                }
                updateOperation(connection, holdId, "COMPLETED", "OK", "", newBalance, 0L);
                connection.commit();
                return new CurrencyOperationResult(true, false, "OK", "", holdId, newBalance, 0L);
            } catch (Exception exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    @Override
    public CurrencyOperationResult capture(
            UUID holdId,
            UUID targetPlayerUuid,
            UUID operationId,
            CurrencyContext context
    ) throws Exception {
        requireReady();
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(targetPlayerUuid, "targetPlayerUuid");
        Objects.requireNonNull(operationId, "operationId");

        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                HoldRow hold = readHold(connection, holdId, true);
                if (hold == null) {
                    connection.commit();
                    return CurrencyOperationResult.failure(operationId, "HOLD_NOT_FOUND", "Резерв не найден");
                }

                CurrencyOperationResult existing = readOperation(
                        connection,
                        operationId,
                        "CAPTURE",
                        hold.ownerUuid(),
                        targetPlayerUuid,
                        holdId,
                        hold.amount()
                );
                if (existing != null) {
                    connection.commit();
                    return existing;
                }

                insertOperation(
                        connection,
                        operationId,
                        "CAPTURE",
                        hold.ownerUuid(),
                        targetPlayerUuid,
                        holdId,
                        hold.amount(),
                        context
                );

                if (!"RESERVED".equals(hold.status())) {
                    String code = "CAPTURED".equals(hold.status()) ? "HOLD_ALREADY_CAPTURED" : "HOLD_NOT_RESERVED";
                    String message = "CAPTURED".equals(hold.status())
                            ? "Резерв уже списан"
                            : "Резерв уже освобождён";
                    updateOperation(connection, operationId, "REJECTED", code, message, 0L, 0L);
                    connection.commit();
                    return new CurrencyOperationResult(false, false, code, message, operationId, 0L, 0L);
                }

                ensureAccount(connection, targetPlayerUuid);
                long targetBalance = readBalance(connection, targetPlayerUuid, true);
                long targetReserved = readReservedAmount(connection, targetPlayerUuid);
                if (targetBalance > descriptor().maximumBalance() - targetReserved - hold.amount()) {
                    updateOperation(
                            connection,
                            operationId,
                            "REJECTED",
                            "MAXIMUM_BALANCE",
                            "Баланс получателя достиг максимума",
                            0L,
                            targetBalance
                    );
                    connection.commit();
                    return new CurrencyOperationResult(
                            false,
                            false,
                            "MAXIMUM_BALANCE",
                            "Баланс получателя достиг максимума",
                            operationId,
                            0L,
                            targetBalance
                    );
                }

                long newTargetBalance = targetBalance + hold.amount();
                updateBalance(connection, targetPlayerUuid, newTargetBalance);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE coint_currency_holds
                        SET status = 'CAPTURED', recipient_uuid = ?, captured_operation_id = ?,
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE hold_id = ? AND status = 'RESERVED'
                        """)) {
                    statement.setString(1, targetPlayerUuid.toString());
                    statement.setString(2, operationId.toString());
                    statement.setString(3, holdId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Currency hold capture lost optimistic lock");
                    }
                }
                updateOperation(connection, operationId, "COMPLETED", "OK", "", 0L, newTargetBalance);
                connection.commit();
                return new CurrencyOperationResult(
                        true,
                        false,
                        "OK",
                        "",
                        operationId,
                        0L,
                        newTargetBalance
                );
            } catch (Exception exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    @Override
    public CurrencyOperationResult settle(
            UUID holdId,
            List<CurrencySettlementEntry> entries,
            UUID operationId,
            CurrencyContext context
    ) throws Exception {
        requireReady();
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(operationId, "operationId");

        TreeMap<String, Long> credits = new TreeMap<>();
        if (entries != null) {
            for (CurrencySettlementEntry entry : entries) {
                if (entry == null || entry.recipientUuid() == null || entry.amount() <= 0L) {
                    throw new IllegalArgumentException("settlement entry");
                }
                credits.merge(
                        entry.recipientUuid().toString(),
                        entry.amount(),
                        Math::addExact
                );
            }
        }

        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                HoldRow hold = readHold(connection, holdId, true);
                if (hold == null) {
                    connection.commit();
                    return CurrencyOperationResult.failure(operationId, "HOLD_NOT_FOUND", "Резерв не найден");
                }

                long creditedAmount = 0L;
                for (long amount : credits.values()) {
                    creditedAmount = Math.addExact(creditedAmount, amount);
                }
                if (creditedAmount > hold.amount()) {
                    connection.commit();
                    return CurrencyOperationResult.failure(
                            operationId,
                            "SETTLEMENT_AMOUNT_MISMATCH",
                            "Сумма распределения превышает резерв"
                    );
                }

                long burnedAmount = hold.amount() - creditedAmount;
                String fingerprint = settlementFingerprint(credits, burnedAmount);
                CurrencyOperationResult existing = readOperation(
                        connection,
                        operationId,
                        "SETTLE",
                        hold.ownerUuid(),
                        null,
                        holdId,
                        hold.amount(),
                        fingerprint
                );
                if (existing != null) {
                    connection.commit();
                    return existing;
                }

                insertOperation(
                        connection,
                        operationId,
                        "SETTLE",
                        hold.ownerUuid(),
                        null,
                        holdId,
                        hold.amount(),
                        context,
                        fingerprint
                );

                if (!"RESERVED".equals(hold.status())) {
                    String code = "CAPTURED".equals(hold.status())
                            ? "HOLD_ALREADY_CAPTURED"
                            : "HOLD_NOT_RESERVED";
                    String message = "CAPTURED".equals(hold.status())
                            ? "Резерв уже списан"
                            : "Резерв уже освобождён";
                    updateOperation(connection, operationId, "REJECTED", code, message, 0L, 0L);
                    connection.commit();
                    return new CurrencyOperationResult(false, false, code, message, operationId, 0L, 0L);
                }

                TreeMap<String, Long> balances = new TreeMap<>();
                for (String recipient : credits.keySet()) {
                    UUID recipientUuid = UUID.fromString(recipient);
                    ensureAccount(connection, recipientUuid);
                }
                for (String recipient : credits.keySet()) {
                    UUID recipientUuid = UUID.fromString(recipient);
                    long balance = readBalance(connection, recipientUuid, true);
                    long reserved = readReservedAmountExcludingHold(connection, recipientUuid, holdId);
                    long credit = credits.get(recipient);
                    long maximum = descriptor().maximumBalance();
                    if (reserved > maximum
                            || credit > maximum - reserved
                            || balance > maximum - reserved - credit) {
                        updateOperation(
                                connection,
                                operationId,
                                "REJECTED",
                                "MAXIMUM_BALANCE",
                                "Баланс одного из получателей достиг максимума",
                                0L,
                                balance
                        );
                        connection.commit();
                        return new CurrencyOperationResult(
                                false,
                                false,
                                "MAXIMUM_BALANCE",
                                "Баланс одного из получателей достиг максимума",
                                operationId,
                                0L,
                                balance
                        );
                    }
                    balances.put(recipient, balance);
                }

                long firstTargetBalance = 0L;
                boolean first = true;
                for (Map.Entry<String, Long> entry : credits.entrySet()) {
                    UUID recipientUuid = UUID.fromString(entry.getKey());
                    long newBalance = Math.addExact(balances.get(entry.getKey()), entry.getValue());
                    updateBalance(connection, recipientUuid, newBalance);
                    if (first) {
                        firstTargetBalance = newBalance;
                        first = false;
                    }
                }

                UUID singleRecipient = credits.size() == 1 && burnedAmount == 0L
                        ? UUID.fromString(credits.firstKey())
                        : null;
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE coint_currency_holds
                        SET status = 'CAPTURED', recipient_uuid = ?, captured_operation_id = ?,
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE hold_id = ? AND status = 'RESERVED'
                        """)) {
                    setUuid(statement, 1, singleRecipient);
                    statement.setString(2, operationId.toString());
                    statement.setString(3, holdId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Currency settlement lost optimistic lock");
                    }
                }

                updateOperation(
                        connection,
                        operationId,
                        "COMPLETED",
                        "OK",
                        burnedAmount > 0L ? "burned=" + burnedAmount : "",
                        0L,
                        firstTargetBalance
                );
                connection.commit();
                return new CurrencyOperationResult(
                        true,
                        false,
                        "OK",
                        burnedAmount > 0L ? "burned=" + burnedAmount : "",
                        operationId,
                        0L,
                        firstTargetBalance
                );
            } catch (Exception exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    @Override
    public CurrencyOperationResult release(
            UUID holdId,
            UUID operationId,
            CurrencyContext context
    ) throws Exception {
        requireReady();
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(operationId, "operationId");

        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                HoldRow hold = readHold(connection, holdId, true);
                if (hold == null) {
                    connection.commit();
                    return CurrencyOperationResult.failure(operationId, "HOLD_NOT_FOUND", "Резерв не найден");
                }

                CurrencyOperationResult existing = readOperation(
                        connection,
                        operationId,
                        "RELEASE",
                        hold.ownerUuid(),
                        null,
                        holdId,
                        hold.amount()
                );
                if (existing != null) {
                    connection.commit();
                    return existing;
                }

                insertOperation(
                        connection,
                        operationId,
                        "RELEASE",
                        hold.ownerUuid(),
                        null,
                        holdId,
                        hold.amount(),
                        context
                );

                if (!"RESERVED".equals(hold.status())) {
                    String code = "RELEASED".equals(hold.status()) ? "HOLD_ALREADY_RELEASED" : "HOLD_ALREADY_CAPTURED";
                    String message = "RELEASED".equals(hold.status())
                            ? "Резерв уже освобождён"
                            : "Резерв уже списан";
                    updateOperation(connection, operationId, "REJECTED", code, message, 0L, 0L);
                    connection.commit();
                    return new CurrencyOperationResult(false, false, code, message, operationId, 0L, 0L);
                }

                ensureAccount(connection, hold.ownerUuid());
                long balance = readBalance(connection, hold.ownerUuid(), true);
                long newBalance = Math.addExact(balance, hold.amount());
                updateBalance(connection, hold.ownerUuid(), newBalance);
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE coint_currency_holds
                        SET status = 'RELEASED', released_operation_id = ?,
                            updated_at = CURRENT_TIMESTAMP(3)
                        WHERE hold_id = ? AND status = 'RESERVED'
                        """)) {
                    statement.setString(1, operationId.toString());
                    statement.setString(2, holdId.toString());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Currency hold release lost optimistic lock");
                    }
                }
                updateOperation(connection, operationId, "COMPLETED", "OK", "", newBalance, 0L);
                connection.commit();
                return new CurrencyOperationResult(true, false, "OK", "", operationId, newBalance, 0L);
            } catch (Exception exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    @Override
    public int releaseExpiredHolds(int maximumCount, CurrencyContext context) throws Exception {
        requireReady();
        int limit = Math.max(1, Math.min(1000, maximumCount));
        List<UUID> ids = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT hold_id
                     FROM coint_currency_holds
                     WHERE provider_id = ? AND currency_id = ? AND status = 'RESERVED'
                       AND expires_at <= CURRENT_TIMESTAMP(3)
                     ORDER BY expires_at
                     LIMIT ?
                     """)) {
            statement.setString(1, providerId());
            statement.setString(2, descriptor().currencyId());
            statement.setInt(3, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    ids.add(UUID.fromString(resultSet.getString(1)));
                }
            }
        }

        int released = 0;
        for (UUID holdId : ids) {
            CurrencyOperationResult result = release(holdId, UUID.randomUUID(), context);
            if (result.success()) {
                released++;
            }
        }
        return released;
    }

    private CurrencyOperationResult executeBalanceOperation(
            String type,
            UUID sourceUuid,
            UUID targetUuid,
            UUID holdId,
            long amount,
            UUID operationId,
            CurrencyContext context
    ) throws Exception {
        requireReady();
        Objects.requireNonNull(operationId, "operationId");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                CurrencyOperationResult existing = readOperation(
                        connection,
                        operationId,
                        type,
                        sourceUuid,
                        targetUuid,
                        holdId,
                        amount
                );
                if (existing != null) {
                    connection.commit();
                    return existing;
                }

                insertOperation(connection, operationId, type, sourceUuid, targetUuid, holdId, amount, context);
                if (sourceUuid != null) {
                    ensureAccount(connection, sourceUuid);
                }
                if (targetUuid != null) {
                    ensureAccount(connection, targetUuid);
                }

                lockAccounts(connection, sourceUuid, targetUuid);
                long sourceBalance = sourceUuid == null ? 0L : readBalance(connection, sourceUuid, false);
                long targetBalance = targetUuid == null ? 0L : readBalance(connection, targetUuid, false);

                if (sourceUuid != null && sourceBalance < amount) {
                    updateOperation(
                            connection,
                            operationId,
                            "REJECTED",
                            "INSUFFICIENT_FUNDS",
                            "Недостаточно средств",
                            sourceBalance,
                            targetBalance
                    );
                    connection.commit();
                    return new CurrencyOperationResult(
                            false,
                            false,
                            "INSUFFICIENT_FUNDS",
                            "Недостаточно средств",
                            operationId,
                            sourceBalance,
                            targetBalance
                    );
                }

                long targetReserved = targetUuid == null ? 0L : readReservedAmount(connection, targetUuid);
                if (targetUuid != null && targetBalance > descriptor().maximumBalance() - targetReserved - amount) {
                    updateOperation(
                            connection,
                            operationId,
                            "REJECTED",
                            "MAXIMUM_BALANCE",
                            "Баланс получателя достиг максимума",
                            sourceBalance,
                            targetBalance
                    );
                    connection.commit();
                    return new CurrencyOperationResult(
                            false,
                            false,
                            "MAXIMUM_BALANCE",
                            "Баланс получателя достиг максимума",
                            operationId,
                            sourceBalance,
                            targetBalance
                    );
                }

                long newSourceBalance = sourceUuid == null ? 0L : sourceBalance - amount;
                long newTargetBalance = targetUuid == null ? 0L : targetBalance + amount;
                if (sourceUuid != null) {
                    updateBalance(connection, sourceUuid, newSourceBalance);
                }
                if (targetUuid != null) {
                    updateBalance(connection, targetUuid, newTargetBalance);
                }
                updateOperation(
                        connection,
                        operationId,
                        "COMPLETED",
                        "OK",
                        "",
                        newSourceBalance,
                        newTargetBalance
                );
                connection.commit();
                return new CurrencyOperationResult(
                        true,
                        false,
                        "OK",
                        "",
                        operationId,
                        newSourceBalance,
                        newTargetBalance
                );
            } catch (Exception exception) {
                rollbackQuietly(connection);
                throw exception;
            } finally {
                restoreAutoCommit(connection);
            }
        }
    }

    private CurrencyOperationResult readOperation(
            Connection connection,
            UUID operationId,
            String expectedType,
            UUID expectedSource,
            UUID expectedTarget,
            UUID expectedHold,
            long expectedAmount
    ) throws SQLException {
        return readOperation(
                connection,
                operationId,
                expectedType,
                expectedSource,
                expectedTarget,
                expectedHold,
                expectedAmount,
                ""
        );
    }

    private CurrencyOperationResult readOperation(
            Connection connection,
            UUID operationId,
            String expectedType,
            UUID expectedSource,
            UUID expectedTarget,
            UUID expectedHold,
            long expectedAmount,
            String expectedFingerprint
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type, source_uuid, target_uuid, hold_id, amount, operation_fingerprint,
                       status, result_code, result_message, source_balance, target_balance
                FROM coint_currency_operations
                WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                String type = resultSet.getString("operation_type");
                UUID source = readUuid(resultSet, "source_uuid");
                UUID target = readUuid(resultSet, "target_uuid");
                UUID hold = readUuid(resultSet, "hold_id");
                long amount = resultSet.getLong("amount");
                String fingerprint = resultSet.getString("operation_fingerprint");
                if (!expectedType.equals(type)
                        || !Objects.equals(expectedSource, source)
                        || !Objects.equals(expectedTarget, target)
                        || !Objects.equals(expectedHold, hold)
                        || expectedAmount != amount
                        || !Objects.equals(expectedFingerprint == null ? "" : expectedFingerprint,
                        fingerprint == null ? "" : fingerprint)) {
                    return new CurrencyOperationResult(
                            false,
                            true,
                            "IDEMPOTENCY_CONFLICT",
                            "Идентификатор операции уже использован с другими параметрами",
                            operationId,
                            0L,
                            0L
                    );
                }
                String status = resultSet.getString("status");
                return new CurrencyOperationResult(
                        "COMPLETED".equals(status),
                        true,
                        resultSet.getString("result_code"),
                        resultSet.getString("result_message"),
                        operationId,
                        resultSet.getLong("source_balance"),
                        resultSet.getLong("target_balance")
                );
            }
        }
    }

    private void insertOperation(
            Connection connection,
            UUID operationId,
            String type,
            UUID sourceUuid,
            UUID targetUuid,
            UUID holdId,
            long amount,
            CurrencyContext context
    ) throws SQLException {
        insertOperation(
                connection,
                operationId,
                type,
                sourceUuid,
                targetUuid,
                holdId,
                amount,
                context,
                ""
        );
    }

    private void insertOperation(
            Connection connection,
            UUID operationId,
            String type,
            UUID sourceUuid,
            UUID targetUuid,
            UUID holdId,
            long amount,
            CurrencyContext context,
            String fingerprint
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coint_currency_operations (
                    operation_id, provider_id, currency_id, operation_type, status,
                    source_uuid, target_uuid, hold_id, amount, operation_fingerprint,
                    actor_uuid, actor_name, node_id, reason, source_type, source_id,
                    context_json, result_code, result_message,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'PROCESSING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '', '',
                          CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """)) {
            statement.setString(1, operationId.toString());
            statement.setString(2, providerId());
            statement.setString(3, descriptor().currencyId());
            statement.setString(4, type);
            setUuid(statement, 5, sourceUuid);
            setUuid(statement, 6, targetUuid);
            setUuid(statement, 7, holdId);
            statement.setLong(8, amount);
            statement.setString(9, truncate(fingerprint, 128));
            setUuid(statement, 10, context == null ? null : context.actorUuid());
            statement.setString(11, truncate(context == null ? "" : context.actorName(), 64));
            statement.setString(12, truncate(context == null ? "" : context.nodeId(), 64));
            statement.setString(13, truncate(context == null ? "" : context.reason(), 255));
            statement.setString(14, truncate(context == null ? "" : context.sourceType(), 64));
            statement.setString(15, truncate(context == null ? "" : context.sourceId(), 255));
            statement.setString(16, contextJson(context));
            statement.executeUpdate();
        }
    }

    private void updateOperation(
            Connection connection,
            UUID operationId,
            String status,
            String code,
            String message,
            long sourceBalance,
            long targetBalance
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE coint_currency_operations
                SET status = ?, result_code = ?, result_message = ?,
                    source_balance = ?, target_balance = ?, updated_at = CURRENT_TIMESTAMP(3)
                WHERE operation_id = ?
                """)) {
            statement.setString(1, status);
            statement.setString(2, truncate(code, 64));
            statement.setString(3, truncate(message, 512));
            statement.setLong(4, sourceBalance);
            statement.setLong(5, targetBalance);
            statement.setString(6, operationId.toString());
            statement.executeUpdate();
        }
    }

    private HoldRow readHold(Connection connection, UUID holdId, boolean forUpdate) throws SQLException {
        String sql = """
                SELECT owner_uuid, amount, status
                FROM coint_currency_holds
                WHERE hold_id = ? AND provider_id = ? AND currency_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, holdId.toString());
            statement.setString(2, providerId());
            statement.setString(3, descriptor().currencyId());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new HoldRow(
                        UUID.fromString(resultSet.getString("owner_uuid")),
                        resultSet.getLong("amount"),
                        resultSet.getString("status")
                );
            }
        }
    }

    private void ensureAccount(Connection connection, UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT IGNORE INTO coint_currency_accounts (
                    provider_id, currency_id, player_uuid, balance, created_at, updated_at
                ) VALUES (?, ?, ?, 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """)) {
            statement.setString(1, providerId());
            statement.setString(2, descriptor().currencyId());
            statement.setString(3, playerUuid.toString());
            statement.executeUpdate();
        }
    }

    private long readBalance(Connection connection, UUID playerUuid, boolean forUpdate) throws SQLException {
        String sql = """
                SELECT balance
                FROM coint_currency_accounts
                WHERE provider_id = ? AND currency_id = ? AND player_uuid = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, providerId());
            statement.setString(2, descriptor().currencyId());
            statement.setString(3, playerUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Currency account disappeared: " + playerUuid);
                }
                return resultSet.getLong(1);
            }
        }
    }

    private void lockAccounts(Connection connection, UUID first, UUID second) throws SQLException {
        if (first == null && second == null) {
            return;
        }
        if (first == null || second == null || first.equals(second)) {
            readBalance(connection, first == null ? second : first, true);
            return;
        }
        UUID lower = first.toString().compareTo(second.toString()) <= 0 ? first : second;
        UUID higher = lower.equals(first) ? second : first;
        readBalance(connection, lower, true);
        readBalance(connection, higher, true);
    }

    private long readReservedAmount(Connection connection, UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(amount), 0)
                FROM coint_currency_holds
                WHERE provider_id = ? AND currency_id = ? AND owner_uuid = ? AND status = 'RESERVED'
                """)) {
            statement.setString(1, providerId());
            statement.setString(2, descriptor().currencyId());
            statement.setString(3, playerUuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    private long readReservedAmountExcludingHold(
            Connection connection,
            UUID playerUuid,
            UUID excludedHoldId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(SUM(amount), 0)
                FROM coint_currency_holds
                WHERE provider_id = ? AND currency_id = ? AND owner_uuid = ?
                  AND status = 'RESERVED' AND hold_id <> ?
                """)) {
            statement.setString(1, providerId());
            statement.setString(2, descriptor().currencyId());
            statement.setString(3, playerUuid.toString());
            statement.setString(4, excludedHoldId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    private static String settlementFingerprint(TreeMap<String, Long> credits, long burnedAmount) {
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, Long> entry : credits.entrySet()) {
            canonical.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        canonical.append("burn=").append(burnedAmount);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void updateBalance(Connection connection, UUID playerUuid, long balance) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE coint_currency_accounts
                SET balance = ?, updated_at = CURRENT_TIMESTAMP(3)
                WHERE provider_id = ? AND currency_id = ? AND player_uuid = ?
                """)) {
            statement.setLong(1, balance);
            statement.setString(2, providerId());
            statement.setString(3, descriptor().currencyId());
            statement.setString(4, playerUuid.toString());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Currency balance update failed: " + playerUuid);
            }
        }
    }

    private Connection open() throws SQLException {
        ClusterConfig config = clusterConfig;
        if (config == null) {
            throw new SQLException("Currency provider is not initialized");
        }
        return DriverManager.getConnection(config.jdbcUrl(), config.username(), config.password());
    }

    private void createTables(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS coint_currency_accounts (
                        provider_id VARCHAR(64) NOT NULL,
                        currency_id VARCHAR(64) NOT NULL,
                        player_uuid CHAR(36) NOT NULL,
                        balance BIGINT NOT NULL DEFAULT 0,
                        created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        PRIMARY KEY (provider_id, currency_id, player_uuid),
                        INDEX idx_currency_accounts_player (player_uuid)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS coint_currency_operations (
                        operation_id CHAR(36) NOT NULL PRIMARY KEY,
                        provider_id VARCHAR(64) NOT NULL,
                        currency_id VARCHAR(64) NOT NULL,
                        operation_type VARCHAR(24) NOT NULL,
                        status VARCHAR(24) NOT NULL,
                        source_uuid CHAR(36) NULL,
                        target_uuid CHAR(36) NULL,
                        hold_id CHAR(36) NULL,
                        amount BIGINT NOT NULL,
                        operation_fingerprint VARCHAR(128) NOT NULL DEFAULT '',
                        actor_uuid CHAR(36) NULL,
                        actor_name VARCHAR(64) NOT NULL DEFAULT '',
                        node_id VARCHAR(64) NOT NULL DEFAULT '',
                        reason VARCHAR(255) NOT NULL DEFAULT '',
                        source_type VARCHAR(64) NOT NULL DEFAULT '',
                        source_id VARCHAR(255) NOT NULL DEFAULT '',
                        context_json LONGTEXT NULL,
                        result_code VARCHAR(64) NOT NULL DEFAULT '',
                        result_message VARCHAR(512) NOT NULL DEFAULT '',
                        source_balance BIGINT NOT NULL DEFAULT 0,
                        target_balance BIGINT NOT NULL DEFAULT 0,
                        created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        INDEX idx_currency_operations_source (source_uuid, created_at),
                        INDEX idx_currency_operations_target (target_uuid, created_at),
                        INDEX idx_currency_operations_hold (hold_id),
                        INDEX idx_currency_operations_status (status, created_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            ensureColumnExists(
                    connection,
                    "coint_currency_operations",
                    "operation_fingerprint",
                    "VARCHAR(128) NOT NULL DEFAULT '' AFTER amount"
            );
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS coint_currency_holds (
                        hold_id CHAR(36) NOT NULL PRIMARY KEY,
                        provider_id VARCHAR(64) NOT NULL,
                        currency_id VARCHAR(64) NOT NULL,
                        owner_uuid CHAR(36) NOT NULL,
                        amount BIGINT NOT NULL,
                        status VARCHAR(24) NOT NULL,
                        recipient_uuid CHAR(36) NULL,
                        captured_operation_id CHAR(36) NULL,
                        released_operation_id CHAR(36) NULL,
                        expires_at TIMESTAMP(3) NOT NULL,
                        context_json LONGTEXT NULL,
                        created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                        INDEX idx_currency_holds_owner (owner_uuid, status),
                        INDEX idx_currency_holds_expiration (status, expires_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
        }
    }

    private void ensureColumnExists(
            Connection connection,
            String tableName,
            String columnName,
            String columnDefinition
    ) throws SQLException {
        if (columnExists(connection, tableName, columnName)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "ALTER TABLE `" + tableName + "` ADD COLUMN `" + columnName + "` " + columnDefinition
            );
        } catch (SQLException exception) {
            if (!columnExists(connection, tableName, columnName)) {
                throw exception;
            }
        }
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = ?
                  AND COLUMN_NAME = ?
                LIMIT 1
                """)) {
            statement.setString(1, tableName);
            statement.setString(2, columnName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private String contextJson(CurrencyContext context) {
        if (context == null || !CurrencyConfig.auditMetadata()) {
            return null;
        }
        return GSON.toJson(Map.of(
                "actor_uuid", context.actorUuid() == null ? "" : context.actorUuid().toString(),
                "actor_name", context.actorName(),
                "node_id", context.nodeId(),
                "reason", context.reason(),
                "source_type", context.sourceType(),
                "source_id", context.sourceId(),
                "metadata", context.metadata()
        ));
    }

    private void requireReady() {
        if (!initialized) {
            throw new IllegalStateException("Currency provider is not initialized");
        }
    }

    private void validateAmount(long amount) {
        if (amount <= 0L) {
            throw new IllegalArgumentException("amount");
        }
        if (amount > descriptor().maximumBalance()) {
            throw new IllegalArgumentException("amount exceeds maximum balance");
        }
    }

    private static UUID readUuid(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static void setUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.CHAR);
        } else {
            statement.setString(index, value.toString());
        }
    }

    private static String truncate(String value, int maximum) {
        if (value == null) {
            return "";
        }
        return value.length() <= maximum ? value : value.substring(0, maximum);
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

    private static synchronized void ensureDriverLoaded() throws SQLException {
        ClassLoader classLoader = MysqlCurrencyProvider.class.getClassLoader();
        try {
            try {
                Class.forName(SHADED_MYSQL_DRIVER, true, classLoader);
            } catch (ClassNotFoundException ignored) {
                Class.forName(DEVELOPMENT_MYSQL_DRIVER, true, classLoader);
            }
        } catch (ClassNotFoundException exception) {
            throw new SQLException("MySQL JDBC driver was not found in CointCoreGTO", exception);
        }
    }

    private record HoldRow(UUID ownerUuid, long amount, String status) {
    }
}
