package Crazer.cubeofinterest.cubechat;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod(CubeChat.MODID)
public class CubeChat {
    public static final String MODID = "cubechat";

    private static final ForgeConfigSpec CONFIG_SPEC;

    private static final ForgeConfigSpec.DoubleValue LOCAL_RADIUS;
    private static final ForgeConfigSpec.ConfigValue<String> LOCAL_PREFIX;
    private static final ForgeConfigSpec.ConfigValue<String> GLOBAL_PREFIX;
    private static final ForgeConfigSpec.ConfigValue<String> PRIVATE_PREFIX;
    private static final ForgeConfigSpec.ConfigValue<String> DISCORD_PREFIX;
    private static final ForgeConfigSpec.BooleanValue USE_EXCLAMATION_FOR_GLOBAL;
    private static final ForgeConfigSpec.BooleanValue SHOW_CHAT_PANEL_ON_JOIN;

    private static final ForgeConfigSpec.BooleanValue DISCORD_ENABLED;
    private static final ForgeConfigSpec.ConfigValue<String> DISCORD_BOT_TOKEN;
    private static final ForgeConfigSpec.ConfigValue<String> DISCORD_CHANNEL_ID;
    private static final ForgeConfigSpec.ConfigValue<String> DISCORD_LOG_CHANNEL_ID;
    private static final ForgeConfigSpec.BooleanValue DISCORD_SEND_SERVER_STATUS;
    private static final ForgeConfigSpec.BooleanValue DISCORD_SEND_GLOBAL_CHAT;
    private static final ForgeConfigSpec.BooleanValue DISCORD_SEND_LOCAL_CHAT;
    private static final ForgeConfigSpec.BooleanValue DISCORD_SEND_PRIVATE_CHAT;
    public static ForgeConfigSpec.IntValue RESERVED_PUBLIC_SLOTS;
    public static ForgeConfigSpec.IntValue RESERVED_TOTAL_SLOTS;
    public static ForgeConfigSpec.ConfigValue<String> RESERVED_PERMISSION;
    public static ForgeConfigSpec.ConfigValue<String> RESERVED_FULL_MESSAGE;
    public static ForgeConfigSpec.ConfigValue<String> RESERVED_NO_PERMISSION_MESSAGE;

    private static final Map<UUID, ChatView> CHAT_VIEWS = new HashMap<>();
    private static final Map<UUID, UUID> LAST_PRIVATE = new HashMap<>();
    private static final Map<UUID, Boolean> SHOW_TIME = new HashMap<>();

    private static MinecraftServer CURRENT_SERVER;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");

    enum ChatView {
        ALL,
        LOCAL,
        GLOBAL,
        PRIVATE
    }

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("chat");

        LOCAL_RADIUS = builder
                .comment("Radius of local chat in blocks.")
                .defineInRange("local_radius", 300.0D, 1.0D, 100000.0D);

        LOCAL_PREFIX = builder
                .comment("Prefix for local chat. Supports & color codes and §.")
                .define("local_prefix", "&a[L] ");

        GLOBAL_PREFIX = builder
                .comment("Prefix for global chat. Supports & color codes and §.")
                .define("global_prefix", "&6[G] ");

        PRIVATE_PREFIX = builder
                .comment("Prefix for private messages. Supports & color codes and §.")
                .define("private_prefix", "&d[PM] ");

        DISCORD_PREFIX = builder
                .comment("Prefix for messages from Discord in Minecraft chat.")
                .define("discord_prefix", "&9[D] ");

        USE_EXCLAMATION_FOR_GLOBAL = builder
                .comment("If true, messages starting with ! will be sent to global chat.")
                .define("use_exclamation_for_global", true);

        SHOW_CHAT_PANEL_ON_JOIN = builder
                .comment("If true, short chat hint will be shown when player joins.")
                .define("show_chat_panel_on_join", true);

        builder.pop();

        builder.push("reserved_slots");

        RESERVED_PUBLIC_SLOTS = builder
                .comment("How many slots are available for regular players.")
                .defineInRange("public_slots", 100, 1, 10000);

        RESERVED_TOTAL_SLOTS = builder
                .comment("Maximum players including reserved slots. This should be equal to or lower than max-players in server.properties.")
                .defineInRange("total_slots", 110, 1, 10000);

        RESERVED_PERMISSION = builder
                .comment("LuckPerms permission for joining reserved slots.")
                .define("permission", "cubechat.joinfull");

        RESERVED_FULL_MESSAGE = builder
                .comment("Kick message when even reserved slots are full.")
                .define("full_message", "Сервер заполнен.");

        RESERVED_NO_PERMISSION_MESSAGE = builder
                .comment("Kick message when only reserved slots are left.")
                .define("no_permission_message", "Сервер заполнен. Резервные слоты доступны только администрации и донатерам.");

        builder.pop();

        builder.push("discord");

        DISCORD_ENABLED = builder
                .comment("Enable Discord bridge.")
                .define("enabled", false);

        DISCORD_BOT_TOKEN = builder
                .comment("Discord bot token. Do not share it.")
                .define("bot_token", "TOKEN_HERE");

        DISCORD_CHANNEL_ID = builder
                .comment("Discord channel ID.")
                .define("channel_id", "CHANNEL_ID_HERE");

        DISCORD_LOG_CHANNEL_ID = builder
                .comment("Discord channel ID for local chat and private messages log.")
                .define("log_channel_id", "LOG_CHANNEL_ID_HERE");

        DISCORD_SEND_SERVER_STATUS = builder
                .comment("Send server start/stop messages to Discord.")
                .define("send_server_status", true);

        DISCORD_SEND_GLOBAL_CHAT = builder
                .comment("Send global Minecraft chat to Discord.")
                .define("send_global_chat", true);

        DISCORD_SEND_LOCAL_CHAT = builder
                .comment("Send local Minecraft chat to Discord.")
                .define("send_local_chat", false);

        DISCORD_SEND_PRIVATE_CHAT = builder
                .comment("Send private Minecraft messages to Discord.")
                .define("send_private_chat", false);

        builder.pop();

        CONFIG_SPEC = builder.build();
    }

    public CubeChat() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CONFIG_SPEC);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        CURRENT_SERVER = event.getServer();

        CubeDiscordBridge.start(
                CURRENT_SERVER,
                DISCORD_ENABLED.get(),
                DISCORD_BOT_TOKEN.get(),
                DISCORD_CHANNEL_ID.get(),
                DISCORD_LOG_CHANNEL_ID.get(),
                DISCORD_SEND_SERVER_STATUS.get()
        );
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        CubeDiscordBridge.stop();
        CURRENT_SERVER = null;
    }

    @SubscribeEvent
    public void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        CHAT_VIEWS.putIfAbsent(player.getUUID(), ChatView.ALL);

        if (SHOW_CHAT_PANEL_ON_JOIN.get()) {
            player.displayClientMessage(Component.literal("§7Откройте чат, чтобы выбрать канал: §f[ALL] §a[L] §6[G] §d[PM]"), true);
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("chat")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            player.displayClientMessage(Component.literal("§7Откройте чат и выберите канал сверху: §f[ALL] §a[L] §6[G] §d[PM]"), true);
                            return 1;
                        })

                        .then(Commands.literal("all")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    setChatView(player, ChatView.ALL);
                                    return 1;
                                }))

                        .then(Commands.literal("local")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    setChatView(player, ChatView.LOCAL);
                                    return 1;
                                }))

                        .then(Commands.literal("l")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    setChatView(player, ChatView.LOCAL);
                                    return 1;
                                }))

                        .then(Commands.literal("global")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    setChatView(player, ChatView.GLOBAL);
                                    return 1;
                                }))

                        .then(Commands.literal("g")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    setChatView(player, ChatView.GLOBAL);
                                    return 1;
                                }))

                        .then(Commands.literal("pm")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    setChatView(player, ChatView.PRIVATE);
                                    return 1;
                                }))

                        .then(Commands.literal("time")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    toggleTime(player);
                                    return 1;
                                }))
        );

        event.getDispatcher().register(
                Commands.literal("g")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String message = StringArgumentType.getString(ctx, "message");

                                    sendGlobalChat(player, message);
                                    return 1;
                                }))
        );

        event.getDispatcher().register(
                Commands.literal("global")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String message = StringArgumentType.getString(ctx, "message");

                                    sendGlobalChat(player, message);
                                    return 1;
                                }))
        );

        event.getDispatcher().register(
                Commands.literal("l")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String message = StringArgumentType.getString(ctx, "message");

                                    sendLocalChat(player, message);
                                    return 1;
                                }))
        );

        event.getDispatcher().register(
                Commands.literal("local")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String message = StringArgumentType.getString(ctx, "message");

                                    sendLocalChat(player, message);
                                    return 1;
                                }))
        );

        event.getDispatcher().register(
                Commands.literal("pm")
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            String message = StringArgumentType.getString(ctx, "message");

                                            sendPrivateMessage(player, target, message);
                                            return 1;
                                        })))
        );

        event.getDispatcher().register(
                Commands.literal("r")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String message = StringArgumentType.getString(ctx, "message");

                                    UUID lastUuid = LAST_PRIVATE.get(player.getUUID());

                                    if (lastUuid == null) {
                                        player.displayClientMessage(Component.literal("§cНекому ответить."), true);
                                        return 0;
                                    }

                                    ServerPlayer target = player.server.getPlayerList().getPlayer(lastUuid);

                                    if (target == null) {
                                        player.displayClientMessage(Component.literal("§cИгрок уже не в сети."), true);
                                        return 0;
                                    }

                                    sendPrivateMessage(player, target, message);
                                    return 1;
                                }))
        );
    }


    @SubscribeEvent
    public void onCommand(net.minecraftforge.event.CommandEvent event) {
        if (!(event.getParseResults().getContext().getSource().getEntity() instanceof ServerPlayer player)) {
            return;
        }

        String input = event.getParseResults().getReader().getString();

        if (input.startsWith("/")) {
            input = input.substring(1);
        }

        String lower = input.toLowerCase(java.util.Locale.ROOT);

        if (!lower.equals("tell")
                && !lower.startsWith("tell ")
                && !lower.equals("message")
                && !lower.startsWith("message ")
                && !lower.equals("w")
                && !lower.startsWith("w ")
                && !lower.equals("msg")
                && !lower.startsWith("msg ")) {
            return;
        }

        player.displayClientMessage(Component.literal(
                "§cВанильные личные сообщения отключены. Используйте §d/pm <ник> <сообщение> §cили §d/r <сообщение>§c."
        ), true);

        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        String message = event.getRawText();

        event.setCanceled(true);

        if (USE_EXCLAMATION_FOR_GLOBAL.get() && message.startsWith("!")) {
            String globalMessage = message.substring(1).trim();

            if (globalMessage.isEmpty()) {
                player.displayClientMessage(Component.literal("§cВведите сообщение после !"), true);
                return;
            }

            sendGlobalChat(player, globalMessage);
            return;
        }

        ChatView view = getChatView(player);

        if (view == ChatView.GLOBAL) {
            sendGlobalChat(player, message);
            return;
        }

        if (view == ChatView.PRIVATE) {
            player.displayClientMessage(Component.literal("§dЛичные сообщения отправляются так: §f/msg Ник сообщение §7или §f/r сообщение"), true);
            return;
        }

        sendLocalChat(player, message);
    }

    private static void setChatView(ServerPlayer player, ChatView view) {
        CHAT_VIEWS.put(player.getUUID(), view);

        if (view == ChatView.ALL) {
            player.displayClientMessage(Component.literal("§aТеперь вы видите все чаты."), true);
        }

        if (view == ChatView.LOCAL) {
            player.displayClientMessage(Component.literal("§aТеперь вы видите только локальный чат."), true);
        }

        if (view == ChatView.GLOBAL) {
            player.displayClientMessage(Component.literal("§6Теперь вы видите только глобальный чат."), true);
        }

        if (view == ChatView.PRIVATE) {
            player.displayClientMessage(Component.literal("§dТеперь вы видите только личные сообщения."), true);
        }
    }

    private static ChatView getChatView(ServerPlayer player) {
        return CHAT_VIEWS.getOrDefault(player.getUUID(), ChatView.ALL);
    }

    private static boolean canReceive(ServerPlayer target, ChatView messageType) {
        ChatView view = getChatView(target);

        if (view == ChatView.ALL) {
            return true;
        }

        return view == messageType;
    }

    private static void sendLocalChat(ServerPlayer player, String message) {
        String withoutTime = color(LOCAL_PREFIX.get())
                + getLuckPermsPrefix(player)
                + getStaffTag(player)
                + "§f" + player.getGameProfile().getName()
                + "§7: §f"
                + message;

        String discordFormatted = stripColor(withoutTime);

        int receivers = 0;
        double radius = LOCAL_RADIUS.get();
        double radiusSquared = radius * radius;

        for (ServerPlayer target : player.server.getPlayerList().getPlayers()) {
            if (!canReceive(target, ChatView.LOCAL)) {
                continue;
            }

            if (target.level().dimension() != player.level().dimension()) {
                continue;
            }

            if (target.distanceToSqr(player) > radiusSquared) {
                continue;
            }

            target.sendSystemMessage(Component.literal(timePrefix(target) + withoutTime));
            receivers++;
        }

        if (receivers <= 1) {
            player.displayClientMessage(Component.literal("§7Рядом никого нет. Для глобального чата используйте §e!сообщение §7или §e/g сообщение§7."), true);
        }

        if (DISCORD_SEND_LOCAL_CHAT.get()) {
            CubeDiscordBridge.sendToDiscordLog(discordFormatted);
        }

        System.out.println("[LocalChat] " + stripColor(timePrefix(player) + withoutTime));
    }

    private static void sendGlobalChat(ServerPlayer player, String message) {
        String withoutTime = color(GLOBAL_PREFIX.get())
                + getLuckPermsPrefix(player)
                + getStaffTag(player)
                + "§f" + player.getGameProfile().getName()
                + "§7: §f"
                + message;

        String discordFormatted = stripColor(withoutTime);

        for (ServerPlayer target : player.server.getPlayerList().getPlayers()) {
            if (!canReceive(target, ChatView.GLOBAL)) {
                continue;
            }

            target.sendSystemMessage(Component.literal(timePrefix(target) + withoutTime));
        }

        if (DISCORD_SEND_GLOBAL_CHAT.get()) {
            CubeDiscordBridge.sendToDiscord(discordFormatted);
        }

        System.out.println("[GlobalChat] " + stripColor(timePrefix(player) + withoutTime));
    }

    private static void sendPrivateMessage(ServerPlayer sender, ServerPlayer target, String message) {
        if (sender.getUUID().equals(target.getUUID())) {
            sender.displayClientMessage(Component.literal("§cНельзя написать самому себе."), true);
            return;
        }

        String senderName = sender.getGameProfile().getName();
        String targetName = target.getGameProfile().getName();

        String toSender = timePrefix(sender)
                + color(PRIVATE_PREFIX.get())
                + "§7Вы -> §d"
                + targetName
                + "§7: §f"
                + message;

        String toTarget = timePrefix(target)
                + color(PRIVATE_PREFIX.get())
                + "§d"
                + senderName
                + " §7-> Вы: §f"
                + message;

        sender.sendSystemMessage(Component.literal(toSender));
        target.sendSystemMessage(Component.literal(toTarget));

        LAST_PRIVATE.put(sender.getUUID(), target.getUUID());
        LAST_PRIVATE.put(target.getUUID(), sender.getUUID());

        if (DISCORD_SEND_PRIVATE_CHAT.get()) {
            CubeDiscordBridge.sendToDiscordLog("[PM] " + senderName + " -> " + targetName + ": " + message);
        }

        System.out.println("[PrivateChat] " + senderName + " -> " + targetName + ": " + message);
    }

    public static void broadcastDiscordMessage(String author, String message, String replyToMinecraftPlayer) {
        if (CURRENT_SERVER == null) {
            return;
        }

        String safeAuthor = sanitizeDiscord(author);
        String safeMessage = sanitizeDiscord(message);

        String formatted;

        if (replyToMinecraftPlayer != null && !replyToMinecraftPlayer.isBlank()) {
            formatted = timePrefix()
                    + color(DISCORD_PREFIX.get())
                    + "§f"
                    + safeAuthor
                    + " §7-> §e"
                    + sanitizeDiscord(replyToMinecraftPlayer)
                    + "§7: §f"
                    + safeMessage;
        } else {
            formatted = timePrefix()
                    + color(DISCORD_PREFIX.get())
                    + "§f"
                    + safeAuthor
                    + "§7: §f"
                    + safeMessage;
        }

        for (ServerPlayer target : CURRENT_SERVER.getPlayerList().getPlayers()) {
            if (!canReceive(target, ChatView.GLOBAL)) {
                continue;
            }

            target.sendSystemMessage(Component.literal(formatted));
        }

        System.out.println("[DiscordChat] " + safeAuthor + ": " + safeMessage);
    }

    private static String getLuckPermsPrefix(ServerPlayer player) {
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            User user = luckPerms.getPlayerAdapter(ServerPlayer.class).getUser(player);

            String prefix = user.getCachedData().getMetaData().getPrefix();

            if (prefix == null) {
                return "";
            }

            return color(prefix);
        } catch (Throwable e) {
            return "";
        }
    }

    private static String getPrimaryGroup(ServerPlayer player) {
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            User user = luckPerms.getPlayerAdapter(ServerPlayer.class).getUser(player);

            return user.getPrimaryGroup().toLowerCase();
        } catch (Throwable e) {
            return "";
        }
    }

    private static String getStaffTag(ServerPlayer player) {
        String group = getPrimaryGroup(player);

        return switch (group) {
            case "admin" -> "§4[Админ] ";
            case "curator" -> "§6[Куратор] ";
            case "headmoderator" -> "§c[Гл.Модератор] ";
            case "moderator" -> "§2[Модератор] ";
            case "helper" -> "§b[Хелпер] ";
            case "trainee" -> "§a[Стажёр] ";
            default -> "";
        };
    }

    private static String color(String text) {
        return text.replace("&", "§");
    }

    private static String stripColor(String text) {
        return text.replaceAll("§.", "");
    }

    private static String sanitizeDiscord(String text) {
        return text
                .replace("@everyone", "@\u200Beveryone")
                .replace("@here", "@\u200Bhere")
                .replace("§", "");
    }

    private static String timePrefix() {
        return "§8[" + ZonedDateTime.now(MOSCOW_ZONE).format(TIME_FORMAT) + " МСК] ";
    }

    private static boolean shouldShowTime(ServerPlayer player) {
        return SHOW_TIME.getOrDefault(player.getUUID(), true);
    }

    private static void toggleTime(ServerPlayer player) {
        boolean current = shouldShowTime(player);
        boolean next = !current;

        SHOW_TIME.put(player.getUUID(), next);

        if (next) {
            player.displayClientMessage(Component.literal("§aВремя в чате включено."), true);
        } else {
            player.displayClientMessage(Component.literal("§cВремя в чате выключено."), true);
        }
    }

    private static String timePrefix(ServerPlayer player) {
        if (!shouldShowTime(player)) {
            return "";
        }

        return "§8[" + ZonedDateTime.now(MOSCOW_ZONE).format(TIME_FORMAT) + " МСК] ";
    }
}