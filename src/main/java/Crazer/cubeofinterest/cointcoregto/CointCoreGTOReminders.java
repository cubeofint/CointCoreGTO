package Crazer.cubeofinterest.cointcoregto;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class CointCoreGTOReminders {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private static final Path CONFIG_PATH = FMLPaths.CONFIGDIR
            .get()
            .resolve("cointcoregto-reminders.json");
    private static final Path STATE_PATH = FMLPaths.CONFIGDIR
            .get()
            .resolve("cointcoregto-reminders-state.json");

    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final long CHECK_INTERVAL_MILLIS = 1_000L;
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)https?://[^\\s§]+");

    private static final Map<String, Long> NEXT_MESSAGE_SEND_AT = new HashMap<>();
    private static final Map<String, Long> NEXT_RANDOM_GROUP_SEND_AT = new HashMap<>();

    private static ReminderConfig config = ReminderConfig.createDefault();
    private static ReminderState state = new ReminderState();
    private static long lastCheckMillis = 0L;

    private CointCoreGTOReminders() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        loadState();
        reloadConfig(false);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        saveState();
        lastCheckMillis = 0L;
        NEXT_MESSAGE_SEND_AT.clear();
        NEXT_RANDOM_GROUP_SEND_AT.clear();
        state = new ReminderState();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        MinecraftServer server = event.getServer();
        long now = System.currentTimeMillis();

        if (now - lastCheckMillis < CHECK_INTERVAL_MILLIS) {
            return;
        }

        lastCheckMillis = now;

        ReminderConfig activeConfig = config;
        if (!activeConfig.enabled) {
            return;
        }

        int minimumOnlinePlayers = Math.max(0, activeConfig.minimumOnlinePlayers);
        if (server.getPlayerList().getPlayerCount() < minimumOnlinePlayers) {
            return;
        }

        processDueNotifications(server, activeConfig, now);
    }

    private static void processDueNotifications(
            MinecraftServer server,
            ReminderConfig activeConfig,
            long now
    ) {
        if (!canSendAnotherNotification(activeConfig, now)) {
            return;
        }

        DispatchCandidate selected = null;
        int order = 0;

        if (activeConfig.messages != null) {
            for (ReminderEntry entry : activeConfig.messages) {
                if (!isValid(entry)) {
                    order++;
                    continue;
                }

                String id = normalizeId(entry.id);
                Long nextSendAt = NEXT_MESSAGE_SEND_AT.get(id);
                if (nextSendAt == null || now < nextSendAt) {
                    order++;
                    continue;
                }

                if (!isTextRepeatAllowed(activeConfig, entry.text, now)) {
                    order++;
                    continue;
                }

                DispatchCandidate candidate = DispatchCandidate.forMessage(
                        nextSendAt,
                        order,
                        entry
                );
                selected = selectEarlier(selected, candidate);
                order++;
            }
        }

        if (activeConfig.randomGroups != null) {
            for (RandomGroup group : activeConfig.randomGroups) {
                if (!isValid(group)) {
                    order++;
                    continue;
                }

                String id = normalizeId(group.id);
                Long nextSendAt = NEXT_RANDOM_GROUP_SEND_AT.get(id);
                if (nextSendAt == null || now < nextSendAt) {
                    order++;
                    continue;
                }

                RandomSelection selection = selectRandomText(
                        activeConfig,
                        group,
                        now,
                        true
                );
                if (selection == null) {
                    order++;
                    continue;
                }

                DispatchCandidate candidate = DispatchCandidate.forRandomGroup(
                        nextSendAt,
                        order,
                        group,
                        selection
                );
                selected = selectEarlier(selected, candidate);
                order++;
            }
        }

        if (selected == null) {
            return;
        }

        int recipients = broadcast(server, selected.text);
        if (recipients <= 0) {
            return;
        }

        recordSend(
                selected.text,
                selected.randomGroup,
                selected.randomIndex,
                now
        );

        if (selected.message != null) {
            scheduleMessageAfterSend(selected.message, now);
        } else if (selected.randomGroup != null) {
            scheduleRandomGroupAfterSend(selected.randomGroup, now);
        }
    }

    private static DispatchCandidate selectEarlier(
            DispatchCandidate current,
            DispatchCandidate candidate
    ) {
        if (current == null) {
            return candidate;
        }
        if (candidate.scheduledAt < current.scheduledAt) {
            return candidate;
        }
        if (candidate.scheduledAt == current.scheduledAt
                && candidate.order < current.order) {
            return candidate;
        }
        return current;
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("cointcoregto")
                        .requires(source -> source.hasPermission(2))
                        .then(
                                Commands.literal("reminders")
                                        .then(
                                                Commands.literal("reload")
                                                        .executes(context -> {
                                                            boolean loaded = reloadConfig(true);
                                                            if (loaded) {
                                                                context.getSource().sendSuccess(
                                                                        () -> Component.literal(
                                                                                "§aНапоминания CointCoreGTO перезагружены."
                                                                        ),
                                                                        false
                                                                );
                                                                return 1;
                                                            }
                                                            context.getSource().sendFailure(
                                                                    Component.literal(
                                                                            "§cНе удалось загрузить cointcoregto-reminders.json. Проверьте формат файла."
                                                                    )
                                                            );
                                                            return 0;
                                                        })
                                        )
                                        .then(
                                                Commands.literal("status")
                                                        .executes(context -> {
                                                            sendStatus(context.getSource());
                                                            return 1;
                                                        })
                                        )
                                        .then(
                                                Commands.literal("send")
                                                        .then(
                                                                Commands.argument(
                                                                                "id",
                                                                                StringArgumentType.word()
                                                                        )
                                                                        .suggests((context, builder) -> {
                                                                            addIdSuggestions(builder);
                                                                            return builder.buildFuture();
                                                                        })
                                                                        .executes(context -> sendById(
                                                                                context.getSource(),
                                                                                StringArgumentType.getString(
                                                                                        context,
                                                                                        "id"
                                                                                )
                                                                        ))
                                                        )
                                        )
                        )
        );
    }

    private static void addIdSuggestions(com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        if (config.messages != null) {
            for (ReminderEntry entry : config.messages) {
                if (entry != null && entry.id != null && !entry.id.isBlank()) {
                    builder.suggest(entry.id);
                }
            }
        }

        if (config.randomGroups != null) {
            for (RandomGroup group : config.randomGroups) {
                if (group != null && group.id != null && !group.id.isBlank()) {
                    builder.suggest(group.id);
                }
            }
        }
    }

    private static int sendById(CommandSourceStack source, String requestedId) {
        ReminderEntry entry = findMessage(requestedId);
        if (entry != null) {
            int recipients = broadcast(source.getServer(), entry.text);
            if (recipients <= 0) {
                source.sendFailure(
                        Component.literal("§cНет игроков, которым можно отправить напоминание.")
                );
                return 0;
            }

            recordSend(entry.text, null, null, System.currentTimeMillis());
            source.sendSuccess(
                    () -> Component.literal(
                            "§aНапоминание '" + entry.id + "' отправлено игрокам: " + recipients + "."
                    ),
                    false
            );
            return 1;
        }

        RandomGroup group = findRandomGroup(requestedId);
        if (group != null) {
            long now = System.currentTimeMillis();
            RandomSelection selection = selectRandomText(
                    config,
                    group,
                    now,
                    false
            );
            if (selection == null) {
                source.sendFailure(
                        Component.literal(
                                "§cВ случайной группе '" + group.id + "' нет доступных фраз."
                        )
                );
                return 0;
            }

            int recipients = broadcast(source.getServer(), selection.text);
            if (recipients <= 0) {
                source.sendFailure(
                        Component.literal("§cНет игроков, которым можно отправить напоминание.")
                );
                return 0;
            }

            recordSend(selection.text, group, selection.index, now);
            source.sendSuccess(
                    () -> Component.literal(
                            "§aСлучайная фраза из группы '"
                                    + group.id
                                    + "' отправлена игрокам: "
                                    + recipients
                                    + "."
                    ),
                    false
            );
            return 1;
        }

        source.sendFailure(
                Component.literal(
                        "§cНапоминание или случайная группа с ID '"
                                + requestedId
                                + "' не найдены."
                )
        );
        return 0;
    }

    private static boolean reloadConfig(boolean keepOldConfigOnError) {
        try {
            if (Files.notExists(CONFIG_PATH)) {
                writeDefaultConfig();
            }

            ReminderConfig loaded;
            try (Reader reader = Files.newBufferedReader(
                    CONFIG_PATH,
                    StandardCharsets.UTF_8
            )) {
                loaded = GSON.fromJson(reader, ReminderConfig.class);
            }

            if (loaded == null) {
                throw new IOException("Config file is empty");
            }

            normalizeConfig(loaded);
            validateConfig(loaded);
            validateUniqueIds(loaded.messages, loaded.randomGroups);

            config = loaded;
            resetSchedule(System.currentTimeMillis());

            try {
                writeConfig(config);
            } catch (IOException ignored) {
            }

            return true;
        } catch (Exception ignored) {
            if (!keepOldConfigOnError) {
                config = ReminderConfig.createDefault();
                NEXT_MESSAGE_SEND_AT.clear();
                NEXT_RANDOM_GROUP_SEND_AT.clear();
            }
            return false;
        }
    }

    private static void normalizeConfig(ReminderConfig loaded) {
        if (loaded.messages == null) {
            loaded.messages = new ArrayList<>();
        }
        if (loaded.randomGroups == null) {
            loaded.randomGroups = new ArrayList<>();
        }
    }

    private static void validateConfig(ReminderConfig loaded) {
        if (loaded.minimumOnlinePlayers < 0) {
            throw new IllegalArgumentException("minimumOnlinePlayers must be non-negative");
        }
        if (loaded.minimumMinutesBetweenNotifications < 0L) {
            throw new IllegalArgumentException(
                    "minimumMinutesBetweenNotifications must be non-negative"
            );
        }
        if (loaded.repeatCooldownMinutes < 0L) {
            throw new IllegalArgumentException("repeatCooldownMinutes must be non-negative");
        }
    }

    private static void writeDefaultConfig() throws IOException {
        writeConfig(ReminderConfig.createDefault());
    }

    private static void writeConfig(ReminderConfig value) throws IOException {
        Path parent = CONFIG_PATH.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (Writer writer = Files.newBufferedWriter(
                CONFIG_PATH,
                StandardCharsets.UTF_8
        )) {
            GSON.toJson(value, writer);
        }
    }

    private static void loadState() {
        try {
            if (Files.notExists(STATE_PATH)) {
                state = new ReminderState();
                return;
            }

            ReminderState loaded;
            try (Reader reader = Files.newBufferedReader(
                    STATE_PATH,
                    StandardCharsets.UTF_8
            )) {
                loaded = GSON.fromJson(reader, ReminderState.class);
            }

            if (loaded == null) {
                state = new ReminderState();
                return;
            }

            if (loaded.lastTextSendAtMillis == null) {
                loaded.lastTextSendAtMillis = new HashMap<>();
            }
            if (loaded.lastRandomIndexes == null) {
                loaded.lastRandomIndexes = new HashMap<>();
            }

            loaded.lastTextSendAtMillis.entrySet().removeIf(
                    entry -> entry.getKey() == null
                            || entry.getKey().isBlank()
                            || entry.getValue() == null
                            || entry.getValue() <= 0L
            );
            loaded.lastRandomIndexes.entrySet().removeIf(
                    entry -> entry.getKey() == null
                            || entry.getKey().isBlank()
                            || entry.getValue() == null
                            || entry.getValue() < 0
            );

            state = loaded;
        } catch (Exception ignored) {
            state = new ReminderState();
        }
    }

    private static void saveState() {
        try {
            Path parent = STATE_PATH.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (Writer writer = Files.newBufferedWriter(
                    STATE_PATH,
                    StandardCharsets.UTF_8
            )) {
                GSON.toJson(state, writer);
            }
        } catch (IOException ignored) {
        }
    }

    private static void resetSchedule(long now) {
        NEXT_MESSAGE_SEND_AT.clear();
        NEXT_RANDOM_GROUP_SEND_AT.clear();

        if (!config.enabled) {
            return;
        }

        if (config.messages != null) {
            for (ReminderEntry entry : config.messages) {
                if (!isValid(entry)) {
                    continue;
                }

                long delayMillis = safeMinutesToMillis(
                        Math.max(0L, entry.delayMinutes)
                );
                NEXT_MESSAGE_SEND_AT.put(
                        normalizeId(entry.id),
                        safeAdd(now, delayMillis)
                );
            }
        }

        if (config.randomGroups != null) {
            for (RandomGroup group : config.randomGroups) {
                if (!isValid(group)) {
                    continue;
                }

                long delayMillis = safeMinutesToMillis(
                        Math.max(0L, group.delayMinutes)
                );
                NEXT_RANDOM_GROUP_SEND_AT.put(
                        normalizeId(group.id),
                        safeAdd(now, delayMillis)
                );
            }
        }
    }

    private static void scheduleMessageAfterSend(ReminderEntry entry, long now) {
        String id = normalizeId(entry.id);
        if (entry.intervalMinutes <= 0L) {
            NEXT_MESSAGE_SEND_AT.remove(id);
            return;
        }

        NEXT_MESSAGE_SEND_AT.put(
                id,
                safeAdd(now, safeMinutesToMillis(entry.intervalMinutes))
        );
    }

    private static void scheduleRandomGroupAfterSend(RandomGroup group, long now) {
        String id = normalizeId(group.id);
        if (group.intervalMinutes <= 0L) {
            NEXT_RANDOM_GROUP_SEND_AT.remove(id);
            return;
        }

        NEXT_RANDOM_GROUP_SEND_AT.put(
                id,
                safeAdd(now, safeMinutesToMillis(group.intervalMinutes))
        );
    }

    private static boolean canSendAnotherNotification(
            ReminderConfig activeConfig,
            long now
    ) {
        if (activeConfig.minimumMinutesBetweenNotifications <= 0L) {
            return true;
        }

        long lastSentAt = state.lastNotificationAtMillis;
        if (lastSentAt <= 0L) {
            return true;
        }

        long cooldownMillis = safeMinutesToMillis(
                activeConfig.minimumMinutesBetweenNotifications
        );
        return now >= safeAdd(lastSentAt, cooldownMillis);
    }

    private static boolean isTextRepeatAllowed(
            ReminderConfig activeConfig,
            String text,
            long now
    ) {
        if (activeConfig.repeatCooldownMinutes <= 0L) {
            return true;
        }

        Long lastSentAt = state.lastTextSendAtMillis.get(normalizeTextKey(text));
        if (lastSentAt == null || lastSentAt <= 0L) {
            return true;
        }

        long cooldownMillis = safeMinutesToMillis(activeConfig.repeatCooldownMinutes);
        return now >= safeAdd(lastSentAt, cooldownMillis);
    }

    private static RandomSelection selectRandomText(
            ReminderConfig activeConfig,
            RandomGroup group,
            long now,
            boolean enforceRepeatCooldown
    ) {
        List<Integer> validIndexes = new ArrayList<>();
        List<Integer> eligibleIndexes = new ArrayList<>();

        for (int index = 0; index < group.texts.size(); index++) {
            String text = group.texts.get(index);
            if (text == null || text.isBlank()) {
                continue;
            }

            validIndexes.add(index);
            if (!enforceRepeatCooldown
                    || isTextRepeatAllowed(activeConfig, text, now)) {
                eligibleIndexes.add(index);
            }
        }

        if (eligibleIndexes.isEmpty()) {
            return null;
        }

        List<Integer> candidates = eligibleIndexes;
        Integer lastIndex = state.lastRandomIndexes.get(normalizeId(group.id));

        if (group.avoidImmediateRepeat
                && validIndexes.size() > 1
                && lastIndex != null) {
            candidates = new ArrayList<>(eligibleIndexes);
            candidates.remove(lastIndex);
            if (candidates.isEmpty()) {
                return null;
            }
        }

        int selectedIndex = candidates.get(
                ThreadLocalRandom.current().nextInt(candidates.size())
        );
        return new RandomSelection(
                selectedIndex,
                group.texts.get(selectedIndex)
        );
    }

    private static void recordSend(
            String text,
            RandomGroup randomGroup,
            Integer randomIndex,
            long now
    ) {
        state.lastNotificationAtMillis = now;
        state.lastTextSendAtMillis.put(normalizeTextKey(text), now);

        if (randomGroup != null && randomIndex != null) {
            state.lastRandomIndexes.put(
                    normalizeId(randomGroup.id),
                    randomIndex
            );
        }

        saveState();
    }

    private static int broadcast(MinecraftServer server, String rawText) {
        if (server == null || rawText == null || rawText.isBlank()) {
            return 0;
        }

        Component message = buildClickableMessage(rawText);
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer player : players) {
            player.sendSystemMessage(message);
        }
        return players.size();
    }

    private static Component buildClickableMessage(String rawText) {
        String formattedText = applyLegacyColors(rawText);
        MutableComponent result = Component.empty();
        Matcher matcher = URL_PATTERN.matcher(formattedText);
        int previousEnd = 0;

        while (matcher.find()) {
            int urlStart = matcher.start();
            int urlEnd = trimUrlEnd(formattedText, urlStart, matcher.end());

            if (urlStart > previousEnd) {
                result.append(Component.literal(formattedText.substring(previousEnd, urlStart)));
            }

            if (urlEnd > urlStart) {
                String url = formattedText.substring(urlStart, urlEnd);
                result.append(
                        Component.literal(url)
                                .withStyle(style -> style
                                        .withColor(ChatFormatting.AQUA)
                                        .withUnderlined(true)
                                        .withClickEvent(new ClickEvent(
                                                ClickEvent.Action.OPEN_URL,
                                                url
                                        ))
                                        .withHoverEvent(new HoverEvent(
                                                HoverEvent.Action.SHOW_TEXT,
                                                Component.literal("Открыть ссылку")
                                        )))
                );
            }

            if (urlEnd < matcher.end()) {
                result.append(Component.literal(formattedText.substring(urlEnd, matcher.end())));
            }

            previousEnd = matcher.end();
        }

        if (previousEnd < formattedText.length()) {
            result.append(Component.literal(formattedText.substring(previousEnd)));
        }

        return result;
    }

    private static int trimUrlEnd(String text, int start, int end) {
        int result = end;
        while (result > start && isTrailingUrlPunctuation(text.charAt(result - 1))) {
            result--;
        }
        return result;
    }

    private static boolean isTrailingUrlPunctuation(char character) {
        return character == '.'
                || character == ','
                || character == '!'
                || character == '?'
                || character == ';'
                || character == ':'
                || character == ')'
                || character == ']'
                || character == '}'
                || character == '"'
                || character == '\''
                || character == '»';
    }

    private static String applyLegacyColors(String text) {
        StringBuilder result = new StringBuilder(text.length());

        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '&' && index + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(index + 1));
                if ("0123456789abcdefklmnor".indexOf(code) >= 0) {
                    result.append('§').append(code);
                    index++;
                    continue;
                }
            }

            result.append(current);
        }

        return result.toString();
    }

    private static String normalizeTextKey(String text) {
        String formatted = applyLegacyColors(text == null ? "" : text);
        StringBuilder result = new StringBuilder(formatted.length());

        for (int index = 0; index < formatted.length(); index++) {
            char current = formatted.charAt(index);
            if (current == '§' && index + 1 < formatted.length()) {
                char code = Character.toLowerCase(formatted.charAt(index + 1));
                if ("0123456789abcdefklmnor".indexOf(code) >= 0) {
                    index++;
                    continue;
                }
            }
            result.append(current);
        }

        return result.toString().trim();
    }

    private static boolean isValid(ReminderEntry entry) {
        return entry != null
                && entry.enabled
                && entry.id != null
                && !entry.id.isBlank()
                && entry.text != null
                && !entry.text.isBlank()
                && entry.delayMinutes >= 0L
                && entry.intervalMinutes >= 0L;
    }

    private static boolean isValid(RandomGroup group) {
        return group != null
                && group.enabled
                && group.id != null
                && !group.id.isBlank()
                && group.texts != null
                && hasAtLeastOneText(group.texts)
                && group.delayMinutes >= 0L
                && group.intervalMinutes >= 0L;
    }

    private static boolean hasAtLeastOneText(List<String> texts) {
        for (String text : texts) {
            if (text != null && !text.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static void validateUniqueIds(
            List<ReminderEntry> messages,
            List<RandomGroup> randomGroups
    ) {
        Set<String> ids = new HashSet<>();

        for (ReminderEntry entry : messages) {
            if (entry == null || entry.id == null || entry.id.isBlank()) {
                continue;
            }
            addUniqueId(ids, entry.id);
        }

        for (RandomGroup group : randomGroups) {
            if (group == null || group.id == null || group.id.isBlank()) {
                continue;
            }
            addUniqueId(ids, group.id);
        }
    }

    private static void addUniqueId(Set<String> ids, String rawId) {
        String id = normalizeId(rawId);
        if (!ids.add(id)) {
            throw new IllegalArgumentException("Duplicate reminder id: " + id);
        }
    }

    private static ReminderEntry findMessage(String requestedId) {
        if (requestedId == null || config.messages == null) {
            return null;
        }

        String normalized = normalizeId(requestedId);
        for (ReminderEntry entry : config.messages) {
            if (entry != null
                    && entry.id != null
                    && normalizeId(entry.id).equals(normalized)) {
                return entry;
            }
        }
        return null;
    }

    private static RandomGroup findRandomGroup(String requestedId) {
        if (requestedId == null || config.randomGroups == null) {
            return null;
        }

        String normalized = normalizeId(requestedId);
        for (RandomGroup group : config.randomGroups) {
            if (group != null
                    && group.id != null
                    && normalizeId(group.id).equals(normalized)) {
                return group;
            }
        }
        return null;
    }

    private static String normalizeId(String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }

    private static long safeMinutesToMillis(long minutes) {
        try {
            return Math.multiplyExact(minutes, MILLIS_PER_MINUTE);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static void sendStatus(CommandSourceStack source) {
        int configuredMessages = config.messages == null ? 0 : config.messages.size();
        int configuredGroups = config.randomGroups == null ? 0 : config.randomGroups.size();

        source.sendSuccess(
                () -> Component.literal(
                        "§eНапоминания CointCoreGTO: §f"
                                + (config.enabled ? "включены" : "выключены")
                                + "§7, сообщений: §f" + configuredMessages
                                + "§7, случайных групп: §f" + configuredGroups
                ),
                false
        );

        source.sendSuccess(
                () -> Component.literal(
                        "§7Минимальный онлайн: §f"
                                + config.minimumOnlinePlayers
                                + "§7, пауза между уведомлениями: §f"
                                + config.minimumMinutesBetweenNotifications
                                + " мин§7, запрет повтора: §f"
                                + config.repeatCooldownMinutes
                                + " мин"
                ),
                false
        );

        if (!config.enabled) {
            return;
        }

        long now = System.currentTimeMillis();
        source.sendSuccess(
                () -> Component.literal(
                        "§7Следующее уведомление глобально доступно: §f"
                                + describeNextGlobalSend(now)
                ),
                false
        );

        if (config.messages != null) {
            for (ReminderEntry entry : config.messages) {
                if (!isValid(entry)) {
                    continue;
                }

                String line = "§7- §bСообщение §f"
                        + entry.id
                        + "§7: "
                        + describeNextRun(
                        NEXT_MESSAGE_SEND_AT.get(normalizeId(entry.id)),
                        now
                );
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }

        if (config.randomGroups != null) {
            for (RandomGroup group : config.randomGroups) {
                if (!isValid(group)) {
                    continue;
                }

                String line = "§7- §dСлучайная группа §f"
                        + group.id
                        + "§7: "
                        + describeNextRun(
                        NEXT_RANDOM_GROUP_SEND_AT.get(normalizeId(group.id)),
                        now
                );
                source.sendSuccess(() -> Component.literal(line), false);
            }
        }
    }

    private static String describeNextGlobalSend(long now) {
        if (state.lastNotificationAtMillis <= 0L
                || config.minimumMinutesBetweenNotifications <= 0L) {
            return "сейчас";
        }

        long next = safeAdd(
                state.lastNotificationAtMillis,
                safeMinutesToMillis(config.minimumMinutesBetweenNotifications)
        );
        if (now >= next) {
            return "сейчас";
        }
        return formatDuration(next - now);
    }

    private static String describeNextRun(Long next, long now) {
        if (next == null) {
            return "уже выполнено";
        }
        return formatDuration(Math.max(0L, next - now));
    }

    private static String formatDuration(long millis) {
        Duration duration = Duration.ofMillis(millis);
        long hours = duration.toHours();
        long minutes = duration.minusHours(hours).toMinutes();
        long seconds = duration.minusHours(hours).minusMinutes(minutes).toSeconds();

        if (hours > 0L) {
            return "через " + hours + " ч " + minutes + " мин";
        }
        if (minutes > 0L) {
            return "через " + minutes + " мин " + seconds + " сек";
        }
        return "через " + seconds + " сек";
    }

    private static final class ReminderConfig {
        boolean enabled = false;
        int minimumOnlinePlayers = 21;
        long minimumMinutesBetweenNotifications = 240L;
        long repeatCooldownMinutes = 2_880L;
        List<ReminderEntry> messages = new ArrayList<>();
        List<RandomGroup> randomGroups = new ArrayList<>();

        ReminderConfig() {
        }

        static ReminderConfig createDefault() {
            ReminderConfig result = new ReminderConfig();
            result.enabled = false;
            result.minimumOnlinePlayers = 21;
            result.minimumMinutesBetweenNotifications = 240L;
            result.repeatCooldownMinutes = 2_880L;

            result.messages.add(
                    new ReminderEntry(
                            "scheduled_example",
                            false,
                            60L,
                            360L,
                            "&6[Напоминание] &fПример регулярного сообщения с собственным расписанием."
                    )
            );

            List<String> tips = new ArrayList<>();
            tips.add(
                    "&b[Совет] &fFTB Chunks на проекте используется только для прогрузки чанков."
            );
            tips.add(
                    "&b[Совет] &fВ персональном измерении взаимодействовать могут только члены вашей FTB-пати и администрация проекта."
            );

            result.randomGroups.add(
                    new RandomGroup(
                            "server_tips",
                            false,
                            30L,
                            360L,
                            true,
                            tips
                    )
            );

            return result;
        }
    }

    private static final class ReminderState {
        long lastNotificationAtMillis = 0L;
        Map<String, Long> lastTextSendAtMillis = new HashMap<>();
        Map<String, Integer> lastRandomIndexes = new HashMap<>();

        ReminderState() {
        }
    }

    private static final class ReminderEntry {
        String id = "reminder";
        boolean enabled = false;
        long delayMinutes = 60L;
        long intervalMinutes = 0L;
        String text = "&e[Напоминание] &fТекст";

        ReminderEntry() {
        }

        ReminderEntry(
                String id,
                boolean enabled,
                long delayMinutes,
                long intervalMinutes,
                String text
        ) {
            this.id = id;
            this.enabled = enabled;
            this.delayMinutes = delayMinutes;
            this.intervalMinutes = intervalMinutes;
            this.text = text;
        }
    }

    private static final class RandomGroup {
        String id = "random_group";
        boolean enabled = false;
        long delayMinutes = 30L;
        long intervalMinutes = 360L;
        boolean avoidImmediateRepeat = true;
        List<String> texts = new ArrayList<>();

        RandomGroup() {
        }

        RandomGroup(
                String id,
                boolean enabled,
                long delayMinutes,
                long intervalMinutes,
                boolean avoidImmediateRepeat,
                List<String> texts
        ) {
            this.id = id;
            this.enabled = enabled;
            this.delayMinutes = delayMinutes;
            this.intervalMinutes = intervalMinutes;
            this.avoidImmediateRepeat = avoidImmediateRepeat;
            this.texts = texts;
        }
    }

    private static final class RandomSelection {
        final int index;
        final String text;

        RandomSelection(int index, String text) {
            this.index = index;
            this.text = text;
        }
    }

    private static final class DispatchCandidate {
        final long scheduledAt;
        final int order;
        final ReminderEntry message;
        final RandomGroup randomGroup;
        final Integer randomIndex;
        final String text;

        private DispatchCandidate(
                long scheduledAt,
                int order,
                ReminderEntry message,
                RandomGroup randomGroup,
                Integer randomIndex,
                String text
        ) {
            this.scheduledAt = scheduledAt;
            this.order = order;
            this.message = message;
            this.randomGroup = randomGroup;
            this.randomIndex = randomIndex;
            this.text = text;
        }

        static DispatchCandidate forMessage(
                long scheduledAt,
                int order,
                ReminderEntry message
        ) {
            return new DispatchCandidate(
                    scheduledAt,
                    order,
                    message,
                    null,
                    null,
                    message.text
            );
        }

        static DispatchCandidate forRandomGroup(
                long scheduledAt,
                int order,
                RandomGroup randomGroup,
                RandomSelection selection
        ) {
            return new DispatchCandidate(
                    scheduledAt,
                    order,
                    null,
                    randomGroup,
                    selection.index,
                    selection.text
            );
        }
    }
}
