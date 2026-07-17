package Crazer.cubeofinterest.cointcoregto;

import Crazer.cubeofinterest.cointcoregto.compat.radio.CointRadioBlocks;
import Crazer.cubeofinterest.cointcoregto.compat.radio.CointRadioNetwork;
import Crazer.cubeofinterest.cointcoregto.exchanger.CointExchangerClient;
import Crazer.cubeofinterest.cointcoregto.exchanger.CointExchangerNetwork;
import Crazer.cubeofinterest.cointcoregto.exchanger.CointExchangerRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.io.WritingMode;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Mod(CointCoreGTO.MODID)
public class CointCoreGTO {
    public static final String MODID = "cointcoregto";

    private static final Logger LOCAL_CHAT_LOGGER = LogManager.getLogger("CuBe:LocalChat");
    private static final Logger GLOBAL_CHAT_LOGGER = LogManager.getLogger("CuBe:GlobalChat");
    private static final Logger TRADE_CHAT_LOGGER = LogManager.getLogger("CuBe:TradeChat");
    private static final Logger PRIVATE_CHAT_LOGGER = LogManager.getLogger("CuBe:PrivateChat");
    private static final Logger DISCORD_CHAT_LOGGER = LogManager.getLogger("CuBe:DiscordChat");

    private static final String NETWORK_PROTOCOL_VERSION = "1";
    private static final SimpleChannel NETWORK_CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID, "main"),
            () -> NETWORK_PROTOCOL_VERSION,
            NETWORK_PROTOCOL_VERSION::equals,
            NETWORK_PROTOCOL_VERSION::equals
    );
    private static boolean NETWORK_REGISTERED = false;

    private static final ForgeConfigSpec CONFIG_SPEC;

    private static final ForgeConfigSpec.DoubleValue LOCAL_RADIUS;
    private static final ForgeConfigSpec.ConfigValue<String> LOCAL_PREFIX;
    private static final ForgeConfigSpec.ConfigValue<String> GLOBAL_PREFIX;
    private static final ForgeConfigSpec.ConfigValue<String> TRADE_PREFIX;
    private static final ForgeConfigSpec.ConfigValue<String> PRIVATE_PREFIX;
    private static final ForgeConfigSpec.ConfigValue<String> DISCORD_PREFIX;
    private static final ForgeConfigSpec.BooleanValue USE_EXCLAMATION_FOR_GLOBAL;
    private static final ForgeConfigSpec.BooleanValue USE_DOLLAR_FOR_TRADE;
    private static final ForgeConfigSpec.IntValue TRADE_COOLDOWN_SECONDS;
    private static final ForgeConfigSpec.BooleanValue ITEM_PING_LOCAL_ENABLED;
    private static final ForgeConfigSpec.BooleanValue ITEM_PING_GLOBAL_ENABLED;
    private static final ForgeConfigSpec.BooleanValue ITEM_PING_TRADE_ENABLED;
    private static final ForgeConfigSpec.BooleanValue ITEM_PING_PRIVATE_ENABLED;
    private static final ForgeConfigSpec.BooleanValue SHOW_CHAT_PANEL_ON_JOIN;
    private static final ForgeConfigSpec.BooleanValue HIDE_JOIN_LEAVE_MESSAGES;
    public static ForgeConfigSpec.BooleanValue RADIO_ENABLED;
    public static ForgeConfigSpec.ConfigValue<String> RADIO_DEFAULT_STATION;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> RADIO_STATIONS;
    public static ForgeConfigSpec.ConfigValue<String> RADIO_ON_MESSAGE;
    public static ForgeConfigSpec.ConfigValue<String> RADIO_OFF_MESSAGE;
    public static ForgeConfigSpec.IntValue RADIO_RADIUS;

    private static final ForgeConfigSpec.BooleanValue ANNOUNCE_MUTES;
    private static final ForgeConfigSpec.BooleanValue ANNOUNCE_BANS;
    private static final ForgeConfigSpec.BooleanValue ANNOUNCE_WARNS;

    private static final ForgeConfigSpec.BooleanValue DISCORD_ENABLED;
    private static final ForgeConfigSpec.ConfigValue<String> DISCORD_BOT_TOKEN;
    private static final ForgeConfigSpec.ConfigValue<String> DISCORD_WEBHOOK_URL;
    private static final ForgeConfigSpec.ConfigValue<String> DISCORD_AVATAR_URL_TEMPLATE;
    private static final ForgeConfigSpec.ConfigValue<String> DISCORD_CHANNEL_ID;
    private static final ForgeConfigSpec.ConfigValue<String> DISCORD_LOG_CHANNEL_ID;
    private static final ForgeConfigSpec.BooleanValue DISCORD_SEND_SERVER_STATUS;
    private static final ForgeConfigSpec.BooleanValue DISCORD_ONLINE_STATUS_ENABLED;
    private static final ForgeConfigSpec.ConfigValue<String> DISCORD_ONLINE_STATUS_CHANNEL_ID;
    private static final ForgeConfigSpec.IntValue DISCORD_ONLINE_STATUS_UPDATE_SECONDS;
    private static final ForgeConfigSpec.BooleanValue DISCORD_SEND_GLOBAL_CHAT;
    private static final ForgeConfigSpec.BooleanValue DISCORD_SEND_LOCAL_CHAT;
    private static final ForgeConfigSpec.BooleanValue DISCORD_SEND_PRIVATE_CHAT;
    public static ForgeConfigSpec.IntValue RESERVED_PUBLIC_SLOTS;
    public static ForgeConfigSpec.IntValue RESERVED_TOTAL_SLOTS;
    public static ForgeConfigSpec.ConfigValue<String> RESERVED_PERMISSION;
    public static ForgeConfigSpec.ConfigValue<String> RESERVED_FULL_MESSAGE;
    public static ForgeConfigSpec.ConfigValue<String> RESERVED_NO_PERMISSION_MESSAGE;

    private static final ForgeConfigSpec.BooleanValue RESTART_ENABLED;
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> RESTART_TIMES;
    private static final ForgeConfigSpec.ConfigValue<List<? extends Integer>> RESTART_WARNING_MINUTES;
    private static final ForgeConfigSpec.IntValue RESTART_COUNTDOWN_SECONDS;
    private static final ForgeConfigSpec.BooleanValue RESTART_SHOW_TITLE;
    private static final ForgeConfigSpec.BooleanValue RESTART_SHOW_ACTIONBAR;
    private static final ForgeConfigSpec.BooleanValue RESTART_SHOW_CHAT;
    private static final ForgeConfigSpec.BooleanValue RESTART_KICK_PLAYERS;
    private static final ForgeConfigSpec.IntValue RESTART_KICK_SECONDS_BEFORE_STOP;
    private static final ForgeConfigSpec.ConfigValue<String> RESTART_KICK_MESSAGE;

    private static final Map<UUID, ChatView> CHAT_VIEWS = new HashMap<>();
    private static final Map<UUID, UUID> LAST_PRIVATE = new HashMap<>();
    private static final Map<UUID, Boolean> SHOW_TIME = new HashMap<>();
    private static final Map<UUID, Long> LAST_ITEM_SHARE_MILLIS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> LAST_TRADE_MESSAGE_MILLIS = new ConcurrentHashMap<>();
    private static final long ITEM_SHARE_COOLDOWN_MILLIS = 3000L;
    private static final Map<UUID, Deque<ChatHistoryMessage>> CHAT_HISTORY = new ConcurrentHashMap<>();
    private static final AtomicLong CHAT_HISTORY_COUNTER = new AtomicLong();
    private static final Map<UUID, Long> LAST_CHAT_VIEW_SWITCH_MILLIS = new ConcurrentHashMap<>();
    private static final long CHAT_VIEW_SWITCH_COOLDOWN_MILLIS = 300L;
    private static final int MAX_CHAT_HISTORY_PER_PLAYER = 5000;
    private static final int MAX_CHAT_HISTORY_REPLAY = 250;
    private static final Map<UUID, MuteData> MUTED_PLAYERS = new ConcurrentHashMap<>();
    private static final Map<UUID, TempBanData> TEMP_BANNED_PLAYERS = new ConcurrentHashMap<>();
    private static final Map<UUID, LastLocationData> LAST_LOCATIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, ArrayList<WarnData>> WARNED_PLAYERS = new ConcurrentHashMap<>();
    private static final Map<UUID, ArrayList<PunishmentHistoryData>> PUNISHMENT_HISTORY = new ConcurrentHashMap<>();

    private static long NEXT_RESTART_MILLIS = -1L;
    private static long LAST_RESTART_CHECK_SECOND = -1L;
    private static boolean RESTARTING_NOW = false;
    private static boolean RESTART_PLAYERS_KICKED = false;
    private static final Set<Integer> SENT_RESTART_WARNINGS = ConcurrentHashMap.newKeySet();

    private static MinecraftServer CURRENT_SERVER;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId MOSCOW_ZONE = ZoneId.of("Europe/Moscow");

    enum ChatView {
        ALL,
        LOCAL,
        GLOBAL,
        TRADE,
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

        TRADE_PREFIX = builder
                .comment("Prefix for trade chat. Supports & color codes and §.")
                .define("trade_prefix", "&b[$] ");

        PRIVATE_PREFIX = builder
                .comment("Prefix for private messages. Supports & color codes and §.")
                .define("private_prefix", "&d[PM] ");

        DISCORD_PREFIX = builder
                .comment("Prefix for messages from Discord in Minecraft chat.")
                .define("discord_prefix", "&9[D] ");

        USE_EXCLAMATION_FOR_GLOBAL = builder
                .comment("If true, messages starting with ! will be sent to global chat.")
                .define("use_exclamation_for_global", true);

        USE_DOLLAR_FOR_TRADE = builder
                .comment("If true, messages starting with $ will be sent to trade chat.")
                .define("use_dollar_for_trade", true);

        TRADE_COOLDOWN_SECONDS = builder
                .comment("Cooldown between trade chat messages in seconds. 0 disables cooldown.")
                .defineInRange("trade_cooldown_seconds", 10, 0, 3600);

        ITEM_PING_LOCAL_ENABLED = builder.define("item_ping_local_enabled", true);
        ITEM_PING_GLOBAL_ENABLED = builder.define("item_ping_global_enabled", true);
        ITEM_PING_TRADE_ENABLED = builder.define("item_ping_trade_enabled", true);
        ITEM_PING_PRIVATE_ENABLED = builder.define("item_ping_private_enabled", true);

        SHOW_CHAT_PANEL_ON_JOIN = builder
                .comment("If true, short chat hint will be shown when player joins.")
                .define("show_chat_panel_on_join", true);

        HIDE_JOIN_LEAVE_MESSAGES = builder
                .comment("If true, CointCoreGTO hides vanilla player join and leave messages on clients with the mod installed.")
                .define("hide_join_leave_messages", true);

        builder.pop();

        builder.push("moderation");

        ANNOUNCE_MUTES = builder
                .comment("If true, mute and unmute messages are announced to all players in chat.")
                .define("announce_mutes", false);

        ANNOUNCE_BANS = builder
                .comment("If true, tempban, untempban and unban messages are announced to all players in chat.")
                .define("announce_bans", false);

        ANNOUNCE_WARNS = builder
                .comment("If true, warn and unwarn messages are announced to all players in chat.")
                .define("announce_warns", false);

        builder.pop();

        builder.push("reserved_slots");

        RESERVED_PUBLIC_SLOTS = builder
                .comment("How many slots are available for regular players.")
                .defineInRange("public_slots", 50, 1, 10000);

        RESERVED_TOTAL_SLOTS = builder
                .comment("Maximum players including reserved slots. This should be equal to or lower than max-players in server.properties.")
                .defineInRange("total_slots", 75, 1, 10000);

        RESERVED_PERMISSION = builder
                .comment("LuckPerms permission for joining reserved slots.")
                .define("permission", "cointcoregto.joinfull");

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

        DISCORD_WEBHOOK_URL = builder
                .comment("Discord webhook URL for Minecraft -> Discord player messages. If empty, the bot sends messages normally.")
                .define("webhook_url", "");

        DISCORD_AVATAR_URL_TEMPLATE = builder
                .comment("Avatar URL template for Minecraft -> Discord webhook messages. Use %username% for player name and %uuid% for UUID. Example: https://mawlee.org/api/skin-api/skins/%username%.png")
                .define("avatar_url_template", "https://mawlee.org/api/skin-api/skins/%username%.png");

        DISCORD_CHANNEL_ID = builder
                .comment("Discord channel ID.")
                .define("channel_id", "CHANNEL_ID_HERE");

        DISCORD_LOG_CHANNEL_ID = builder
                .comment("Discord channel ID for local chat and private messages log.")
                .define("log_channel_id", "LOG_CHANNEL_ID_HERE");

        DISCORD_SEND_SERVER_STATUS = builder
                .comment("Send server start/stop messages to Discord.")
                .define("send_server_status", true);

        DISCORD_ONLINE_STATUS_ENABLED = builder
                .comment("Keep one Discord message updated with current server online list.")
                .define("online_status_enabled", true);

        DISCORD_ONLINE_STATUS_CHANNEL_ID = builder
                .comment("Discord channel ID for online status message. If empty, main channel_id is used.")
                .define("online_status_channel_id", "");

        DISCORD_ONLINE_STATUS_UPDATE_SECONDS = builder
                .comment("How often to edit the online status message.")
                .defineInRange("online_status_update_seconds", 60, 15, 3600);

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

        builder.push("restart");

        RESTART_ENABLED = builder
                .comment("Enable automatic scheduled restarts. CointCoreGTO stops the server; your host/start script must start it again.")
                .define("enabled", false);

        RESTART_TIMES = builder
                .comment("Restart times in Europe/Moscow timezone, HH:mm format. Example: 06:00, 18:00")
                .defineList("times", List.of("06:00", "18:00"), value -> value instanceof String);

        RESTART_WARNING_MINUTES = builder
                .comment("Warnings before restart, in minutes.")
                .defineList("warning_minutes", List.of(30, 15, 10, 5, 3, 2, 1), value -> value instanceof Integer integer && integer >= 1);

        RESTART_COUNTDOWN_SECONDS = builder
                .comment("Big title countdown in the last N seconds before restart.")
                .defineInRange("countdown_seconds", 10, 0, 60);

        RESTART_SHOW_TITLE = builder
                .comment("Show big title on players screens for restart warnings.")
                .define("show_title", true);

        RESTART_SHOW_ACTIONBAR = builder
                .comment("Show actionbar restart warnings.")
                .define("show_actionbar", true);

        RESTART_SHOW_CHAT = builder
                .comment("Send restart warnings to chat.")
                .define("show_chat", true);

        RESTART_KICK_PLAYERS = builder
                .comment("Disconnect players before /stop with restart message.")
                .define("kick_players", true);

        RESTART_KICK_SECONDS_BEFORE_STOP = builder
                .comment("How many seconds before /stop players should be kicked. This helps avoid item/inventory loss when players are interacting right before restart.")
                .defineInRange("kick_seconds_before_stop", 10, 0, 60);

        RESTART_KICK_MESSAGE = builder
                .comment("Kick message when automatic restart begins.")
                .define("kick_message", "Сервер перезапускается. Зайдите через пару минут.");

        builder.pop();

        builder.push("radio");

        RADIO_ENABLED = builder
                .comment("Enable CointCoreGTO radio block.")
                .define("enabled", true);

        RADIO_RADIUS = builder
                .comment("Radio block radius in blocks.")
                .defineInRange("radius", 24, 1, 128);

        RADIO_DEFAULT_STATION = builder.comment("Default station id from stations list.")
                .define("default_station", "dorognoe");

        RADIO_STATIONS = builder.defineList(
                "stations",
                List.of(
                        "dorognoe|Дорожное радио|http://dorognoe.hostingradio.ru:8000/dorognoe",
                        "europa_plus|Европа Плюс|http://ep128.hostingradio.ru:8030/ep128",
                        "shanson|Радио Шансон|http://chanson.hostingradio.ru:8041/chanson128.mp3",

                        "soma_groove|SomaFM Groove Salad|https://somafm.com/groovesalad.pls",
                        "soma_drone|SomaFM Drone Zone|https://somafm.com/dronezone.pls",
                        "soma_defcon|SomaFM DEF CON Radio|https://somafm.com/defcon.pls",
                        "soma_lush|SomaFM Lush|https://somafm.com/lush.pls",
                        "soma_secret|SomaFM Secret Agent|https://somafm.com/secretagent.pls",
                        "soma_indiepop|SomaFM Indie Pop Rocks|https://somafm.com/indiepop.pls"
                ),
                value -> {
                    if (!(value instanceof String string)) {
                        return false;
                    }

                    String trimmed = string.trim();

                    if (trimmed.isBlank()) {
                        return false;
                    }

                    if (trimmed.contains("=")) {
                        String[] parts = trimmed.split("=", 2);

                        return parts.length == 2
                                && !parts[0].trim().isBlank()
                                && isValidRadioUrl(parts[1].trim());
                    }

                    if (trimmed.contains("|")) {
                        String[] parts = trimmed.split("\\|", 3);

                        return parts.length == 3
                                && !parts[0].trim().isBlank()
                                && !parts[1].trim().isBlank()
                                && isValidRadioUrl(parts[2].trim());
                    }

                    return false;
                }
        );

        RADIO_ON_MESSAGE = builder
                .comment("Actionbar message when radio starts. Use %station% for station id.")
                .define("on_message", "§a[CointMusic] Радио включено: §f%station%");

        RADIO_OFF_MESSAGE = builder
                .comment("Actionbar message when radio stops.")
                .define("off_message", "§c[CointMusic] Радио выключено.");

        builder.pop();

        CONFIG_SPEC = builder.build();
    }

    public CointCoreGTO() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        CointRadioBlocks.register(modEventBus);
        CointExchangerRegistry.register(modEventBus);

        modEventBus.addListener(this::onClientSetup);

        registerNetwork();
        CointCoreGTOItemShare.registerNetwork();
        CointCoreGTOEmoji.registerNetwork();
        CointRadioNetwork.register();
        CointExchangerNetwork.register();

        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CONFIG_SPEC, "cubechat-common.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, DimensionQuestLockConfig.SPEC, "CointCoreGTO-FTBQuest-Dimension-Locking.toml");
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(CointExchangerClient::registerScreens);
    }


    public static boolean shouldHideJoinLeaveMessages() {
        try {
            return HIDE_JOIN_LEAVE_MESSAGES.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static void reloadCointCoreGTOConfig() {
        Path configPath = FMLPaths.CONFIGDIR.get().resolve("cubechat-common.toml");

        CommentedFileConfig configData = CommentedFileConfig.builder(configPath)
                .sync()
                .autosave()
                .writingMode(WritingMode.REPLACE)
                .build();

        configData.load();
        CONFIG_SPEC.setConfig(configData);

        resetRestartSchedule();
        reloadDiscordBridgeFromConfig();
    }

    private static void reloadDiscordBridgeFromConfig() {
        if (CURRENT_SERVER == null) {
            return;
        }

        CointCoreGTODiscordProxy.reload(
                CURRENT_SERVER,
                DISCORD_ENABLED.get(),
                DISCORD_BOT_TOKEN.get(),
                DISCORD_WEBHOOK_URL.get(),
                DISCORD_AVATAR_URL_TEMPLATE.get(),
                DISCORD_CHANNEL_ID.get(),
                DISCORD_LOG_CHANNEL_ID.get(),
                DISCORD_SEND_SERVER_STATUS.get(),
                DISCORD_ONLINE_STATUS_ENABLED.get(),
                DISCORD_ONLINE_STATUS_CHANNEL_ID.get(),
                DISCORD_ONLINE_STATUS_UPDATE_SECONDS.get()
        );
    }

    private enum PunishmentAnnounceType {
        MUTE,
        BAN,
        WARN
    }

    private static boolean shouldAnnouncePunishment(PunishmentAnnounceType type) {
        try {
            return switch (type) {
                case MUTE -> ANNOUNCE_MUTES.get();
                case BAN -> ANNOUNCE_BANS.get();
                case WARN -> ANNOUNCE_WARNS.get();
            };
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void announcePunishment(MinecraftServer server, PunishmentAnnounceType type, String message) {
        if (server == null || !shouldAnnouncePunishment(type)) {
            return;
        }

        Component component = Component.literal(message);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(component);
        }
    }

    private static void registerNetwork() {
        if (NETWORK_REGISTERED) {
            return;
        }

        NETWORK_CHANNEL.messageBuilder(ClearChatPacket.class, 0)
                .encoder(ClearChatPacket::encode)
                .decoder(ClearChatPacket::decode)
                .consumerMainThread(ClearChatPacket::handle)
                .add();

        NETWORK_REGISTERED = true;
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        CURRENT_SERVER = event.getServer();
        loadTempBans();
        loadLastLocations();
        loadWarns();
        loadPunishmentHistory();
        CointCoreGTODiscordProxy.start(
                CURRENT_SERVER,
                DISCORD_ENABLED.get(),
                DISCORD_BOT_TOKEN.get(),
                DISCORD_WEBHOOK_URL.get(),
                DISCORD_AVATAR_URL_TEMPLATE.get(),
                DISCORD_CHANNEL_ID.get(),
                DISCORD_LOG_CHANNEL_ID.get(),
                DISCORD_SEND_SERVER_STATUS.get(),
                DISCORD_ONLINE_STATUS_ENABLED.get(),
                DISCORD_ONLINE_STATUS_CHANNEL_ID.get(),
                DISCORD_ONLINE_STATUS_UPDATE_SECONDS.get()
        );

        resetRestartSchedule();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        saveTempBans();
        saveLastLocations();
        saveWarns();
        savePunishmentHistory();
        CointCoreGTODiscordProxy.stop();
        NEXT_RESTART_MILLIS = -1L;
        LAST_RESTART_CHECK_SECOND = -1L;
        RESTARTING_NOW = false;
        RESTART_PLAYERS_KICKED = false;
        SENT_RESTART_WARNINGS.clear();
        CURRENT_SERVER = null;
    }

    @SubscribeEvent
    public void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }


        if (isTempBanned(player)) {
            disconnectTempBannedPlayer(player);
            return;
        }

        saveLastLocation(player);
        CointCoreGTOEmoji.sendEmojiRegistry(player);

        CHAT_VIEWS.putIfAbsent(player.getUUID(), ChatView.ALL);

        if (SHOW_CHAT_PANEL_ON_JOIN.get()) {
            player.displayClientMessage(Component.literal("§7Откройте чат, чтобы выбрать канал: §f[ALL] §a[L] §6[G] §b[$] §d[PM]"), true);
        }

        CointCoreGTODiscordProxy.requestOnlineStatusUpdate();
    }

    @SubscribeEvent
    public void onLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        saveLastLocation(player);
        saveLastLocations();
        CHAT_HISTORY.remove(player.getUUID());
        LAST_CHAT_VIEW_SWITCH_MILLIS.remove(player.getUUID());

        CointCoreGTODiscordProxy.requestOnlineStatusUpdate();
    }

    private static void removeRootCommand(CommandDispatcher<CommandSourceStack> dispatcher, String commandName) {
        if (dispatcher == null || commandName == null || commandName.isBlank()) {
            return;
        }

        try {
            CommandNode<CommandSourceStack> root = dispatcher.getRoot();
            removeCommandNodeChild(root, commandName);
        } catch (Throwable e) {
            System.out.println("[CointCoreGTO] Failed to remove vanilla command /" + commandName + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void removeCommandNodeChild(CommandNode<CommandSourceStack> node, String childName) throws ReflectiveOperationException {
        String loweredName = childName.toLowerCase(java.util.Locale.ROOT);

        Field childrenField = CommandNode.class.getDeclaredField("children");
        childrenField.setAccessible(true);
        ((Map<String, CommandNode<CommandSourceStack>>) childrenField.get(node)).remove(loweredName);

        Field literalsField = CommandNode.class.getDeclaredField("literals");
        literalsField.setAccessible(true);
        ((Map<String, CommandNode<CommandSourceStack>>) literalsField.get(node)).remove(loweredName);

        Field argumentsField = CommandNode.class.getDeclaredField("arguments");
        argumentsField.setAccessible(true);
        ((Map<String, CommandNode<CommandSourceStack>>) argumentsField.get(node)).remove(loweredName);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        removeRootCommand(event.getDispatcher(), "me");

        event.getDispatcher().register(
                Commands.literal("cointcoregto")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.reload"))
                        .then(Commands.literal("reload")
                                .executes(ctx -> {
                                    try {
                                        reloadCointCoreGTOConfig();
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("§aКонфиг CointCoreGTO перезагружен. Расписание рестартов и Discord bridge обновлены."),
                                                false
                                        );
                                        return 1;
                                    } catch (Throwable e) {
                                        ctx.getSource().sendFailure(Component.literal("§cНе удалось перезагрузить конфиг CointCoreGTO: " + e.getMessage()));
                                        e.printStackTrace();
                                        return 0;
                                    }
                                }))
                        .then(Commands.literal("discordlog")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String message = StringArgumentType.getString(ctx, "message");

                                            if (message == null || message.isBlank()) {
                                                return 0;
                                            }

                                            CointCoreGTODiscordProxy.sendToDiscordLog(stripColor(message));
                                            return 1;
                                        })))
                        .then(Commands.literal("discordannounce")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            String message = StringArgumentType.getString(ctx, "message");

                                            if (message == null || message.isBlank()) {
                                                return 0;
                                            }

                                            CointCoreGTODiscordProxy.sendToDiscord(stripColor(message));
                                            return 1;
                                        })))
        );



        event.getDispatcher().register(
                Commands.literal("chat")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            player.displayClientMessage(Component.literal("§7Откройте чат и выберите канал сверху: §f[ALL] §a[L] §6[G] §b[$] §d[PM]"), true);
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

                        .then(Commands.literal("trade")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    setChatView(player, ChatView.TRADE);
                                    return 1;
                                }))

                        .then(Commands.literal("t")
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    setChatView(player, ChatView.TRADE);
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
                Commands.literal("mute")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.mute"))
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("time", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            String time = StringArgumentType.getString(ctx, "time");
                                            long duration = parsePunishmentTime(time);

                                            if (duration <= 0L) {
                                                ctx.getSource().sendFailure(Component.literal("Использование: /mute <ник> <10s/5m/2h/1d> [причина]"));
                                                return 0;
                                            }

                                            mutePlayer(target, duration, "");
                                            recordPunishment(target.getUUID(), target.getGameProfile().getName(), "MUTE", getCommandSourceName(ctx.getSource()), duration, "");

                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Игрок " + target.getGameProfile().getName() + " замучен на " + time),
                                                    false
                                            );
                                            announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.MUTE, "§c[Модерация] §fИгрок §e" + target.getGameProfile().getName() + " §fполучил мут на §e" + time + "§f.");
                                            target.sendSystemMessage(Component.literal("§cТы получил мут на §e" + time));
                                            return 1;
                                        })
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                                    String time = StringArgumentType.getString(ctx, "time");
                                                    String reason = StringArgumentType.getString(ctx, "reason");
                                                    long duration = parsePunishmentTime(time);

                                                    if (duration <= 0L) {
                                                        ctx.getSource().sendFailure(Component.literal("Использование: /mute <ник> <10s/5m/2h/1d> [причина]"));
                                                        return 0;
                                                    }

                                                    mutePlayer(target, duration, reason);
                                                    recordPunishment(target.getUUID(), target.getGameProfile().getName(), "MUTE", getCommandSourceName(ctx.getSource()), duration, reason);

                                                    ctx.getSource().sendSuccess(
                                                            () -> Component.literal("Игрок " + target.getGameProfile().getName() + " замучен на " + time + ". Причина: " + reason),
                                                            false
                                                    );
                                                    announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.MUTE, "§c[Модерация] §fИгрок §e" + target.getGameProfile().getName() + " §fполучил мут на §e" + time + "§f. Причина: §7" + reason);
                                                    target.sendSystemMessage(Component.literal("§cТы получил мут на §e" + time));
                                                    target.sendSystemMessage(Component.literal("§cПричина: §f" + reason));
                                                    return 1;
                                                })
                                        )
                                )
                        )
        );

        event.getDispatcher().register(
                Commands.literal("unmute")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.mute"))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");

                                    if (!MUTED_PLAYERS.containsKey(target.getUUID())) {
                                        ctx.getSource().sendFailure(Component.literal("Игрок не замучен."));
                                        return 0;
                                    }

                                    unmutePlayer(target);
                                    recordPunishment(target.getUUID(), target.getGameProfile().getName(), "UNMUTE", getCommandSourceName(ctx.getSource()), 0L, "");

                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("Мут снят с игрока " + target.getGameProfile().getName()),
                                            false
                                    );
                                    announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.MUTE, "§a[Модерация] §fС игрока §e" + target.getGameProfile().getName() + " §fснят мут.");
                                    target.sendSystemMessage(Component.literal("§aС тебя сняли мут"));
                                    return 1;
                                })
                        )
        );


        event.getDispatcher().register(
                Commands.literal("unban")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.unban"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestKnownPlayerNames(builder))
                                .executes(ctx -> {
                                    String targetName = StringArgumentType.getString(ctx, "target");

                                    UUID targetUuid = findKnownUuidByName(ctx.getSource().getServer(), targetName);
                                    ctx.getSource().getServer().getCommands().performPrefixedCommand(
                                            ctx.getSource(),
                                            "pardon " + targetName
                                    );
                                    if (targetUuid != null) {
                                        recordPunishment(targetUuid, targetName, "UNBAN", getCommandSourceName(ctx.getSource()), 0L, "");
                                    }

                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("Бан снят с игрока " + targetName),
                                            false
                                    );
                                    announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.BAN, "§a[Модерация] §fС игрока §e" + targetName + " §fснят бан.");
                                    return 1;
                                })
                        )
        );


        event.getDispatcher().register(
                Commands.literal("tempban")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.tempban"))
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("time", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            String time = StringArgumentType.getString(ctx, "time");
                                            long duration = parsePunishmentTime(time);

                                            if (duration <= 0L) {
                                                ctx.getSource().sendFailure(Component.literal("Использование: /tempban <ник> <10s/5m/2h/1d> [причина]"));
                                                return 0;
                                            }

                                            tempBanPlayer(target, duration, "");
                                            recordPunishment(target.getUUID(), target.getGameProfile().getName(), "TEMPBAN", getCommandSourceName(ctx.getSource()), duration, "");

                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Игрок " + target.getGameProfile().getName() + " забанен на " + time),
                                                    false
                                            );
                                            announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.BAN, "§c[Модерация] §fИгрок §e" + target.getGameProfile().getName() + " §fполучил временный бан на §e" + time + "§f.");
                                            disconnectTempBannedPlayer(target);
                                            return 1;
                                        })
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                                    String time = StringArgumentType.getString(ctx, "time");
                                                    String reason = StringArgumentType.getString(ctx, "reason");
                                                    long duration = parsePunishmentTime(time);

                                                    if (duration <= 0L) {
                                                        ctx.getSource().sendFailure(Component.literal("Использование: /tempban <ник> <10s/5m/2h/1d> [причина]"));
                                                        return 0;
                                                    }

                                                    tempBanPlayer(target, duration, reason);
                                                    recordPunishment(target.getUUID(), target.getGameProfile().getName(), "TEMPBAN", getCommandSourceName(ctx.getSource()), duration, reason);

                                                    ctx.getSource().sendSuccess(
                                                            () -> Component.literal("Игрок " + target.getGameProfile().getName() + " забанен на " + time + ". Причина: " + reason),
                                                            false
                                                    );
                                                    announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.BAN, "§c[Модерация] §fИгрок §e" + target.getGameProfile().getName() + " §fполучил временный бан на §e" + time + "§f. Причина: §7" + reason);
                                                    disconnectTempBannedPlayer(target);
                                                    return 1;
                                                })
                                        )
                                )
                        )
        );

        event.getDispatcher().register(
                Commands.literal("untempban")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.tempban"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestKnownPlayerNames(builder))
                                .executes(ctx -> {
                                    String targetName = StringArgumentType.getString(ctx, "target");
                                    UUID targetUuid = findKnownUuidByName(ctx.getSource().getServer(), targetName);

                                    if (!untempBanPlayerByName(targetName)) {
                                        ctx.getSource().sendFailure(Component.literal("Игрок не находится во временном бане."));
                                        return 0;
                                    }

                                    if (targetUuid != null) {
                                        recordPunishment(targetUuid, targetName, "UNTEMPBAN", getCommandSourceName(ctx.getSource()), 0L, "");
                                    }

                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("Временный бан снят с игрока " + targetName),
                                            false
                                    );
                                    announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.BAN, "§a[Модерация] §fС игрока §e" + targetName + " §fснят временный бан.");
                                    return 1;
                                })
                        )
        );


        event.getDispatcher().register(
                Commands.literal("warn")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.warn"))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");

                                    warnPlayer(target, "");
                                    recordPunishment(target.getUUID(), target.getGameProfile().getName(), "WARN", getCommandSourceName(ctx.getSource()), 0L, "");

                                    int count = getWarnCount(target.getUUID());
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("Игрок " + target.getGameProfile().getName() + " получил предупреждение. Всего варнов: " + count),
                                            false
                                    );
                                    announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.WARN, "§e[Модерация] §fИгрок §e" + target.getGameProfile().getName() + " §fполучил предупреждение. Всего варнов: §e" + count + "§f.");
                                    target.sendSystemMessage(Component.literal("§cТы получил предупреждение. Всего варнов: §e" + count));
                                    return 1;
                                })
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            String reason = StringArgumentType.getString(ctx, "reason");

                                            warnPlayer(target, reason);
                                            recordPunishment(target.getUUID(), target.getGameProfile().getName(), "WARN", getCommandSourceName(ctx.getSource()), 0L, reason);

                                            int count = getWarnCount(target.getUUID());
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Игрок " + target.getGameProfile().getName() + " получил предупреждение. Всего варнов: " + count + ". Причина: " + reason),
                                                    false
                                            );
                                            announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.WARN, "§e[Модерация] §fИгрок §e" + target.getGameProfile().getName() + " §fполучил предупреждение. Всего варнов: §e" + count + "§f. Причина: §7" + reason);
                                            target.sendSystemMessage(Component.literal("§cТы получил предупреждение. Всего варнов: §e" + count));
                                            target.sendSystemMessage(Component.literal("§cПричина: §f" + reason));
                                            return 1;
                                        })
                                )
                        )
        );

        event.getDispatcher().register(
                Commands.literal("warns")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.warn"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestKnownPlayerNames(builder))
                                .executes(ctx -> {
                                    String targetName = StringArgumentType.getString(ctx, "target");
                                    ArrayList<WarnData> warns = getWarnsByName(targetName);

                                    if (warns == null || warns.isEmpty()) {
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("У игрока " + targetName + " нет варнов."),
                                                false
                                        );
                                        return 1;
                                    }

                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("У игрока " + targetName + " варнов: " + warns.size()),
                                            false
                                    );

                                    for (int i = 0; i < warns.size(); i++) {
                                        WarnData warn = warns.get(i);
                                        String reason = warn.reason() == null || warn.reason().isBlank() ? "не указана" : warn.reason();
                                        int number = i + 1;

                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("#" + number + " | " + formatDateTime(warn.createdMillis()) + " | Причина: " + reason),
                                                false
                                        );
                                    }

                                    return 1;
                                })
                        )
        );

        event.getDispatcher().register(
                Commands.literal("unwarn")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.warn"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestKnownPlayerNames(builder))
                                .executes(ctx -> {
                                    String targetName = StringArgumentType.getString(ctx, "target");

                                    UUID targetUuid = findKnownUuidByName(ctx.getSource().getServer(), targetName);

                                    if (!removeLastWarnByName(targetName)) {
                                        ctx.getSource().sendFailure(Component.literal("У игрока нет варнов."));
                                        return 0;
                                    }

                                    if (targetUuid != null) {
                                        recordPunishment(targetUuid, targetName, "UNWARN", getCommandSourceName(ctx.getSource()), 0L, "last");
                                    }

                                    int count = getWarnCountByName(targetName);
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("Последний варн снят с игрока " + targetName + ". Осталось варнов: " + count),
                                            false
                                    );
                                    announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.WARN, "§a[Модерация] §fС игрока §e" + targetName + " §fснят последний варн. Осталось варнов: §e" + count + "§f.");
                                    return 1;
                                })
                                .then(Commands.argument("number", IntegerArgumentType.integer(1))
                                        .suggests((ctx, builder) -> suggestWarnNumbers(StringArgumentType.getString(ctx, "target"), builder))
                                        .executes(ctx -> {
                                            String targetName = StringArgumentType.getString(ctx, "target");
                                            int number = IntegerArgumentType.getInteger(ctx, "number");

                                            UUID targetUuid = findKnownUuidByName(ctx.getSource().getServer(), targetName);

                                            if (!removeWarnByNameAndNumber(targetName, number)) {
                                                ctx.getSource().sendFailure(Component.literal("У игрока нет варна с номером " + number + "."));
                                                return 0;
                                            }

                                            if (targetUuid != null) {
                                                recordPunishment(targetUuid, targetName, "UNWARN", getCommandSourceName(ctx.getSource()), 0L, "#" + number);
                                            }

                                            int count = getWarnCountByName(targetName);
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Варн #" + number + " снят с игрока " + targetName + ". Осталось варнов: " + count),
                                                    false
                                            );
                                            announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.WARN, "§a[Модерация] §fС игрока §e" + targetName + " §fснят варн #" + number + ". Осталось варнов: §e" + count + "§f.");
                                            return 1;
                                        })
                                )
                        )
        );

        event.getDispatcher().register(
                Commands.literal("history")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.history"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestKnownPlayerNames(builder))
                                .executes(ctx -> {
                                    String targetName = StringArgumentType.getString(ctx, "target");
                                    showPunishmentHistory(ctx.getSource(), targetName);
                                    return 1;
                                })
                        )
        );

        event.getDispatcher().register(
                Commands.literal("punishhistory")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.history"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestKnownPlayerNames(builder))
                                .executes(ctx -> {
                                    String targetName = StringArgumentType.getString(ctx, "target");
                                    showPunishmentHistory(ctx.getSource(), targetName);
                                    return 1;
                                })
                        )
        );


        event.getDispatcher().register(
                Commands.literal("cmute")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.mute"))
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("time", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            String time = StringArgumentType.getString(ctx, "time");
                                            long duration = parsePunishmentTime(time);

                                            if (duration <= 0L) {
                                                ctx.getSource().sendFailure(Component.literal("Использование: /cmute <ник> <10s/5m/2h/1d> [причина]"));
                                                return 0;
                                            }

                                            mutePlayer(target, duration, "");
                                            recordPunishment(target.getUUID(), target.getGameProfile().getName(), "MUTE", getCommandSourceName(ctx.getSource()), duration, "");

                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Игрок " + target.getGameProfile().getName() + " замучен на " + time),
                                                    false
                                            );
                                            announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.MUTE, "§c[Модерация] §fИгрок §e" + target.getGameProfile().getName() + " §fполучил мут на §e" + time + "§f.");
                                            target.sendSystemMessage(Component.literal("§cТы получил мут на §e" + time));
                                            return 1;
                                        })
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                                    String time = StringArgumentType.getString(ctx, "time");
                                                    String reason = StringArgumentType.getString(ctx, "reason");
                                                    long duration = parsePunishmentTime(time);

                                                    if (duration <= 0L) {
                                                        ctx.getSource().sendFailure(Component.literal("Использование: /cmute <ник> <10s/5m/2h/1d> [причина]"));
                                                        return 0;
                                                    }

                                                    mutePlayer(target, duration, reason);
                                                    recordPunishment(target.getUUID(), target.getGameProfile().getName(), "MUTE", getCommandSourceName(ctx.getSource()), duration, reason);

                                                    ctx.getSource().sendSuccess(
                                                            () -> Component.literal("Игрок " + target.getGameProfile().getName() + " замучен на " + time + ". Причина: " + reason),
                                                            false
                                                    );
                                                    announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.MUTE, "§c[Модерация] §fИгрок §e" + target.getGameProfile().getName() + " §fполучил мут на §e" + time + "§f. Причина: §7" + reason);
                                                    target.sendSystemMessage(Component.literal("§cТы получил мут на §e" + time));
                                                    target.sendSystemMessage(Component.literal("§cПричина: §f" + reason));
                                                    return 1;
                                                })
                                        )
                                )
                        )
        );

        event.getDispatcher().register(
                Commands.literal("cunmute")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.mute"))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");

                                    if (!MUTED_PLAYERS.containsKey(target.getUUID())) {
                                        ctx.getSource().sendFailure(Component.literal("Игрок не замучен."));
                                        return 0;
                                    }

                                    unmutePlayer(target);
                                    recordPunishment(target.getUUID(), target.getGameProfile().getName(), "UNMUTE", getCommandSourceName(ctx.getSource()), 0L, "");

                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("Мут снят с игрока " + target.getGameProfile().getName()),
                                            false
                                    );
                                    announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.MUTE, "§a[Модерация] §fС игрока §e" + target.getGameProfile().getName() + " §fснят мут.");
                                    target.sendSystemMessage(Component.literal("§aС тебя сняли мут"));
                                    return 1;
                                })
                        )
        );

        event.getDispatcher().register(
                Commands.literal("ctempban")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.tempban"))
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("time", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            String time = StringArgumentType.getString(ctx, "time");
                                            long duration = parsePunishmentTime(time);

                                            if (duration <= 0L) {
                                                ctx.getSource().sendFailure(Component.literal("Использование: /ctempban <ник> <10s/5m/2h/1d> [причина]"));
                                                return 0;
                                            }

                                            tempBanPlayer(target, duration, "");
                                            recordPunishment(target.getUUID(), target.getGameProfile().getName(), "TEMPBAN", getCommandSourceName(ctx.getSource()), duration, "");

                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Игрок " + target.getGameProfile().getName() + " забанен на " + time),
                                                    false
                                            );
                                            announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.BAN, "§c[Модерация] §fИгрок §e" + target.getGameProfile().getName() + " §fполучил временный бан на §e" + time + "§f.");
                                            disconnectTempBannedPlayer(target);
                                            return 1;
                                        })
                                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                                .executes(ctx -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                                    String time = StringArgumentType.getString(ctx, "time");
                                                    String reason = StringArgumentType.getString(ctx, "reason");
                                                    long duration = parsePunishmentTime(time);

                                                    if (duration <= 0L) {
                                                        ctx.getSource().sendFailure(Component.literal("Использование: /ctempban <ник> <10s/5m/2h/1d> [причина]"));
                                                        return 0;
                                                    }

                                                    tempBanPlayer(target, duration, reason);
                                                    recordPunishment(target.getUUID(), target.getGameProfile().getName(), "TEMPBAN", getCommandSourceName(ctx.getSource()), duration, reason);

                                                    ctx.getSource().sendSuccess(
                                                            () -> Component.literal("Игрок " + target.getGameProfile().getName() + " забанен на " + time + ". Причина: " + reason),
                                                            false
                                                    );
                                                    announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.BAN, "§c[Модерация] §fИгрок §e" + target.getGameProfile().getName() + " §fполучил временный бан на §e" + time + "§f. Причина: §7" + reason);
                                                    disconnectTempBannedPlayer(target);
                                                    return 1;
                                                })
                                        )
                                )
                        )
        );

        event.getDispatcher().register(
                Commands.literal("cuntempban")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.tempban"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestKnownPlayerNames(builder))
                                .executes(ctx -> {
                                    String targetName = StringArgumentType.getString(ctx, "target");
                                    UUID targetUuid = findKnownUuidByName(ctx.getSource().getServer(), targetName);

                                    if (!untempBanPlayerByName(targetName)) {
                                        ctx.getSource().sendFailure(Component.literal("Игрок не находится во временном бане."));
                                        return 0;
                                    }

                                    if (targetUuid != null) {
                                        recordPunishment(targetUuid, targetName, "UNTEMPBAN", getCommandSourceName(ctx.getSource()), 0L, "");
                                    }

                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("Временный бан снят с игрока " + targetName),
                                            false
                                    );
                                    announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.BAN, "§a[Модерация] §fС игрока §e" + targetName + " §fснят временный бан.");
                                    return 1;
                                })
                        )
        );

        event.getDispatcher().register(
                Commands.literal("cwarn")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.warn"))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> {
                                    ServerPlayer target = EntityArgument.getPlayer(ctx, "target");

                                    warnPlayer(target, "");
                                    recordPunishment(target.getUUID(), target.getGameProfile().getName(), "WARN", getCommandSourceName(ctx.getSource()), 0L, "");

                                    int count = getWarnCount(target.getUUID());
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("Игрок " + target.getGameProfile().getName() + " получил предупреждение. Всего варнов: " + count),
                                            false
                                    );
                                    announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.WARN, "§e[Модерация] §fИгрок §e" + target.getGameProfile().getName() + " §fполучил предупреждение. Всего варнов: §e" + count + "§f.");
                                    target.sendSystemMessage(Component.literal("§cТы получил предупреждение. Всего варнов: §e" + count));
                                    return 1;
                                })
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx -> {
                                            ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                            String reason = StringArgumentType.getString(ctx, "reason");

                                            warnPlayer(target, reason);
                                            recordPunishment(target.getUUID(), target.getGameProfile().getName(), "WARN", getCommandSourceName(ctx.getSource()), 0L, reason);

                                            int count = getWarnCount(target.getUUID());
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Игрок " + target.getGameProfile().getName() + " получил предупреждение. Всего варнов: " + count + ". Причина: " + reason),
                                                    false
                                            );
                                            announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.WARN, "§e[Модерация] §fИгрок §e" + target.getGameProfile().getName() + " §fполучил предупреждение. Всего варнов: §e" + count + "§f. Причина: §7" + reason);
                                            target.sendSystemMessage(Component.literal("§cТы получил предупреждение. Всего варнов: §e" + count));
                                            target.sendSystemMessage(Component.literal("§cПричина: §f" + reason));
                                            return 1;
                                        })
                                )
                        )
        );

        event.getDispatcher().register(
                Commands.literal("cwarns")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.warn"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestKnownPlayerNames(builder))
                                .executes(ctx -> {
                                    String targetName = StringArgumentType.getString(ctx, "target");
                                    ArrayList<WarnData> warns = getWarnsByName(targetName);

                                    if (warns == null || warns.isEmpty()) {
                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("У игрока " + targetName + " нет варнов."),
                                                false
                                        );
                                        return 1;
                                    }

                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("У игрока " + targetName + " варнов: " + warns.size()),
                                            false
                                    );

                                    for (int i = 0; i < warns.size(); i++) {
                                        WarnData warn = warns.get(i);
                                        String reason = warn.reason() == null || warn.reason().isBlank() ? "не указана" : warn.reason();
                                        int number = i + 1;

                                        ctx.getSource().sendSuccess(
                                                () -> Component.literal("#" + number + " | " + formatDateTime(warn.createdMillis()) + " | Причина: " + reason),
                                                false
                                        );
                                    }

                                    return 1;
                                })
                        )
        );

        event.getDispatcher().register(
                Commands.literal("cunwarn")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.warn"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestKnownPlayerNames(builder))
                                .executes(ctx -> {
                                    String targetName = StringArgumentType.getString(ctx, "target");
                                    UUID targetUuid = findKnownUuidByName(ctx.getSource().getServer(), targetName);

                                    if (!removeLastWarnByName(targetName)) {
                                        ctx.getSource().sendFailure(Component.literal("У игрока нет варнов."));
                                        return 0;
                                    }

                                    if (targetUuid != null) {
                                        recordPunishment(targetUuid, targetName, "UNWARN", getCommandSourceName(ctx.getSource()), 0L, "last");
                                    }

                                    int count = getWarnCountByName(targetName);
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("Последний варн снят с игрока " + targetName + ". Осталось варнов: " + count),
                                            false
                                    );
                                    announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.WARN, "§a[Модерация] §fС игрока §e" + targetName + " §fснят последний варн. Осталось варнов: §e" + count + "§f.");
                                    return 1;
                                })
                                .then(Commands.argument("number", IntegerArgumentType.integer(1))
                                        .suggests((ctx, builder) -> suggestWarnNumbers(StringArgumentType.getString(ctx, "target"), builder))
                                        .executes(ctx -> {
                                            String targetName = StringArgumentType.getString(ctx, "target");
                                            int number = IntegerArgumentType.getInteger(ctx, "number");
                                            UUID targetUuid = findKnownUuidByName(ctx.getSource().getServer(), targetName);

                                            if (!removeWarnByNameAndNumber(targetName, number)) {
                                                ctx.getSource().sendFailure(Component.literal("У игрока нет варна с номером " + number + "."));
                                                return 0;
                                            }

                                            if (targetUuid != null) {
                                                recordPunishment(targetUuid, targetName, "UNWARN", getCommandSourceName(ctx.getSource()), 0L, "#" + number);
                                            }

                                            int count = getWarnCountByName(targetName);
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("Варн #" + number + " снят с игрока " + targetName + ". Осталось варнов: " + count),
                                                    false
                                            );
                                            announcePunishment(ctx.getSource().getServer(), PunishmentAnnounceType.WARN, "§a[Модерация] §fС игрока §e" + targetName + " §fснят варн #" + number + ". Осталось варнов: §e" + count + "§f.");
                                            return 1;
                                        })
                                )
                        )
        );

        event.getDispatcher().register(
                Commands.literal("chistory")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.history"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestKnownPlayerNames(builder))
                                .executes(ctx -> {
                                    String targetName = StringArgumentType.getString(ctx, "target");
                                    showPunishmentHistory(ctx.getSource(), targetName);
                                    return 1;
                                })
                        )
        );

        event.getDispatcher().register(
                Commands.literal("tpl")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.tpl"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestKnownPlayerNames(builder))
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String targetName = StringArgumentType.getString(ctx, "target");

                                    if (!teleportToPlayerOrLastLocation(player, targetName)) {
                                        ctx.getSource().sendFailure(Component.literal("Игрок не найден и его последняя позиция не сохранена."));
                                        return 0;
                                    }

                                    return 1;
                                })
                        )
        );

        event.getDispatcher().register(
                Commands.literal("homeother")
                        .requires(source -> hasCommandPermission(source, "cointcoregto.home.others"))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggestKnownPlayerNames(builder))
                                .then(Commands.argument("home", StringArgumentType.word())
                                        .suggests((ctx, builder) -> suggestFtbHomeNamesForPlayer(StringArgumentType.getString(ctx, "target"), builder))
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                                            String targetName = StringArgumentType.getString(ctx, "target");
                                            String homeName = StringArgumentType.getString(ctx, "home");

                                            if (!teleportToFtbHome(player, homeName, targetName)) {
                                                ctx.getSource().sendFailure(Component.literal("Использование: /homeother <ник> <название_хома>"));
                                                return 0;
                                            }

                                            return 1;
                                        })
                                )
                        )
        );

        event.getDispatcher().register(
                Commands.literal("cuberestart")
                        .then(Commands.literal("status")
                                .executes(ctx -> {
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal(getRestartStatusText()),
                                            false
                                    );
                                    return 1;
                                }))
                        .then(Commands.literal("reload")
                                .requires(source -> hasCommandPermission(source, "cointcoregto.restart"))
                                .executes(ctx -> {
                                    resetRestartSchedule();
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("§aРасписание рестартов CointCoreGTO перезагружено. " + getRestartStatusText()),
                                            true
                                    );
                                    return 1;
                                }))
                        .then(Commands.literal("cancel")
                                .requires(source -> hasCommandPermission(source, "cointcoregto.restart"))
                                .executes(ctx -> {
                                    NEXT_RESTART_MILLIS = -1L;
                                    RESTARTING_NOW = false;
                                    RESTART_PLAYERS_KICKED = false;
                                    SENT_RESTART_WARNINGS.clear();
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("§cБлижайший рестарт CointCoreGTO отменён до /cuberestart reload или перезапуска сервера."),
                                            true
                                    );
                                    return 1;
                                }))
                        .then(Commands.literal("now")
                                .requires(source -> hasCommandPermission(source, "cointcoregto.restart"))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 3600))
                                        .executes(ctx -> {
                                            int seconds = IntegerArgumentType.getInteger(ctx, "seconds");
                                            scheduleManualRestart(seconds);
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("§eРучной рестарт запланирован через " + seconds + " сек."),
                                                    true
                                            );
                                            return 1;
                                        })))
        );

        event.getDispatcher().register(
                Commands.literal("g")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String message = StringArgumentType.getString(ctx, "message");

                                    if (isMuted(player)) {
                                        sendMutedMessage(player);
                                        return 0;
                                    }

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

                                    if (isMuted(player)) {
                                        sendMutedMessage(player);
                                        return 0;
                                    }

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

                                    if (isMuted(player)) {
                                        sendMutedMessage(player);
                                        return 0;
                                    }

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

                                    if (isMuted(player)) {
                                        sendMutedMessage(player);
                                        return 0;
                                    }

                                    sendLocalChat(player, message);
                                    return 1;
                                }))
        );

        event.getDispatcher().register(
                Commands.literal("trade")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                                    String message = StringArgumentType.getString(ctx, "message");

                                    if (isMuted(player)) {
                                        sendMutedMessage(player);
                                        return 0;
                                    }

                                    sendTradeChat(player, message);
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

                                            if (isMuted(player)) {
                                                sendMutedMessage(player);
                                                return 0;
                                            }

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

                                    if (isMuted(player)) {
                                        sendMutedMessage(player);
                                        return 0;
                                    }

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
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        MinecraftServer server = CURRENT_SERVER;
        if (server == null) {
            return;
        }

        long currentSecond = System.currentTimeMillis() / 1000L;
        if (currentSecond == LAST_RESTART_CHECK_SECOND) {
            return;
        }
        LAST_RESTART_CHECK_SECOND = currentSecond;

        handleRestartTick(server);
    }

    private static void resetRestartSchedule() {
        SENT_RESTART_WARNINGS.clear();
        RESTARTING_NOW = false;
        RESTART_PLAYERS_KICKED = false;
        LAST_RESTART_CHECK_SECOND = -1L;
        NEXT_RESTART_MILLIS = calculateNextRestartMillis();

        if (NEXT_RESTART_MILLIS > 0L) {
            System.out.println("[CointCoreGTO] Next automatic restart: " + formatDateTime(NEXT_RESTART_MILLIS));
        } else {
            System.out.println("[CointCoreGTO] Automatic restarts are disabled or no valid restart times configured.");
        }
    }

    private static void scheduleManualRestart(int seconds) {
        SENT_RESTART_WARNINGS.clear();
        RESTARTING_NOW = false;
        RESTART_PLAYERS_KICKED = false;
        LAST_RESTART_CHECK_SECOND = -1L;
        NEXT_RESTART_MILLIS = System.currentTimeMillis() + Math.max(5, seconds) * 1000L;
        broadcastRestartWarning(Math.max(5, seconds), true);
    }

    private static void handleRestartTick(MinecraftServer server) {
        if (NEXT_RESTART_MILLIS <= 0L) {
            if (RESTART_ENABLED.get()) {
                NEXT_RESTART_MILLIS = calculateNextRestartMillis();
            }
            return;
        }

        long millisLeft = NEXT_RESTART_MILLIS - System.currentTimeMillis();
        long secondsLeftLong = Math.max(0L, (millisLeft + 999L) / 1000L);

        if (secondsLeftLong <= 0L) {
            performRestart(server);
            return;
        }

        if (secondsLeftLong > Integer.MAX_VALUE) {
            return;
        }

        int secondsLeft = (int) secondsLeftLong;

        int kickSecondsBeforeStop = RESTART_KICK_SECONDS_BEFORE_STOP.get();
        if (RESTART_KICK_PLAYERS.get()
                && kickSecondsBeforeStop > 0
                && secondsLeft <= kickSecondsBeforeStop
                && !RESTART_PLAYERS_KICKED) {
            kickPlayersBeforeRestart(server);
        }

        int countdownSeconds = RESTART_COUNTDOWN_SECONDS.get();

        if (countdownSeconds > 0 && secondsLeft <= countdownSeconds) {
            int key = -secondsLeft;
            if (SENT_RESTART_WARNINGS.add(key)) {
                broadcastRestartWarning(secondsLeft, true);
            }
            return;
        }

        for (Integer minutes : RESTART_WARNING_MINUTES.get()) {
            if (minutes == null || minutes <= 0) {
                continue;
            }

            int warningSeconds = minutes * 60;
            if (secondsLeft <= warningSeconds && secondsLeft > warningSeconds - 3 && SENT_RESTART_WARNINGS.add(warningSeconds)) {
                broadcastRestartWarning(secondsLeft, true);
                return;
            }
        }
    }

    private static long calculateNextRestartMillis() {
        if (!RESTART_ENABLED.get()) {
            return -1L;
        }

        ZonedDateTime now = ZonedDateTime.now(MOSCOW_ZONE);
        ZonedDateTime best = null;

        for (String rawTime : RESTART_TIMES.get()) {
            if (rawTime == null || rawTime.isBlank()) {
                continue;
            }

            try {
                LocalTime time = LocalTime.parse(rawTime.trim());
                ZonedDateTime candidate = now.withHour(time.getHour()).withMinute(time.getMinute()).withSecond(0).withNano(0);

                if (!candidate.isAfter(now)) {
                    candidate = candidate.plusDays(1);
                }

                if (best == null || candidate.isBefore(best)) {
                    best = candidate;
                }
            } catch (Throwable ignored) {
                System.out.println("[CointCoreGTO] Invalid restart time in config: " + rawTime + ". Use HH:mm, for example 06:00");
            }
        }

        return best == null ? -1L : best.toInstant().toEpochMilli();
    }

    private static void broadcastRestartWarning(int secondsLeft, boolean important) {
        MinecraftServer server = CURRENT_SERVER;
        if (server == null) {
            return;
        }

        String timeText = formatRestartTime(secondsLeft);
        String chatMessage = "§c⚠ Рестарт сервера через §e" + timeText + "§c!";
        String title = "§c⚠ РЕСТАРТ СЕРВЕРА ⚠";
        String subtitle = "§eДо рестарта " + timeText;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (RESTART_SHOW_CHAT.get()) {
                player.sendSystemMessage(Component.literal(chatMessage));
            }

            if (RESTART_SHOW_ACTIONBAR.get()) {
                player.displayClientMessage(Component.literal(chatMessage), true);
            }

            if (RESTART_SHOW_TITLE.get() && important) {
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(10, 60, 10));
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal(title)));
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
            }
        }

        System.out.println("[CointCoreGTO] Restart warning: " + stripColor(chatMessage));
        CointCoreGTODiscordProxy.sendToDiscord("⚠ Рестарт сервера через **" + stripColor(timeText) + "**!");
    }


    private static void kickPlayersBeforeRestart(MinecraftServer server) {
        if (RESTART_PLAYERS_KICKED) {
            return;
        }

        RESTART_PLAYERS_KICKED = true;

        System.out.println("[CointCoreGTO] Kicking players before restart and saving the world.");

        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "save-all flush");
        } catch (Throwable e) {
            System.out.println("[CointCoreGTO] Failed to execute save-all flush before kicking players: " + e.getMessage());
        }

        Component kickMessage = Component.literal(RESTART_KICK_MESSAGE.get());
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            try {
                player.connection.disconnect(kickMessage);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void performRestart(MinecraftServer server) {
        if (RESTARTING_NOW) {
            return;
        }

        RESTARTING_NOW = true;
        NEXT_RESTART_MILLIS = -1L;

        if (RESTART_KICK_PLAYERS.get() && !RESTART_PLAYERS_KICKED) {
            kickPlayersBeforeRestart(server);
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket(5, 80, 10));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket(Component.literal("§cСЕРВЕР ПЕРЕЗАПУСКАЕТСЯ")));
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(Component.literal("§7Зайдите через пару минут")));
            player.sendSystemMessage(Component.literal("§cСервер перезапускается. Зайдите через пару минут."));
        }

        CointCoreGTODiscordProxy.sendToDiscord("🔄 **Сервер уходит на плановый рестарт.**");
        System.out.println("[CointCoreGTO] Automatic restart started.");

        server.execute(() -> {
            try {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "save-all flush");
            } catch (Throwable e) {
                System.out.println("[CointCoreGTO] Failed to execute save-all flush: " + e.getMessage());
            }

            try {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "stop");
            } catch (Throwable e) {
                System.out.println("[CointCoreGTO] Failed to execute stop command: " + e.getMessage());
                try {
                    server.halt(false);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static String getRestartStatusText() {
        if (NEXT_RESTART_MILLIS <= 0L) {
            return RESTART_ENABLED.get()
                    ? "§eАвто-рестарт включён, но ближайшее время не рассчитано. Используйте /cuberestart reload."
                    : "§cАвто-рестарт выключен в конфиге.";
        }

        long secondsLeft = Math.max(0L, (NEXT_RESTART_MILLIS - System.currentTimeMillis() + 999L) / 1000L);
        return "§aБлижайший рестарт: §e" + formatDateTime(NEXT_RESTART_MILLIS) + " МСК§7, осталось §e" + formatRestartTime((int) Math.min(Integer.MAX_VALUE, secondsLeft));
    }

    private static String formatRestartTime(int seconds) {
        seconds = Math.max(0, seconds);

        if (seconds < 60) {
            return seconds + " сек.";
        }

        int minutes = seconds / 60;
        int restSeconds = seconds % 60;

        if (minutes < 60) {
            return restSeconds > 0 ? minutes + " мин. " + restSeconds + " сек." : minutes + " мин.";
        }

        int hours = minutes / 60;
        int restMinutes = minutes % 60;
        return restMinutes > 0 ? hours + " ч. " + restMinutes + " мин." : hours + " ч.";
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

        if (lower.equals("me") || lower.startsWith("me ")) {
            player.displayClientMessage(Component.literal("§cКоманда /me отключена. Используйте обычный чат."), true);
            event.setCanceled(true);
            return;
        }

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

        if (isMuted(player)) {
            sendMutedMessage(player);
            return;
        }

        if (USE_EXCLAMATION_FOR_GLOBAL.get() && message.startsWith("!")) {
            String globalMessage = message.substring(1).trim();

            if (globalMessage.isEmpty()) {
                player.displayClientMessage(Component.literal("§cВведите сообщение после !"), true);
                return;
            }

            sendGlobalChat(player, globalMessage);
            return;
        }

        if (USE_DOLLAR_FOR_TRADE.get() && message.startsWith("$")) {
            String tradeMessage = message.substring(1).trim();

            if (tradeMessage.isEmpty()) {
                player.displayClientMessage(Component.literal("§cВведите сообщение после $"), true);
                return;
            }

            sendTradeChat(player, tradeMessage);
            return;
        }

        ChatView view = getChatView(player);

        if (view == ChatView.GLOBAL) {
            sendGlobalChat(player, message);
            return;
        }

        if (view == ChatView.TRADE) {
            sendTradeChat(player, message);
            return;
        }

        if (view == ChatView.PRIVATE) {
            player.displayClientMessage(Component.literal("§dЛичные сообщения отправляются так: §f/msg Ник сообщение §7или §f/r сообщение"), true);
            return;
        }

        sendLocalChat(player, message);
    }

    private static void setChatView(ServerPlayer player, ChatView view) {
        if (player == null || view == null) {
            return;
        }

        UUID uuid = player.getUUID();
        ChatView previousView = CHAT_VIEWS.getOrDefault(uuid, ChatView.ALL);
        long now = System.currentTimeMillis();
        long lastSwitch = LAST_CHAT_VIEW_SWITCH_MILLIS.getOrDefault(uuid, 0L);

        if (previousView == view && now - lastSwitch < CHAT_VIEW_SWITCH_COOLDOWN_MILLIS) {
            return;
        }

        if (now - lastSwitch < CHAT_VIEW_SWITCH_COOLDOWN_MILLIS) {
            return;
        }

        LAST_CHAT_VIEW_SWITCH_MILLIS.put(uuid, now);
        CHAT_VIEWS.put(uuid, view);
        replayChatHistory(player, view);

        if (view == ChatView.ALL) {
            player.displayClientMessage(Component.literal("§aТеперь вы видите все чаты."), true);
        }

        if (view == ChatView.LOCAL) {
            player.displayClientMessage(Component.literal("§aТеперь вы видите только локальный чат."), true);
        }

        if (view == ChatView.GLOBAL) {
            player.displayClientMessage(Component.literal("§6Теперь вы видите только глобальный чат."), true);
        }

        if (view == ChatView.TRADE) {
            player.displayClientMessage(Component.literal("§bТеперь вы видите только торговый чат."), true);
        }

        if (view == ChatView.PRIVATE) {
            player.displayClientMessage(Component.literal("§dТеперь вы видите только личные сообщения."), true);
        }
    }

    private static void rememberChatMessage(ServerPlayer target, ChatView view, String formattedMessage) {
        rememberChatMessage(target, view, formattedMessage, Component.literal(formattedMessage == null ? "" : formattedMessage));
    }

    private static void rememberChatMessage(ServerPlayer target, ChatView view, String formattedMessage, Component componentMessage) {
        if (target == null || formattedMessage == null || formattedMessage.isBlank()) {
            return;
        }

        Component storedComponent = componentMessage == null ? Component.literal(formattedMessage) : componentMessage.copy();
        Deque<ChatHistoryMessage> history = CHAT_HISTORY.computeIfAbsent(target.getUUID(), uuid -> new ArrayDeque<>());

        synchronized (history) {
            history.addLast(new ChatHistoryMessage(CHAT_HISTORY_COUNTER.incrementAndGet(), view, formattedMessage, storedComponent));

            while (history.size() > MAX_CHAT_HISTORY_PER_PLAYER) {
                history.removeFirst();
            }
        }
    }

    private static void sendFilteredChatMessage(ServerPlayer target, ChatView view, String formattedMessage) {
        sendFilteredChatMessage(target, view, formattedMessage, Component.literal(formattedMessage));
    }

    private static void sendFilteredChatMessage(ServerPlayer target, ChatView view, String formattedMessage, Component liveMessage) {
        rememberChatMessage(target, view, formattedMessage, liveMessage);

        if (canReceive(target, view)) {
            target.sendSystemMessage(liveMessage);
        }
    }

    private static void replayChatHistory(ServerPlayer player, ChatView view) {
        clearClientChat(player, true);

        Deque<ChatHistoryMessage> history = CHAT_HISTORY.get(player.getUUID());
        if (history == null || history.isEmpty()) {
            return;
        }

        ArrayList<ChatHistoryMessage> snapshot;
        synchronized (history) {
            snapshot = new ArrayList<>(history);
        }

        ArrayList<ChatHistoryMessage> filtered = new ArrayList<>();
        for (ChatHistoryMessage message : snapshot) {
            if (view != ChatView.ALL && message.view() != view) {
                continue;
            }
            filtered.add(message);
        }

        int startIndex = Math.max(0, filtered.size() - MAX_CHAT_HISTORY_REPLAY);
        for (int i = startIndex; i < filtered.size(); i++) {
            ChatHistoryMessage message = filtered.get(i);
            Component component = message.component() == null ? Component.literal(message.message()) : message.component().copy();
            player.sendSystemMessage(component);
        }
    }

    private static void clearClientChat(ServerPlayer player, boolean keepSystemMessages) {
        try {
            NETWORK_CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ClearChatPacket(keepSystemMessages));
        } catch (Throwable ignored) {
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
        String plainMessage = CointCoreGTOItemPreview.toPlainMessage(player, message);
        String withoutTimePrefix = color(LOCAL_PREFIX.get())
                + getLuckPermsPrefix(player)
                + getStaffTag(player)
                + "§f" + player.getGameProfile().getName()
                + "§7: §f";
        String withoutTime = withoutTimePrefix + plainMessage;

        String discordFormatted = stripColor(withoutTime);

        int receivers = 0;
        double radius = LOCAL_RADIUS.get();
        double radiusSquared = radius * radius;

        for (ServerPlayer target : player.server.getPlayerList().getPlayers()) {
            if (target.level().dimension() != player.level().dimension()) {
                continue;
            }

            if (target.distanceToSqr(player) > radiusSquared) {
                continue;
            }

            String fullPrefix = timePrefix(target) + withoutTimePrefix;
            String fullPlain = fullPrefix + plainMessage;
            Component liveMessage = CointCoreGTOItemPreview.buildMessage(player, fullPrefix, message);
            sendFilteredChatMessage(target, ChatView.LOCAL, fullPlain, liveMessage);
            receivers++;
        }

        if (receivers <= 1) {
            player.displayClientMessage(Component.literal("§7Рядом никого нет. Для глобального чата используйте §e!сообщение §7или §e/g сообщение§7."), true);
        }

        if (DISCORD_SEND_LOCAL_CHAT.get()) {
            CointCoreGTODiscordProxy.sendToDiscordLog(discordFormatted);
        }

        LOCAL_CHAT_LOGGER.info(stripColor(timePrefix(player) + withoutTime));
    }

    private static void sendGlobalChat(ServerPlayer player, String message) {
        String plainMessage = CointCoreGTOItemPreview.toPlainMessage(player, message);
        String withoutTimePrefix = color(GLOBAL_PREFIX.get())
                + getLuckPermsPrefix(player)
                + getStaffTag(player)
                + "§f" + player.getGameProfile().getName()
                + "§7: §f";
        String withoutTime = withoutTimePrefix + plainMessage;

        String discordFormatted = stripColor(withoutTime);

        for (ServerPlayer target : player.server.getPlayerList().getPlayers()) {
            String fullPrefix = timePrefix(target) + withoutTimePrefix;
            String fullPlain = fullPrefix + plainMessage;
            Component liveMessage = CointCoreGTOItemPreview.buildMessage(player, fullPrefix, message);
            sendFilteredChatMessage(target, ChatView.GLOBAL, fullPlain, liveMessage);
        }

        if (DISCORD_SEND_GLOBAL_CHAT.get()) {
            CointCoreGTODiscordProxy.sendPlayerMessageToDiscord(
                    getDiscordDisplayName(player, GLOBAL_PREFIX.get()),
                    plainMessage,
                    player.getUUID().toString(),
                    player.getGameProfile().getName()
            );
        }

        GLOBAL_CHAT_LOGGER.info(stripColor(timePrefix(player) + withoutTime));
    }

    private static boolean checkTradeCooldown(ServerPlayer player) {
        int seconds = TRADE_COOLDOWN_SECONDS.get();
        if (seconds <= 0) {
            return true;
        }

        long now = System.currentTimeMillis();
        long cooldown = seconds * 1000L;
        long last = LAST_TRADE_MESSAGE_MILLIS.getOrDefault(player.getUUID(), 0L);

        if (now - last < cooldown) {
            long left = (cooldown - (now - last) + 999L) / 1000L;
            player.displayClientMessage(Component.literal("§cПодождите " + left + " сек. перед следующим сообщением в торговый чат."), true);
            return false;
        }

        LAST_TRADE_MESSAGE_MILLIS.put(player.getUUID(), now);
        return true;
    }

    private static void sendTradeChat(ServerPlayer player, String message) {
        if (!checkTradeCooldown(player)) {
            return;
        }

        String plainMessage = CointCoreGTOItemPreview.toPlainMessage(player, message);
        String withoutTimePrefix = color(TRADE_PREFIX.get())
                + getLuckPermsPrefix(player)
                + getStaffTag(player)
                + "§f" + player.getGameProfile().getName()
                + "§7: §f";
        String withoutTime = withoutTimePrefix + plainMessage;

        for (ServerPlayer target : player.server.getPlayerList().getPlayers()) {
            String fullPrefix = timePrefix(target) + withoutTimePrefix;
            String fullPlain = fullPrefix + plainMessage;
            Component liveMessage = CointCoreGTOItemPreview.buildMessage(player, fullPrefix, message);
            sendFilteredChatMessage(target, ChatView.TRADE, fullPlain, liveMessage);
        }

        TRADE_CHAT_LOGGER.info(stripColor(timePrefix(player) + withoutTime));
    }

    private static void sendPrivateMessage(ServerPlayer sender, ServerPlayer target, String message) {
        if (sender.getUUID().equals(target.getUUID())) {
            sender.displayClientMessage(Component.literal("§cНельзя написать самому себе."), true);
            return;
        }

        String senderName = sender.getGameProfile().getName();
        String targetName = target.getGameProfile().getName();
        String plainMessage = CointCoreGTOItemPreview.toPlainMessage(sender, message);

        String senderPrefix = timePrefix(sender)
                + color(PRIVATE_PREFIX.get())
                + "§7Вы -> §d"
                + targetName
                + "§7: §f";

        String targetPrefix = timePrefix(target)
                + color(PRIVATE_PREFIX.get())
                + "§d"
                + senderName
                + " §7-> Вы: §f";

        String toSender = senderPrefix + plainMessage;
        String toTarget = targetPrefix + plainMessage;

        Component senderLiveMessage = CointCoreGTOItemPreview.buildMessage(sender, senderPrefix, message);
        Component targetLiveMessage = CointCoreGTOItemPreview.buildMessage(sender, targetPrefix, message);

        rememberChatMessage(sender, ChatView.PRIVATE, toSender, senderLiveMessage);
        rememberChatMessage(target, ChatView.PRIVATE, toTarget, targetLiveMessage);

        sender.sendSystemMessage(senderLiveMessage);
        target.sendSystemMessage(targetLiveMessage);

        LAST_PRIVATE.put(sender.getUUID(), target.getUUID());
        LAST_PRIVATE.put(target.getUUID(), sender.getUUID());

        if (DISCORD_SEND_PRIVATE_CHAT.get()) {
            CointCoreGTODiscordProxy.sendToDiscordLog("[PM] " + senderName + " -> " + targetName + ": " + plainMessage);
        }

        PRIVATE_CHAT_LOGGER.info(senderName + " -> " + targetName + ": " + plainMessage);
    }

    private static boolean checkItemShareCooldown(ServerPlayer player) {
        if (player == null) {
            return false;
        }

        UUID uuid = player.getUUID();
        long now = System.currentTimeMillis();

        Long lastUse = LAST_ITEM_SHARE_MILLIS.get(uuid);
        if (lastUse != null) {
            long elapsed = now - lastUse;

            if (elapsed < ITEM_SHARE_COOLDOWN_MILLIS) {
                long leftMillis = ITEM_SHARE_COOLDOWN_MILLIS - elapsed;
                double leftSeconds = Math.ceil(leftMillis / 100.0D) / 10.0D;

                player.displayClientMessage(
                        Component.literal("§cПодожди " + leftSeconds + " сек. перед следующим пингом предмета."),
                        true
                );

                return false;
            }
        }

        LAST_ITEM_SHARE_MILLIS.put(uuid, now);
        return true;
    }

    public static void shareItemInCurrentChat(ServerPlayer player, ItemStack stack) {
        shareItemInCurrentChat(player, stack, CointCoreGTOItemPreview.toPlainText(stack));
    }

    public static void shareItemInCurrentChat(ServerPlayer player, ItemStack stack, String clientDisplayName) {
        shareItem(player, stack, clientDisplayName, ItemShareChannel.CURRENT);
    }

    public static void shareItem(ServerPlayer player, ItemStack stack, String clientDisplayName, ItemShareChannel requestedChannel) {
        if (player == null || stack == null || stack.isEmpty()) {
            return;
        }

        if (isMuted(player)) {
            sendMutedMessage(player);
            return;
        }

        if (!checkItemShareCooldown(player)) {
            return;
        }

        String itemText = normalizeSharedItemName(clientDisplayName, stack);
        ItemShareChannel channel = requestedChannel == null ? ItemShareChannel.CURRENT : requestedChannel;

        if (channel == ItemShareChannel.CURRENT) {
            channel = switch (getChatView(player)) {
                case GLOBAL -> ItemShareChannel.GLOBAL;
                case TRADE -> ItemShareChannel.TRADE;
                case PRIVATE -> ItemShareChannel.PRIVATE;
                default -> ItemShareChannel.LOCAL;
            };
        }

        switch (channel) {
            case LOCAL -> {
                if (ITEM_PING_LOCAL_ENABLED.get()) sendLocalItemShare(player, stack, itemText);
                else player.displayClientMessage(Component.literal("§cПинг предметов в локальный чат отключён."), true);
            }
            case GLOBAL -> {
                if (ITEM_PING_GLOBAL_ENABLED.get()) sendGlobalItemShare(player, stack, itemText);
                else player.displayClientMessage(Component.literal("§cПинг предметов в глобальный чат отключён."), true);
            }
            case TRADE -> {
                if (ITEM_PING_TRADE_ENABLED.get()) sendTradeItemShare(player, stack, itemText);
                else player.displayClientMessage(Component.literal("§cПинг предметов в торговый чат отключён."), true);
            }
            case PRIVATE -> {
                if (ITEM_PING_PRIVATE_ENABLED.get()) sendPrivateItemShare(player, stack, itemText);
                else player.displayClientMessage(Component.literal("§cПинг предметов в личный чат отключён."), true);
            }
            default -> { }
        }
    }

    private static String normalizeSharedItemName(String clientDisplayName, ItemStack stack) {
        return CointCoreGTOItemPreview.normalizeDisplayText(clientDisplayName, stack);
    }

    private static void sendLocalItemShare(ServerPlayer player, ItemStack stack, String itemText) {
        String cleanItemText = normalizeSharedItemName(itemText, stack);
        String plainItemText = "    " + cleanItemText;
        String iconItemText = cleanItemText;

        String withoutTimePrefix = color(LOCAL_PREFIX.get())
                + getLuckPermsPrefix(player)
                + getStaffTag(player)
                + "§f" + player.getGameProfile().getName()
                + "§7: §f";
        String withoutTime = withoutTimePrefix + plainItemText;
        String discordFormatted = stripColor(withoutTime);

        int receivers = 0;
        double radius = LOCAL_RADIUS.get();
        double radiusSquared = radius * radius;

        for (ServerPlayer target : player.server.getPlayerList().getPlayers()) {
            if (target.level().dimension() != player.level().dimension()) {
                continue;
            }

            if (target.distanceToSqr(player) > radiusSquared) {
                continue;
            }

            String fullPrefix = timePrefix(target) + withoutTimePrefix;
            String fullPlain = fullPrefix + plainItemText;
            Component liveMessage = Component.literal(fullPrefix + "    ")
                    .append(CointCoreGTOItemPreview.buildItemComponent(stack, cleanItemText));

            CointCoreGTOItemShare.sendIconHintToPlayer(target, stack, fullPrefix, iconItemText);
            sendFilteredChatMessage(target, ChatView.LOCAL, fullPlain, liveMessage);
            receivers++;
        }

        if (receivers <= 1) {
            player.displayClientMessage(Component.literal("§7Рядом никого нет. Для глобального чата используйте §e/g §7или выберите §6[G]§7."), true);
        }

        if (DISCORD_SEND_LOCAL_CHAT.get()) {
            CointCoreGTODiscordProxy.sendToDiscordLog(discordFormatted);
        }

        LOCAL_CHAT_LOGGER.info(stripColor(timePrefix(player) + withoutTime));
    }

    private static void sendGlobalItemShare(ServerPlayer player, ItemStack stack, String itemText) {
        String cleanItemText = normalizeSharedItemName(itemText, stack);
        String plainItemText = "    " + cleanItemText;
        String iconItemText = cleanItemText;

        String withoutTimePrefix = color(GLOBAL_PREFIX.get())
                + getLuckPermsPrefix(player)
                + getStaffTag(player)
                + "§f" + player.getGameProfile().getName()
                + "§7: §f";
        String withoutTime = withoutTimePrefix + plainItemText;
        String discordFormatted = stripColor(withoutTime);

        for (ServerPlayer target : player.server.getPlayerList().getPlayers()) {
            String fullPrefix = timePrefix(target) + withoutTimePrefix;
            String fullPlain = fullPrefix + plainItemText;
            Component liveMessage = Component.literal(fullPrefix + "    ")
                    .append(CointCoreGTOItemPreview.buildItemComponent(stack, cleanItemText));

            CointCoreGTOItemShare.sendIconHintToPlayer(target, stack, fullPrefix, iconItemText);
            sendFilteredChatMessage(target, ChatView.GLOBAL, fullPlain, liveMessage);
        }

        if (DISCORD_SEND_GLOBAL_CHAT.get()) {
            CointCoreGTODiscordProxy.sendPlayerMessageToDiscord(
                    getDiscordDisplayName(player, GLOBAL_PREFIX.get()),
                    plainItemText,
                    player.getUUID().toString(),
                    player.getGameProfile().getName()
            );
        }

        GLOBAL_CHAT_LOGGER.info(stripColor(timePrefix(player) + withoutTime));
    }

    private static void sendTradeItemShare(ServerPlayer player, ItemStack stack, String itemText) {
        if (!checkTradeCooldown(player)) {
            return;
        }

        String cleanItemText = normalizeSharedItemName(itemText, stack);
        String plainItemText = "    " + cleanItemText;
        String withoutTimePrefix = color(TRADE_PREFIX.get())
                + getLuckPermsPrefix(player)
                + getStaffTag(player)
                + "§f" + player.getGameProfile().getName()
                + "§7: §f";
        String withoutTime = withoutTimePrefix + plainItemText;

        for (ServerPlayer target : player.server.getPlayerList().getPlayers()) {
            String fullPrefix = timePrefix(target) + withoutTimePrefix;
            String fullPlain = fullPrefix + plainItemText;
            Component liveMessage = Component.literal(fullPrefix + "    ")
                    .append(CointCoreGTOItemPreview.buildItemComponent(stack, cleanItemText));
            sendFilteredChatMessage(target, ChatView.TRADE, fullPlain, liveMessage);
        }

        TRADE_CHAT_LOGGER.info(stripColor(timePrefix(player) + withoutTime));
    }

    private static void sendPrivateItemShare(ServerPlayer sender, ItemStack stack, String itemText) {
        UUID targetUuid = LAST_PRIVATE.get(sender.getUUID());
        if (targetUuid == null) {
            sender.displayClientMessage(Component.literal("§cНекому отправить предмет. Сначала напишите игроку через /pm."), true);
            return;
        }

        ServerPlayer target = sender.server.getPlayerList().getPlayer(targetUuid);
        if (target == null) {
            sender.displayClientMessage(Component.literal("§cИгрок уже не в сети."), true);
            return;
        }

        String cleanItemText = normalizeSharedItemName(itemText, stack);
        String plainItemText = "    " + cleanItemText;
        String senderName = sender.getGameProfile().getName();
        String targetName = target.getGameProfile().getName();

        String senderPrefix = timePrefix(sender) + color(PRIVATE_PREFIX.get()) + "§7Вы -> §d" + targetName + "§7: §f";
        String targetPrefix = timePrefix(target) + color(PRIVATE_PREFIX.get()) + "§d" + senderName + " §7-> Вы: §f";

        Component senderMessage = Component.literal(senderPrefix + "    ")
                .append(CointCoreGTOItemPreview.buildItemComponent(stack, cleanItemText));
        Component targetMessage = Component.literal(targetPrefix + "    ")
                .append(CointCoreGTOItemPreview.buildItemComponent(stack, cleanItemText));

        rememberChatMessage(sender, ChatView.PRIVATE, senderPrefix + plainItemText, senderMessage);
        rememberChatMessage(target, ChatView.PRIVATE, targetPrefix + plainItemText, targetMessage);
        sender.sendSystemMessage(senderMessage);
        target.sendSystemMessage(targetMessage);
        LAST_PRIVATE.put(sender.getUUID(), target.getUUID());
        LAST_PRIVATE.put(target.getUUID(), sender.getUUID());
        PRIVATE_CHAT_LOGGER.info(senderName + " -> " + targetName + ": " + stripColor(plainItemText));
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
            sendFilteredChatMessage(target, ChatView.GLOBAL, formatted);
        }

        DISCORD_CHAT_LOGGER.info(safeAuthor + ": " + safeMessage);
    }

    private static void mutePlayer(ServerPlayer player, long durationMillis, String reason) {
        long untilMillis = System.currentTimeMillis() + durationMillis;
        MUTED_PLAYERS.put(
                player.getUUID(),
                new MuteData(player.getGameProfile().getName(), untilMillis, reason == null ? "" : reason)
        );
    }

    private static void unmutePlayer(ServerPlayer player) {
        MUTED_PLAYERS.remove(player.getUUID());
    }

    private static void tempBanPlayer(ServerPlayer player, long durationMillis, String reason) {
        long untilMillis = System.currentTimeMillis() + durationMillis;
        TEMP_BANNED_PLAYERS.put(
                player.getUUID(),
                new TempBanData(player.getGameProfile().getName(), untilMillis, reason == null ? "" : reason)
        );
        saveTempBans();
    }

    private static boolean untempBanPlayerByName(String name) {
        UUID foundUuid = null;

        for (Map.Entry<UUID, TempBanData> entry : TEMP_BANNED_PLAYERS.entrySet()) {
            if (entry.getValue().name().equalsIgnoreCase(name)) {
                foundUuid = entry.getKey();
                break;
            }
        }

        if (foundUuid == null) {
            return false;
        }

        TEMP_BANNED_PLAYERS.remove(foundUuid);
        saveTempBans();
        return true;
    }

    private static boolean isTempBanned(ServerPlayer player) {
        TempBanData data = TEMP_BANNED_PLAYERS.get(player.getUUID());

        if (data == null) {
            return false;
        }

        if (System.currentTimeMillis() >= data.untilMillis()) {
            TEMP_BANNED_PLAYERS.remove(player.getUUID());
            saveTempBans();
            return false;
        }

        return true;
    }

    private static void disconnectTempBannedPlayer(ServerPlayer player) {
        TempBanData data = TEMP_BANNED_PLAYERS.get(player.getUUID());

        if (data == null) {
            return;
        }

        long leftMillis = Math.max(1000L, data.untilMillis() - System.currentTimeMillis());
        String reason = data.reason() == null || data.reason().isBlank() ? "не указана" : data.reason();

        player.connection.disconnect(Component.literal(
                "§cТы временно забанен.\n"
                        + "§cОсталось: §e" + formatMuteTime(leftMillis) + "\n"
                        + "§cПричина: §f" + reason
        ));
    }


    private static Path tempBansPath() {
        return FMLPaths.CONFIGDIR.get().resolve("cubechat-tempbans.txt");
    }

    private static void loadTempBans() {
        TEMP_BANNED_PLAYERS.clear();

        Path path = tempBansPath();
        if (!Files.exists(path)) {
            return;
        }

        try {
            long now = System.currentTimeMillis();

            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }

                String[] parts = line.split("\\t", 4);
                if (parts.length < 4) {
                    continue;
                }

                UUID uuid = UUID.fromString(parts[0]);
                long untilMillis = Long.parseLong(parts[1]);

                if (now >= untilMillis) {
                    continue;
                }

                String name = decodeBase64(parts[2]);
                String reason = decodeBase64(parts[3]);

                TEMP_BANNED_PLAYERS.put(uuid, new TempBanData(name, untilMillis, reason));
            }
        } catch (Throwable e) {
            System.out.println("[CointCoreGTO] Failed to load tempbans: " + e.getMessage());
        }
    }

    private static void saveTempBans() {
        Path path = tempBansPath();

        try {
            Files.createDirectories(path.getParent());

            long now = System.currentTimeMillis();
            ArrayList<String> lines = new ArrayList<>();

            for (Map.Entry<UUID, TempBanData> entry : TEMP_BANNED_PLAYERS.entrySet()) {
                TempBanData data = entry.getValue();

                if (now >= data.untilMillis()) {
                    continue;
                }

                lines.add(entry.getKey()
                        + "\t" + data.untilMillis()
                        + "\t" + encodeBase64(data.name())
                        + "\t" + encodeBase64(data.reason()));
            }

            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            System.out.println("[CointCoreGTO] Failed to save tempbans: " + e.getMessage());
        }
    }

    private static String encodeBase64(String text) {
        String safe = text == null ? "" : text;
        return Base64.getEncoder().encodeToString(safe.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeBase64(String text) {
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }


    private static Path lastLocationsPath() {
        return FMLPaths.CONFIGDIR.get().resolve("cubechat-lastlocations.txt");
    }

    private static void saveLastLocation(ServerPlayer player) {
        LAST_LOCATIONS.put(
                player.getUUID(),
                new LastLocationData(
                        player.getGameProfile().getName(),
                        player.serverLevel().dimension().location().toString(),
                        player.getX(),
                        player.getY(),
                        player.getZ(),
                        player.getYRot(),
                        player.getXRot(),
                        System.currentTimeMillis()
                )
        );
    }

    private static LastLocationData getLastLocationByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        for (LastLocationData data : LAST_LOCATIONS.values()) {
            if (data.name().equalsIgnoreCase(name)) {
                return data;
            }
        }

        return null;
    }

    private static boolean teleportToPlayerOrLastLocation(ServerPlayer player, String targetName) {
        MinecraftServer server = player.getServer();
        if (server == null || targetName == null || targetName.isBlank()) {
            return false;
        }

        ServerPlayer onlineTarget = server.getPlayerList().getPlayerByName(targetName);
        if (onlineTarget != null) {
            player.teleportTo(
                    onlineTarget.serverLevel(),
                    onlineTarget.getX(),
                    onlineTarget.getY(),
                    onlineTarget.getZ(),
                    onlineTarget.getYRot(),
                    onlineTarget.getXRot()
            );
            player.sendSystemMessage(Component.literal("§aТелепорт к игроку §e" + onlineTarget.getGameProfile().getName()));
            return true;
        }

        LastLocationData location = getLastLocationByName(targetName);
        if (location == null) {
            return false;
        }

        ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(location.dimension()));
        ServerLevel level = server.getLevel(dimensionKey);

        if (level == null) {
            player.sendSystemMessage(Component.literal("§cМир последней позиции не найден: §f" + location.dimension()));
            return true;
        }

        player.teleportTo(
                level,
                location.x(),
                location.y(),
                location.z(),
                location.yRot(),
                location.xRot()
        );

        player.sendSystemMessage(Component.literal(
                "§aТелепорт к последней позиции игрока §e"
                        + location.name()
                        + "§7 ("
                        + formatDateTime(location.savedMillis())
                        + ")"
        ));
        return true;
    }

    private static void loadLastLocations() {
        LAST_LOCATIONS.clear();

        Path path = lastLocationsPath();
        if (!Files.exists(path)) {
            return;
        }

        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }

                String[] parts = line.split("\\t", 9);
                if (parts.length < 9) {
                    continue;
                }

                UUID uuid = UUID.fromString(parts[0]);
                String name = decodeBase64(parts[1]);
                String dimension = decodeBase64(parts[2]);
                double x = Double.parseDouble(parts[3]);
                double y = Double.parseDouble(parts[4]);
                double z = Double.parseDouble(parts[5]);
                float yRot = Float.parseFloat(parts[6]);
                float xRot = Float.parseFloat(parts[7]);
                long savedMillis = Long.parseLong(parts[8]);

                LAST_LOCATIONS.put(uuid, new LastLocationData(name, dimension, x, y, z, yRot, xRot, savedMillis));
            }
        } catch (Throwable e) {
            System.out.println("[CointCoreGTO] Failed to load last locations: " + e.getMessage());
        }
    }

    private static void saveLastLocations() {
        Path path = lastLocationsPath();

        try {
            Files.createDirectories(path.getParent());

            ArrayList<String> lines = new ArrayList<>();

            for (Map.Entry<UUID, LastLocationData> entry : LAST_LOCATIONS.entrySet()) {
                LastLocationData data = entry.getValue();
                lines.add(entry.getKey()
                        + "\t" + encodeBase64(data.name())
                        + "\t" + encodeBase64(data.dimension())
                        + "\t" + data.x()
                        + "\t" + data.y()
                        + "\t" + data.z()
                        + "\t" + data.yRot()
                        + "\t" + data.xRot()
                        + "\t" + data.savedMillis());
            }

            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            System.out.println("[CointCoreGTO] Failed to save last locations: " + e.getMessage());
        }
    }

    private static Path warnsPath() {
        return FMLPaths.CONFIGDIR.get().resolve("cubechat-warns.txt");
    }

    private static void warnPlayer(ServerPlayer player, String reason) {
        ArrayList<WarnData> warns = WARNED_PLAYERS.computeIfAbsent(player.getUUID(), uuid -> new ArrayList<>());
        warns.add(new WarnData(player.getGameProfile().getName(), System.currentTimeMillis(), reason == null ? "" : reason));
        saveWarns();
    }

    private static int getWarnCount(UUID uuid) {
        ArrayList<WarnData> warns = WARNED_PLAYERS.get(uuid);
        return warns == null ? 0 : warns.size();
    }

    private static int getWarnCountByName(String name) {
        ArrayList<WarnData> warns = getWarnsByName(name);
        return warns == null ? 0 : warns.size();
    }

    private static ArrayList<WarnData> getWarnsByName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        for (ArrayList<WarnData> warns : WARNED_PLAYERS.values()) {
            if (warns.isEmpty()) {
                continue;
            }

            if (warns.get(0).name().equalsIgnoreCase(name)) {
                return warns;
            }
        }

        return null;
    }

    private static boolean canUseOthersHomeCommand(ServerPlayer player) {
        return player.hasPermissions(2) || hasPermissionNode(player, "cointcoregto.home.others");
    }

    private static UUID findKnownUuidByName(MinecraftServer server, String name) {
        if (server == null || name == null || name.isBlank()) {
            return null;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.getGameProfile().getName().equalsIgnoreCase(name)) {
                return player.getUUID();
            }
        }

        UUID cachedUuid = findUuidInUserCache(server, name);
        if (cachedUuid != null) {
            return cachedUuid;
        }

        try {
            return server.getProfileCache()
                    .get(name)
                    .map(profile -> profile.getId())
                    .orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static UUID findUuidInUserCache(MinecraftServer server, String name) {
        if (server == null || name == null || name.isBlank()) {
            return null;
        }

        try {
            Path userCachePath = server.getServerDirectory().toPath().resolve("usercache.json");

            if (!Files.exists(userCachePath)) {
                return null;
            }

            String json = Files.readString(userCachePath, StandardCharsets.UTF_8);
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();

            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }

                String cachedName = element.getAsJsonObject().get("name").getAsString();
                String cachedUuid = element.getAsJsonObject().get("uuid").getAsString();

                if (cachedName.equalsIgnoreCase(name)) {
                    return UUID.fromString(cachedUuid);
                }
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static boolean teleportToFtbHome(ServerPlayer admin, String homeName, String targetName) {
        MinecraftServer server = admin.getServer();
        if (server == null || homeName == null || homeName.isBlank() || targetName == null || targetName.isBlank()) {
            return false;
        }

        UUID targetUuid = findKnownUuidByName(server, targetName);
        if (targetUuid == null) {
            admin.sendSystemMessage(Component.literal("§cИгрок не найден в известных данных сервера: §f" + targetName));
            return true;
        }

        try {
            Class<?> dataClass = Class.forName("dev.ftb.mods.ftbessentials.util.FTBEPlayerData");
            GameProfile profile = new GameProfile(targetUuid, targetName);

            Object optional = dataClass
                    .getMethod("getOrCreate", GameProfile.class)
                    .invoke(null, profile);

            if (!(optional instanceof java.util.Optional<?> opt) || opt.isEmpty()) {
                admin.sendSystemMessage(Component.literal("§cFTB Essentials не нашёл данные игрока: §f" + targetName));
                return true;
            }

            Object data = opt.get();

            try {
                Object exists = dataClass.getMethod("playerExists", UUID.class).invoke(null, targetUuid);
                if (!(exists instanceof Boolean value) || !value) {
                    data.getClass().getMethod("load").invoke(data);
                }
            } catch (Throwable ignored) {
                try {
                    data.getClass().getMethod("load").invoke(data);
                } catch (Throwable ignored2) {
                }
            }

            Object homeManager = data.getClass().getMethod("homeManager").invoke(data);
            Object streamObj = homeManager.getClass().getMethod("destinations").invoke(homeManager);

            if (!(streamObj instanceof java.util.stream.Stream<?> stream)) {
                admin.sendSystemMessage(Component.literal("§cНе удалось прочитать список хомов FTB Essentials."));
                return true;
            }

            final Object[] foundDestination = new Object[1];
            final String[] foundName = new String[1];

            stream.forEach(entry -> {
                try {
                    String entryName = String.valueOf(entry.getClass().getMethod("name").invoke(entry));
                    if (entryName.equalsIgnoreCase(homeName)) {
                        foundName[0] = entryName;
                        foundDestination[0] = entry.getClass().getMethod("destination").invoke(entry);
                    }
                } catch (Throwable ignored) {
                }
            });

            if (foundDestination[0] == null) {
                admin.sendSystemMessage(Component.literal("§cУ игрока §f" + targetName + " §cнет хома §f" + homeName));
                return true;
            }

            Object result = foundDestination[0].getClass()
                    .getMethod("teleport", ServerPlayer.class)
                    .invoke(foundDestination[0], admin);

            int commandResult = 1;
            try {
                Object runResult = result.getClass().getMethod("runCommand", ServerPlayer.class).invoke(result, admin);
                if (runResult instanceof Integer value) {
                    commandResult = value;
                }
            } catch (Throwable ignored) {
            }

            boolean success = commandResult > 0;
            try {
                Object successObj = result.getClass().getMethod("isSuccess").invoke(result);
                if (successObj instanceof Boolean value) {
                    success = value;
                }
            } catch (Throwable ignored) {
            }

            if (success) {
                admin.sendSystemMessage(Component.literal("§aТелепорт к хому §e" + foundName[0] + " §aигрока §e" + targetName));
            }

            return true;
        } catch (ClassNotFoundException e) {
            admin.sendSystemMessage(Component.literal("§cFTB Essentials не найден на сервере."));
            return true;
        } catch (Throwable e) {
            admin.sendSystemMessage(Component.literal("§cОшибка чтения хомов FTB Essentials: §f" + e.getMessage()));
            return true;
        }
    }


    private static CompletableFuture<Suggestions> suggestFtbHomeNamesForPlayer(String targetName, SuggestionsBuilder builder) {
        if (CURRENT_SERVER == null || targetName == null || targetName.isBlank()) {
            return builder.buildFuture();
        }

        UUID targetUuid = findKnownUuidByName(CURRENT_SERVER, targetName);
        if (targetUuid == null) {
            return builder.buildFuture();
        }

        String remaining = builder.getRemainingLowerCase();
        Set<String> suggestedHomeNames = new HashSet<>();

        try {
            Class<?> dataClass = Class.forName("dev.ftb.mods.ftbessentials.util.FTBEPlayerData");
            GameProfile profile = new GameProfile(targetUuid, targetName);

            Object optional = dataClass
                    .getMethod("getOrCreate", GameProfile.class)
                    .invoke(null, profile);

            if (!(optional instanceof java.util.Optional<?> opt) || opt.isEmpty()) {
                return builder.buildFuture();
            }

            Object data = opt.get();

            try {
                Object exists = dataClass.getMethod("playerExists", UUID.class).invoke(null, targetUuid);
                if (!(exists instanceof Boolean value) || !value) {
                    data.getClass().getMethod("load").invoke(data);
                }
            } catch (Throwable ignored) {
                try {
                    data.getClass().getMethod("load").invoke(data);
                } catch (Throwable ignored2) {
                }
            }

            Object homeManager = data.getClass().getMethod("homeManager").invoke(data);

            try {
                Object namesObj = homeManager.getClass().getMethod("getNames").invoke(homeManager);
                if (namesObj instanceof Iterable<?> names) {
                    for (Object name : names) {
                        if (name != null) {
                            suggestIfMatches(builder, suggestedHomeNames, String.valueOf(name), remaining);
                        }
                    }
                    return builder.buildFuture();
                }
            } catch (Throwable ignored) {
            }

            Object streamObj = homeManager.getClass().getMethod("destinations").invoke(homeManager);
            if (streamObj instanceof java.util.stream.Stream<?> stream) {
                stream.forEach(entry -> {
                    try {
                        Object name = entry.getClass().getMethod("name").invoke(entry);
                        if (name != null) {
                            suggestIfMatches(builder, suggestedHomeNames, String.valueOf(name), remaining);
                        }
                    } catch (Throwable ignored) {
                    }
                });
            }
        } catch (Throwable ignored) {
        }

        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestFtbHomeNames(SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        Set<String> suggestedHomeNames = new HashSet<>();

        suggestIfMatches(builder, suggestedHomeNames, "home", remaining);

        if (CURRENT_SERVER == null) {
            return builder.buildFuture();
        }

        try {
            Class<?> dataClass = Class.forName("dev.ftb.mods.ftbessentials.util.FTBEPlayerData");
            for (ServerPlayer player : CURRENT_SERVER.getPlayerList().getPlayers()) {
                Object optional = dataClass
                        .getMethod("getOrCreate", net.minecraft.world.entity.player.Player.class)
                        .invoke(null, player);

                if (!(optional instanceof java.util.Optional<?> opt) || opt.isEmpty()) {
                    continue;
                }

                Object data = opt.get();
                Object homeManager = data.getClass().getMethod("homeManager").invoke(data);
                Object namesObj = homeManager.getClass().getMethod("getNames").invoke(homeManager);

                if (namesObj instanceof Iterable<?> names) {
                    for (Object name : names) {
                        if (name != null) {
                            suggestIfMatches(builder, suggestedHomeNames, String.valueOf(name), remaining);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestKnownPlayerNames(SuggestionsBuilder builder) {
        String remaining = builder.getRemainingLowerCase();
        Set<String> suggestedNames = new HashSet<>();

        if (CURRENT_SERVER != null) {
            for (ServerPlayer player : CURRENT_SERVER.getPlayerList().getPlayers()) {
                suggestIfMatches(builder, suggestedNames, player.getGameProfile().getName(), remaining);
            }
        }

        for (TempBanData data : TEMP_BANNED_PLAYERS.values()) {
            suggestIfMatches(builder, suggestedNames, data.name(), remaining);
        }

        for (LastLocationData data : LAST_LOCATIONS.values()) {
            suggestIfMatches(builder, suggestedNames, data.name(), remaining);
        }

        for (ArrayList<WarnData> warns : WARNED_PLAYERS.values()) {
            if (!warns.isEmpty()) {
                suggestIfMatches(builder, suggestedNames, warns.get(0).name(), remaining);
            }
        }

        suggestUserCacheNames(CURRENT_SERVER, builder, suggestedNames, remaining);

        return builder.buildFuture();
    }

    private static void suggestUserCacheNames(
            MinecraftServer server,
            SuggestionsBuilder builder,
            Set<String> suggestedNames,
            String remaining
    ) {
        if (server == null) {
            return;
        }

        try {
            Path userCachePath = server.getServerDirectory().toPath().resolve("usercache.json");

            if (!Files.exists(userCachePath)) {
                return;
            }

            String json = Files.readString(userCachePath, StandardCharsets.UTF_8);
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();

            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }

                if (!element.getAsJsonObject().has("name")) {
                    continue;
                }

                String cachedName = element.getAsJsonObject().get("name").getAsString();
                suggestIfMatches(builder, suggestedNames, cachedName, remaining);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void suggestIfMatches(SuggestionsBuilder builder, Set<String> alreadySuggested, String value, String remainingLowerCase) {
        if (value == null || value.isBlank()) {
            return;
        }

        String trimmedValue = value.trim();
        String loweredValue = trimmedValue.toLowerCase(java.util.Locale.ROOT);

        if (!remainingLowerCase.isBlank() && !loweredValue.startsWith(remainingLowerCase)) {
            return;
        }

        if (alreadySuggested.add(loweredValue)) {
            builder.suggest(trimmedValue);
        }
    }

    private static CompletableFuture<Suggestions> suggestWarnNumbers(String name, SuggestionsBuilder builder) {
        ArrayList<WarnData> warns = getWarnsByName(name);

        if (warns != null) {
            for (int i = 1; i <= warns.size(); i++) {
                builder.suggest(i);
            }
        }

        return builder.buildFuture();
    }

    private static boolean removeLastWarnByName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }

        UUID foundUuid = null;
        ArrayList<WarnData> foundWarns = null;

        for (Map.Entry<UUID, ArrayList<WarnData>> entry : WARNED_PLAYERS.entrySet()) {
            ArrayList<WarnData> warns = entry.getValue();

            if (warns.isEmpty()) {
                continue;
            }

            if (warns.get(0).name().equalsIgnoreCase(name)) {
                foundUuid = entry.getKey();
                foundWarns = warns;
                break;
            }
        }

        if (foundUuid == null || foundWarns == null || foundWarns.isEmpty()) {
            return false;
        }

        foundWarns.remove(foundWarns.size() - 1);

        if (foundWarns.isEmpty()) {
            WARNED_PLAYERS.remove(foundUuid);
        }

        saveWarns();
        return true;
    }

    private static boolean removeWarnByNameAndNumber(String name, int number) {
        if (name == null || name.isBlank() || number <= 0) {
            return false;
        }

        UUID foundUuid = null;
        ArrayList<WarnData> foundWarns = null;

        for (Map.Entry<UUID, ArrayList<WarnData>> entry : WARNED_PLAYERS.entrySet()) {
            ArrayList<WarnData> warns = entry.getValue();

            if (warns.isEmpty()) {
                continue;
            }

            if (warns.get(0).name().equalsIgnoreCase(name)) {
                foundUuid = entry.getKey();
                foundWarns = warns;
                break;
            }
        }

        if (foundUuid == null || foundWarns == null || number > foundWarns.size()) {
            return false;
        }

        foundWarns.remove(number - 1);

        if (foundWarns.isEmpty()) {
            WARNED_PLAYERS.remove(foundUuid);
        }

        saveWarns();
        return true;
    }

    private static void loadWarns() {
        WARNED_PLAYERS.clear();

        Path path = warnsPath();
        if (!Files.exists(path)) {
            return;
        }

        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }

                String[] parts = line.split("\\t", 4);
                if (parts.length < 4) {
                    continue;
                }

                UUID uuid = UUID.fromString(parts[0]);
                String name = decodeBase64(parts[1]);
                long createdMillis = Long.parseLong(parts[2]);
                String reason = decodeBase64(parts[3]);

                WARNED_PLAYERS
                        .computeIfAbsent(uuid, key -> new ArrayList<>())
                        .add(new WarnData(name, createdMillis, reason));
            }
        } catch (Throwable e) {
            System.out.println("[CointCoreGTO] Failed to load warns: " + e.getMessage());
        }
    }

    private static void saveWarns() {
        Path path = warnsPath();

        try {
            Files.createDirectories(path.getParent());

            ArrayList<String> lines = new ArrayList<>();

            for (Map.Entry<UUID, ArrayList<WarnData>> entry : WARNED_PLAYERS.entrySet()) {
                for (WarnData warn : entry.getValue()) {
                    lines.add(entry.getKey()
                            + "\t" + encodeBase64(warn.name())
                            + "\t" + warn.createdMillis()
                            + "\t" + encodeBase64(warn.reason()));
                }
            }

            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            System.out.println("[CointCoreGTO] Failed to save warns: " + e.getMessage());
        }
    }

    private static boolean isMuted(ServerPlayer player) {
        MuteData data = MUTED_PLAYERS.get(player.getUUID());

        if (data == null) {
            return false;
        }

        if (System.currentTimeMillis() >= data.untilMillis()) {
            MUTED_PLAYERS.remove(player.getUUID());
            return false;
        }

        return true;
    }

    private static void sendMutedMessage(ServerPlayer player) {
        MuteData data = MUTED_PLAYERS.get(player.getUUID());

        if (data == null) {
            return;
        }

        long leftMillis = Math.max(1000L, data.untilMillis() - System.currentTimeMillis());
        String reason = data.reason() == null || data.reason().isBlank() ? "не указана" : data.reason();

        String fullMessage = "§cТы замучен. Осталось: §e"
                + formatMuteTime(leftMillis)
                + "§c. Причина: §f"
                + reason;

        player.displayClientMessage(Component.literal(fullMessage), true);
        player.sendSystemMessage(Component.literal(fullMessage));
    }

    private static Path punishmentHistoryPath() {
        return FMLPaths.CONFIGDIR.get().resolve("cubechat-punishment-history.txt");
    }

    private static void recordPunishment(UUID uuid, String playerName, String type, String moderator, long durationMillis, String reason) {
        if (uuid == null) {
            return;
        }

        PunishmentHistoryData data = new PunishmentHistoryData(
                playerName == null ? "" : playerName,
                type == null ? "UNKNOWN" : type,
                moderator == null || moderator.isBlank() ? "Console" : moderator,
                System.currentTimeMillis(),
                Math.max(0L, durationMillis),
                reason == null ? "" : reason
        );

        PUNISHMENT_HISTORY.computeIfAbsent(uuid, key -> new ArrayList<>()).add(data);
        savePunishmentHistory();
    }

    private static void showPunishmentHistory(CommandSourceStack source, String targetName) {
        MinecraftServer server = source.getServer();
        UUID targetUuid = findKnownUuidByName(server, targetName);

        if (targetUuid == null) {
            source.sendFailure(Component.literal("Игрок не найден в известных данных CointCoreGTO: " + targetName));
            return;
        }

        ArrayList<PunishmentHistoryData> history = PUNISHMENT_HISTORY.get(targetUuid);
        if (history == null || history.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§aУ игрока " + targetName + " нет истории наказаний."), false);
            return;
        }

        String resolvedDisplayName = history.get(history.size() - 1).name();
        if (resolvedDisplayName == null || resolvedDisplayName.isBlank()) {
            resolvedDisplayName = targetName;
        }

        final String displayName = resolvedDisplayName;

        source.sendSuccess(() -> Component.literal("§6История наказаний игрока §e" + displayName + "§6: §f" + history.size()), false);

        int start = Math.max(0, history.size() - 20);
        if (start > 0) {
            final int hidden = start;
            source.sendSuccess(() -> Component.literal("§7Показаны последние 20 записей. Старых записей скрыто: " + hidden), false);
        }

        for (int i = start; i < history.size(); i++) {
            PunishmentHistoryData data = history.get(i);
            String type = formatPunishmentType(data.type());
            String duration = data.durationMillis() > 0L ? " §7на §e" + formatMuteTime(data.durationMillis()) : "";
            String reason = data.reason() == null || data.reason().isBlank() ? "не указана" : data.reason();
            int number = i + 1;

            source.sendSuccess(() -> Component.literal(
                    "§8#" + number
                            + " §7| §f" + formatDateTime(data.createdMillis())
                            + " §7| §c" + type
                            + duration
                            + " §7| Модератор: §e" + data.moderator()
                            + " §7| Причина: §f" + reason
            ), false);
        }
    }

    private static String formatPunishmentType(String type) {
        if (type == null) {
            return "UNKNOWN";
        }

        return switch (type.toUpperCase(java.util.Locale.ROOT)) {
            case "MUTE" -> "Мут";
            case "UNMUTE" -> "Снятие мута";
            case "TEMPBAN" -> "Временный бан";
            case "UNTEMPBAN" -> "Снятие временного бана";
            case "UNBAN" -> "Разбан";
            case "WARN" -> "Варн";
            case "UNWARN" -> "Снятие варна";
            default -> type;
        };
    }

    private static String getCommandSourceName(CommandSourceStack source) {
        if (source == null) {
            return "Console";
        }

        if (source.getEntity() instanceof ServerPlayer player) {
            return player.getGameProfile().getName();
        }

        String text = source.getTextName();
        return text == null || text.isBlank() ? "Console" : text;
    }

    private static void loadPunishmentHistory() {
        PUNISHMENT_HISTORY.clear();

        Path path = punishmentHistoryPath();
        if (!Files.exists(path)) {
            return;
        }

        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }

                String[] parts = line.split("\\t", 7);
                if (parts.length < 7) {
                    continue;
                }

                UUID uuid = UUID.fromString(parts[0]);
                String name = decodeBase64(parts[1]);
                String type = decodeBase64(parts[2]);
                String moderator = decodeBase64(parts[3]);
                long createdMillis = Long.parseLong(parts[4]);
                long durationMillis = Long.parseLong(parts[5]);
                String reason = decodeBase64(parts[6]);

                PUNISHMENT_HISTORY
                        .computeIfAbsent(uuid, key -> new ArrayList<>())
                        .add(new PunishmentHistoryData(name, type, moderator, createdMillis, durationMillis, reason));
            }
        } catch (Throwable e) {
            System.out.println("[CointCoreGTO] Failed to load punishment history: " + e.getMessage());
        }
    }

    private static void savePunishmentHistory() {
        Path path = punishmentHistoryPath();

        try {
            Files.createDirectories(path.getParent());
            ArrayList<String> lines = new ArrayList<>();

            for (Map.Entry<UUID, ArrayList<PunishmentHistoryData>> entry : PUNISHMENT_HISTORY.entrySet()) {
                for (PunishmentHistoryData data : entry.getValue()) {
                    lines.add(entry.getKey()
                            + "\t" + encodeBase64(data.name())
                            + "\t" + encodeBase64(data.type())
                            + "\t" + encodeBase64(data.moderator())
                            + "\t" + data.createdMillis()
                            + "\t" + data.durationMillis()
                            + "\t" + encodeBase64(data.reason()));
                }
            }

            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (Throwable e) {
            System.out.println("[CointCoreGTO] Failed to save punishment history: " + e.getMessage());
        }
    }

    private static long parsePunishmentTime(String input) {
        if (input == null || input.length() < 2) {
            return -1L;
        }

        input = input.toLowerCase(java.util.Locale.ROOT);

        long value;
        try {
            value = Long.parseLong(input.substring(0, input.length() - 1));
        } catch (NumberFormatException e) {
            return -1L;
        }

        if (value <= 0L) {
            return -1L;
        }

        char unit = input.charAt(input.length() - 1);

        return switch (unit) {
            case 's' -> value * 1000L;
            case 'm' -> value * 60_000L;
            case 'h' -> value * 3_600_000L;
            case 'd' -> value * 86_400_000L;
            default -> -1L;
        };
    }

    private static String formatMuteTime(long millis) {
        long seconds = Math.max(1L, millis / 1000L);

        if (seconds < 60L) {
            return seconds + " сек.";
        }

        long minutes = seconds / 60L;
        if (minutes < 60L) {
            return minutes + " мин.";
        }

        long hours = minutes / 60L;
        if (hours < 24L) {
            return hours + " ч.";
        }

        long days = hours / 24L;
        return days + " дн.";
    }


    private static String formatDateTime(long millis) {
        return ZonedDateTime
                .ofInstant(java.time.Instant.ofEpochMilli(millis), MOSCOW_ZONE)
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }

    public static String getDiscordDisplayName(ServerPlayer player) {
        return getDiscordDisplayName(player, "");
    }

    public static String getDiscordDisplayName(ServerPlayer player, String channelPrefix) {
        String displayName = color(channelPrefix == null ? "" : channelPrefix)
                + getLuckPermsPrefix(player)
                + getStaffTag(player)
                + player.getGameProfile().getName();

        displayName = stripColor(displayName).trim().replaceAll("\\s+", " ");

        if (displayName.length() > 80) {
            displayName = displayName.substring(0, 80);
        }

        return displayName;
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

    private static boolean hasCommandPermission(CommandSourceStack source, String permission) {
        if (source.hasPermission(2)) {
            return true;
        }

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return false;
        }

        return hasPermissionNode(player, permission);
    }

    public static boolean shouldShowInDiscordOnlineStatus(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        return !player.getTags().contains("cointcoregto_hide_online")
                && !player.getTags().contains("cointcoregto_hidden");
    }

    private static boolean isFtbEssentialsVanished(ServerPlayer player) {
        try {
            Class<?> dataClass = Class.forName("dev.ftb.mods.ftbessentials.util.FTBEPlayerData");
            Object optional = dataClass
                    .getMethod("getOrCreate", net.minecraft.world.entity.player.Player.class)
                    .invoke(null, player);

            if (!(optional instanceof java.util.Optional<?> opt) || opt.isEmpty()) {
                return false;
            }

            Object data = opt.get();
            String[] methodNames = {"isVanished", "getVanished", "vanished", "isVanish", "getVanish", "vanish"};

            for (String methodName : methodNames) {
                try {
                    Object result = data.getClass().getMethod(methodName).invoke(data);
                    if (result instanceof Boolean value) {
                        return value;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    public static boolean hasPermissionNode(ServerPlayer player, String permission) {
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            User user = luckPerms.getPlayerAdapter(ServerPlayer.class).getUser(player);
            return user.getCachedData().getPermissionData().checkPermission(permission).asBoolean();
        } catch (Throwable ignored) {
            return player.hasPermissions(2);
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

    private static final class ClearChatPacket {
        private final boolean keepSystemMessages;

        private ClearChatPacket(boolean keepSystemMessages) {
            this.keepSystemMessages = keepSystemMessages;
        }

        private static void encode(ClearChatPacket packet, FriendlyByteBuf buffer) {
            buffer.writeBoolean(packet.keepSystemMessages);
        }

        private static ClearChatPacket decode(FriendlyByteBuf buffer) {
            return new ClearChatPacket(buffer.readBoolean());
        }

        private static void handle(ClearChatPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                    net.minecraftforge.api.distmarker.Dist.CLIENT,
                    () -> () -> CointCoreGTOClient.clearChatMessages(packet.keepSystemMessages)
            ));
            context.setPacketHandled(true);
        }
    }

    private static boolean isValidRadioUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        String lowered = url.toLowerCase(java.util.Locale.ROOT);

        return lowered.startsWith("http://") || lowered.startsWith("https://");
    }

    private record ChatHistoryMessage(long order, ChatView view, String message, Component component) {
    }

    private record MuteData(String name, long untilMillis, String reason) {
    }

    private record TempBanData(String name, long untilMillis, String reason) {
    }


    private record LastLocationData(String name, String dimension, double x, double y, double z, float yRot, float xRot, long savedMillis) {
    }

    private record WarnData(String name, long createdMillis, String reason) {
    }

    private record PunishmentHistoryData(String name, String type, String moderator, long createdMillis, long durationMillis, String reason) {
    }
}
