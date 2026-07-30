package Crazer.cubeofinterest.cointcoregto;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final boolean failClosedRouting;
    private final boolean syncPlayerData;
    private final int maxPlayerDataBytes;
    private final boolean syncForgeCapabilities;
    private final boolean dimensionTickIsolation;
    private final Path dimensionMigrationStagingPath;
    private final int dimensionMigrationBackupRetentionDays;
    private final boolean automaticDimensionSnapshots;
    private final int dimensionSnapshotIntervalMinutes;
    private final int dimensionSnapshotRetentionDays;
    private final int dimensionSnapshotMaxPerDimension;
    private final int dimensionSnapshotMaxAgeMinutes;

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
            boolean failClosedRouting,
            boolean syncPlayerData,
            int maxPlayerDataBytes,
            boolean syncForgeCapabilities,
            boolean dimensionTickIsolation,
            Path dimensionMigrationStagingPath,
            int dimensionMigrationBackupRetentionDays,
            boolean automaticDimensionSnapshots,
            int dimensionSnapshotIntervalMinutes,
            int dimensionSnapshotRetentionDays,
            int dimensionSnapshotMaxPerDimension,
            int dimensionSnapshotMaxAgeMinutes
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
        this.failClosedRouting = failClosedRouting;
        this.syncPlayerData = syncPlayerData;
        this.maxPlayerDataBytes = maxPlayerDataBytes;
        this.syncForgeCapabilities = syncForgeCapabilities;
        this.dimensionTickIsolation = dimensionTickIsolation;
        this.dimensionMigrationStagingPath = dimensionMigrationStagingPath;
        this.dimensionMigrationBackupRetentionDays = dimensionMigrationBackupRetentionDays;
        this.automaticDimensionSnapshots = automaticDimensionSnapshots;
        this.dimensionSnapshotIntervalMinutes = dimensionSnapshotIntervalMinutes;
        this.dimensionSnapshotRetentionDays = dimensionSnapshotRetentionDays;
        this.dimensionSnapshotMaxPerDimension = dimensionSnapshotMaxPerDimension;
        this.dimensionSnapshotMaxAgeMinutes = dimensionSnapshotMaxAgeMinutes;
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
                                "dimension_tick_isolation",
                                "false"
                        ).trim()
                ),
                optionalPath(properties, "dimension_migration_staging_path"),
                positiveInt(properties, "dimension_migration_backup_retention_days", 7),
                Boolean.parseBoolean(properties.getProperty("automatic_dimension_snapshots", "false").trim()),
                positiveInt(properties, "dimension_snapshot_interval_minutes", 30),
                positiveInt(properties, "dimension_snapshot_retention_days", 7),
                positiveInt(properties, "dimension_snapshot_max_per_dimension", 8),
                positiveInt(properties, "dimension_snapshot_max_age_minutes", 60)
        );
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
        defaults.setProperty(
                "dimension_tick_isolation",
                "false"
        );
        defaults.setProperty(
                "dimension_migration_staging_path",
                ""
        );
        defaults.setProperty(
                "dimension_migration_backup_retention_days",
                "7"
        );
        defaults.setProperty("automatic_dimension_snapshots", "false");
        defaults.setProperty("dimension_snapshot_interval_minutes", "30");
        defaults.setProperty("dimension_snapshot_retention_days", "7");
        defaults.setProperty("dimension_snapshot_max_per_dimension", "8");
        defaults.setProperty("dimension_snapshot_max_age_minutes", "60");

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

    public boolean dimensionTickIsolation() {
        return dimensionTickIsolation;
    }

    public Path dimensionMigrationStagingPath() {
        return dimensionMigrationStagingPath;
    }

    public int dimensionMigrationBackupRetentionDays() {
        return dimensionMigrationBackupRetentionDays;
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
}
