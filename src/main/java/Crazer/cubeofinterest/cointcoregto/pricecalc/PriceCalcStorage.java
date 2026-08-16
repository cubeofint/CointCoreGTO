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
import java.util.LinkedHashMap;
import java.util.Map;

public final class PriceCalcStorage {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:PriceCalc");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type DOUBLE_MAP_TYPE = new TypeToken<LinkedHashMap<String, Double>>() {}.getType();
    private static final Type STRING_MAP_TYPE = new TypeToken<LinkedHashMap<String, String>>() {}.getType();
    private static final Type COMPUTED_MAP_TYPE = new TypeToken<LinkedHashMap<String, ComputedPrice>>() {}.getType();
    private static final Path DIRECTORY = FMLPaths.CONFIGDIR.get().resolve(CointCoreGTO.MODID).resolve("pricecalc");
    private static final Path ITEMS_FILE = DIRECTORY.resolve("base_prices_items.json");
    private static final Path FLUIDS_FILE = DIRECTORY.resolve("base_prices_fluids.json");
    private static final Path TAGS_FILE = DIRECTORY.resolve("base_prices_tags.json");
    private static final Path SETTINGS_FILE = DIRECTORY.resolve("settings.json");
    private static final Path COMPUTED_FILE = DIRECTORY.resolve("computed_prices.json");
    private static final Path PREFERRED_FILE = DIRECTORY.resolve("preferred_recipes.json");

    private static Map<String, Double> itemPrices = new LinkedHashMap<>();
    private static Map<String, Double> fluidPrices = new LinkedHashMap<>();
    private static Map<String, Double> tagPrices = new LinkedHashMap<>();
    private static Map<String, String> preferredRecipes = new LinkedHashMap<>();
    private static Map<String, ComputedPrice> computedPrices = new LinkedHashMap<>();
    private static Settings settings = new Settings();
    private static boolean loaded;

    private PriceCalcStorage() {
    }

    public static synchronized void ensureLoaded() {
        if (!loaded) {
            load(false);
        }
    }

    public static synchronized void reloadAndInvalidateComputed() {
        load(true);
    }

    public static synchronized void clearComputed() {
        ensureLoaded();
        computedPrices.clear();
        writeJson(COMPUTED_FILE, computedPrices);
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

    public static synchronized int getMaxDepth() {
        ensureLoaded();
        return Math.max(8, Math.min(256, settings.maxDepth));
    }

    public static Path getDirectory() {
        return DIRECTORY;
    }

    private static void load(boolean invalidateComputed) {
        try {
            Files.createDirectories(DIRECTORY);
            ensureFile(ITEMS_FILE, "{}\n");
            ensureFile(FLUIDS_FILE, "{}\n");
            ensureFile(TAGS_FILE, "{}\n");
            ensureFile(PREFERRED_FILE, "{}\n");
            ensureFile(COMPUTED_FILE, "{}\n");
            ensureFile(SETTINGS_FILE, "{\n  \"price_per_eu\": 0.0,\n  \"max_depth\": 64\n}\n");

            itemPrices = readDoubleMap(ITEMS_FILE);
            fluidPrices = readDoubleMap(FLUIDS_FILE);
            tagPrices = readDoubleMap(TAGS_FILE);
            preferredRecipes = readStringMap(PREFERRED_FILE);
            computedPrices = invalidateComputed ? new LinkedHashMap<>() : readComputedMap(COMPUTED_FILE);
            settings = readSettings(SETTINGS_FILE);
            loaded = true;

            if (invalidateComputed) {
                writeJson(COMPUTED_FILE, computedPrices);
            }
        } catch (Throwable throwable) {
            LOGGER.error("Unable to load price calculator configuration", throwable);
            itemPrices = new LinkedHashMap<>();
            fluidPrices = new LinkedHashMap<>();
            tagPrices = new LinkedHashMap<>();
            preferredRecipes = new LinkedHashMap<>();
            computedPrices = new LinkedHashMap<>();
            settings = new Settings();
            loaded = true;
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
        return value;
    }

    private static void ensureFile(Path file, String defaultContent) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, defaultContent, StandardCharsets.UTF_8);
        }
    }

    private static void writeJson(Path file, Object value) {
        try {
            Files.createDirectories(DIRECTORY);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(value) + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable throwable) {
            LOGGER.error("Unable to save price calculator file {}", file, throwable);
        }
    }

    private static Double sanitizePrice(Double price) {
        if (price == null || !Double.isFinite(price) || price < 0.0D) {
            return null;
        }
        return price;
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
    }
}
