package Crazer.cubeofinterest.cointcoregto;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
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

    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final long CHECK_INTERVAL_MILLIS = 1_000L;

    private static final Map<String, Long> NEXT_MESSAGE_SEND_AT = new HashMap<>();
    private static final Map<String, Long> NEXT_RANDOM_GROUP_SEND_AT = new HashMap<>();
    private static final Map<String, Integer> LAST_RANDOM_INDEX = new HashMap<>();

    private static ReminderConfig config = ReminderConfig.createDefault();
    private static long lastCheckMillis = 0L;

    private CointCoreGTOReminders() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        reloadConfig(false);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        lastCheckMillis = 0L;
        NEXT_MESSAGE_SEND_AT.clear();
        NEXT_RANDOM_GROUP_SEND_AT.clear();
        LAST_RANDOM_INDEX.clear();
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

        if (activeConfig.onlyWhenPlayersOnline
                && server.getPlayerList().getPlayerCount() == 0) {
            return;
        }

        processScheduledMessages(server, activeConfig.messages, now);
        processRandomGroups(server, activeConfig.randomGroups, now);
    }

    private static void processScheduledMessages(
            MinecraftServer server,
            List<ReminderEntry> entries,
            long now
    ) {
        if (entries == null || entries.isEmpty()) {
            return;
        }

        for (ReminderEntry entry : entries) {
            if (!isValid(entry)) {
                continue;
            }

            String id = normalizeId(entry.id);
            Long nextSendAt = NEXT_MESSAGE_SEND_AT.get(id);

            if (nextSendAt == null || now < nextSendAt) {
                continue;
            }

            broadcast(server, entry.text);
            scheduleMessageAfterSend(entry, now);
        }
    }

    private static void processRandomGroups(
            MinecraftServer server,
            List<RandomGroup> groups,
            long now
    ) {
        if (groups == null || groups.isEmpty()) {
            return;
        }

        for (RandomGroup group : groups) {
            if (!isValid(group)) {
                continue;
            }

            String id = normalizeId(group.id);
            Long nextSendAt = NEXT_RANDOM_GROUP_SEND_AT.get(id);

            if (nextSendAt == null || now < nextSendAt) {
                continue;
            }

            String selectedText = selectRandomText(group);
            if (selectedText != null) {
                broadcast(server, selectedText);
            }

            scheduleRandomGroupAfterSend(group, now);
        }
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
            broadcast(source.getServer(), entry.text);
            source.sendSuccess(
                    () -> Component.literal(
                            "§aНапоминание '" + entry.id + "' отправлено."
                    ),
                    false
            );
            return 1;
        }

        RandomGroup group = findRandomGroup(requestedId);
        if (group != null) {
            String selectedText = selectRandomText(group);
            if (selectedText == null) {
                source.sendFailure(
                        Component.literal(
                                "§cВ случайной группе '" + group.id + "' нет доступных фраз."
                        )
                );
                return 0;
            }

            broadcast(source.getServer(), selectedText);
            source.sendSuccess(
                    () -> Component.literal(
                            "§aСлучайная фраза из группы '" + group.id + "' отправлена."
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

            if (loaded.messages == null) {
                loaded.messages = new ArrayList<>();
            }

            if (loaded.randomGroups == null) {
                loaded.randomGroups = new ArrayList<>();
            }

            validateUniqueIds(loaded.messages, loaded.randomGroups);

            config = loaded;
            resetSchedule(System.currentTimeMillis());
            return true;
        } catch (Exception ignored) {
            if (!keepOldConfigOnError) {
                config = ReminderConfig.createDefault();
                NEXT_MESSAGE_SEND_AT.clear();
                NEXT_RANDOM_GROUP_SEND_AT.clear();
                LAST_RANDOM_INDEX.clear();
            }

            return false;
        }
    }

    private static void writeDefaultConfig() throws IOException {
        Path parent = CONFIG_PATH.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (Writer writer = Files.newBufferedWriter(
                CONFIG_PATH,
                StandardCharsets.UTF_8
        )) {
            GSON.toJson(ReminderConfig.createDefault(), writer);
        }
    }

    private static void resetSchedule(long now) {
        NEXT_MESSAGE_SEND_AT.clear();
        NEXT_RANDOM_GROUP_SEND_AT.clear();
        LAST_RANDOM_INDEX.clear();

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

    private static String selectRandomText(RandomGroup group) {
        List<Integer> validIndexes = new ArrayList<>();

        for (int index = 0; index < group.texts.size(); index++) {
            String text = group.texts.get(index);
            if (text != null && !text.isBlank()) {
                validIndexes.add(index);
            }
        }

        if (validIndexes.isEmpty()) {
            return null;
        }

        String id = normalizeId(group.id);
        Integer lastIndex = LAST_RANDOM_INDEX.get(id);

        int selectedIndex;
        if (group.avoidImmediateRepeat
                && validIndexes.size() > 1
                && lastIndex != null) {
            List<Integer> candidates = new ArrayList<>(validIndexes);
            candidates.remove(lastIndex);
            selectedIndex = candidates.get(
                    ThreadLocalRandom.current().nextInt(candidates.size())
            );
        } else {
            selectedIndex = validIndexes.get(
                    ThreadLocalRandom.current().nextInt(validIndexes.size())
            );
        }

        LAST_RANDOM_INDEX.put(id, selectedIndex);
        return group.texts.get(selectedIndex);
    }

    private static void broadcast(MinecraftServer server, String rawText) {
        if (server == null || rawText == null || rawText.isBlank()) {
            return;
        }

        Component message = Component.literal(applyLegacyColors(rawText));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(message);
        }
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

        if (!config.enabled) {
            return;
        }

        long now = System.currentTimeMillis();

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
        boolean onlyWhenPlayersOnline = true;
        List<ReminderEntry> messages = new ArrayList<>();
        List<RandomGroup> randomGroups = new ArrayList<>();

        static ReminderConfig createDefault() {
            ReminderConfig result = new ReminderConfig();
            result.enabled = false;
            result.onlyWhenPlayersOnline = true;

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
}
