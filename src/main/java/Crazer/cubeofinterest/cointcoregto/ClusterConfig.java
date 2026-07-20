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

    private ClusterConfig(
            boolean enabled,
            String nodeId,
            String redirectAddress,
            String jdbcUrl,
            String username,
            String password
    ) {
        this.enabled = enabled;
        this.nodeId = nodeId;
        this.redirectAddress = redirectAddress;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    public static ClusterConfig load() throws IOException {
        ensureTemplateExists();

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(CONFIG_PATH)) {
            properties.load(input);
        }

        return new ClusterConfig(
                Boolean.parseBoolean(properties.getProperty("enabled", "false").trim()),
                required(properties, "node_id"),
                required(properties, "redirect_address"),
                required(properties, "jdbc_url"),
                required(properties, "username"),
                properties.getProperty("password", "")
        );
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            throw new IOException("Cluster config property is empty: " + key);
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
        defaults.setProperty("password", "GtoClusterLocal_2026!");

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
}
