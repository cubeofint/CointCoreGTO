package Crazer.cubeofinterest.cointcoregto.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class CraftingRecipeLoader {
    public static final ResourceLocation CRAFTING_SHAPED =
            new ResourceLocation("minecraft", "crafting_shaped");
    public static final ResourceLocation CRAFTING_SHAPELESS =
            new ResourceLocation("minecraft", "crafting_shapeless");

    public static final Path RECIPE_DIRECTORY = FMLPaths.CONFIGDIR.get()
            .resolve("cointcoregto")
            .resolve("crafting_recipes");

    private static boolean loadAttempted;

    private CraftingRecipeLoader() {
    }

    public static boolean isSupportedType(ResourceLocation type) {
        return CRAFTING_SHAPED.equals(type) || CRAFTING_SHAPELESS.equals(type);
    }

    public record LoadResult(int loaded, int skipped, int failed, int files) {
    }

    public static Set<ResourceLocation> discoverConfiguredRecipeIds() {
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        try {
            Files.createDirectories(RECIPE_DIRECTORY);
            try (Stream<Path> stream = Files.walk(RECIPE_DIRECTORY)) {
                for (Path file : stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".json"))
                        .sorted()
                        .toList()) {
                    try {
                        JsonElement element = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                        if (!element.isJsonObject()) {
                            continue;
                        }
                        JsonObject root = element.getAsJsonObject();
                        if (!root.has("id") || !root.has("type")) {
                            continue;
                        }
                        ResourceLocation type = new ResourceLocation(root.get("type").getAsString());
                        if (!isSupportedType(type)) {
                            continue;
                        }
                        ids.add(new ResourceLocation(root.get("id").getAsString()));
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return ids;
    }

    public static synchronized LoadResult loadIntoGTRecipeMap() {
        if (loadAttempted) {
            return new LoadResult(0, 0, 0, 0);
        }
        loadAttempted = true;

        int loaded = 0;
        int skipped = 0;
        int failed = 0;

        try {
            Files.createDirectories(RECIPE_DIRECTORY);
        } catch (Throwable throwable) {
            return new LoadResult(0, 0, 1, 0);
        }

        final List<Path> files;
        try (Stream<Path> stream = Files.walk(RECIPE_DIRECTORY)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (Throwable throwable) {
            return new LoadResult(0, 0, 1, 0);
        }

        
        Map<ResourceLocation, PreparedRecipe> prepared = new LinkedHashMap<>();
        for (Path file : files) {
            try {
                JsonElement element = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (!element.isJsonObject()) {
                    throw new IllegalArgumentException("root is not a JSON object");
                }

                JsonObject root = element.getAsJsonObject();
                if (root.has("enabled") && !root.get("enabled").getAsBoolean()) {
                    skipped++;
                    continue;
                }

                ResourceLocation id = new ResourceLocation(requiredString(root, "id"));
                ResourceLocation type = new ResourceLocation(requiredString(root, "type"));
                if (!isSupportedType(type)) {
                    skipped++;
                    continue;
                }

                PreparedRecipe previous = prepared.put(id, new PreparedRecipe(file, id, type, root));
                if (previous != null) {
                }
            } catch (Throwable throwable) {
                failed++;
            }
        }

        if (prepared.isEmpty()) {
            return new LoadResult(0, skipped, failed, files.size());
        }

        try {
            BuilderAccess builders = BuilderAccess.open();
            for (PreparedRecipe recipe : prepared.values()) {
                try {
                    if (CRAFTING_SHAPED.equals(recipe.type())) {
                        builders.registerShaped(recipe.id(), recipe.root());
                    } else {
                        builders.registerShapeless(recipe.id(), recipe.root());
                    }

                    boolean present = builders.contains(recipe.id());
                    if (!present) {
                        throw new IllegalStateException("builder.save() returned but GTRecipes.RECIPE_MAP does not contain " + recipe.id());
                    }

                    loaded++;
                } catch (Throwable throwable) {
                    failed++;
                }
            }

        } catch (Throwable throwable) {
            failed += prepared.size();
            loaded = 0;
        }

        return new LoadResult(loaded, skipped, failed, files.size());
    }

    private record PreparedRecipe(Path source, ResourceLocation id, ResourceLocation type, JsonObject root) {
    }
    private static final class BuilderAccess {
        private final Constructor<?> shapedConstructor;
        private final Method shapedPattern;
        private final Method shapedDefineItem;
        private final Method shapedDefineTag;
        private final Method shapedOutput;
        private final Method shapedGroup;
        private final Method shapedSave;

        private final Constructor<?> shapelessConstructor;
        private final Method shapelessRequiresItem;
        private final Method shapelessRequiresTag;
        private final Method shapelessOutput;
        private final Method shapelessGroup;
        private final Method shapelessSave;

        private final Map<?, ?> recipeMap;

        private BuilderAccess(
                Constructor<?> shapedConstructor,
                Method shapedPattern,
                Method shapedDefineItem,
                Method shapedDefineTag,
                Method shapedOutput,
                Method shapedGroup,
                Method shapedSave,
                Constructor<?> shapelessConstructor,
                Method shapelessRequiresItem,
                Method shapelessRequiresTag,
                Method shapelessOutput,
                Method shapelessGroup,
                Method shapelessSave,
                Map<?, ?> recipeMap
        ) {
            this.shapedConstructor = shapedConstructor;
            this.shapedPattern = shapedPattern;
            this.shapedDefineItem = shapedDefineItem;
            this.shapedDefineTag = shapedDefineTag;
            this.shapedOutput = shapedOutput;
            this.shapedGroup = shapedGroup;
            this.shapedSave = shapedSave;
            this.shapelessConstructor = shapelessConstructor;
            this.shapelessRequiresItem = shapelessRequiresItem;
            this.shapelessRequiresTag = shapelessRequiresTag;
            this.shapelessOutput = shapelessOutput;
            this.shapelessGroup = shapelessGroup;
            this.shapelessSave = shapelessSave;
            this.recipeMap = recipeMap;
        }

        static BuilderAccess open() throws Exception {
            ClassLoader loader = CraftingRecipeLoader.class.getClassLoader();

            Class<?> shapedClass = Class.forName(
                    "com.gregtechceu.gtceu.data.recipe.builder.ShapedRecipeBuilder",
                    true,
                    loader
            );
            Class<?> shapelessClass = Class.forName(
                    "com.gregtechceu.gtceu.data.recipe.builder.ShapelessRecipeBuilder",
                    true,
                    loader
            );

            Constructor<?> shapedConstructor = shapedClass.getConstructor(ResourceLocation.class);
            Method shapedPattern = shapedClass.getMethod("pattern", String.class);
            Method shapedDefineItem = shapedClass.getMethod("define", char.class, ItemLike.class);
            Method shapedDefineTag = shapedClass.getMethod("define", char.class, TagKey.class);
            Method shapedOutput = shapedClass.getMethod("output", ItemStack.class);
            Method shapedGroup = shapedClass.getMethod("group", String.class);
            Method shapedSave = shapedClass.getMethod("save");

            Constructor<?> shapelessConstructor = shapelessClass.getConstructor(ResourceLocation.class);
            Method shapelessRequiresItem = shapelessClass.getMethod("requires", ItemLike.class);
            Method shapelessRequiresTag = shapelessClass.getMethod("requires", TagKey.class);
            Method shapelessOutput = shapelessClass.getMethod("output", ItemStack.class);
            Method shapelessGroup = shapelessClass.getMethod("group", String.class);
            Method shapelessSave = shapelessClass.getMethod("save");

            Class<?> gtRecipesClass = Class.forName(
                    "com.gregtechceu.gtceu.common.data.GTRecipes",
                    true,
                    loader
            );
            Field recipeMapField = gtRecipesClass.getField("RECIPE_MAP");
            Object rawMap = recipeMapField.get(null);
            if (!(rawMap instanceof Map<?, ?> map)) {
                throw new IllegalStateException("GTRecipes.RECIPE_MAP is not a Map");
            }

            return new BuilderAccess(
                    shapedConstructor,
                    shapedPattern,
                    shapedDefineItem,
                    shapedDefineTag,
                    shapedOutput,
                    shapedGroup,
                    shapedSave,
                    shapelessConstructor,
                    shapelessRequiresItem,
                    shapelessRequiresTag,
                    shapelessOutput,
                    shapelessGroup,
                    shapelessSave,
                    map
            );
        }

        void registerShaped(ResourceLocation id, JsonObject root) throws Exception {
            Object builder = shapedConstructor.newInstance(id);

            String group = optionalString(root, "group");
            if (!group.isEmpty()) {
                shapedGroup.invoke(builder, group);
            }

            JsonArray pattern = requiredArray(root, "pattern");
            List<Character> symbols = new ArrayList<>();
            for (JsonElement rowElement : pattern) {
                String row = rowElement.getAsString();
                if (row.isEmpty()) {
                    throw new IllegalArgumentException("pattern row is empty");
                }
                shapedPattern.invoke(builder, row);
                for (int i = 0; i < row.length(); i++) {
                    char symbol = row.charAt(i);
                    if (symbol != ' ' && !symbols.contains(symbol)) {
                        symbols.add(symbol);
                    }
                }
            }

            JsonObject key = requiredObject(root, "key");
            for (char symbol : symbols) {
                String name = String.valueOf(symbol);
                if (!key.has(name)) {
                    throw new IllegalArgumentException("key does not contain symbol " + symbol);
                }
                applyShapedIngredient(builder, symbol, key.get(name));
            }

            shapedOutput.invoke(builder, parseResult(root));
            shapedSave.invoke(builder);
        }

        void registerShapeless(ResourceLocation id, JsonObject root) throws Exception {
            Object builder = shapelessConstructor.newInstance(id);

            String group = optionalString(root, "group");
            if (!group.isEmpty()) {
                shapelessGroup.invoke(builder, group);
            }

            JsonArray ingredients = requiredArray(root, "ingredients");
            if (ingredients.size() < 1 || ingredients.size() > 9) {
                throw new IllegalArgumentException("shapeless ingredients must contain 1..9 entries");
            }

            for (JsonElement ingredient : ingredients) {
                applyShapelessIngredient(builder, ingredient);
            }

            shapelessOutput.invoke(builder, parseResult(root));
            shapelessSave.invoke(builder);
        }

        private void applyShapedIngredient(Object builder, char symbol, JsonElement element) throws Exception {
            IngredientSpec spec = parseIngredient(element);
            if (spec.item() != null) {
                shapedDefineItem.invoke(builder, symbol, spec.item());
            } else {
                shapedDefineTag.invoke(builder, symbol, spec.tag());
            }
        }

        private void applyShapelessIngredient(Object builder, JsonElement element) throws Exception {
            IngredientSpec spec = parseIngredient(element);
            if (spec.item() != null) {
                shapelessRequiresItem.invoke(builder, spec.item());
            } else {
                shapelessRequiresTag.invoke(builder, spec.tag());
            }
        }

        boolean contains(ResourceLocation id) {
            return recipeMap.containsKey(id);
        }

        int size() {
            return recipeMap.size();
        }
    }

    private record IngredientSpec(Item item, TagKey<Item> tag) {
    }

    private static IngredientSpec parseIngredient(JsonElement element) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("crafting ingredient must be a JSON object");
        }
        JsonObject object = element.getAsJsonObject();

        boolean hasItem = object.has("item");
        boolean hasTag = object.has("tag");
        if (hasItem == hasTag) {
            throw new IllegalArgumentException("crafting ingredient must contain exactly one of item or tag");
        }

        if (hasItem) {
            ResourceLocation id = new ResourceLocation(object.get("item").getAsString());
            Item item = ForgeRegistries.ITEMS.getValue(id);
            if (item == null) {
                throw new IllegalArgumentException("unknown item " + id);
            }
            return new IngredientSpec(item, null);
        }

        ResourceLocation id = new ResourceLocation(object.get("tag").getAsString());
        return new IngredientSpec(null, TagKey.create(Registries.ITEM, id));
    }

    private static ItemStack parseResult(JsonObject root) {
        JsonObject result = requiredObject(root, "result");
        ResourceLocation itemId = new ResourceLocation(requiredString(result, "item"));
        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null) {
            throw new IllegalArgumentException("unknown result item " + itemId);
        }

        int count = result.has("count") ? result.get("count").getAsInt() : 1;
        if (count < 1 || count > 64) {
            throw new IllegalArgumentException("result.count must be 1..64");
        }
        return new ItemStack(item, count);
    }

    private static JsonArray requiredArray(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonArray()) {
            throw new IllegalArgumentException("Missing/invalid array field " + field);
        }
        return object.getAsJsonArray(field);
    }

    private static JsonObject requiredObject(JsonObject object, String field) {
        if (!object.has(field) || !object.get(field).isJsonObject()) {
            throw new IllegalArgumentException("Missing/invalid object field " + field);
        }
        return object.getAsJsonObject(field);
    }

    private static String requiredString(JsonObject object, String field) {
        if (!object.has(field)) {
            throw new IllegalArgumentException("Missing field " + field);
        }
        String value = object.get(field).getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Empty field " + field);
        }
        return value;
    }

    private static String optionalString(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return "";
        }
        return object.get(field).getAsString().trim();
    }
}