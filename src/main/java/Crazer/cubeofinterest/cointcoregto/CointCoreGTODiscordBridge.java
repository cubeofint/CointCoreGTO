package Crazer.cubeofinterest.cointcoregto;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class CointCoreGTODiscordBridge {
    private static JDA jda;
    private static TextChannel textChannel;
    private static TextChannel logChannel;
    private static String botToken = "";
    private static MinecraftServer server;
    private static boolean enabled = false;
    private static boolean sendServerStatus = true;
    private static long lastOnlineStatusErrorLogMillis = 0L;

    private static String webhookUrl = "";
    private static String avatarUrlTemplate = "https://mawlee.org/api/skin-api/skins/%username%.png";
    private static boolean onlineStatusEnabled = false;
    private static String onlineStatusChannelId = "";
    private static int onlineStatusUpdateSeconds = 60;
    private static ScheduledExecutorService onlineStatusExecutor;
    private static ScheduledFuture<?> pendingOnlineStatusUpdate;
    private static final AtomicBoolean onlineStatusRequestInFlight = new AtomicBoolean(false);
    private static final AtomicBoolean onlineStatusUpdatePending = new AtomicBoolean(false);
    private static final AtomicBoolean onlineStatusPinResolveInFlight = new AtomicBoolean(false);
    private static volatile boolean onlineStatusPinResolved = false;
    private static volatile String lastOnlineStatusText = "";
    private static volatile String lastBotPresenceText = "";
    private static volatile long onlineStatusRetryNotBeforeMillis = 0L;
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

    public static void start(
            MinecraftServer minecraftServer,
            boolean bridgeEnabled,
            String token,
            String configuredWebhookUrl,
            String configuredAvatarUrlTemplate,
            String channelId,
            String logChannelId,
            boolean statusMessages,
            boolean configuredOnlineStatusEnabled,
            String configuredOnlineStatusChannelId,
            int configuredOnlineStatusUpdateSeconds
    ) {
        server = minecraftServer;
        enabled = bridgeEnabled;
        sendServerStatus = statusMessages;
        botToken = token == null ? "" : token.trim();
        webhookUrl = configuredWebhookUrl == null ? "" : configuredWebhookUrl.trim();
        avatarUrlTemplate = configuredAvatarUrlTemplate == null || configuredAvatarUrlTemplate.isBlank()
                ? ""
                : configuredAvatarUrlTemplate.trim();
        onlineStatusEnabled = configuredOnlineStatusEnabled;
        onlineStatusChannelId = configuredOnlineStatusChannelId == null ? "" : configuredOnlineStatusChannelId.trim();
        onlineStatusUpdateSeconds = Math.max(10, configuredOnlineStatusUpdateSeconds);

        stopOnlineStatusUpdater();
        System.out.println("[CointDiscord] Starting bridge. onlineStatus=" + onlineStatusEnabled
                + ", update=" + onlineStatusUpdateSeconds + "s");

        if (!enabled) {
            System.out.println("[CointDiscord] Discord bridge is disabled.");
            return;
        }

        if (token == null || token.isBlank() || token.equalsIgnoreCase("TOKEN_HERE")) {
            System.out.println("[CointDiscord] Bot token is empty. Discord bridge disabled.");
            return;
        }

        if (channelId == null || channelId.isBlank() || channelId.equalsIgnoreCase("CHANNEL_ID_HERE")) {
            System.out.println("[CointDiscord] Channel ID is empty. Discord bridge disabled.");
            return;
        }

        try {
            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_EMOJIS_AND_STICKERS)
                    .addEventListeners(new ListenerAdapter() {
                        @Override
                        public void onMessageReceived(@NotNull MessageReceivedEvent event) {
                            handleDiscordMessage(event);
                        }
                    })
                    .build();

            ensureOnlineStatusUpdater();
            requestOnlineStatusUpdate();

            new Thread(() -> {
                try {
                    jda.awaitReady();

                    textChannel = jda.getTextChannelById(channelId);

                    if (logChannelId != null
                            && !logChannelId.isBlank()
                            && !logChannelId.equalsIgnoreCase("LOG_CHANNEL_ID_HERE")) {
                        logChannel = jda.getTextChannelById(logChannelId);

                        if (logChannel == null) {
                            System.out.println("[CointDiscord] Log channel not found: " + logChannelId);
                        }
                    }

                    if (textChannel == null) {
                        System.out.println("[CointDiscord] Channel not found: " + channelId);
                        return;
                    }

                    resolvePinnedOnlineStatusMessage(getOnlineStatusChannel());
                    updateBotPresenceNow();
                    System.out.println("[CointDiscord] Discord bridge connected.");
                    CointCoreGTOEmoji.refreshFromJda(jda);
                    CointCoreGTOEmoji.broadcastEmojiRegistry();

                    if (sendServerStatus) {
                        sendToDiscord("**[A] сервер включился!**");
                    }

                    ensureOnlineStatusUpdater();
                    requestOnlineStatusUpdate();
                } catch (Throwable e) {
                    System.out.println("[CointDiscord] Failed to start Discord bridge: " + e.getMessage());
                    e.printStackTrace();
                }
            }, "CointDiscord-Init").start();

        } catch (Throwable e) {
            System.out.println("[CointDiscord] Failed to create JDA: " + e.getMessage());
        }
    }

    public static void reload(
            MinecraftServer minecraftServer,
            boolean bridgeEnabled,
            String token,
            String configuredWebhookUrl,
            String configuredAvatarUrlTemplate,
            String channelId,
            String logChannelId,
            boolean statusMessages,
            boolean configuredOnlineStatusEnabled,
            String configuredOnlineStatusChannelId,
            int configuredOnlineStatusUpdateSeconds
    ) {
        boolean oldSendServerStatus = sendServerStatus;

        try {
            sendServerStatus = false;
            stop();
        } finally {
            sendServerStatus = oldSendServerStatus;
        }

        start(
                minecraftServer,
                bridgeEnabled,
                token,
                configuredWebhookUrl,
                configuredAvatarUrlTemplate,
                channelId,
                logChannelId,
                statusMessages,
                configuredOnlineStatusEnabled,
                configuredOnlineStatusChannelId,
                configuredOnlineStatusUpdateSeconds
        );
    }

    public static void stop() {
        stopOnlineStatusUpdater();

        JDA oldJda = jda;
        TextChannel oldTextChannel = textChannel;

        jda = null;
        textChannel = null;
        logChannel = null;
        server = null;
        botToken = "";

        if (sendServerStatus && oldTextChannel != null) {
            try {
                oldTextChannel
                        .sendMessage("**[A] сервер выключился**")
                        .setAllowedMentions(java.util.Collections.emptyList())
                        .complete(false);
            } catch (Throwable ignored) {
            }
        }

        if (oldJda != null) {
            try {
                oldJda.shutdownNow();
            } catch (Throwable ignored) {
                try {
                    oldJda.shutdown();
                } catch (Throwable ignoredAgain) {
                }
            }
        }

        CointCoreGTOEmoji.clearServerRegistry();
    }

    public static boolean isReady() {
        return enabled && jda != null && textChannel != null;
    }

    public static void sendToDiscord(String message) {
        if (!enabled) {
            return;
        }

        if (textChannel == null) {
            return;
        }

        if (message == null || message.isBlank()) {
            return;
        }

        String safe = sanitizeMessageForDiscord(CointCoreGTOEmoji.minecraftToDiscord(message));

        try {
            textChannel
                    .sendMessage(safe)
                    .setAllowedMentions(java.util.Collections.emptyList())
                    .queue();
        } catch (Throwable e) {
            System.out.println("[CointDiscord] Failed to send message: " + e.getMessage());
        }
    }

    public static void sendPlayerMessageToDiscord(String username, String message, String uuid, String playerName) {
        if (!enabled || message == null || message.isBlank()) {
            return;
        }

        if (webhookUrl == null || webhookUrl.isBlank()) {
            String fallbackName = username == null || username.isBlank() ? "Minecraft" : username;
            sendToDiscord("**" + sanitizeMessageForDiscord(fallbackName) + "**: " + sanitizeMessageForDiscord(CointCoreGTOEmoji.minecraftToDiscord(message)));
            return;
        }

        String safeUsername = username == null || username.isBlank() ? "Minecraft" : username.trim();
        if (safeUsername.length() > 80) {
            safeUsername = safeUsername.substring(0, 80);
        }

        String avatarUrl = buildAvatarUrl(uuid, playerName);
        String payload = "{"
                + "\"username\":\"" + jsonEscape(safeUsername) + "\","
                + "\"content\":\"" + jsonEscape(sanitizeMessageForDiscord(CointCoreGTOEmoji.minecraftToDiscord(message))) + "\","
                + "\"allowed_mentions\":{\"parse\":[]}"
                + (avatarUrl.isBlank() ? "" : ",\"avatar_url\":\"" + jsonEscape(avatarUrl) + "\"")
                + "}";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(error -> {
                        System.out.println("[CointDiscord] Failed to send webhook message: " + error.getMessage());
                        return null;
                    });
        } catch (Throwable e) {
            System.out.println("[CointDiscord] Failed to build webhook request: " + e.getMessage());
        }
    }

    public static void sendPlayerMessageToDiscord(String username, String message, String uuid) {
        sendPlayerMessageToDiscord(username, message, uuid, username);
    }

    public static void sendToDiscordLog(String message) {
        if (!enabled) {
            return;
        }

        if (logChannel == null) {
            return;
        }

        if (message == null || message.isBlank()) {
            return;
        }

        String safe = sanitizeMessageForDiscord(CointCoreGTOEmoji.minecraftToDiscord(message));

        try {
            logChannel
                    .sendMessage(safe)
                    .setAllowedMentions(java.util.Collections.emptyList())
                    .queue();
        } catch (Throwable e) {
            System.out.println("[CointDiscord] Failed to send log message: " + e.getMessage());
        }
    }

    private static boolean isMainDiscordChannel(MessageReceivedEvent event) {
        if (event == null || textChannel == null || event.getChannel() == null) {
            return false;
        }

        String targetId = textChannel.getId();
        String channelId = event.getChannel().getId();

        if (targetId.equals(channelId)) {
            return true;
        }
        try {
            Object channel = event.getChannel();
            Object parentChannel = channel.getClass().getMethod("getParentChannel").invoke(channel);

            if (parentChannel != null) {
                Object parentId = parentChannel.getClass().getMethod("getId").invoke(parentChannel);
                return targetId.equals(String.valueOf(parentId));
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static void handleDiscordMessage(MessageReceivedEvent event) {
        if (!enabled) {
            return;
        }

        if (event.getAuthor().isBot()) {
            return;
        }

        if (textChannel == null) {
            return;
        }

        if (!isMainDiscordChannel(event)) {
            return;
        }

        String author = event.getAuthor().getName();

        String message = event.getMessage().getContentRaw();

        if (message == null || message.isBlank()) {
            message = event.getMessage().getContentDisplay();
        }

        message = removeBotMention(event, message);
        message = CointCoreGTOEmoji.discordToMinecraft(message);
        message = sanitizeDiscordMessageForMinecraft(message);

        if (message == null || message.isBlank()) {
            return;
        }

        String replyToMinecraftPlayer = null;

        Message referenced = event.getMessage().getReferencedMessage();
        if (referenced != null && referenced.getAuthor().isBot()) {
            if (!referenced.getAuthor().getId().equals(event.getJDA().getSelfUser().getId())) {
                replyToMinecraftPlayer = extractMinecraftNameFromWebhookAuthor(referenced.getAuthor().getName());
            }
            if (replyToMinecraftPlayer == null || replyToMinecraftPlayer.isBlank()) {
                replyToMinecraftPlayer = extractMinecraftNameFromBotMessage(referenced.getContentDisplay());
            }
        }

        if (server == null) {
            return;
        }

        String finalMessage = message;
        String finalReplyToMinecraftPlayer = replyToMinecraftPlayer;

        server.execute(() -> CointCoreGTO.broadcastDiscordMessage(author, finalMessage, finalReplyToMinecraftPlayer));
    }

    public static void requestOnlineStatusUpdate() {
        if (!enabled || !onlineStatusEnabled || jda == null || server == null) {
            return;
        }

        ensureOnlineStatusUpdater();

        ScheduledExecutorService executor = onlineStatusExecutor;
        if (executor == null || executor.isShutdown()) {
            return;
        }

        synchronized (CointCoreGTODiscordBridge.class) {
            if (pendingOnlineStatusUpdate != null) {
                pendingOnlineStatusUpdate.cancel(false);
            }

            long now = System.currentTimeMillis();
            long retryDelayMillis = Math.max(0L, onlineStatusRetryNotBeforeMillis - now);
            long delayMillis = Math.max(10_000L, retryDelayMillis);

            pendingOnlineStatusUpdate = executor.schedule(
                    () -> safeUpdateOnlineStatusMessageNow("event"),
                    delayMillis,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private static synchronized void ensureOnlineStatusUpdater() {
        if (!enabled || !onlineStatusEnabled || jda == null || server == null) {
            return;
        }

        if (onlineStatusExecutor != null && !onlineStatusExecutor.isShutdown()) {
            return;
        }

        onlineStatusExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CointDiscord-OnlineStatus");
            thread.setDaemon(true);
            return thread;
        });

        long initialDelay = Math.min(15L, onlineStatusUpdateSeconds);
        onlineStatusExecutor.scheduleAtFixedRate(
                () -> safeUpdateOnlineStatusMessageNow("timer"),
                initialDelay,
                onlineStatusUpdateSeconds,
                TimeUnit.SECONDS
        );

        System.out.println("[CointDiscord] Online status updater started.");
    }

    private static void stopOnlineStatusUpdater() {
        synchronized (CointCoreGTODiscordBridge.class) {
            if (pendingOnlineStatusUpdate != null) {
                pendingOnlineStatusUpdate.cancel(false);
                pendingOnlineStatusUpdate = null;
            }
        }

        if (onlineStatusExecutor != null) {
            try {
                onlineStatusExecutor.shutdownNow();
            } catch (Throwable ignored) {
            }
        }

        onlineStatusExecutor = null;
        onlineStatusRequestInFlight.set(false);
        onlineStatusUpdatePending.set(false);
        onlineStatusPinResolveInFlight.set(false);
        onlineStatusPinResolved = false;
        lastOnlineStatusText = "";
        lastBotPresenceText = "";
        onlineStatusRetryNotBeforeMillis = 0L;
    }

    private static void safeUpdateOnlineStatusMessageNow(String reason) {
        try {
            updateOnlineStatusMessageNow();
        } catch (Throwable error) {
            System.out.println("[CointDiscord] Online status update failed [" + reason + "]: " + error.getMessage());
            error.printStackTrace();
        }
    }

    private static void updateOnlineStatusMessageNow() {
        if (!enabled || !onlineStatusEnabled || jda == null || server == null) {
            return;
        }

        MinecraftServer minecraftServer = server;
        minecraftServer.execute(() -> {
            updateBotPresenceNow();
            if (!enabled || !onlineStatusEnabled || jda == null || server == null) {
                return;
            }

            TextChannel statusChannel = getOnlineStatusChannel();
            if (statusChannel == null) {
                onlineStatusUpdatePending.set(true);
                requestOnlineStatusUpdate();
                return;
            }

            if (!onlineStatusPinResolved) {
                resolvePinnedOnlineStatusMessage(statusChannel);
                return;
            }

            String messageText = buildOnlineStatusMessage();
            if (messageText.equals(lastOnlineStatusText)) {
                return;
            }

            if (System.currentTimeMillis() < onlineStatusRetryNotBeforeMillis) {
                onlineStatusUpdatePending.set(true);
                requestOnlineStatusUpdate();
                return;
            }

            if (!onlineStatusRequestInFlight.compareAndSet(false, true)) {
                onlineStatusUpdatePending.set(true);
                return;
            }

            String messageId = loadOnlineStatusMessageId();

            if (messageId == null || messageId.isBlank()) {
                sendNewOnlineStatusMessage(statusChannel, messageText);
                return;
            }

            editOnlineStatusMessageRaw(statusChannel, messageId, messageText);
        });
    }

    private static TextChannel getOnlineStatusChannel() {
        if (jda == null) {
            return null;
        }

        if (onlineStatusChannelId != null && !onlineStatusChannelId.isBlank()) {
            TextChannel channel = jda.getTextChannelById(onlineStatusChannelId);
            if (channel != null) {
                return channel;
            }
        }

        return textChannel;
    }


    private static void resolvePinnedOnlineStatusMessage(TextChannel channel) {
        if (channel == null || onlineStatusPinResolved) {
            return;
        }

        if (!onlineStatusPinResolveInFlight.compareAndSet(false, true)) {
            return;
        }

        try {
            channel.retrievePinnedMessages().queue(messages -> {
                try {
                    JDA currentJda = jda;
                    String selfId = currentJda == null ? "" : currentJda.getSelfUser().getId();
                    Message pinnedStatus = messages.stream()
                            .filter(message -> message != null)
                            .filter(message -> !selfId.isBlank() && selfId.equals(message.getAuthor().getId()))
                            .filter(message -> isOnlineStatusMessageText(message.getContentRaw()))
                            .min(Comparator.comparing(Message::getTimeCreated))
                            .orElse(null);

                    if (pinnedStatus != null) {
                        String savedId = loadOnlineStatusMessageId();
                        if (!pinnedStatus.getId().equals(savedId)) {
                            saveOnlineStatusMessageId(pinnedStatus.getId());
                            System.out.println("[CointDiscord] Rebound online status to pinned message. old="
                                    + (savedId == null || savedId.isBlank() ? "<empty>" : savedId)
                                    + ", new="
                                    + pinnedStatus.getId());
                        } else {
                            System.out.println("[CointDiscord] Pinned online status message confirmed. ID: "
                                    + pinnedStatus.getId());
                        }
                    } else {
                        System.out.println("[CointDiscord] No matching pinned online status message found; using saved message ID.");
                    }

                    lastOnlineStatusText = "";
                } finally {
                    onlineStatusPinResolved = true;
                    onlineStatusPinResolveInFlight.set(false);
                    safeUpdateOnlineStatusMessageNow("pin-resolved");
                }
            }, error -> {
                onlineStatusPinResolved = true;
                onlineStatusPinResolveInFlight.set(false);
                System.out.println("[CointDiscord] Failed to inspect pinned messages: " + error.getMessage());
                safeUpdateOnlineStatusMessageNow("pin-resolve-failed");
            });
        } catch (Throwable error) {
            onlineStatusPinResolved = true;
            onlineStatusPinResolveInFlight.set(false);
            System.out.println("[CointDiscord] Failed to inspect pinned messages: " + error.getMessage());
            safeUpdateOnlineStatusMessageNow("pin-resolve-failed");
        }
    }

    private static boolean isOnlineStatusMessageText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String normalized = text.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("cube of interest") && normalized.contains("онлайн:");
    }

    private static void editOnlineStatusMessageRaw(TextChannel channel, String messageId, String messageText) {
        if (channel == null || messageId == null || messageId.isBlank()) {
            return;
        }

        if (botToken == null || botToken.isBlank()) {
            logOnlineStatusError("[CointDiscord] Cannot edit online status message: bot token is empty.");
            sendNewOnlineStatusMessage(channel, messageText);
            return;
        }

        String payload = "{"
                + "\"content\":\"" + jsonEscape(messageText == null ? "" : messageText) + "\","
                + "\"allowed_mentions\":{\"parse\":[]}"
                + "}";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://discord.com/api/v10/channels/"
                            + channel.getId()
                            + "/messages/"
                            + messageId.trim()))
                    .header("Authorization", "Bot " + botToken)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenAccept(response -> {
                        int code = response.statusCode();

                        if (code >= 200 && code < 300) {
                            onlineStatusRetryNotBeforeMillis = 0L;
                            lastOnlineStatusText = messageText == null ? "" : messageText;
                            System.out.println("[CointDiscord] Online status message updated. channel="
                                    + channel.getId()
                                    + ", message="
                                    + messageId.trim());
                            finishOnlineStatusRequest();
                            return;
                        }

                        String body = response.body() == null ? "" : response.body();

                        if (code == 404) {
                            sendNewOnlineStatusMessage(channel, messageText);
                            return;
                        }

                        if (code == 429) {
                            long retryMillis = parseRetryAfterMillis(body);
                            onlineStatusRetryNotBeforeMillis = Math.max(
                                    onlineStatusRetryNotBeforeMillis,
                                    System.currentTimeMillis() + retryMillis
                            );
                            onlineStatusUpdatePending.set(true);
                            logOnlineStatusError("[CointDiscord] Discord rate limited online status edit. Retrying same message in "
                                    + retryMillis
                                    + " ms. Body: "
                                    + body);
                            finishOnlineStatusRequest();
                            return;
                        }

                        logOnlineStatusError("[CointDiscord] Failed to edit online status message via HTTP. Code: "
                                + code
                                + ", body: "
                                + body);
                        finishOnlineStatusRequest();
                    })
                    .exceptionally(error -> {
                        logOnlineStatusError("[CointDiscord] Failed to edit online status message via HTTP: "
                                + error.getMessage());
                        finishOnlineStatusRequest();
                        return null;
                    });
        } catch (Throwable e) {
            logOnlineStatusError("[CointDiscord] Failed to build online status edit request: " + e.getMessage());
            finishOnlineStatusRequest();
        }
    }

    private static long parseRetryAfterMillis(String body) {
        if (body == null || body.isBlank()) {
            return 10_000L;
        }

        int key = body.indexOf("\"retry_after\"");
        if (key < 0) {
            return 10_000L;
        }

        int colon = body.indexOf(':', key);
        if (colon < 0) {
            return 10_000L;
        }

        int start = colon + 1;
        while (start < body.length() && Character.isWhitespace(body.charAt(start))) {
            start++;
        }

        int end = start;
        while (end < body.length()) {
            char c = body.charAt(end);
            if ((c >= '0' && c <= '9') || c == '.') {
                end++;
                continue;
            }
            break;
        }

        if (end <= start) {
            return 10_000L;
        }

        try {
            double seconds = Double.parseDouble(body.substring(start, end));
            return Math.max(1_000L, (long) Math.ceil(seconds * 1000.0D) + 500L);
        } catch (NumberFormatException ignored) {
            return 10_000L;
        }
    }

    private static void finishOnlineStatusRequest() {
        onlineStatusRequestInFlight.set(false);

        if (onlineStatusUpdatePending.getAndSet(false)) {
            requestOnlineStatusUpdate();
        }
    }

    private static void logOnlineStatusError(String message) {
        long now = System.currentTimeMillis();

        if (now - lastOnlineStatusErrorLogMillis < 60_000L) {
            return;
        }

        lastOnlineStatusErrorLogMillis = now;
        System.out.println(message);
    }

    private static void sendNewOnlineStatusMessage(TextChannel channel, String messageText) {
        try {
            channel.sendMessage(messageText).queue(message -> {
                saveOnlineStatusMessageId(message.getId());
                lastOnlineStatusText = messageText == null ? "" : messageText;
                try {
                    message.pin().queue(ignored -> {
                    }, ignored -> {
                    });
                } catch (Throwable ignored) {
                }
                System.out.println("[CointDiscord] Created online status message. ID: " + message.getId());
                finishOnlineStatusRequest();
            }, error -> {
                System.out.println("[CointDiscord] Failed to create online status message: " + error.getMessage());
                finishOnlineStatusRequest();
            });
        } catch (Throwable e) {
            System.out.println("[CointDiscord] Failed to send online status message: " + e.getMessage());
            finishOnlineStatusRequest();
        }
    }

    private static void updateBotPresenceNow() {
        JDA currentJda = jda;
        MinecraftServer minecraftServer = server;
        if (!enabled || currentJda == null || minecraftServer == null) {
            return;
        }

        int online = (int) minecraftServer.getPlayerList().getPlayers()
                .stream()
                .filter(CointCoreGTO::shouldShowInDiscordOnlineStatus)
                .count();
        int max = minecraftServer.getPlayerList().getMaxPlayers();
        String presenceText = "Онлайн [" + online + "/" + max + "]";

        if (presenceText.equals(lastBotPresenceText)) {
            return;
        }

        try {
            currentJda.getPresence().setActivity(Activity.customStatus(presenceText));
            lastBotPresenceText = presenceText;
            System.out.println("[CointDiscord] Bot status updated: " + presenceText);
        } catch (Throwable error) {
            System.out.println("[CointDiscord] Failed to update bot status: " + error.getMessage());
        }
    }

    private static String buildOnlineStatusMessage() {
        MinecraftServer minecraftServer = server;
        if (minecraftServer == null) {
            return "🔴 **Cube Of Interest** — сервер выключен";
        }

        List<ServerPlayer> players = minecraftServer.getPlayerList().getPlayers()
                .stream()
                .filter(CointCoreGTO::shouldShowInDiscordOnlineStatus)
                .toList();
        int online = players.size();
        int max = minecraftServer.getPlayerList().getMaxPlayers();

        StringBuilder builder = new StringBuilder();
        builder.append("🟢 **Cube Of Interest** — онлайн: **")
                .append(online)
                .append("/")
                .append(max)
                .append("**\n");

        if (online <= 0) {
            builder.append("\nИгроков онлайн нет.");
            return builder.toString();
        }

        builder.append("\n**Игроки:**\n");

        int added = 0;
        for (ServerPlayer player : players) {
            String line = "• " + CointCoreGTO.getDiscordDisplayName(player) + "\n";

            if (builder.length() + line.length() > 1900) {
                builder.append("• ...и ещё ").append(online - added).append("\n");
                break;
            }

            builder.append(line);
            added++;
        }

        return builder.toString();
    }

    private static Path onlineStatusMessageIdPath() {
        return FMLPaths.CONFIGDIR.get().resolve("cubechat-discord-online-message-id.txt");
    }

    private static String loadOnlineStatusMessageId() {
        try {
            Path path = onlineStatusMessageIdPath();
            if (!Files.exists(path)) {
                return "";
            }

            return Files.readString(path, StandardCharsets.UTF_8).trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void saveOnlineStatusMessageId(String messageId) {
        try {
            Path path = onlineStatusMessageIdPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, messageId == null ? "" : messageId.trim(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            System.out.println("[CointDiscord] Failed to save online status message ID: " + e.getMessage());
        }
    }

    private static String removeBotMention(MessageReceivedEvent event, String message) {
        if (message == null || message.isBlank()) {
            return message;
        }

        String botId = event.getJDA().getSelfUser().getId();
        String botName = event.getJDA().getSelfUser().getName();

        message = message.replaceFirst("^<@!?" + java.util.regex.Pattern.quote(botId) + ">\\s*", "");

        message = message.replaceFirst("^@" + java.util.regex.Pattern.quote(botName) + "\\s*", "");

        return message.trim();
    }

    private static String sanitizeDiscordMessageForMinecraft(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String cleaned = text;
        cleaned = cleaned.replaceAll("\\[([^\\]]+)]\\((?i:https?://)[^\\s)]+\\)", "$1");
        cleaned = cleaned.replaceAll("<(?i:https?://)[^>\\s]+>", "");
        cleaned = cleaned.replaceAll("(?i:https?://)\\S+", "");
        cleaned = cleaned.replaceAll("(?i:www\\.)\\S+", "");
        cleaned = cleaned.replaceAll("[ \t]{2,}", " ");
        cleaned = cleaned.replaceAll(" ?\\n ?", "\n");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");
        return cleaned.trim();
    }

    private static String extractMinecraftNameFromWebhookAuthor(String authorName) {
        if (authorName == null || authorName.isBlank()) {
            return null;
        }

        String value = authorName.trim();

        while (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) {
                break;
            }
            value = value.substring(close + 1).trim();
        }

        if (value.isBlank()) {
            return null;
        }

        String[] parts = value.split("\\s+");
        if (parts.length == 0) {
            return null;
        }

        String candidate = parts[parts.length - 1]
                .replaceAll("^[^A-Za-z0-9_]+", "")
                .replaceAll("[^A-Za-z0-9_]+$", "");

        if (candidate.isBlank() || candidate.length() > 16) {
            return null;
        }

        return candidate;
    }

    private static String extractMinecraftNameFromBotMessage(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        int colon = text.indexOf(":");
        if (colon <= 0) {
            return null;
        }

        String beforeColon = text.substring(0, colon).trim();

        beforeColon = beforeColon.replaceFirst("^\\[[^\\]]+]\\s*", "");

        beforeColon = beforeColon.replaceFirst("^\\[[^\\]]+]\\s*", "");

        String[] parts = beforeColon.trim().split("\\s+");

        if (parts.length == 0) {
            return null;
        }

        return parts[parts.length - 1];
    }

    private static String sanitizeMessageForDiscord(String message) {
        if (message == null) {
            return "";
        }

        return message
                .replaceAll("§.", "")
                .replace("@everyone", "@\u200Beveryone")
                .replace("@here", "@\u200Bhere");
    }

    private static String buildAvatarUrl(String uuid, String playerName) {
        String template = avatarUrlTemplate;
        if (template == null || template.isBlank()) {
            return "";
        }

        String safeName = playerName == null ? "" : playerName.trim();
        String safeUuid = uuid == null ? "" : uuid.trim();

        if (safeName.isBlank() && safeUuid.isBlank()) {
            return "";
        }

        try {
            String encodedName = URLEncoder.encode(safeName, StandardCharsets.UTF_8).replace("+", "%20");
            String encodedUuid = URLEncoder.encode(safeUuid, StandardCharsets.UTF_8).replace("+", "%20");

            return template
                    .replace("%username%", encodedName)
                    .replace("%name%", encodedName)
                    .replace("{username}", encodedName)
                    .replace("{name}", encodedName)
                    .replace("%uuid%", encodedUuid)
                    .replace("{uuid}", encodedUuid);
        } catch (Throwable e) {
            System.out.println("[CointDiscord] Failed to build avatar URL: " + e.getMessage());
            return "";
        }
    }

    private static String jsonEscape(String text) {
        if (text == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            switch (c) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }

        return builder.toString();
    }
}
