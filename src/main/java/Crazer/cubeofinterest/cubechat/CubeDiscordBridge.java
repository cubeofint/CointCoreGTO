package Crazer.cubeofinterest.cubechat;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;

public class CubeDiscordBridge {
    private static JDA jda;
    private static TextChannel textChannel;
    private static TextChannel logChannel;
    private static MinecraftServer server;
    private static boolean enabled = false;
    private static boolean sendServerStatus = true;

    public static void start(
            MinecraftServer minecraftServer,
            boolean bridgeEnabled,
            String token,
            String channelId,
            String logChannelId,
            boolean statusMessages
    ) {
        server = minecraftServer;
        enabled = bridgeEnabled;
        sendServerStatus = statusMessages;

        if (!enabled) {
            System.out.println("[CubeDiscord] Discord bridge is disabled.");
            return;
        }

        if (token == null || token.isBlank() || token.equalsIgnoreCase("TOKEN_HERE")) {
            System.out.println("[CubeDiscord] Bot token is empty. Discord bridge disabled.");
            return;
        }

        if (channelId == null || channelId.isBlank() || channelId.equalsIgnoreCase("CHANNEL_ID_HERE")) {
            System.out.println("[CubeDiscord] Channel ID is empty. Discord bridge disabled.");
            return;
        }

        try {
            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(new ListenerAdapter() {
                        @Override
                        public void onMessageReceived(@NotNull MessageReceivedEvent event) {
                            handleDiscordMessage(event);
                        }
                    })
                    .build();

            new Thread(() -> {
                try {
                    jda.awaitReady();

                    textChannel = jda.getTextChannelById(channelId);

                    if (logChannelId != null
                            && !logChannelId.isBlank()
                            && !logChannelId.equalsIgnoreCase("LOG_CHANNEL_ID_HERE")) {
                        logChannel = jda.getTextChannelById(logChannelId);

                        if (logChannel == null) {
                            System.out.println("[CubeDiscord] Log channel not found: " + logChannelId);
                        }
                    }

                    if (textChannel == null) {
                        System.out.println("[CubeDiscord] Channel not found: " + channelId);
                        return;
                    }

                    System.out.println("[CubeDiscord] Discord bridge connected.");

                    if (sendServerStatus) {
                        sendToDiscord("**[A] сервер включился!**");
                    }
                } catch (Exception e) {
                    System.out.println("[CubeDiscord] Failed to start Discord bridge: " + e.getMessage());
                }
            }, "CubeDiscord-Init").start();

        } catch (Throwable e) {
            System.out.println("[CubeDiscord] Failed to create JDA: " + e.getMessage());
        }
    }

    public static void stop() {
        if (sendServerStatus) {
            sendToDiscord("**[A] сервер выключился**");
        }

        if (jda != null) {
            try {
                jda.shutdown();
            } catch (Throwable ignored) {
            }
        }

        jda = null;
        textChannel = null;
        logChannel = null;
        server = null;
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

        String safe = sanitizeMessageForDiscord(message);

        try {
            textChannel
                    .sendMessage(safe)
                    .setAllowedMentions(java.util.Collections.emptyList())
                    .queue();
        } catch (Throwable e) {
            System.out.println("[CubeDiscord] Failed to send message: " + e.getMessage());
        }
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

        String safe = sanitizeMessageForDiscord(message);

        try {
            logChannel
                    .sendMessage(safe)
                    .setAllowedMentions(java.util.Collections.emptyList())
                    .queue();
        } catch (Throwable e) {
            System.out.println("[CubeDiscord] Failed to send log message: " + e.getMessage());
        }
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

        if (!event.getChannel().getId().equals(textChannel.getId())) {
            return;
        }

        String author = event.getAuthor().getName();

        // ВАЖНО:
        // getContentRaw() не превращает Discord emoji в ссылки CDN.
        String message = event.getMessage().getContentRaw();

        message = removeBotMention(event, message);
        message = removeDiscordEmojis(message);

        if (message == null || message.isBlank()) {
            return;
        }

        String replyToMinecraftPlayer = null;

        Message referenced = event.getMessage().getReferencedMessage();
        if (referenced != null && referenced.getAuthor().isBot()) {
            replyToMinecraftPlayer = extractMinecraftNameFromBotMessage(referenced.getContentDisplay());
        }

        if (server == null) {
            return;
        }

        String finalMessage = message;
        String finalReplyToMinecraftPlayer = replyToMinecraftPlayer;

        server.execute(() -> CubeChat.broadcastDiscordMessage(author, finalMessage, finalReplyToMinecraftPlayer));
    }

    private static String removeBotMention(MessageReceivedEvent event, String message) {
        if (message == null || message.isBlank()) {
            return message;
        }

        String botId = event.getJDA().getSelfUser().getId();
        String botName = event.getJDA().getSelfUser().getName();

        // Убирает упоминание вида <@123> или <@!123>
        message = message.replaceFirst("^<@!?" + java.util.regex.Pattern.quote(botId) + ">\\s*", "");

        // Убирает отображаемое упоминание вида @Crazer
        message = message.replaceFirst("^@" + java.util.regex.Pattern.quote(botName) + "\\s*", "");

        return message.trim();
    }

    private static String removeDiscordEmojis(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        // Кастомные Discord emoji:
        // <:name:id> и <a:name:id>
        text = text.replaceAll("<a?:[a-zA-Z0-9_~]+:\\d+>", "");

        // Если где-то всё-таки прилетела markdown-ссылка на emoji CDN
        text = text.replaceAll("\\[[^\\]]*]\\(https://cdn\\.discordapp\\.com/emojis/[^)]*\\)", "");

        // Обычные unicode emoji
        text = text.replaceAll("[\\x{1F300}-\\x{1FAFF}]", "");
        text = text.replaceAll("[\\x{2600}-\\x{27BF}]", "");

        // Variation selectors и zero-width joiner, которые часто остаются после emoji
        text = text.replaceAll("[\\x{FE00}-\\x{FE0F}]", "");
        text = text.replaceAll("\\x{200D}", "");

        return text.replaceAll("\\s{2,}", " ").trim();
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

        // Убираем время, если оно вдруг осталось: [23:56 МСК]
        beforeColon = beforeColon.replaceFirst("^\\[[^\\]]+\\]\\s*", "");

        // Убираем канал: [G], [L], [PM]
        beforeColon = beforeColon.replaceFirst("^\\[[^\\]]+\\]\\s*", "");

        String[] parts = beforeColon.trim().split("\\s+");

        if (parts.length == 0) {
            return null;
        }

        return parts[parts.length - 1];
    }

    private static String sanitizeMessageForDiscord(String message) {
        return message
                .replace("@everyone", "@\u200Beveryone")
                .replace("@here", "@\u200Bhere");
    }
}