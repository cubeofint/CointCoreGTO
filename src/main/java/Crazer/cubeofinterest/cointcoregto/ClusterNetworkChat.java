package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ClusterNetworkChat {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:NetworkChat");
    private static final String DISCORD_LEASE_NAME = "discord_bridge";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "CointCoreGTO-Network-Chat");
        thread.setDaemon(true);
        thread.setContextClassLoader(ClusterNetworkChat.class.getClassLoader());
        return thread;
    });
    private static final AtomicBoolean POLL_IN_FLIGHT = new AtomicBoolean();

    private static volatile MinecraftServer server;
    private static volatile ClusterConfig config;
    private static volatile DiscordSettings discordSettings;
    private static volatile long lastSequence;
    private static volatile int tickCounter;
    private static volatile boolean active;
    private static volatile boolean discordLeader;
    private static volatile long lastDiscordLeaseSuccessMillis;
    private static volatile long nextDiscordLeaseCheckMillis;
    private static volatile long nextCleanupMillis;

    private ClusterNetworkChat() {
    }

    public static synchronized void start(
            MinecraftServer minecraftServer,
            ClusterConfig clusterConfig,
            DiscordSettings settings
    ) {
        stopInternal(false);
        server = minecraftServer;
        config = clusterConfig;
        discordSettings = settings;
        active = clusterConfig != null && clusterConfig.enabled() && clusterConfig.networkChatEnabled();
        tickCounter = 0;
        lastSequence = 0L;
        nextDiscordLeaseCheckMillis = 0L;
        nextCleanupMillis = 0L;
        if (!active) {
            startStandaloneDiscord(minecraftServer, settings);
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                lastSequence = ClusterDatabase.currentChatMessageSequence(clusterConfig);
                ClusterDatabase.cleanupChatMessages(clusterConfig, clusterConfig.networkChatRetentionMinutes());
                ClusterDatabase.cleanupChatTestReceipts(clusterConfig, 60);
                nextCleanupMillis = System.currentTimeMillis() + cleanupIntervalMillis(clusterConfig);
                refreshDiscordLeadership(true);
                forwardPendingDiscordMessages();
                LOGGER.info(
                        "Network chat started for node {} role {} prefix {}",
                        clusterConfig.nodeId(),
                        clusterConfig.networkRole(),
                        clusterConfig.networkChatPrefix()
                );
            } catch (Exception exception) {
                LOGGER.error("Unable to start network chat", exception);
            }
        });
    }

    public static synchronized void reload(
            MinecraftServer minecraftServer,
            ClusterConfig clusterConfig,
            DiscordSettings settings
    ) {
        start(minecraftServer, clusterConfig, settings);
    }

    public static synchronized void stop() {
        stopInternal(true);
    }

    private static void stopInternal(boolean releaseLease) {
        ClusterConfig oldConfig = config;
        boolean oldLeader = discordLeader;
        active = false;
        server = null;
        config = null;
        discordSettings = null;
        tickCounter = 0;
        lastSequence = 0L;
        nextDiscordLeaseCheckMillis = 0L;
        nextCleanupMillis = 0L;
        discordLeader = false;
        lastDiscordLeaseSuccessMillis = 0L;
        POLL_IN_FLIGHT.set(false);
        CointCoreGTODiscordProxy.stop();
        if (releaseLease && oldLeader && oldConfig != null && oldConfig.discordClusterLeaderElection()) {
            try {
                ClusterDatabase.releaseOperationLease(oldConfig, DISCORD_LEASE_NAME);
            } catch (Exception exception) {
                LOGGER.warn("Unable to release Discord bridge lease for node {}", oldConfig.nodeId(), exception);
            }
        }
    }

    public static boolean isActive() {
        return active;
    }

    public static boolean isDiscordLeader() {
        return discordLeader;
    }

    public static String role() {
        ClusterConfig current = config;
        return current == null ? "" : current.networkRole();
    }

    public static String sourcePrefix(String dimensionId) {
        ClusterConfig current = config;
        if (!active || current == null) {
            return "";
        }
        ClusterConfig.NetworkChatDimensionOverride override = current.networkChatDimensionOverrides().get(dimensionId);
        return override == null ? current.networkChatPrefix() : override.prefix();
    }

    public static String sourceRole(String dimensionId) {
        ClusterConfig current = config;
        if (!active || current == null) {
            return "";
        }
        ClusterConfig.NetworkChatDimensionOverride override = current.networkChatDimensionOverrides().get(dimensionId);
        return override == null ? current.networkRole() : override.role();
    }

    public static void tick() {
        ClusterConfig current = config;
        if (!active || current == null || server == null) {
            return;
        }
        tickCounter++;
        if (tickCounter < current.networkChatPollIntervalTicks()) {
            return;
        }
        tickCounter = 0;
        if (!POLL_IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                refreshDiscordLeadership(false);
                pollMessages();
                forwardPendingDiscordMessages();
                cleanupIfDue();
            } finally {
                POLL_IN_FLIGHT.set(false);
            }
        });
    }

    public static void publish(
            String originType,
            String originRole,
            String channelName,
            String senderUuid,
            String senderName,
            String discordUsername,
            String plainText,
            String componentJson,
            String discordMessage,
            boolean forwardToDiscord
    ) {
        ClusterConfig current = config;
        if (!active || current == null) {
            if (forwardToDiscord) {
                CointCoreGTODiscordProxy.sendPlayerMessageToDiscord(
                        discordUsername,
                        discordMessage == null ? plainText : discordMessage,
                        senderUuid,
                        senderName
                );
            }
            return;
        }
        ClusterDatabase.NetworkChatPublish publish = new ClusterDatabase.NetworkChatPublish(
                UUID.randomUUID().toString(),
                current.nodeId(),
                originRole == null || originRole.isBlank() ? current.networkRole() : originRole,
                originType,
                channelName,
                senderUuid,
                senderName == null ? "" : senderName,
                discordUsername,
                plainText == null ? "" : plainText,
                componentJson,
                discordMessage,
                forwardToDiscord
        );
        EXECUTOR.execute(() -> {
            try {
                ClusterDatabase.publishChatMessage(current, publish);
            } catch (Exception exception) {
                LOGGER.error("Unable to publish network chat message from node {}", current.nodeId(), exception);
            }
        });
    }

    private static void pollMessages() {
        ClusterConfig current = config;
        MinecraftServer currentServer = server;
        if (!active || current == null || currentServer == null) {
            return;
        }
        try {
            for (int page = 0; page < 10; page++) {
                List<ClusterDatabase.NetworkChatMessage> messages =
                        ClusterDatabase.listChatMessagesAfter(current, lastSequence, 100);
                if (messages.isEmpty()) {
                    break;
                }
                for (ClusterDatabase.NetworkChatMessage message : messages) {
                    if (isDeliveryTest(message)) {
                        if (!message.originNode().equalsIgnoreCase(current.nodeId())) {
                            ClusterDatabase.recordChatTestReceipt(
                                    current,
                                    message.messageId(),
                                    current.nodeId()
                            );
                        }
                        lastSequence = Math.max(lastSequence, message.sequence());
                        continue;
                    }
                    lastSequence = Math.max(lastSequence, message.sequence());
                    if (message.originNode().equalsIgnoreCase(current.nodeId())) {
                        continue;
                    }
                    currentServer.execute(() -> CointCoreGTO.receiveNetworkChatMessage(message));
                }
                if (messages.size() < 100) {
                    break;
                }
            }
        } catch (Exception exception) {
            LOGGER.error("Unable to poll network chat messages for node {}", current.nodeId(), exception);
        }
    }

    private static void forwardPendingDiscordMessages() {
        ClusterConfig current = config;
        if (!active
                || current == null
                || !current.discordClusterLeaderElection()
                || !discordLeader
                || !CointCoreGTODiscordProxy.isReady()) {
            return;
        }
        try {
            for (int page = 0; page < 5; page++) {
                List<ClusterDatabase.NetworkChatMessage> messages =
                        ClusterDatabase.listPendingDiscordChatMessages(current, 100);
                if (messages.isEmpty()) {
                    break;
                }
                for (ClusterDatabase.NetworkChatMessage message : messages) {
                    if (!"MINECRAFT".equalsIgnoreCase(message.originType())) {
                        ClusterDatabase.markChatMessageDiscordForwarded(current, message.sequence());
                        continue;
                    }
                    forwardToDiscord(
                            message.discordUsername(),
                            message.discordMessage(),
                            message.senderUuid(),
                            message.senderName()
                    );
                    ClusterDatabase.markChatMessageDiscordForwarded(current, message.sequence());
                }
                if (messages.size() < 100) {
                    break;
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("Unable to forward pending network chat messages to Discord", exception);
        }
    }

    private static void cleanupIfDue() {
        ClusterConfig current = config;
        long now = System.currentTimeMillis();
        if (current == null || now < nextCleanupMillis) {
            return;
        }
        nextCleanupMillis = now + cleanupIntervalMillis(current);
        try {
            ClusterDatabase.cleanupChatMessages(current, current.networkChatRetentionMinutes());
            ClusterDatabase.cleanupChatTestReceipts(current, 60);
        } catch (Exception exception) {
            LOGGER.warn("Unable to clean old network chat messages", exception);
        }
    }

    private static boolean isDeliveryTest(ClusterDatabase.NetworkChatMessage message) {
        return message != null
                && "TEST".equalsIgnoreCase(message.originType())
                && "DIAGNOSTIC".equalsIgnoreCase(message.channelName());
    }

    private static long cleanupIntervalMillis(ClusterConfig current) {
        if (current == null) {
            return 300_000L;
        }
        long halfRetention = current.networkChatRetentionMinutes() * 30_000L;
        return Math.max(60_000L, Math.min(300_000L, halfRetention));
    }

    private static void refreshDiscordLeadership(boolean force) {
        ClusterConfig current = config;
        DiscordSettings settings = discordSettings;
        MinecraftServer currentServer = server;
        if (!active || current == null || settings == null || currentServer == null || !settings.enabled()) {
            if (discordLeader) {
                discordLeader = false;
                CointCoreGTODiscordProxy.stop();
            }
            return;
        }
        if (!current.discordClusterLeaderElection()) {
            if (discordLeader) {
                discordLeader = false;
                CointCoreGTODiscordProxy.stop();
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now < nextDiscordLeaseCheckMillis) {
            return;
        }
        nextDiscordLeaseCheckMillis = now + Math.max(2_000L, current.discordClusterLeaseSeconds() * 1000L / 3L);
        try {
            boolean acquired = ClusterDatabase.tryAcquireOperationLease(
                    current,
                    DISCORD_LEASE_NAME,
                    current.discordClusterLeaseSeconds()
            );
            if (acquired) {
                lastDiscordLeaseSuccessMillis = now;
                if (!discordLeader) {
                    discordLeader = true;
                    startClusterDiscord(currentServer, settings);
                    LOGGER.info("Node {} became Discord bridge leader", current.nodeId());
                }
                return;
            }
        } catch (Exception exception) {
            LOGGER.warn("Unable to refresh Discord bridge lease for node {}", current.nodeId(), exception);
        }
        if (discordLeader && now - lastDiscordLeaseSuccessMillis >= current.discordClusterLeaseSeconds() * 1000L) {
            discordLeader = false;
            CointCoreGTODiscordProxy.stop();
            LOGGER.warn("Node {} lost Discord bridge leadership", current.nodeId());
        }
    }

    private static void startClusterDiscord(MinecraftServer minecraftServer, DiscordSettings settings) {
        CointCoreGTODiscordProxy.start(
                minecraftServer,
                settings.enabled(),
                settings.token(),
                settings.webhookUrl(),
                settings.avatarUrlTemplate(),
                settings.channelId(),
                settings.logChannelId(),
                false,
                settings.onlineStatusEnabled(),
                settings.onlineStatusChannelId(),
                settings.onlineStatusUpdateSeconds()
        );
    }

    private static void startStandaloneDiscord(MinecraftServer minecraftServer, DiscordSettings settings) {
        if (minecraftServer == null || settings == null) {
            return;
        }
        CointCoreGTODiscordProxy.start(
                minecraftServer,
                settings.enabled(),
                settings.token(),
                settings.webhookUrl(),
                settings.avatarUrlTemplate(),
                settings.channelId(),
                settings.logChannelId(),
                settings.sendServerStatus(),
                settings.onlineStatusEnabled(),
                settings.onlineStatusChannelId(),
                settings.onlineStatusUpdateSeconds()
        );
    }

    private static void forwardToDiscord(String username, String message, String uuid, String playerName) {
        CointCoreGTODiscordProxy.sendPlayerMessageToDiscord(
                username == null || username.isBlank() ? "Minecraft" : username,
                message == null ? "" : message,
                uuid == null ? "" : uuid,
                playerName == null ? "" : playerName
        );
    }

    public record DiscordSettings(
            boolean enabled,
            String token,
            String webhookUrl,
            String avatarUrlTemplate,
            String channelId,
            String logChannelId,
            boolean sendServerStatus,
            boolean onlineStatusEnabled,
            String onlineStatusChannelId,
            int onlineStatusUpdateSeconds
    ) {
    }
}
