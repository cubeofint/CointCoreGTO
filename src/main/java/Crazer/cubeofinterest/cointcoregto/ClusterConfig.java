package Crazer.cubeofinterest.cointcoregto;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;


public final class ClusterConfig {
    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR.get().resolve("cointcoregto-cluster.properties");

    private final boolean enabled;
    private final String nodeId;
    private final String redirectAddress;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int heartbeatIntervalTicks;
    private final int nodeTimeoutSeconds;
    private final int transferTtlSeconds;
    private final int staleClaimSeconds;
    private final int playerSessionLeaseSeconds;
    private final int transferLockTimeoutSeconds;
    private final int playerBackupRetentionDays;
    private final boolean automaticFailover;
    private final int automaticFailoverConfirmationSeconds;
    private final int automaticFailoverLeaseSeconds;
    private final boolean automaticFailoverIncludeCleanStops;
    private final boolean automaticOperationRecovery;
    private final int automaticOperationRecoveryIntervalSeconds;
    private final boolean pendingApplyRestartEnabled;
    private final int pendingApplyRestartDelaySeconds;
    private final int pendingApplyRestartConfirmationTimeoutSeconds;
    private final int pendingApplyRestartNotificationIntervalSeconds;
    private final boolean failClosedRouting;
    private final boolean syncPlayerData;
    private final int maxPlayerDataBytes;
    private final boolean syncForgeCapabilities;
    private final boolean syncFtbQuests;
    private final int maxFtbQuestDataBytes;
    private final int ftbQuestSyncIntervalSeconds;
    private final boolean syncFtbQuestBook;
    private final String ftbQuestBookAuthorityNode;
    private final boolean ftbQuestBookAutoPublish;
    private final int ftbQuestBookSyncIntervalSeconds;
    private final int maxFtbQuestBookBytes;
    private final int ftbQuestBookRevisionRetention;
    private final int ftbQuestBookBackupRetention;
    private final boolean syncFtbChunks;
    private final int ftbChunksSyncIntervalSeconds;
    private final boolean ftbChunksForceLoadOwnerOnly;
    private final String ftbChunksDefaultAuthorityNode;
    private final int ftbChunksApplyBatchSize;
    private final int ftbChunksEventRetentionDays;
    private final boolean dimensionTickIsolation;
    private final int dimensionOwnerCacheMaxAgeSeconds;
    private final Path dimensionMigrationStagingPath;
    private final int dimensionMigrationBackupRetentionDays;
    private final int dimensionMigrationStaleWarningMinutes;
    private final boolean automaticDimensionSnapshots;
    private final int dimensionSnapshotIntervalMinutes;
    private final int dimensionSnapshotRetentionDays;
    private final int dimensionSnapshotMaxPerDimension;
    private final int dimensionSnapshotMaxAgeMinutes;
    private final boolean dimensionRoleRoutingEnabled;
    private final String nodeRole;
    private final int nodeRoleCapacity;
    private final List<DimensionRoleRule> dimensionRoleRules;
    private final boolean automaticDimensionRoleAssignment;
    private final int automaticDimensionRoleAssignmentIntervalSeconds;
    private final boolean automaticNewDimensionProvisioning;
    private final int automaticNewDimensionProvisioningTimeoutSeconds;
    private final boolean personalSpaceProvisioningTestPauseAfterArchive;
    private final int personalSpaceProvisioningTestPauseSeconds;
    private final boolean networkChatEnabled;
    private final String networkRole;
    private final String networkChatPrefix;
    private final Map<String, NetworkChatDimensionOverride> networkChatDimensionOverrides;
    private final int networkChatPollIntervalTicks;
    private final int networkChatRetentionMinutes;
    private final boolean discordClusterLeaderElection;
    private final int discordClusterLeaseSeconds;

    private ClusterConfig(
            boolean enabled,
            String nodeId,
            String redirectAddress,
            String jdbcUrl,
            String username,
            String password,
            int heartbeatIntervalTicks,
            int nodeTimeoutSeconds,
            int transferTtlSeconds,
            int staleClaimSeconds,
            int playerSessionLeaseSeconds,
            int transferLockTimeoutSeconds,
            int playerBackupRetentionDays,
            boolean automaticFailover,
            int automaticFailoverConfirmationSeconds,
            int automaticFailoverLeaseSeconds,
            boolean automaticFailoverIncludeCleanStops,
            boolean automaticOperationRecovery,
            int automaticOperationRecoveryIntervalSeconds,
            boolean pendingApplyRestartEnabled,
            int pendingApplyRestartDelaySeconds,
            int pendingApplyRestartConfirmationTimeoutSeconds,
            int pendingApplyRestartNotificationIntervalSeconds,
            boolean failClosedRouting,
            boolean syncPlayerData,
            int maxPlayerDataBytes,
            boolean syncForgeCapabilities,
            boolean syncFtbQuests,
            int maxFtbQuestDataBytes,
            int ftbQuestSyncIntervalSeconds,
            boolean syncFtbQuestBook,
            String ftbQuestBookAuthorityNode,
            boolean ftbQuestBookAutoPublish,
            int ftbQuestBookSyncIntervalSeconds,
            int maxFtbQuestBookBytes,
            int ftbQuestBookRevisionRetention,
            int ftbQuestBookBackupRetention,
            boolean syncFtbChunks,
            int ftbChunksSyncIntervalSeconds,
            boolean ftbChunksForceLoadOwnerOnly,
            String ftbChunksDefaultAuthorityNode,
            int ftbChunksApplyBatchSize,
            int ftbChunksEventRetentionDays,
            boolean dimensionTickIsolation,
            int dimensionOwnerCacheMaxAgeSeconds,
            Path dimensionMigrationStagingPath,
            int dimensionMigrationBackupRetentionDays,
            int dimensionMigrationStaleWarningMinutes,
            boolean automaticDimensionSnapshots,
            int dimensionSnapshotIntervalMinutes,
            int dimensionSnapshotRetentionDays,
            int dimensionSnapshotMaxPerDimension,
            int dimensionSnapshotMaxAgeMinutes,
            boolean dimensionRoleRoutingEnabled,
            String nodeRole,
            int nodeRoleCapacity,
            List<DimensionRoleRule> dimensionRoleRules,
            boolean automaticDimensionRoleAssignment,
            int automaticDimensionRoleAssignmentIntervalSeconds,
            boolean automaticNewDimensionProvisioning,
            int automaticNewDimensionProvisioningTimeoutSeconds,
            boolean personalSpaceProvisioningTestPauseAfterArchive,
            int personalSpaceProvisioningTestPauseSeconds,
            boolean networkChatEnabled,
            String networkRole,
            String networkChatPrefix,
            Map<String, NetworkChatDimensionOverride> networkChatDimensionOverrides,
            int networkChatPollIntervalTicks,
            int networkChatRetentionMinutes,
            boolean discordClusterLeaderElection,
            int discordClusterLeaseSeconds
    ) {
        this.enabled = enabled;
        this.nodeId = nodeId;
        this.redirectAddress = redirectAddress;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.heartbeatIntervalTicks = heartbeatIntervalTicks;
        this.nodeTimeoutSeconds = nodeTimeoutSeconds;
        this.transferTtlSeconds = transferTtlSeconds;
        this.staleClaimSeconds = staleClaimSeconds;
        this.playerSessionLeaseSeconds = playerSessionLeaseSeconds;
        this.transferLockTimeoutSeconds = transferLockTimeoutSeconds;
        this.playerBackupRetentionDays = playerBackupRetentionDays;
        this.automaticFailover = automaticFailover;
        this.automaticFailoverConfirmationSeconds = automaticFailoverConfirmationSeconds;
        this.automaticFailoverLeaseSeconds = automaticFailoverLeaseSeconds;
        this.automaticFailoverIncludeCleanStops = automaticFailoverIncludeCleanStops;
        this.automaticOperationRecovery = automaticOperationRecovery;
        this.automaticOperationRecoveryIntervalSeconds = automaticOperationRecoveryIntervalSeconds;
        this.pendingApplyRestartEnabled = pendingApplyRestartEnabled;
        this.pendingApplyRestartDelaySeconds = pendingApplyRestartDelaySeconds;
        this.pendingApplyRestartConfirmationTimeoutSeconds = pendingApplyRestartConfirmationTimeoutSeconds;
        this.pendingApplyRestartNotificationIntervalSeconds = pendingApplyRestartNotificationIntervalSeconds;
        this.failClosedRouting = failClosedRouting;
        this.syncPlayerData = syncPlayerData;
        this.maxPlayerDataBytes = maxPlayerDataBytes;
        this.syncForgeCapabilities = syncForgeCapabilities;
        this.syncFtbQuests = syncFtbQuests;
        this.maxFtbQuestDataBytes = maxFtbQuestDataBytes;
        this.ftbQuestSyncIntervalSeconds = ftbQuestSyncIntervalSeconds;
        this.syncFtbQuestBook = syncFtbQuestBook;
        this.ftbQuestBookAuthorityNode = ftbQuestBookAuthorityNode;
        this.ftbQuestBookAutoPublish = ftbQuestBookAutoPublish;
        this.ftbQuestBookSyncIntervalSeconds = ftbQuestBookSyncIntervalSeconds;
        this.maxFtbQuestBookBytes = maxFtbQuestBookBytes;
        this.ftbQuestBookRevisionRetention = ftbQuestBookRevisionRetention;
        this.ftbQuestBookBackupRetention = ftbQuestBookBackupRetention;
        this.syncFtbChunks = syncFtbChunks;
        this.ftbChunksSyncIntervalSeconds = ftbChunksSyncIntervalSeconds;
        this.ftbChunksForceLoadOwnerOnly = ftbChunksForceLoadOwnerOnly;
        this.ftbChunksDefaultAuthorityNode = ftbChunksDefaultAuthorityNode;
        this.ftbChunksApplyBatchSize = ftbChunksApplyBatchSize;
        this.ftbChunksEventRetentionDays = ftbChunksEventRetentionDays;
        this.dimensionTickIsolation = dimensionTickIsolation;
        this.dimensionOwnerCacheMaxAgeSeconds = dimensionOwnerCacheMaxAgeSeconds;
        this.dimensionMigrationStagingPath = dimensionMigrationStagingPath;
        this.dimensionMigrationBackupRetentionDays = dimensionMigrationBackupRetentionDays;
        this.dimensionMigrationStaleWarningMinutes = dimensionMigrationStaleWarningMinutes;
        this.automaticDimensionSnapshots = automaticDimensionSnapshots;
        this.dimensionSnapshotIntervalMinutes = dimensionSnapshotIntervalMinutes;
        this.dimensionSnapshotRetentionDays = dimensionSnapshotRetentionDays;
        this.dimensionSnapshotMaxPerDimension = dimensionSnapshotMaxPerDimension;
        this.dimensionSnapshotMaxAgeMinutes = dimensionSnapshotMaxAgeMinutes;
        this.dimensionRoleRoutingEnabled = dimensionRoleRoutingEnabled;
        this.nodeRole = nodeRole;
        this.nodeRoleCapacity = nodeRoleCapacity;
        this.dimensionRoleRules = List.copyOf(dimensionRoleRules);
        this.automaticDimensionRoleAssignment = automaticDimensionRoleAssignment;
        this.automaticDimensionRoleAssignmentIntervalSeconds = automaticDimensionRoleAssignmentIntervalSeconds;
        this.automaticNewDimensionProvisioning = automaticNewDimensionProvisioning;
        this.automaticNewDimensionProvisioningTimeoutSeconds = automaticNewDimensionProvisioningTimeoutSeconds;
        this.personalSpaceProvisioningTestPauseAfterArchive = personalSpaceProvisioningTestPauseAfterArchive;
        this.personalSpaceProvisioningTestPauseSeconds = personalSpaceProvisioningTestPauseSeconds;
        this.networkChatEnabled = networkChatEnabled;
        this.networkRole = networkRole;
        this.networkChatPrefix = networkChatPrefix;
        this.networkChatDimensionOverrides = Map.copyOf(networkChatDimensionOverrides);
        this.networkChatPollIntervalTicks = networkChatPollIntervalTicks;
        this.networkChatRetentionMinutes = networkChatRetentionMinutes;
        this.discordClusterLeaderElection = discordClusterLeaderElection;
        this.discordClusterLeaseSeconds = discordClusterLeaseSeconds;
    }

    public static ClusterConfig load() throws IOException {
        ensureTemplateExists();

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(CONFIG_PATH)) {
            properties.load(input);
        }

        return new ClusterConfig(
                Boolean.parseBoolean(properties.getProperty("enabled", "false").trim()),
                nodeId(properties),
                redirectAddress(properties),
                required(properties, "jdbc_url"),
                required(properties, "username"),
                properties.getProperty("password", ""),
                positiveInt(properties, "heartbeat_interval_ticks", 100),
                positiveInt(properties, "node_timeout_seconds", 15),
                positiveInt(properties, "transfer_ttl_seconds", 300),
                positiveInt(properties, "stale_claim_seconds", 30),
                positiveInt(properties, "player_session_lease_seconds", 30),
                positiveInt(properties, "transfer_lock_timeout_seconds", 90),
                positiveInt(properties, "player_backup_retention_days", 7),
                Boolean.parseBoolean(
                        properties.getProperty(
                                "automatic_failover",
                                "true"
                        ).trim()
                ),
                positiveInt(properties, "automatic_failover_confirmation_seconds", 60),
                positiveInt(properties, "automatic_failover_lease_seconds", 30),
                Boolean.parseBoolean(
                        properties.getProperty(
                                "automatic_failover_include_clean_stops",
                                "false"
                        ).trim()
                ),
                Boolean.parseBoolean(
                        properties.getProperty(
                                "automatic_operation_recovery",
                                "false"
                        ).trim()
                ),
                rangedInt(
                        properties,
                        "automatic_operation_recovery_interval_seconds",
                        60,
                        15,
                        3600
                ),
                Boolean.parseBoolean(
                        properties.getProperty(
                                "pending_apply_restart_enabled",
                                "true"
                        ).trim()
                ),
                rangedInt(
                        properties,
                        "pending_apply_restart_delay_seconds",
                        15,
                        5,
                        3600
                ),
                rangedInt(
                        properties,
                        "pending_apply_restart_confirmation_timeout_seconds",
                        300,
                        30,
                        3600
                ),
                rangedInt(
                        properties,
                        "pending_apply_restart_notification_interval_seconds",
                        300,
                        60,
                        3600
                ),
                Boolean.parseBoolean(
                        properties.getProperty(
                                "fail_closed_routing",
                                "true"
                        ).trim()
                ),
                Boolean.parseBoolean(
                        properties.getProperty(
                                "sync_player_data",
                                "true"
                        ).trim()
                ),
                positiveInt(
                        properties,
                        "max_player_data_bytes",
                        16 * 1024 * 1024
                ),
                Boolean.parseBoolean(
                        properties.getProperty(
                                "sync_forge_capabilities",
                                "true"
                        ).trim()
                ),
                Boolean.parseBoolean(
                        properties.getProperty(
                                "sync_ftb_quests",
                                "true"
                        ).trim()
                ),
                positiveInt(
                        properties,
                        "max_ftb_quest_data_bytes",
                        8 * 1024 * 1024
                ),
                rangedInt(
                        properties,
                        "ftb_quest_sync_interval_seconds",
                        15,
                        5,
                        3600
                ),
                Boolean.parseBoolean(
                        properties.getProperty(
                                "sync_ftb_quest_book",
                                "true"
                        ).trim()
                ),
                nonBlank(
                        properties.getProperty(
                                "ftb_quest_book_authority_node",
                                "gto1"
                        ),
                        "gto1"
                ),
                Boolean.parseBoolean(
                        properties.getProperty(
                                "ftb_quest_book_auto_publish",
                                "true"
                        ).trim()
                ),
                rangedInt(
                        properties,
                        "ftb_quest_book_sync_interval_seconds",
                        15,
                        5,
                        3600
                ),
                positiveInt(
                        properties,
                        "max_ftb_quest_book_bytes",
                        64 * 1024 * 1024
                ),
                rangedInt(
                        properties,
                        "ftb_quest_book_revision_retention",
                        20,
                        2,
                        500
                ),
                rangedInt(
                        properties,
                        "ftb_quest_book_backup_retention",
                        3,
                        1,
                        50
                ),
                Boolean.parseBoolean(
                        properties.getProperty(
                                "sync_ftb_chunks",
                                "true"
                        ).trim()
                ),
                rangedInt(
                        properties,
                        "ftb_chunks_sync_interval_seconds",
                        5,
                        1,
                        3600
                ),
                Boolean.parseBoolean(
                        properties.getProperty(
                                "ftb_chunks_force_load_owner_only",
                                "true"
                        ).trim()
                ),
                nonBlank(
                        properties.getProperty(
                                "ftb_chunks_default_authority_node",
                                "gto1"
                        ),
                        "gto1"
                ),
                rangedInt(
                        properties,
                        "ftb_chunks_apply_batch_size",
                        128,
                        1,
                        10000
                ),
                rangedInt(
                        properties,
                        "ftb_chunks_event_retention_days",
                        30,
                        1,
                        3650
                ),
                Boolean.parseBoolean(
                        properties.getProperty(
                                "dimension_tick_isolation",
                                "false"
                        ).trim()
                ),
                rangedInt(
                        properties,
                        "dimension_owner_cache_max_age_seconds",
                        15,
                        5,
                        3600
                ),
                optionalPath(properties, "dimension_migration_staging_path"),
                positiveInt(properties, "dimension_migration_backup_retention_days", 7),
                rangedInt(
                        properties,
                        "dimension_migration_stale_warning_minutes",
                        360,
                        1,
                        10080
                ),
                Boolean.parseBoolean(properties.getProperty("automatic_dimension_snapshots", "false").trim()),
                positiveInt(properties, "dimension_snapshot_interval_minutes", 30),
                positiveInt(properties, "dimension_snapshot_retention_days", 7),
                positiveInt(properties, "dimension_snapshot_max_per_dimension", 8),
                positiveInt(properties, "dimension_snapshot_max_age_minutes", 60),
                Boolean.parseBoolean(properties.getProperty("dimension_role_routing_enabled", "false").trim()),
                nodeRole(properties),
                rangedInt(properties, "node_role_capacity", 0, 0, 1000000),
                dimensionRoleRules(properties),
                Boolean.parseBoolean(properties.getProperty("automatic_dimension_role_assignment", "true").trim()),
                rangedInt(properties, "automatic_dimension_role_assignment_interval_seconds", 30, 5, 3600),
                Boolean.parseBoolean(properties.getProperty("automatic_new_dimension_provisioning", "false").trim()),
                rangedInt(properties, "automatic_new_dimension_provisioning_timeout_seconds", 300, 30, 1800),
                Boolean.parseBoolean(properties.getProperty("personalspace_provisioning_test_pause_after_archive", "false").trim()),
                rangedInt(properties, "personalspace_provisioning_test_pause_seconds", 60, 5, 600),
                Boolean.parseBoolean(properties.getProperty("network_chat_enabled", "false").trim()),
                networkRole(properties),
                networkChatPrefix(properties.getProperty("network_chat_prefix", "&8[#1] ")),
                networkChatDimensionOverrides(properties),
                rangedInt(properties, "network_chat_poll_interval_ticks", 10, 1, 200),
                rangedInt(properties, "network_chat_retention_minutes", 10, 10, 10080),
                Boolean.parseBoolean(properties.getProperty("discord_cluster_leader_election", properties.getProperty("discord_cluster_leader", "true")).trim()),
                rangedInt(properties, "discord_cluster_lease_seconds", 30, 10, 300)
        );
    }

    private static int rangedInt(
            Properties properties,
            String key,
            int defaultValue,
            int minimumValue,
            int maximumValue
    ) throws IOException {
        String rawValue = properties
                .getProperty(key, Integer.toString(defaultValue))
                .trim();

        try {
            int value = Integer.parseInt(rawValue);
            if (value < minimumValue || value > maximumValue) {
                throw new NumberFormatException(
                        "value must be between " + minimumValue + " and " + maximumValue
                );
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IOException(
                    "Cluster config property must be an integer between "
                            + minimumValue
                            + " and "
                            + maximumValue
                            + ": "
                            + key
                            + "="
                            + rawValue,
                    exception
            );
        }
    }

    private static int positiveInt(
            Properties properties,
            String key,
            int defaultValue
    ) throws IOException {
        String rawValue = properties
                .getProperty(key, Integer.toString(defaultValue))
                .trim();

        try {
            int value = Integer.parseInt(rawValue);

            if (value <= 0) {
                throw new NumberFormatException(
                        "value must be positive"
                );
            }

            return value;
        } catch (NumberFormatException exception) {
            throw new IOException(
                    "Cluster config property must be a positive integer: "
                            + key
                            + "="
                            + rawValue,
                    exception
            );
        }
    }


    private static Path optionalPath(
            Properties properties,
            String key
    ) throws IOException {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            return null;
        }

        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (Exception exception) {
            throw new IOException(
                    "Cluster config property contains an invalid path: "
                            + key
                            + "="
                            + value,
                    exception
            );
        }
    }

    private static String nonBlank(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isEmpty() ? fallback : normalized;
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            throw new IOException("Cluster config property is empty: " + key);
        }
        return value;
    }

    private static String nodeId(
            Properties properties
    ) throws IOException {
        String value = required(properties, "node_id");

        if (!value.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IOException(
                    "Cluster node_id may contain only A-Z, a-z, 0-9, '.', '_' and '-': "
                            + value
            );
        }

        return value;
    }


    private static String networkRole(Properties properties) throws IOException {
        String value = properties.getProperty("network_role", properties.getProperty("node_id", "gto1")).trim();
        if (!value.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IOException("Cluster network_role may contain only A-Z, a-z, 0-9, '.', '_' and '-': " + value);
        }
        return value;
    }

    private static String nodeRole(Properties properties) throws IOException {
        String value = properties.getProperty("node_role", "general").trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9._-]{1,64}")) {
            throw new IOException("Cluster node_role may contain only a-z, 0-9, '.', '_' and '-': " + value);
        }
        return value;
    }

    private static List<DimensionRoleRule> dimensionRoleRules(Properties properties) throws IOException {
        String raw = properties.getProperty("dimension_role_rules", "").trim();
        if (raw.isEmpty()) {
            return List.of();
        }

        List<DimensionRoleRule> rules = new ArrayList<>();
        for (String entry : raw.split(";")) {
            String value = entry.trim();
            if (value.isEmpty()) {
                continue;
            }

            String[] parts = value.split("\\|", 2);
            if (parts.length != 2) {
                throw new IOException("Invalid dimension_role_rules entry: " + value);
            }

            String pattern = parts[0].trim().toLowerCase(Locale.ROOT);
            String role = parts[1].trim().toLowerCase(Locale.ROOT);
            if (pattern.isEmpty() || !pattern.matches("[a-z0-9._:/?*\\-]+")) {
                throw new IOException("Invalid dimension pattern in dimension_role_rules: " + pattern);
            }
            if (!role.matches("[a-z0-9._-]{1,64}")) {
                throw new IOException("Invalid role in dimension_role_rules: " + role);
            }

            rules.add(new DimensionRoleRule(pattern, role));
        }

        return List.copyOf(rules);
    }

    private static boolean globMatches(String pattern, String value) {
        int patternIndex = 0;
        int valueIndex = 0;
        int starIndex = -1;
        int starValueIndex = -1;

        while (valueIndex < value.length()) {
            if (patternIndex < pattern.length()
                    && (pattern.charAt(patternIndex) == '?'
                    || pattern.charAt(patternIndex) == value.charAt(valueIndex))) {
                patternIndex++;
                valueIndex++;
                continue;
            }

            if (patternIndex < pattern.length()
                    && pattern.charAt(patternIndex) == '*') {
                starIndex = patternIndex++;
                starValueIndex = valueIndex;
                continue;
            }

            if (starIndex >= 0) {
                patternIndex = starIndex + 1;
                valueIndex = ++starValueIndex;
                continue;
            }

            return false;
        }

        while (patternIndex < pattern.length()
                && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }

        return patternIndex == pattern.length();
    }

    private static String networkChatPrefix(String value) {
        String prefix = value == null ? "" : value.trim();
        if (prefix.isEmpty()) {
            return "";
        }
        return prefix + " ";
    }

    private static Map<String, NetworkChatDimensionOverride> networkChatDimensionOverrides(Properties properties) throws IOException {
        String raw = properties.getProperty(
                "network_chat_dimension_overrides",
                properties.getProperty("network_chat_dimension_prefixes", "")
        ).trim();
        if (raw.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, NetworkChatDimensionOverride> overrides = new LinkedHashMap<>();
        for (String entry : raw.split(";")) {
            String value = entry.trim();
            if (value.isEmpty()) {
                continue;
            }
            String[] parts = value.split("\\|", 3);
            if (parts.length < 2) {
                throw new IOException("Invalid network_chat_dimension_overrides entry: " + value);
            }
            String dimension = parts[0].trim();
            if (!dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
                throw new IOException("Invalid dimension id in network_chat_dimension_overrides: " + dimension);
            }
            String role;
            String prefix;
            if (parts.length == 2) {
                role = networkRole(properties);
                prefix = parts[1];
            } else {
                role = parts[1].trim();
                prefix = parts[2];
            }
            if (!role.matches("[A-Za-z0-9._-]{1,64}")) {
                throw new IOException("Invalid role in network_chat_dimension_overrides: " + role);
            }
            overrides.put(dimension, new NetworkChatDimensionOverride(role, networkChatPrefix(prefix)));
        }
        return overrides;
    }

    private static String redirectAddress(
            Properties properties
    ) throws IOException {
        String value = required(
                properties,
                "redirect_address"
        );

        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw new IOException(
                    "Cluster redirect_address must not contain whitespace: "
                            + value
            );
        }

        return value;
    }

    private static void ensureTemplateExists() throws IOException {
        if (Files.exists(CONFIG_PATH)) {
            return;
        }

        Files.createDirectories(CONFIG_PATH.getParent());
        Properties defaults = new Properties();
        defaults.setProperty("enabled", "false");
        defaults.setProperty("node_id", "gto1");
        defaults.setProperty("redirect_address", "localhost:25565");
        defaults.setProperty(
                "jdbc_url",
                "jdbc:mysql://127.0.0.1:3306/gto_cluster_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
        );
        defaults.setProperty("username", "gto_test");
        defaults.setProperty("password", "change_me");
        defaults.setProperty("heartbeat_interval_ticks", "100");
        defaults.setProperty("node_timeout_seconds", "15");
        defaults.setProperty("transfer_ttl_seconds", "300");
        defaults.setProperty("stale_claim_seconds", "30");
        defaults.setProperty("player_session_lease_seconds", "30");
        defaults.setProperty("transfer_lock_timeout_seconds", "90");
        defaults.setProperty("player_backup_retention_days", "7");
        defaults.setProperty("automatic_failover", "true");
        defaults.setProperty("automatic_failover_confirmation_seconds", "60");
        defaults.setProperty("automatic_failover_lease_seconds", "30");
        defaults.setProperty("automatic_failover_include_clean_stops", "false");
        defaults.setProperty("automatic_operation_recovery", "false");
        defaults.setProperty("automatic_operation_recovery_interval_seconds", "60");
        defaults.setProperty("pending_apply_restart_enabled", "true");
        defaults.setProperty("pending_apply_restart_delay_seconds", "15");
        defaults.setProperty("pending_apply_restart_confirmation_timeout_seconds", "300");
        defaults.setProperty("pending_apply_restart_notification_interval_seconds", "300");
        defaults.setProperty("fail_closed_routing", "true");
        defaults.setProperty("sync_player_data", "true");
        defaults.setProperty(
                "max_player_data_bytes",
                Integer.toString(16 * 1024 * 1024)
        );
        defaults.setProperty(
                "sync_forge_capabilities",
                "true"
        );
        defaults.setProperty("sync_ftb_quests", "true");
        defaults.setProperty(
                "max_ftb_quest_data_bytes",
                Integer.toString(8 * 1024 * 1024)
        );
        defaults.setProperty("ftb_quest_sync_interval_seconds", "15");
        defaults.setProperty("sync_ftb_quest_book", "true");
        defaults.setProperty("ftb_quest_book_authority_node", "gto1");
        defaults.setProperty("ftb_quest_book_auto_publish", "true");
        defaults.setProperty("ftb_quest_book_sync_interval_seconds", "15");
        defaults.setProperty(
                "max_ftb_quest_book_bytes",
                Integer.toString(64 * 1024 * 1024)
        );
        defaults.setProperty("ftb_quest_book_revision_retention", "20");
        defaults.setProperty("ftb_quest_book_backup_retention", "3");
        defaults.setProperty("sync_ftb_chunks", "true");
        defaults.setProperty("ftb_chunks_sync_interval_seconds", "5");
        defaults.setProperty("ftb_chunks_force_load_owner_only", "true");
        defaults.setProperty("ftb_chunks_default_authority_node", "gto1");
        defaults.setProperty("ftb_chunks_apply_batch_size", "128");
        defaults.setProperty("ftb_chunks_event_retention_days", "30");
        defaults.setProperty(
                "dimension_tick_isolation",
                "false"
        );
        defaults.setProperty(
                "dimension_owner_cache_max_age_seconds",
                "15"
        );
        defaults.setProperty(
                "dimension_migration_staging_path",
                ""
        );
        defaults.setProperty(
                "dimension_migration_backup_retention_days",
                "7"
        );
        defaults.setProperty(
                "dimension_migration_stale_warning_minutes",
                "360"
        );
        defaults.setProperty("automatic_dimension_snapshots", "false");
        defaults.setProperty("dimension_snapshot_interval_minutes", "30");
        defaults.setProperty("dimension_snapshot_retention_days", "7");
        defaults.setProperty("dimension_snapshot_max_per_dimension", "8");
        defaults.setProperty("dimension_snapshot_max_age_minutes", "60");
        defaults.setProperty("dimension_role_routing_enabled", "false");
        defaults.setProperty("node_role", "general");
        defaults.setProperty("node_role_capacity", "0");
        defaults.setProperty("dimension_role_rules", "");
        defaults.setProperty("automatic_dimension_role_assignment", "true");
        defaults.setProperty("automatic_dimension_role_assignment_interval_seconds", "30");
        defaults.setProperty("automatic_new_dimension_provisioning", "false");
        defaults.setProperty("automatic_new_dimension_provisioning_timeout_seconds", "300");
        defaults.setProperty("personalspace_provisioning_test_pause_after_archive", "false");
        defaults.setProperty("personalspace_provisioning_test_pause_seconds", "60");
        defaults.setProperty("network_chat_enabled", "false");
        defaults.setProperty("network_role", "gto1");
        defaults.setProperty("network_chat_prefix", "&8[#1]");
        defaults.setProperty("network_chat_dimension_overrides", "minecraft:overworld|hub|&a[H]");
        defaults.setProperty("network_chat_poll_interval_ticks", "10");
        defaults.setProperty("network_chat_retention_minutes", "10");
        defaults.setProperty("discord_cluster_leader_election", "true");
        defaults.setProperty("discord_cluster_lease_seconds", "30");

        try (OutputStream output = Files.newOutputStream(CONFIG_PATH)) {
            defaults.store(output, "CointCoreGTO local cluster test configuration");
        }
    }

    public static Path path() {
        return CONFIG_PATH;
    }

    public boolean enabled() {
        return enabled;
    }

    public String nodeId() {
        return nodeId;
    }

    public String redirectAddress() {
        return redirectAddress;
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public int heartbeatIntervalTicks() {
        return heartbeatIntervalTicks;
    }

    public int nodeTimeoutSeconds() {
        return nodeTimeoutSeconds;
    }

    public int transferTtlSeconds() {
        return transferTtlSeconds;
    }

    public int staleClaimSeconds() {
        return staleClaimSeconds;
    }

    public int playerSessionLeaseSeconds() {
        return playerSessionLeaseSeconds;
    }

    public int transferLockTimeoutSeconds() {
        return transferLockTimeoutSeconds;
    }

    public int playerBackupRetentionDays() {
        return playerBackupRetentionDays;
    }

    public boolean automaticFailover() {
        return automaticFailover;
    }

    public int automaticFailoverConfirmationSeconds() {
        return automaticFailoverConfirmationSeconds;
    }

    public int automaticFailoverLeaseSeconds() {
        return automaticFailoverLeaseSeconds;
    }

    public boolean automaticFailoverIncludeCleanStops() {
        return automaticFailoverIncludeCleanStops;
    }

    public boolean automaticOperationRecovery() {
        return automaticOperationRecovery;
    }

    public int automaticOperationRecoveryIntervalSeconds() {
        return automaticOperationRecoveryIntervalSeconds;
    }

    public boolean pendingApplyRestartEnabled() {
        return pendingApplyRestartEnabled;
    }

    public int pendingApplyRestartDelaySeconds() {
        return pendingApplyRestartDelaySeconds;
    }

    public int pendingApplyRestartConfirmationTimeoutSeconds() {
        return pendingApplyRestartConfirmationTimeoutSeconds;
    }

    public int pendingApplyRestartNotificationIntervalSeconds() {
        return pendingApplyRestartNotificationIntervalSeconds;
    }

    public boolean failClosedRouting() {
        return failClosedRouting;
    }

    public boolean syncPlayerData() {
        return syncPlayerData;
    }

    public int maxPlayerDataBytes() {
        return maxPlayerDataBytes;
    }

    public boolean syncForgeCapabilities() {
        return syncForgeCapabilities;
    }

    public boolean syncFtbQuests() {
        return syncFtbQuests;
    }

    public int maxFtbQuestDataBytes() {
        return maxFtbQuestDataBytes;
    }

    public int ftbQuestSyncIntervalSeconds() {
        return ftbQuestSyncIntervalSeconds;
    }

    public boolean syncFtbQuestBook() {
        return syncFtbQuestBook;
    }

    public String ftbQuestBookAuthorityNode() {
        return ftbQuestBookAuthorityNode;
    }

    public boolean ftbQuestBookAutoPublish() {
        return ftbQuestBookAutoPublish;
    }

    public int ftbQuestBookSyncIntervalSeconds() {
        return ftbQuestBookSyncIntervalSeconds;
    }

    public int maxFtbQuestBookBytes() {
        return maxFtbQuestBookBytes;
    }

    public int ftbQuestBookRevisionRetention() {
        return ftbQuestBookRevisionRetention;
    }

    public int ftbQuestBookBackupRetention() {
        return ftbQuestBookBackupRetention;
    }

    public boolean syncFtbChunks() {
        return syncFtbChunks;
    }

    public int ftbChunksSyncIntervalSeconds() {
        return ftbChunksSyncIntervalSeconds;
    }

    public boolean ftbChunksForceLoadOwnerOnly() {
        return ftbChunksForceLoadOwnerOnly;
    }

    public String ftbChunksDefaultAuthorityNode() {
        return ftbChunksDefaultAuthorityNode;
    }

    public int ftbChunksApplyBatchSize() {
        return ftbChunksApplyBatchSize;
    }

    public int ftbChunksEventRetentionDays() {
        return ftbChunksEventRetentionDays;
    }

    public boolean dimensionTickIsolation() {
        return dimensionTickIsolation;
    }

    public int dimensionOwnerCacheMaxAgeSeconds() {
        return dimensionOwnerCacheMaxAgeSeconds;
    }

    public Path dimensionMigrationStagingPath() {
        return dimensionMigrationStagingPath;
    }

    public int dimensionMigrationBackupRetentionDays() {
        return dimensionMigrationBackupRetentionDays;
    }

    public int dimensionMigrationStaleWarningMinutes() {
        return dimensionMigrationStaleWarningMinutes;
    }

    public boolean automaticDimensionSnapshots() {
        return automaticDimensionSnapshots;
    }

    public int dimensionSnapshotIntervalMinutes() {
        return dimensionSnapshotIntervalMinutes;
    }

    public int dimensionSnapshotRetentionDays() {
        return dimensionSnapshotRetentionDays;
    }

    public int dimensionSnapshotMaxPerDimension() {
        return dimensionSnapshotMaxPerDimension;
    }

    public int dimensionSnapshotMaxAgeMinutes() {
        return dimensionSnapshotMaxAgeMinutes;
    }

    public boolean dimensionRoleRoutingEnabled() {
        return dimensionRoleRoutingEnabled;
    }

    public String nodeRole() {
        return nodeRole;
    }

    public int nodeRoleCapacity() {
        return nodeRoleCapacity;
    }

    public List<DimensionRoleRule> dimensionRoleRules() {
        return dimensionRoleRules;
    }

    public boolean automaticDimensionRoleAssignment() {
        return automaticDimensionRoleAssignment;
    }

    public int automaticDimensionRoleAssignmentIntervalSeconds() {
        return automaticDimensionRoleAssignmentIntervalSeconds;
    }

    public boolean automaticNewDimensionProvisioning() {
        return automaticNewDimensionProvisioning;
    }

    public int automaticNewDimensionProvisioningTimeoutSeconds() {
        return automaticNewDimensionProvisioningTimeoutSeconds;
    }

    public boolean personalSpaceProvisioningTestPauseAfterArchive() {
        return personalSpaceProvisioningTestPauseAfterArchive;
    }

    public int personalSpaceProvisioningTestPauseSeconds() {
        return personalSpaceProvisioningTestPauseSeconds;
    }

    public boolean roleRoutingEnabled() {
        return dimensionRoleRoutingEnabled && !dimensionRoleRules.isEmpty();
    }

    public String resolveDimensionRole(String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return null;
        }

        String normalized = dimensionId.trim().toLowerCase(Locale.ROOT);
        for (DimensionRoleRule rule : dimensionRoleRules) {
            if (rule.matches(normalized)) {
                return rule.role();
            }
        }

        return null;
    }

    public boolean networkChatEnabled() {
        return networkChatEnabled;
    }

    public String networkRole() {
        return networkRole;
    }

    public String networkChatPrefix() {
        return networkChatPrefix;
    }

    public Map<String, NetworkChatDimensionOverride> networkChatDimensionOverrides() {
        return networkChatDimensionOverrides;
    }

    public int networkChatPollIntervalTicks() {
        return networkChatPollIntervalTicks;
    }

    public int networkChatRetentionMinutes() {
        return networkChatRetentionMinutes;
    }

    public boolean discordClusterLeaderElection() {
        return discordClusterLeaderElection;
    }

    public int discordClusterLeaseSeconds() {
        return discordClusterLeaseSeconds;
    }

    public record DimensionRoleRule(String pattern, String role) {
        public boolean matches(String dimensionId) {
            return globMatches(pattern, dimensionId);
        }
    }

    public record NetworkChatDimensionOverride(String role, String prefix) {
    }
}
