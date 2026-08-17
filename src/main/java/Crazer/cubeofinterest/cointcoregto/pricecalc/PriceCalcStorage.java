package Crazer.cubeofinterest.cointcoregto.pricecalc;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class PriceCalcStorage {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:PriceCalc");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type DOUBLE_MAP_TYPE = new TypeToken<LinkedHashMap<String, Double>>() {}.getType();
    private static final Type STRING_MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {}.getType();
    private static final Type COMPUTED_MAP_TYPE = new TypeToken<LinkedHashMap<String, ComputedPrice>>() {}.getType();
    private static final Path DIRECTORY = FMLPaths.CONFIGDIR.get().resolve(CointCoreGTO.MODID).resolve("pricecalc");
    private static final Path ITEMS_FILE = DIRECTORY.resolve("base_prices_items.json");
    private static final Path FLUIDS_FILE = DIRECTORY.resolve("base_prices_fluids.json");
    private static final Path TAGS_FILE = DIRECTORY.resolve("base_prices_tags.json");
    private static final Path SETTINGS_FILE = DIRECTORY.resolve("settings.json");
    private static final Path COMPUTED_FILE = DIRECTORY.resolve("computed_prices.json");
    private static final Path PREFERRED_FILE = DIRECTORY.resolve("preferred_recipes.json");
    private static final Path MACHINE_BLACKLIST_FILE = DIRECTORY.resolve("machine_blacklist.json");
    private static final Path BACKUP_DIRECTORY = FMLPaths.CONFIGDIR.get().resolve(CointCoreGTO.MODID).resolve("pricecalc_backups");
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss_SSS");

    private static Map<String, Double> itemPrices = new LinkedHashMap<>();
    private static Map<String, Double> fluidPrices = new LinkedHashMap<>();
    private static Map<String, Double> tagPrices = new LinkedHashMap<>();
    private static Map<String, String> preferredRecipes = new LinkedHashMap<>();
    private static Map<String, ComputedPrice> computedPrices = new LinkedHashMap<>();
    private static Set<String> machineBlacklist = new LinkedHashSet<>();
    private static Settings settings = new Settings();
    private static boolean loaded;

    private PriceCalcStorage() {
    }

    public static synchronized void ensureLoaded() {
        if (!loaded) {
            load(false);
        }
    }

    public static synchronized ReloadResult reloadSafely() throws IOException {
        ensureLoaded();
        Path backup = createBackup("reload");
        LoadResult result = load(false);
        if (!result.success()) {
            throw new IOException("Не удалось перечитать конфиги. Бекап сохранён: " + backup.toAbsolutePath().normalize());
        }
        return new ReloadResult(backup, result.computedInvalidated());
    }

    public static synchronized void reloadAndInvalidateComputed() {
        try {
            reloadAndInvalidateComputedSafely();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to back up price calculator configuration", exception);
        }
    }

    public static synchronized Path reloadAndInvalidateComputedSafely() throws IOException {
        ensureLoaded();
        Path backup = createBackup("reload");
        LoadResult result = load(true);
        if (!result.success()) {
            throw new IOException("Не удалось перечитать конфиги. Бекап сохранён: " + backup.toAbsolutePath().normalize());
        }
        return backup;
    }

    public static synchronized void clearComputed() {
        try {
            clearComputedSafely();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to back up price calculator configuration", exception);
        }
    }

    public static synchronized Path clearComputedSafely() throws IOException {
        ensureLoaded();
        Path backup = createBackup("clear");
        Map<String, ComputedPrice> previous = computedPrices;
        Map<String, ComputedPrice> cleared = new LinkedHashMap<>();
        try {
            writeJsonChecked(COMPUTED_FILE, cleared);
            computedPrices = cleared;
        } catch (IOException exception) {
            computedPrices = previous;
            throw exception;
        }
        return backup;
    }

    public static synchronized Double getItemUnitPrice(ResourceLocation id) {
        ensureLoaded();
        return sanitizePrice(itemPrices.get(id.toString()));
    }

    public static synchronized Double getFluidUnitPrice(ResourceLocation id) {
        ensureLoaded();
        return sanitizePrice(fluidPrices.get(id.toString()));
    }

    public static synchronized Double getTagUnitPrice(ResourceLocation id) {
        ensureLoaded();
        Double price = tagPrices.get(id.toString());
        if (price == null) {
            price = tagPrices.get("#" + id);
        }
        return sanitizePrice(price);
    }

    public static synchronized String getPreferredRecipe(String key) {
        ensureLoaded();
        return preferredRecipes.get(key);
    }

    public static synchronized void setPreferredRecipe(String key, String recipeKey) {
        ensureLoaded();
        preferredRecipes.put(key, recipeKey);
        writeJson(PREFERRED_FILE, preferredRecipes);
    }

    public static synchronized void removePreferredRecipe(String key) {
        ensureLoaded();
        if (preferredRecipes.remove(key) != null) {
            writeJson(PREFERRED_FILE, preferredRecipes);
        }
    }

    public static synchronized ComputedPrice getComputedPrice(String key) {
        ensureLoaded();
        return computedPrices.get(key);
    }

    public static synchronized boolean isMachineCategoryBlacklisted(ResourceLocation id) {
        return id != null && isMachineCategoryBlacklisted(id.toString());
    }

    public static synchronized boolean isMachineCategoryBlacklisted(String categoryId) {
        ensureLoaded();
        if (categoryId == null || categoryId.isBlank()) {
            return false;
        }
        return machineBlacklist.contains(categoryId);
    }

    public static synchronized List<String> getMachineBlacklist() {
        ensureLoaded();
        return new ArrayList<>(machineBlacklist);
    }

    public static synchronized Path setMachineCategoryBlacklistedSafely(String categoryId, boolean blocked) throws IOException {
        ensureLoaded();
        ResourceLocation parsed = ResourceLocation.tryParse(categoryId == null ? "" : categoryId.trim());
        if (parsed == null) {
            throw new IllegalArgumentException("Некорректный ID категории: " + categoryId);
        }
        String key = parsed.toString();
        boolean current = machineBlacklist.contains(key);
        if (current == blocked) {
            return null;
        }

        Path backup = createBackup(blocked ? "blacklist-add" : "blacklist-remove");
        LinkedHashSet<String> previousBlacklist = new LinkedHashSet<>(machineBlacklist);
        LinkedHashMap<String, ComputedPrice> previousComputed = new LinkedHashMap<>(computedPrices);
        LinkedHashSet<String> nextBlacklist = new LinkedHashSet<>(machineBlacklist);
        if (blocked) {
            nextBlacklist.add(key);
        } else {
            nextBlacklist.remove(key);
        }
        LinkedHashMap<String, ComputedPrice> clearedComputed = new LinkedHashMap<>();

        try {
            writeJsonChecked(MACHINE_BLACKLIST_FILE, nextBlacklist);
            writeJsonChecked(COMPUTED_FILE, clearedComputed);
            machineBlacklist = nextBlacklist;
            computedPrices = clearedComputed;
        } catch (IOException exception) {
            machineBlacklist = previousBlacklist;
            computedPrices = previousComputed;
            try {
                writeJsonChecked(MACHINE_BLACKLIST_FILE, previousBlacklist);
            } catch (IOException restoreException) {
                exception.addSuppressed(restoreException);
            }
            try {
                writeJsonChecked(COMPUTED_FILE, previousComputed);
            } catch (IOException restoreException) {
                exception.addSuppressed(restoreException);
            }
            throw exception;
        }
        return backup;
    }

    public static synchronized void putComputedPrice(String key, double price, String recipeId) {
        LinkedHashMap<String, PriceCalcResultEntry> values = new LinkedHashMap<>();
        values.put(key, new PriceCalcResultEntry(price, recipeId));
        putComputedPrices(values);
    }

    public static synchronized void putComputedPrices(Map<String, PriceCalcResultEntry> values) {
        ensureLoaded();
        if (values == null || values.isEmpty()) {
            return;
        }
        long calculatedAt = System.currentTimeMillis();
        boolean changed = false;
        for (Map.Entry<String, PriceCalcResultEntry> entry : values.entrySet()) {
            String key = entry.getKey();
            PriceCalcResultEntry value = entry.getValue();
            if (key == null || key.isBlank() || value == null || !Double.isFinite(value.price()) || value.price() < 0.0D) {
                continue;
            }
            computedPrices.put(key, new ComputedPrice(value.price(), value.recipeId(), calculatedAt));
            changed = true;
        }
        if (changed) {
            writeJson(COMPUTED_FILE, computedPrices);
        }
    }

    public static synchronized double getPricePerEu() {
        ensureLoaded();
        return Math.max(0.0D, settings.pricePerEu);
    }

    public static synchronized String getTooltipPriceFormat() {
        ensureLoaded();
        return sanitizeText(settings.tooltipPriceFormat, "Расчётная стоимость: {price}");
    }

    public static synchronized String getTooltipBasePriceFormat() {
        ensureLoaded();
        return sanitizeText(settings.tooltipBasePriceFormat, "Базовая стоимость: {price}");
    }

    public static synchronized String getTooltipUncalculatedText() {
        ensureLoaded();
        return sanitizeText(settings.tooltipUncalculatedText, "[P] — рассчитать стоимость");
    }

    public static synchronized int getMaxDepth() {
        ensureLoaded();
        return Math.max(8, Math.min(256, settings.maxDepth));
    }

    public static synchronized int getComputedPriceCount() {
        ensureLoaded();
        return computedPrices.size();
    }

    public static synchronized int getPreferredRecipeCount() {
        ensureLoaded();
        return preferredRecipes.size();
    }

    public static synchronized int getMachineBlacklistCount() {
        ensureLoaded();
        return machineBlacklist.size();
    }

    public static synchronized int getBaseItemPriceCount() {
        ensureLoaded();
        return itemPrices.size();
    }

    public static synchronized int getBaseFluidPriceCount() {
        ensureLoaded();
        return fluidPrices.size();
    }

    public static synchronized int getBaseTagPriceCount() {
        ensureLoaded();
        return tagPrices.size();
    }

    public static Path getDirectory() {
        return DIRECTORY;
    }

    public static Path getBackupDirectory() {
        return BACKUP_DIRECTORY;
    }

    private static Path createBackup(String action) throws IOException {
        Files.createDirectories(DIRECTORY);
        Files.createDirectories(BACKUP_DIRECTORY);
        String name = BACKUP_TIME.format(LocalDateTime.now()) + "_" + action;
        Path backup = BACKUP_DIRECTORY.resolve(name);
        Files.createDirectories(backup);

        try (Stream<Path> stream = Files.list(DIRECTORY)) {
            for (Path source : stream.filter(Files::isRegularFile).toList()) {
                String fileName = source.getFileName().toString();
                if (fileName.endsWith(".tmp")) {
                    continue;
                }
                Files.copy(
                        source,
                        backup.resolve(source.getFileName()),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES
                );
            }
        }

        return backup;
    }

    private static LoadResult load(boolean forceInvalidateComputed) {
        boolean previouslyLoaded = loaded;
        try {
            Files.createDirectories(DIRECTORY);
            ensureFile(ITEMS_FILE, "{}\n");
            ensureFile(FLUIDS_FILE, "{}\n");
            ensureFile(TAGS_FILE, "{}\n");
            ensureFile(PREFERRED_FILE, "{}\n");
            ensureFile(COMPUTED_FILE, "{}\n");
            ensureFile(MACHINE_BLACKLIST_FILE, "[]\n");
            ensureFile(SETTINGS_FILE, "{\n  \"price_per_eu\": 0.0,\n  \"max_depth\": 64,\n  \"tooltip_price_format\": \"Расчётная стоимость: {price}\",\n  \"tooltip_base_price_format\": \"Базовая стоимость: {price}\",\n  \"tooltip_uncalculated_text\": \"[P] — рассчитать стоимость\"\n}\n");

            Map<String, Double> loadedItemPrices = readDoubleMap(ITEMS_FILE);
            Map<String, Double> loadedFluidPrices = readDoubleMap(FLUIDS_FILE);
            Map<String, Double> loadedTagPrices = readDoubleMap(TAGS_FILE);
            Map<String, String> loadedPreferredRecipes = readStringMap(PREFERRED_FILE);
            Map<String, ComputedPrice> loadedComputedPrices = readComputedMap(COMPUTED_FILE);
            Set<String> loadedMachineBlacklist = readStringSet(MACHINE_BLACKLIST_FILE);
            Settings loadedSettings = readSettings(SETTINGS_FILE);

            boolean calculationChanged = previouslyLoaded && (
                    !itemPrices.equals(loadedItemPrices)
                            || !fluidPrices.equals(loadedFluidPrices)
                            || !tagPrices.equals(loadedTagPrices)
                            || !preferredRecipes.equals(loadedPreferredRecipes)
                            || !machineBlacklist.equals(loadedMachineBlacklist)
                            || Double.compare(settings.pricePerEu, loadedSettings.pricePerEu) != 0
                            || settings.maxDepth != loadedSettings.maxDepth
            );
            boolean invalidateComputed = forceInvalidateComputed || calculationChanged;
            if (invalidateComputed) {
                loadedComputedPrices = new LinkedHashMap<>();
                writeJsonChecked(COMPUTED_FILE, loadedComputedPrices);
            }

            itemPrices = loadedItemPrices;
            fluidPrices = loadedFluidPrices;
            tagPrices = loadedTagPrices;
            preferredRecipes = loadedPreferredRecipes;
            computedPrices = loadedComputedPrices;
            machineBlacklist = loadedMachineBlacklist;
            settings = loadedSettings;
            loaded = true;
            return new LoadResult(true, invalidateComputed);
        } catch (Throwable throwable) {
            LOGGER.error("Unable to load price calculator configuration", throwable);
            if (!previouslyLoaded) {
                itemPrices = new LinkedHashMap<>();
                fluidPrices = new LinkedHashMap<>();
                tagPrices = new LinkedHashMap<>();
                preferredRecipes = new LinkedHashMap<>();
                computedPrices = new LinkedHashMap<>();
                machineBlacklist = new LinkedHashSet<>();
                settings = new Settings();
                loaded = true;
            }
            return new LoadResult(false, false);
        }
    }

    private static Map<String, Double> readDoubleMap(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, Double> map = GSON.fromJson(json, DOUBLE_MAP_TYPE);
        return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
    }

    private static Map<String, String> readStringMap(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, String> map = GSON.fromJson(json, STRING_MAP_TYPE);
        return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
    }

    private static Set<String> readStringSet(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        List<String> values = GSON.fromJson(json, STRING_LIST_TYPE);
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            ResourceLocation id = ResourceLocation.tryParse(value == null ? "" : value.trim());
            if (id != null) {
                result.add(id.toString());
            }
        }
        return result;
    }

    private static Map<String, ComputedPrice> readComputedMap(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, ComputedPrice> map = GSON.fromJson(json, COMPUTED_MAP_TYPE);
        return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
    }

    private static Settings readSettings(Path file) throws IOException {
        JsonElement element = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        if (!element.isJsonObject()) {
            return new Settings();
        }
        JsonObject object = element.getAsJsonObject();
        Settings value = new Settings();
        if (object.has("price_per_eu")) {
            value.pricePerEu = object.get("price_per_eu").getAsDouble();
        }
        if (object.has("max_depth")) {
            value.maxDepth = object.get("max_depth").getAsInt();
        }
        boolean updated = false;
        boolean customPriceFormat = object.has("tooltip_price_format");
        if (customPriceFormat) {
            value.tooltipPriceFormat = object.get("tooltip_price_format").getAsString();
        } else {
            object.addProperty("tooltip_price_format", value.tooltipPriceFormat);
            updated = true;
        }
        if (object.has("tooltip_base_price_format")) {
            value.tooltipBasePriceFormat = object.get("tooltip_base_price_format").getAsString();
        } else {
            if (customPriceFormat) {
                value.tooltipBasePriceFormat = value.tooltipPriceFormat;
            }
            object.addProperty("tooltip_base_price_format", value.tooltipBasePriceFormat);
            updated = true;
        }
        if (object.has("tooltip_uncalculated_text")) {
            value.tooltipUncalculatedText = object.get("tooltip_uncalculated_text").getAsString();
        } else {
            object.addProperty("tooltip_uncalculated_text", value.tooltipUncalculatedText);
            updated = true;
        }
        if (updated) {
            writeJsonChecked(file, object);
        }
        return value;
    }

    private static void ensureFile(Path file, String defaultContent) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, defaultContent, StandardCharsets.UTF_8);
        }
    }

    private static void writeJson(Path file, Object value) {
        try {
            writeJsonChecked(file, value);
        } catch (Throwable throwable) {
            LOGGER.error("Unable to save price calculator file {}", file, throwable);
        }
    }

    private static void writeJsonChecked(Path file, Object value) throws IOException {
        Files.createDirectories(DIRECTORY);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(value) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Double sanitizePrice(Double price) {
        if (price == null || !Double.isFinite(price) || price < 0.0D) {
            return null;
        }
        return price;
    }

    private static String sanitizeText(String text, String fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        return text;
    }

    public record ReloadResult(Path backup, boolean computedInvalidated) {
    }

    private record LoadResult(boolean success, boolean computedInvalidated) {
    }

    public static final class ComputedPrice {
        public double price;
        public String recipeId;
        public long calculatedAt;

        public ComputedPrice() {
        }

        public ComputedPrice(double price, String recipeId, long calculatedAt) {
            this.price = price;
            this.recipeId = recipeId;
            this.calculatedAt = calculatedAt;
        }
    }

    private static final class Settings {
        private double pricePerEu;
        private int maxDepth = 64;
        private String tooltipPriceFormat = "Расчётная стоимость: {price}";
        private String tooltipBasePriceFormat = "Базовая стоимость: {price}";
        private String tooltipUncalculatedText = "[P] — рассчитать стоимость";
    }
}
