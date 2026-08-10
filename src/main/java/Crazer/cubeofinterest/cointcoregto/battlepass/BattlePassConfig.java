package Crazer.cubeofinterest.cointcoregto.battlepass;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BattlePassConfig {
    public static final int MAX_DAYS = 31;
    public static final String FILE_NAME = "cointcoregto-battlepass-gto.json";

    private static final String DEFAULT_TITLE_FORMAT =
            "GregTech Odyssey — {month} {year}: {days} {days_word}";
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO/BattlePass");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);

    private static volatile Catalog catalog = defaultCatalog();
    private static volatile Snapshot snapshot = catalog.select(YearMonth.now(catalog.streakZone()));

    private BattlePassConfig() {
    }

    public static synchronized void reload() {
        try {
            if (Files.notExists(PATH)) {
                Files.createDirectories(PATH.getParent());
                Files.writeString(PATH, GSON.toJson(defaultJson()), StandardCharsets.UTF_8);
            }

            JsonElement parsed = JsonParser.parseString(Files.readString(PATH, StandardCharsets.UTF_8));
            JsonObject root = parsed.getAsJsonObject();
            boolean configChanged = migrateLegacyConfig(root);
            if (!root.has("reset_after_missed_days")) {
                root.addProperty("reset_after_missed_days", 3);
                configChanged = true;
            }
            if (configChanged) {
                Files.writeString(PATH, GSON.toJson(root), StandardCharsets.UTF_8);
                LOGGER.info("Updated the GregTech Odyssey battle pass config format: {}", PATH);
            }

            Catalog loadedCatalog = parse(root);
            YearMonth activeMonth = YearMonth.now(loadedCatalog.streakZone());
            catalog = loadedCatalog;
            snapshot = loadedCatalog.select(activeMonth);
            LOGGER.info(
                    "Loaded GregTech Odyssey battle pass for {} with {} days from {}",
                    activeMonth,
                    snapshot.rewards().size(),
                    PATH
            );
        } catch (Exception exception) {
            LOGGER.error(
                    "Failed to load GregTech Odyssey battle pass config. Keeping the previous valid configuration.",
                    exception
            );
        }
    }

    public static int maxSupportedDays() {
        return MAX_DAYS;
    }

    public static Snapshot get() {
        Catalog currentCatalog = catalog;
        YearMonth currentMonth = YearMonth.now(currentCatalog.streakZone());
        Snapshot currentSnapshot = snapshot;
        if (!currentSnapshot.activeMonth().equals(currentMonth)) {
            synchronized (BattlePassConfig.class) {
                currentCatalog = catalog;
                currentMonth = YearMonth.now(currentCatalog.streakZone());
                if (!snapshot.activeMonth().equals(currentMonth)) {
                    snapshot = currentCatalog.select(currentMonth);
                    LOGGER.info(
                            "Automatically switched GregTech Odyssey battle pass to {} ({} days)",
                            currentMonth,
                            snapshot.rewards().size()
                    );
                }
                currentSnapshot = snapshot;
            }
        }
        return currentSnapshot;
    }

    private static Catalog parse(JsonObject root) {
        boolean enabled = getBoolean(root, "enabled", true);
        String seasonIdPrefix = getString(
                root,
                "season_id_prefix",
                getString(root, "season_id", "gto_odyssey_monthly_s1")
        ).trim();
        if (seasonIdPrefix.isEmpty()) {
            seasonIdPrefix = "gto_odyssey_monthly_s1";
        }

        String titleFormat = getString(root, "title_format", "").trim();
        if (titleFormat.isEmpty()) {
            String legacyTitle = getString(root, "title", "").trim();
            if (legacyTitle.isEmpty() || legacyTitle.equalsIgnoreCase("GregTech Odyssey: 30 дней")) {
                titleFormat = DEFAULT_TITLE_FORMAT;
            } else if (containsTitlePlaceholder(legacyTitle)) {
                titleFormat = legacyTitle;
            } else {
                titleFormat = legacyTitle + " — {month} {year}: {days} {days_word}";
            }
        }

        String premiumLabel = getString(root, "premium_label", "Plutonium Support");
        String premiumPermission = getString(root, "premium_permission", "cointcoregto.battlepass.premium");
        int visibleDays = clamp(getInt(root, "visible_days", 8), 7, 10);
        int resetAfterMissedDays = clamp(getInt(root, "reset_after_missed_days", 3), 0, 31);
        ZoneId zoneId = parseZone(getString(root, "streak_timezone", "Europe/Moscow"));

        JsonElement defaultRewardsElement = root.has("default_rewards")
                ? root.get("default_rewards")
                : root.get("rewards");
        Map<Integer, BattlePassReward> defaultRewards = parseRewardMap(defaultRewardsElement);
        if (defaultRewards.isEmpty()) {
            defaultRewards = fallbackRewardMap();
        }

        List<MonthDefinition> months = new ArrayList<>();
        if (root.has("months") && root.get("months").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("months")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                int month = getInt(object, "month", -1);
                if (month < 1 || month > 12) {
                    LOGGER.warn("Ignoring Battle Pass month with invalid month number: {}", object);
                    continue;
                }
                int configuredYear = getInt(object, "year", 0);
                Integer year = configuredYear > 0 ? configuredYear : null;
                Boolean monthEnabled = object.has("enabled")
                        ? getBoolean(object, "enabled", enabled)
                        : null;
                months.add(new MonthDefinition(
                        year,
                        month,
                        getString(object, "season_id", "").trim(),
                        getString(object, "title", "").trim(),
                        monthEnabled,
                        Collections.unmodifiableMap(parseRewardMap(object.get("rewards")))
                ));
            }
        }

        return new Catalog(
                enabled,
                seasonIdPrefix,
                titleFormat,
                premiumLabel,
                premiumPermission,
                visibleDays,
                resetAfterMissedDays,
                zoneId,
                Collections.unmodifiableMap(new LinkedHashMap<>(defaultRewards)),
                List.copyOf(months)
        );
    }

    private static Map<Integer, BattlePassReward> parseRewardMap(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonArray()) {
            return Collections.emptyMap();
        }

        Map<Integer, BattlePassReward> rewards = new LinkedHashMap<>();
        int sequentialDay = 1;
        for (JsonElement rewardElement : element.getAsJsonArray()) {
            if (!rewardElement.isJsonObject()) {
                sequentialDay++;
                continue;
            }
            JsonObject dayObject = rewardElement.getAsJsonObject();
            int day = clamp(getInt(dayObject, "day", sequentialDay), 1, MAX_DAYS);
            rewards.put(day, new BattlePassReward(
                    parseStacks(dayObject.get("free")),
                    parseStacks(dayObject.get("premium"))
            ));
            sequentialDay++;
        }
        return rewards;
    }

    private static List<ItemStack> parseStacks(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return Collections.emptyList();
        }

        JsonArray array = element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
        List<ItemStack> result = new ArrayList<>();
        for (JsonElement entry : array) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject object = entry.getAsJsonObject();
            Item item = resolveFirstAvailableItem(object);
            if (item == null || item == Items.AIR) {
                LOGGER.warn("Ignoring Battle Pass reward because none of its item candidates are registered: {}", object);
                continue;
            }

            ItemStack stack = new ItemStack(item);
            int count = clamp(getInt(object, "count", 1), 1, Math.max(1, stack.getMaxStackSize()));
            stack.setCount(count);
            String nbtText = getString(object, "nbt", "").trim();
            if (!nbtText.isEmpty()) {
                try {
                    CompoundTag tag = TagParser.parseTag(nbtText);
                    stack.setTag(tag);
                } catch (CommandSyntaxException exception) {
                    LOGGER.warn("Ignoring invalid NBT for Battle Pass item {}: {}", item, exception.getMessage());
                }
            }
            result.add(stack);
            if (result.size() >= 16) {
                break;
            }
        }
        return result;
    }

    private static Item resolveFirstAvailableItem(JsonObject object) {
        List<String> candidates = new ArrayList<>();
        if (object.has("items") && object.get("items").isJsonArray()) {
            for (JsonElement candidate : object.getAsJsonArray("items")) {
                if (candidate.isJsonPrimitive() && candidate.getAsJsonPrimitive().isString()) {
                    candidates.add(candidate.getAsString());
                }
            }
        }
        if (object.has("item") && object.get("item").isJsonPrimitive()) {
            candidates.add(object.get("item").getAsString());
        }

        for (String itemId : candidates) {
            ResourceLocation location = ResourceLocation.tryParse(itemId);
            Item item = location == null ? null : ForgeRegistries.ITEMS.getValue(location);
            if (item != null && item != Items.AIR) {
                return item;
            }
        }
        return null;
    }

    private static boolean migrateLegacyConfig(JsonObject root) {
        if (root.has("default_rewards") || root.has("months") || !root.has("rewards")) {
            return false;
        }

        JsonElement legacyRewards = root.remove("rewards");
        root.add("default_rewards", legacyRewards);

        if (!root.has("season_id_prefix")) {
            String legacySeason = getString(root, "season_id", "gto_odyssey_monthly_s1");
            root.addProperty("season_id_prefix", legacySeason);
        }
        root.remove("season_id");

        String legacyTitle = getString(root, "title", "");
        if (legacyTitle.isBlank() || legacyTitle.equalsIgnoreCase("GregTech Odyssey: 30 дней")) {
            root.addProperty("title_format", DEFAULT_TITLE_FORMAT);
        } else {
            root.addProperty("title_format", legacyTitle + " — {month} {year}: {days} {days_word}");
        }
        root.remove("title");

        ZoneId zone = parseZone(getString(root, "streak_timezone", "Europe/Moscow"));
        YearMonth current = YearMonth.now(zone);
        YearMonth next = current.plusMonths(1L);
        JsonArray months = new JsonArray();
        months.add(monthEntry(current.getYear(), current.getMonthValue()));
        months.add(monthEntry(next.getYear(), next.getMonthValue()));
        root.add("months", months);
        return true;
    }

    private static ZoneId parseZone(String id) {
        try {
            return ZoneId.of(id);
        } catch (DateTimeException ignored) {
            return ZoneId.of("Europe/Moscow");
        }
    }

    private static Catalog defaultCatalog() {
        ZoneId zone = ZoneId.of("Europe/Moscow");
        return new Catalog(
                true,
                "gto_odyssey_monthly_s1",
                DEFAULT_TITLE_FORMAT,
                "Plutonium Support",
                "cointcoregto.battlepass.premium",
                8,
                3,
                zone,
                Collections.unmodifiableMap(fallbackRewardMap()),
                List.of()
        );
    }

    private static Map<Integer, BattlePassReward> fallbackRewardMap() {
        Map<Integer, BattlePassReward> rewards = new LinkedHashMap<>();
        for (int day = 1; day <= MAX_DAYS; day++) {
            rewards.put(day, safeFallbackReward(day));
        }
        return rewards;
    }

    private static BattlePassReward safeFallbackReward(int day) {
        ItemStack free;
        ItemStack premium;
        if (day == 31) {
            free = new ItemStack(Items.NETHERITE_INGOT, 2);
            premium = new ItemStack(Items.NETHER_STAR, 1);
        } else if (day == 30) {
            free = new ItemStack(Items.NETHERITE_BLOCK, 1);
            premium = new ItemStack(Items.NETHER_STAR, 1);
        } else if (day % 10 == 0) {
            free = new ItemStack(Items.DIAMOND, 4);
            premium = new ItemStack(Items.NETHERITE_INGOT, 1);
        } else if (day % 5 == 0) {
            free = new ItemStack(Items.IRON_INGOT, 16);
            premium = new ItemStack(Items.DIAMOND, 2);
        } else {
            free = new ItemStack(Items.COPPER_INGOT, 4 + Math.min(day, 12));
            premium = new ItemStack(Items.REDSTONE, 8 + Math.min(day, 16));
        }
        return new BattlePassReward(List.of(free), List.of(premium));
    }

    private static JsonObject defaultJson() {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", true);
        root.addProperty("season_id_prefix", "gto_odyssey_monthly_s1");
        root.addProperty("title_format", DEFAULT_TITLE_FORMAT);
        root.addProperty("premium_label", "Plutonium Support");
        root.addProperty("premium_permission", "cointcoregto.battlepass.premium");
        root.addProperty("streak_timezone", "Europe/Moscow");
        root.addProperty("visible_days", 8);
        root.addProperty("reset_after_missed_days", 3);
        root.add("default_rewards", defaultRewardsJson());

        JsonArray months = new JsonArray();
        JsonObject august = monthEntry(0, 8);
        august.addProperty("season_id", "gto_odyssey_august");
        august.add("rewards", stacksOfDays(day(31,
                stacks(stack(2, "gtceu:naquadah_ingot", "minecraft:netherite_ingot")),
                stacks(stack(1, "gtceu:iv_robot_arm", "minecraft:nether_star")))));
        months.add(august);

        JsonObject september = monthEntry(0, 9);
        september.addProperty("season_id", "gto_odyssey_september");
        september.add("rewards", stacksOfDays(day(30,
                stacks(stack(1, "gtceu:tungsten_steel_block", "minecraft:netherite_block")),
                stacks(stack(1, "gtceu:iv_electric_motor", "minecraft:nether_star")))));
        months.add(september);

        root.add("months", months);
        return root;
    }

    private static JsonArray defaultRewardsJson() {
        JsonArray rewards = new JsonArray();
        rewards.add(day(1,
                stacks(stack(16, "minecraft:baked_potato"), stack(32, "minecraft:torch")),
                stacks(stack(16, "gtceu:coke_gem", "minecraft:coal"))));
        rewards.add(day(2,
                stacks(stack(8, "gtceu:copper_ingot", "minecraft:copper_ingot")),
                stacks(stack(8, "gtceu:tin_ingot", "minecraft:iron_ingot"))));
        rewards.add(day(3,
                stacks(stack(16, "gtceu:coal_dust", "minecraft:coal")),
                stacks(stack(8, "gtceu:sticky_resin", "minecraft:slime_ball"))));
        rewards.add(day(4,
                stacks(stack(16, "minecraft:redstone")),
                stacks(stack(4, "gtceu:rubber_plate", "minecraft:dried_kelp"))));
        rewards.add(day(5,
                stacks(stack(8, "gtceu:bronze_ingot", "minecraft:copper_ingot")),
                stacks(stack(4, "gtceu:steel_ingot", "minecraft:iron_ingot"))));
        rewards.add(day(6,
                stacks(stack(16, "minecraft:glass")),
                stacks(stack(16, "gtceu:treated_wood_planks", "minecraft:oak_planks"))));
        rewards.add(day(7,
                stacks(stack(8, "gtceu:steel_ingot", "minecraft:iron_ingot")),
                stacks(stack(1, "gtceu:lv_electric_motor", "minecraft:piston"))));
        rewards.add(day(8,
                stacks(stack(8, "gtceu:rubber_plate", "minecraft:dried_kelp")),
                stacks(stack(1, "gtceu:lv_electric_pump", "minecraft:bucket"))));
        rewards.add(day(9,
                stacks(stack(16, "gtceu:copper_single_wire", "minecraft:copper_ingot")),
                stacks(stack(16, "gtceu:red_alloy_single_wire", "minecraft:redstone"))));
        rewards.add(day(10,
                stacks(stack(6, "gtceu:electrum_ingot", "minecraft:gold_ingot")),
                stacks(stack(1, "gtceu:lv_conveyor_module", "minecraft:hopper"))));
        rewards.add(day(11,
                stacks(stack(6, "gtceu:aluminium_ingot", "minecraft:iron_ingot")),
                stacks(stack(1, "gtceu:lv_robot_arm", "minecraft:iron_pickaxe"))));
        rewards.add(day(12,
                stacks(stack(6, "gtceu:stainless_steel_ingot", "minecraft:iron_ingot")),
                stacks(stack(1, "gtceu:lv_emitter", "minecraft:ender_pearl"))));
        rewards.add(day(13,
                stacks(stack(16, "minecraft:glowstone_dust")),
                stacks(stack(1, "gtceu:lv_sensor", "minecraft:observer"))));
        rewards.add(day(14,
                stacks(stack(2, "minecraft:diamond")),
                stacks(stack(8, "minecraft:ender_pearl"))));
        rewards.add(day(15,
                stacks(stack(10, "gtceu:aluminium_ingot", "minecraft:iron_ingot")),
                stacks(stack(1, "gtceu:mv_electric_motor", "minecraft:piston"))));
        rewards.add(day(16,
                stacks(stack(10, "gtceu:stainless_steel_ingot", "minecraft:iron_ingot")),
                stacks(stack(1, "gtceu:mv_electric_pump", "minecraft:bucket"))));
        rewards.add(day(17,
                stacks(stack(10, "gtceu:silver_ingot", "minecraft:gold_ingot")),
                stacks(stack(1, "gtceu:mv_conveyor_module", "minecraft:hopper"))));
        rewards.add(day(18,
                stacks(stack(10, "gtceu:electrum_ingot", "minecraft:gold_ingot")),
                stacks(stack(1, "gtceu:mv_robot_arm", "minecraft:diamond_pickaxe"))));
        rewards.add(day(19,
                stacks(stack(6, "gtceu:chrome_ingot", "minecraft:gold_ingot")),
                stacks(stack(1, "gtceu:mv_emitter", "minecraft:ender_eye"))));
        rewards.add(day(20,
                stacks(stack(6, "gtceu:titanium_ingot", "minecraft:diamond")),
                stacks(stack(1, "gtceu:mv_sensor", "minecraft:observer"))));
        rewards.add(day(21,
                stacks(stack(6, "gtceu:tungsten_ingot", "minecraft:netherite_scrap")),
                stacks(stack(1, "gtceu:hv_electric_motor", "minecraft:piston"))));
        rewards.add(day(22,
                stacks(stack(10, "gtceu:titanium_ingot", "minecraft:diamond")),
                stacks(stack(1, "gtceu:hv_electric_pump", "minecraft:bucket"))));
        rewards.add(day(23,
                stacks(stack(10, "gtceu:chrome_ingot", "minecraft:gold_ingot")),
                stacks(stack(1, "gtceu:hv_conveyor_module", "minecraft:hopper"))));
        rewards.add(day(24,
                stacks(stack(6, "gtceu:tungsten_steel_ingot", "minecraft:netherite_scrap")),
                stacks(stack(1, "gtceu:hv_robot_arm", "minecraft:diamond_pickaxe"))));
        rewards.add(day(25,
                stacks(stack(1, "gtceu:iridium_ingot", "minecraft:netherite_ingot")),
                stacks(stack(1, "gtceu:hv_emitter", "minecraft:ender_eye"))));
        rewards.add(day(26,
                stacks(stack(2, "gtceu:osmium_ingot", "minecraft:netherite_scrap")),
                stacks(stack(1, "gtceu:hv_sensor", "minecraft:observer"))));
        rewards.add(day(27,
                stacks(stack(10, "gtceu:tungsten_steel_ingot", "minecraft:netherite_scrap")),
                stacks(stack(1, "gtceu:ev_electric_motor", "minecraft:piston"))));
        rewards.add(day(28,
                stacks(stack(8, "minecraft:diamond")),
                stacks(stack(1, "gtceu:ev_electric_pump", "minecraft:bucket"))));
        rewards.add(day(29,
                stacks(stack(2, "minecraft:netherite_ingot")),
                stacks(stack(1, "gtceu:ev_conveyor_module", "minecraft:hopper"))));
        rewards.add(day(30,
                stacks(stack(1, "gtceu:tungsten_steel_block", "minecraft:netherite_block")),
                stacks(stack(1, "gtceu:iv_electric_motor", "minecraft:nether_star"))));
        rewards.add(day(31,
                stacks(stack(2, "gtceu:naquadah_ingot", "minecraft:netherite_ingot")),
                stacks(stack(1, "gtceu:iv_robot_arm", "minecraft:nether_star"))));
        return rewards;
    }

    private static JsonObject monthEntry(int year, int month) {
        JsonObject object = new JsonObject();
        if (year > 0) {
            object.addProperty("year", year);
        }
        object.addProperty("month", month);
        return object;
    }

    private static JsonArray stacksOfDays(JsonObject... days) {
        JsonArray array = new JsonArray();
        for (JsonObject day : days) {
            array.add(day);
        }
        return array;
    }

    private static JsonObject day(int day, JsonArray free, JsonArray premium) {
        JsonObject object = new JsonObject();
        object.addProperty("day", day);
        object.add("free", free);
        object.add("premium", premium);
        return object;
    }

    private static JsonArray stacks(JsonObject... stacks) {
        JsonArray array = new JsonArray();
        for (JsonObject stack : stacks) {
            array.add(stack);
        }
        return array;
    }

    private static JsonObject stack(int count, String... itemCandidates) {
        JsonObject object = new JsonObject();
        JsonArray items = new JsonArray();
        for (String itemCandidate : itemCandidates) {
            items.add(itemCandidate);
        }
        object.add("items", items);
        object.addProperty("count", count);
        return object;
    }

    private static String formatTitle(String format, YearMonth month) {
        String result = format == null || format.isBlank() ? DEFAULT_TITLE_FORMAT : format;
        return result
                .replace("{month}", russianMonthName(month.getMonthValue()))
                .replace("{month_number}", String.format("%02d", month.getMonthValue()))
                .replace("{year}", Integer.toString(month.getYear()))
                .replace("{days}", Integer.toString(month.lengthOfMonth()))
                .replace("{days_word}", russianDayWord(month.lengthOfMonth()));
    }

    private static String russianMonthName(int month) {
        return switch (month) {
            case 1 -> "Январь";
            case 2 -> "Февраль";
            case 3 -> "Март";
            case 4 -> "Апрель";
            case 5 -> "Май";
            case 6 -> "Июнь";
            case 7 -> "Июль";
            case 8 -> "Август";
            case 9 -> "Сентябрь";
            case 10 -> "Октябрь";
            case 11 -> "Ноябрь";
            case 12 -> "Декабрь";
            default -> "Месяц";
        };
    }

    private static String russianDayWord(int value) {
        int lastTwo = value % 100;
        if (lastTwo >= 11 && lastTwo <= 14) {
            return "дней";
        }
        return switch (value % 10) {
            case 1 -> "день";
            case 2, 3, 4 -> "дня";
            default -> "дней";
        };
    }

    private static boolean containsTitlePlaceholder(String value) {
        return value.contains("{month}")
                || value.contains("{month_number}")
                || value.contains("{year}")
                || value.contains("{days}")
                || value.contains("{days_word}");
    }

    private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
        try {
            return object.has(key) ? object.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String getString(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) ? object.get(key).getAsString() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record Catalog(
            boolean enabled,
            String seasonIdPrefix,
            String titleFormat,
            String premiumLabel,
            String premiumPermission,
            int visibleDays,
            int resetAfterMissedDays,
            ZoneId streakZone,
            Map<Integer, BattlePassReward> defaultRewards,
            List<MonthDefinition> months
    ) {
        private Snapshot select(YearMonth activeMonth) {
            MonthDefinition selected = null;
            for (MonthDefinition month : this.months) {
                if (month.year() != null
                        && month.year() == activeMonth.getYear()
                        && month.month() == activeMonth.getMonthValue()) {
                    selected = month;
                    break;
                }
            }
            if (selected == null) {
                for (MonthDefinition month : this.months) {
                    if (month.year() == null && month.month() == activeMonth.getMonthValue()) {
                        selected = month;
                        break;
                    }
                }
            }

            boolean selectedEnabled = selected != null && selected.enabled() != null
                    ? selected.enabled()
                    : this.enabled;
            String seasonBase = selected != null && !selected.seasonId().isBlank()
                    ? selected.seasonId()
                    : this.seasonIdPrefix;
            String titlePattern = selected != null && !selected.title().isBlank()
                    ? selected.title()
                    : this.titleFormat;

            int dayCount = activeMonth.lengthOfMonth();
            List<BattlePassReward> rewards = new ArrayList<>(dayCount);
            for (int day = 1; day <= dayCount; day++) {
                BattlePassReward reward = null;
                if (selected != null) {
                    reward = selected.rewards().get(day);
                }
                if (reward == null) {
                    reward = this.defaultRewards.get(day);
                }
                if (reward == null) {
                    reward = safeFallbackReward(day);
                }
                rewards.add(reward);
            }

            return new Snapshot(
                    selectedEnabled,
                    seasonBase + "-" + activeMonth,
                    formatTitle(titlePattern, activeMonth),
                    this.premiumLabel,
                    this.premiumPermission,
                    this.visibleDays,
                    this.resetAfterMissedDays,
                    this.streakZone,
                    activeMonth,
                    Collections.unmodifiableList(rewards)
            );
        }
    }

    private record MonthDefinition(
            Integer year,
            int month,
            String seasonId,
            String title,
            Boolean enabled,
            Map<Integer, BattlePassReward> rewards
    ) {
    }

    public record Snapshot(
            boolean enabled,
            String seasonId,
            String title,
            String premiumLabel,
            String premiumPermission,
            int visibleDays,
            int resetAfterMissedDays,
            ZoneId streakZone,
            YearMonth activeMonth,
            List<BattlePassReward> rewards
    ) {
    }
}
