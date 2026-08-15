package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import Crazer.cubeofinterest.cointcoregto.recipe.CraftingRecipeLoader;
import Crazer.cubeofinterest.cointcoregto.recipe.GtoCustomRecipeLoader;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class RecipeEditorFileService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CLIENT_GT_RECIPE_DIRECTORY = FMLPaths.CONFIGDIR.get()
            .resolve("cointcoregto")
            .resolve("gto_recipes");
    private static final Path CLIENT_CRAFTING_RECIPE_DIRECTORY = FMLPaths.CONFIGDIR.get()
            .resolve("cointcoregto")
            .resolve("crafting_recipes");

    private RecipeEditorFileService() {
    }

    public record SaveResult(
            boolean success,
            String message,
            String relativePath,
            String normalizedJson
    ) {
        static SaveResult failure(String message) {
            return new SaveResult(false, message, "", "");
        }
    }

    public static boolean isCraftingRecipeJson(String rawJson) {
        try {
            JsonObject root = parseRoot(rawJson);
            ResourceLocation type = resourceLocation(requiredString(root, "type"), "type");
            return CraftingRecipeLoader.isSupportedType(type);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static SaveResult saveServerCopy(String rawJson) {
        try {
            JsonObject root = parseRoot(rawJson);
            ResourceLocation type = resourceLocation(requiredString(root, "type"), "type");

            boolean crafting = CraftingRecipeLoader.isSupportedType(type);
            JsonObject recipe = crafting
                    ? validateCraftingRecipe(root)
                    : validateGtRecipe(root);

            ResourceLocation recipeId = resourceLocation(recipe.get("id").getAsString(), "id");
            String normalized = GSON.toJson(recipe) + System.lineSeparator();
            Path relativePath = relativePath(recipeId);
            Path baseDirectory = crafting
                    ? CraftingRecipeLoader.RECIPE_DIRECTORY
                    : GtoCustomRecipeLoader.RECIPE_DIRECTORY;
            Path target = safeResolve(baseDirectory, relativePath);

            Files.createDirectories(target.getParent());
            Files.writeString(target, normalized, StandardCharsets.UTF_8);

            return new SaveResult(
                    true,
                    crafting
                            ? "Верстачный рецепт сохранён. CointCoreGTO загрузит его напрямую после полного рестарта; KubeJS не используется."
                            : "GT/GTO рецепт сохранён. Нужен полный перезапуск клиента и сервера.",
                    normalizeSeparators(relativePath.toString()),
                    normalized
            );
        } catch (Throwable throwable) {
            return SaveResult.failure(throwable.getMessage() == null
                    ? throwable.getClass().getSimpleName()
                    : throwable.getMessage());
        }
    }

    public static void saveClientCopy(String relativePath, String normalizedJson) throws IOException {
        if (relativePath == null || relativePath.isBlank() || normalizedJson == null || normalizedJson.isBlank()) {
            return;
        }

        JsonObject root = parseRoot(normalizedJson);
        ResourceLocation type = resourceLocation(requiredString(root, "type"), "type");
        Path base = CraftingRecipeLoader.isSupportedType(type)
                ? CLIENT_CRAFTING_RECIPE_DIRECTORY
                : CLIENT_GT_RECIPE_DIRECTORY;

        Path relative = Path.of(relativePath.replace('/', java.io.File.separatorChar));
        Path target = safeResolve(base, relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, normalizedJson, StandardCharsets.UTF_8);
    }

    public static String clientDisplayPath(String relativePath, String normalizedJson) {
        try {
            JsonObject root = parseRoot(normalizedJson);
            ResourceLocation type = resourceLocation(requiredString(root, "type"), "type");
            String base = CraftingRecipeLoader.isSupportedType(type)
                    ? "config/cointcoregto/crafting_recipes/"
                    : "config/cointcoregto/gto_recipes/";
            return base + relativePath;
        } catch (Throwable ignored) {
            return relativePath;
        }
    }

    private static JsonObject parseRoot(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw new IllegalArgumentException("Пустой JSON рецепта");
        }
        if (rawJson.length() > 64_000) {
            throw new IllegalArgumentException("JSON рецепта слишком большой");
        }

        JsonElement element = JsonParser.parseString(rawJson);
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Корень рецепта должен быть JSON-объектом");
        }
        return element.getAsJsonObject();
    }

    private static JsonObject validateGtRecipe(JsonObject recipe) {
        ResourceLocation type = resourceLocation(requiredString(recipe, "type"), "type");
        ResourceLocation id = resourceLocation(requiredString(recipe, "id"), "id");

        if (!GtoCustomRecipeLoader.recipeTypeExists(type)) {
            throw new IllegalArgumentException("Неизвестный GT recipe type: " + type);
        }

        int duration = requiredInt(recipe, "duration");
        if (duration <= 0) {
            throw new IllegalArgumentException("duration должен быть > 0");
        }

        if (recipe.has("circuit")) {
            int circuit = recipe.get("circuit").getAsInt();
            if (circuit < 0 || circuit > 32) {
                throw new IllegalArgumentException("circuit должен быть от 0 до 32");
            }
        }

        validateNonNegativeIntField(recipe, "blast_furnace_temp");
        validateNonNegativeIntField(recipe, "heat");
        validateNonNegativeIntField(recipe, "temperature");
        if (recipe.has("mana_per_tick")) {
            recipe.get("mana_per_tick").getAsLong();
        }
        if (recipe.has("eut")) {
            recipe.get("eut").getAsLong();
        }

        validateItemArray(recipe, "item_inputs", true);
        validateItemArray(recipe, "item_outputs", false);
        validateFluidArray(recipe, "fluid_inputs", true);
        validateFluidArray(recipe, "fluid_outputs", false);

        if ((!recipe.has("item_inputs") || recipe.getAsJsonArray("item_inputs").size() == 0)
                && (!recipe.has("fluid_inputs") || recipe.getAsJsonArray("fluid_inputs").size() == 0)) {
            throw new IllegalArgumentException("Нужен хотя бы один входной предмет или жидкость");
        }

        if ((!recipe.has("item_outputs") || recipe.getAsJsonArray("item_outputs").size() == 0)
                && (!recipe.has("fluid_outputs") || recipe.getAsJsonArray("fluid_outputs").size() == 0)) {
            throw new IllegalArgumentException("Нужен хотя бы один выходной предмет или жидкость");
        }

        recipe.addProperty("enabled", true);
        recipe.addProperty("type", type.toString());
        recipe.addProperty("id", id.toString());
        return recipe;
    }

    private static JsonObject validateCraftingRecipe(JsonObject recipe) {
        ResourceLocation type = resourceLocation(requiredString(recipe, "type"), "type");
        ResourceLocation id = resourceLocation(requiredString(recipe, "id"), "id");
        if (!CraftingRecipeLoader.isSupportedType(type)) {
            throw new IllegalArgumentException("Поддерживаются только minecraft:crafting_shaped и minecraft:crafting_shapeless");
        }

        if (!recipe.has("result") || !recipe.get("result").isJsonObject()) {
            throw new IllegalArgumentException("Нужен result");
        }
        validateCraftingResult(recipe.getAsJsonObject("result"));

        if (CraftingRecipeLoader.CRAFTING_SHAPED.equals(type)) {
            validateShaped(recipe);
        } else {
            validateShapeless(recipe);
        }

        recipe.addProperty("enabled", true);
        recipe.addProperty("type", type.toString());
        recipe.addProperty("id", id.toString());
        if (!recipe.has("category")) {
            recipe.addProperty("category", "misc");
        }
        return recipe;
    }

    private static void validateShaped(JsonObject recipe) {
        if (!recipe.has("pattern") || !recipe.get("pattern").isJsonArray()) {
            throw new IllegalArgumentException("Shaped recipe требует pattern");
        }
        JsonArray pattern = recipe.getAsJsonArray("pattern");
        if (pattern.size() < 1 || pattern.size() > 3) {
            throw new IllegalArgumentException("pattern должен иметь 1..3 строки");
        }

        int width = -1;
        Set<Character> usedSymbols = new HashSet<>();
        for (int row = 0; row < pattern.size(); row++) {
            String line = pattern.get(row).getAsString();
            if (line.length() < 1 || line.length() > 3) {
                throw new IllegalArgumentException("Каждая строка pattern должна иметь длину 1..3");
            }
            if (width < 0) {
                width = line.length();
            } else if (width != line.length()) {
                throw new IllegalArgumentException("Все строки pattern должны иметь одинаковую длину");
            }
            for (int i = 0; i < line.length(); i++) {
                char symbol = line.charAt(i);
                if (symbol != ' ') {
                    usedSymbols.add(symbol);
                }
            }
        }
        if (usedSymbols.isEmpty()) {
            throw new IllegalArgumentException("pattern не может быть пустым");
        }

        if (!recipe.has("key") || !recipe.get("key").isJsonObject()) {
            throw new IllegalArgumentException("Shaped recipe требует key");
        }
        JsonObject key = recipe.getAsJsonObject("key");
        for (char symbol : usedSymbols) {
            String name = String.valueOf(symbol);
            if (!key.has(name)) {
                throw new IllegalArgumentException("В key нет символа " + symbol);
            }
            validateCraftingIngredient(key.get(name), "key." + symbol);
        }
    }

    private static void validateShapeless(JsonObject recipe) {
        if (!recipe.has("ingredients") || !recipe.get("ingredients").isJsonArray()) {
            throw new IllegalArgumentException("Shapeless recipe требует ingredients");
        }
        JsonArray ingredients = recipe.getAsJsonArray("ingredients");
        if (ingredients.size() < 1 || ingredients.size() > 9) {
            throw new IllegalArgumentException("Shapeless recipe должен иметь 1..9 ингредиентов");
        }
        for (int i = 0; i < ingredients.size(); i++) {
            validateCraftingIngredient(ingredients.get(i), "ingredients[" + i + "]");
        }
    }

    private static void validateCraftingIngredient(JsonElement element, String label) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException(label + " должен быть объектом");
        }
        JsonObject ingredient = element.getAsJsonObject();
        boolean hasItem = ingredient.has("item");
        boolean hasTag = ingredient.has("tag");
        if (hasItem == hasTag) {
            throw new IllegalArgumentException(label + " должен иметь ровно item или tag");
        }
        if (hasItem) {
            ResourceLocation itemId = resourceLocation(ingredient.get("item").getAsString(), label + ".item");
            if (!ForgeRegistries.ITEMS.containsKey(itemId)) {
                throw new IllegalArgumentException("Неизвестный предмет: " + itemId);
            }
        } else {
            resourceLocation(ingredient.get("tag").getAsString(), label + ".tag");
        }
    }

    private static void validateCraftingResult(JsonObject result) {
        ResourceLocation itemId = resourceLocation(requiredString(result, "item"), "result.item");
        if (!ForgeRegistries.ITEMS.containsKey(itemId)) {
            throw new IllegalArgumentException("Неизвестный предмет результата: " + itemId);
        }
        int count = result.has("count") ? result.get("count").getAsInt() : 1;
        if (count < 1 || count > 64) {
            throw new IllegalArgumentException("result.count должен быть 1..64");
        }
    }

    private static void validateItemArray(JsonObject recipe, String field, boolean input) {
        if (!recipe.has(field)) {
            return;
        }
        JsonElement element = recipe.get(field);
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException(field + " должен быть массивом");
        }

        JsonArray array = element.getAsJsonArray();
        if (array.size() > 32) {
            throw new IllegalArgumentException(field + " содержит слишком много записей");
        }

        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) {
                throw new IllegalArgumentException(field + "[" + i + "] должен быть объектом");
            }
            JsonObject entry = array.get(i).getAsJsonObject();
            boolean hasItem = entry.has("item");
            boolean hasTag = entry.has("tag");

            if (input) {
                if (hasItem == hasTag) {
                    throw new IllegalArgumentException(field + "[" + i + "] должен иметь ровно item или tag");
                }
            } else if (!hasItem || hasTag) {
                throw new IllegalArgumentException(field + "[" + i + "] должен иметь item");
            }

            if (hasItem) {
                ResourceLocation itemId = resourceLocation(entry.get("item").getAsString(), field + "[" + i + "].item");
                if (!ForgeRegistries.ITEMS.containsKey(itemId)) {
                    throw new IllegalArgumentException("Неизвестный предмет: " + itemId);
                }
            }
            if (hasTag) {
                resourceLocation(entry.get("tag").getAsString(), field + "[" + i + "].tag");
            }

            int count = entry.has("count") ? entry.get("count").getAsInt() : 1;
            if (count <= 0) {
                throw new IllegalArgumentException(field + "[" + i + "].count должен быть > 0");
            }
            validateChance(entry, field + "[" + i + "]");
            if (input && entry.has("not_consumable") && entry.get("not_consumable").getAsBoolean() && entry.has("chance")) {
                throw new IllegalArgumentException(field + "[" + i + "]: not_consumable нельзя совмещать с chance");
            }
        }
    }

    private static void validateFluidArray(JsonObject recipe, String field, boolean input) {
        if (!recipe.has(field)) {
            return;
        }
        JsonElement element = recipe.get(field);
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException(field + " должен быть массивом");
        }

        JsonArray array = element.getAsJsonArray();
        if (array.size() > 16) {
            throw new IllegalArgumentException(field + " содержит слишком много записей");
        }

        for (int i = 0; i < array.size(); i++) {
            if (!array.get(i).isJsonObject()) {
                throw new IllegalArgumentException(field + "[" + i + "] должен быть объектом");
            }
            JsonObject entry = array.get(i).getAsJsonObject();
            ResourceLocation fluidId = resourceLocation(requiredString(entry, "fluid"), field + "[" + i + "].fluid");
            if (!ForgeRegistries.FLUIDS.containsKey(fluidId)) {
                throw new IllegalArgumentException("Неизвестная жидкость: " + fluidId);
            }

            long amount = entry.has("amount") ? entry.get("amount").getAsLong() : 1L;
            if (amount <= 0L) {
                throw new IllegalArgumentException(field + "[" + i + "].amount должен быть > 0");
            }
            validateChance(entry, field + "[" + i + "]");
            if (input && entry.has("not_consumable") && entry.get("not_consumable").getAsBoolean() && entry.has("chance")) {
                throw new IllegalArgumentException(field + "[" + i + "]: not_consumable нельзя совмещать с chance");
            }
        }
    }

    private static void validateChance(JsonObject entry, String label) {
        if (!entry.has("chance")) {
            return;
        }
        int chance = entry.get("chance").getAsInt();
        if (chance < 0 || chance > 10_000) {
            throw new IllegalArgumentException(label + ".chance должен быть от 0 до 10000");
        }
    }

    private static void validateNonNegativeIntField(JsonObject object, String field) {
        if (!object.has(field)) {
            return;
        }
        int value = object.get(field).getAsInt();
        if (value < 0) {
            throw new IllegalArgumentException(field + " должен быть >= 0");
        }
    }

    private static int requiredInt(JsonObject object, String field) {
        if (!object.has(field)) {
            throw new IllegalArgumentException("Отсутствует обязательное поле " + field);
        }
        return object.get(field).getAsInt();
    }

    private static String requiredString(JsonObject object, String field) {
        if (!object.has(field)) {
            throw new IllegalArgumentException("Отсутствует обязательное поле " + field);
        }
        String value = object.get(field).getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Поле " + field + " пустое");
        }
        return value;
    }

    private static ResourceLocation resourceLocation(String value, String field) {
        try {
            return new ResourceLocation(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Некорректный ResourceLocation в " + field + ": " + value);
        }
    }

    private static Path relativePath(ResourceLocation id) {
        return Path.of("editor", id.getNamespace()).resolve(id.getPath() + ".json");
    }

    private static Path safeResolve(Path root, Path relative) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(relative).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IOException("Недопустимый путь файла рецепта");
        }
        return target;
    }

    private static String normalizeSeparators(String value) {
        return value.replace('\\', '/');
    }
}