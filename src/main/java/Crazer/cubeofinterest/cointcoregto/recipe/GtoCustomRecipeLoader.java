package Crazer.cubeofinterest.cointcoregto.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
public final class GtoCustomRecipeLoader {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:GtoCustomRecipeLoader");
    public static final Path RECIPE_DIRECTORY = FMLPaths.CONFIGDIR.get()
            .resolve("cointcoregto")
            .resolve("gto_recipes");

    private static final String EXAMPLE_FILE_NAME = "example.json.disabled";

    private static final Map<MethodKey, Method> METHOD_CACHE = new HashMap<>();
    private static final List<RegisteredRecipe> CLIENT_SYNCED_RECIPES = new ArrayList<>();

    private GtoCustomRecipeLoader() {
    }

    public record LoadResult(int loaded, int skipped, int failed, int files) {
    }

    /** Result of mirroring server-owned GT/GTO JSON into the client GTCEu recipe maps. */
    public record ClientSyncResult(int loaded, int skipped, int failed, int files) {
    }

    /**
     * Client-side mirror used only for recipe viewers. Machine execution remains
     * authoritative on the server. The same parser/builder path is intentionally
     * used so EMI sees exactly the recipe shape that GTCEu sees.
     */
    public static synchronized ClientSyncResult registerClientSyncedFiles(List<String> jsonFiles) {
        clearClientSyncedRecipes();

        int loaded = 0;
        int skipped = 0;
        int failed = 0;
        int files = jsonFiles == null ? 0 : jsonFiles.size();
        if (jsonFiles == null || jsonFiles.isEmpty()) {
            return new ClientSyncResult(0, 0, 0, files);
        }

        for (int fileIndex = 0; fileIndex < jsonFiles.size(); fileIndex++) {
            String json = jsonFiles.get(fileIndex);
            String labelBase = "server-sync#" + (fileIndex + 1);
            try {
                JsonElement root = JsonParser.parseString(json);
                List<JsonObject> recipes = extractRecipes(root, labelBase);
                for (int recipeIndex = 0; recipeIndex < recipes.size(); recipeIndex++) {
                    JsonObject recipe = recipes.get(recipeIndex);
                    String label = labelBase + "/" + (recipeIndex + 1);
                    try {
                        if (!getBoolean(recipe, "enabled", true)) {
                            skipped++;
                            continue;
                        }
                        RegisteredRecipe registered = registerRecipe(recipe, label, true);
                        CLIENT_SYNCED_RECIPES.add(registered);
                        loaded++;
                    } catch (Throwable throwable) {
                        failed++;
                        LOGGER.warn("Unable to mirror synced GT/GTO recipe {} on the client", label, throwable);
                    }
                }
            } catch (Throwable throwable) {
                failed++;
                LOGGER.warn("Unable to parse synced GT/GTO recipe file {} on the client", labelBase, throwable);
            }
        }
        return new ClientSyncResult(loaded, skipped, failed, files);
    }

    /** Removes the recipes mirrored for the previous multiplayer connection. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static synchronized void clearClientSyncedRecipes() {
        for (RegisteredRecipe registered : CLIENT_SYNCED_RECIPES) {
            try {
                Field recipesField = registered.recipeType().getClass().getField("recipes");
                Object recipesObject = recipesField.get(registered.recipeType());
                if (recipesObject instanceof Map map) {
                    map.remove(registered.runtimeId());
                }
            } catch (Throwable ignored) {
            }

            // GTCEu versions differ here. If a symmetric category-removal method
            // exists, use it; otherwise map removal is still enough to prevent
            // machine-side lookup from retaining the old client mirror.
            tryInvokeOneArgIfPresent(registered.recipeType(), "removeFromMainCategory", registered.recipeObject());
            tryInvokeOneArgIfPresent(registered.recipeType(), "removeRecipe", registered.recipeObject());
        }
        CLIENT_SYNCED_RECIPES.clear();
    }

    private static final class MutableResult {
        int loaded;
        int skipped;
        int failed;
        int files;

        LoadResult freeze() {
            return new LoadResult(loaded, skipped, failed, files);
        }
    }

    public static LoadResult loadAndRegisterAll() throws IOException {
        ensureRecipeDirectory();
        writeExampleIfMissing();

        List<Path> files = discoverRecipeFiles();
        MutableResult result = new MutableResult();
        result.files = files.size();

        if (files.isEmpty()) {
            return result.freeze();
        }


        for (Path file : files) {
            loadFile(file, result);
        }

        return result.freeze();
    }

    private static void ensureRecipeDirectory() throws IOException {
        Files.createDirectories(RECIPE_DIRECTORY);
    }

    private static List<Path> discoverRecipeFiles() throws IOException {
        try (var stream = Files.walk(RECIPE_DIRECTORY)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> RECIPE_DIRECTORY.relativize(path).toString()))
                    .toList();
        }
    }

    private static void loadFile(Path file, MutableResult result) {
        String displayPath = RECIPE_DIRECTORY.relativize(file).toString();

        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            List<JsonObject> recipes = extractRecipes(root, displayPath);

            for (int index = 0; index < recipes.size(); index++) {
                JsonObject recipe = recipes.get(index);
                String label = displayPath + "#" + (index + 1);

                try {
                    if (!getBoolean(recipe, "enabled", true)) {
                        result.skipped++;
                        continue;
                    }

                    RegisteredRecipe registered = registerRecipe(recipe, label, false);
                    result.loaded++;
                } catch (Throwable throwable) {
                    result.failed++;
                }
            }
        } catch (Throwable throwable) {
            result.failed++;
        }
    }

    private static List<JsonObject> extractRecipes(JsonElement root, String displayPath) {
        List<JsonObject> recipes = new ArrayList<>();

        if (root.isJsonArray()) {
            appendRecipeArray(root.getAsJsonArray(), recipes, displayPath);
            return recipes;
        }

        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("Root must be an object or array in " + displayPath);
        }

        JsonObject object = root.getAsJsonObject();
        if (object.has("recipes")) {
            JsonElement recipesElement = object.get("recipes");
            if (!recipesElement.isJsonArray()) {
                throw new IllegalArgumentException("Field 'recipes' must be an array in " + displayPath);
            }
            appendRecipeArray(recipesElement.getAsJsonArray(), recipes, displayPath);
        } else {
            recipes.add(object);
        }

        return recipes;
    }

    private static void appendRecipeArray(JsonArray array, List<JsonObject> output, String displayPath) {
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(
                        "Recipe at array index " + i + " is not an object in " + displayPath
                );
            }
            output.add(element.getAsJsonObject());
        }
    }

    private record RegisteredRecipe(Object runtimeId, ResourceLocation typeId, Object recipeType, Object recipeObject) {
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static RegisteredRecipe registerRecipe(JsonObject json, String label, boolean replaceExisting) throws Exception {
        ResourceLocation typeId = requireResourceLocation(json, "type", label);
        String configuredId = requireString(json, "id", label);
        String builderId = normalizeBuilderId(configuredId, label);

        Object recipeType = findRecipeType(typeId);
        if (recipeType == null) {
            throw new IllegalArgumentException("Unknown GT recipe type '" + typeId + "'");
        }

        Method builderMethod = findExactMethod(
                recipeType.getClass(),
                "builder",
                String.class,
                Object[].class
        );

        Object builder = invoke(recipeType, builderMethod, builderId, new Object[0]);
        if (builder == null) {
            throw new IllegalStateException("RecipeType.builder() returned null for " + typeId);
        }

        configureRecipe(builder, json, label);

        Method buildMethod = findExactMethod(builder.getClass(), "build", boolean.class);
        Object builtRecipe = invoke(builder, buildMethod, true);
        if (builtRecipe == null) {
            throw new IllegalStateException("RecipeBuilder.build(true) returned null");
        }

        Field idField = builtRecipe.getClass().getField("id");
        Object runtimeId = idField.get(builtRecipe);
        if (runtimeId == null) {
            throw new IllegalStateException("Built recipe id is null");
        }

        Field recipesField = recipeType.getClass().getField("recipes");
        Object recipesObject = recipesField.get(recipeType);
        if (!(recipesObject instanceof Map<?, ?>)) {
            throw new IllegalStateException(
                    "Recipe type '" + typeId + "' recipes field is not a Map"
            );
        }

        Map recipesMap = (Map) recipesObject;
        if (recipesMap.containsKey(runtimeId)) {
            if (!replaceExisting) {
                throw new IllegalStateException(
                        "Duplicate runtime recipe id '" + runtimeId + "' in type '" + typeId + "'"
                );
            }
            Object previous = recipesMap.remove(runtimeId);
            tryInvokeOneArgIfPresent(recipeType, "removeFromMainCategory", previous);
            tryInvokeOneArgIfPresent(recipeType, "removeRecipe", previous);
        }

        recipesMap.put(runtimeId, builtRecipe);
        boolean categoryAttached = false;

        try {
            Method addToMainCategory = findCompatibleOneArgMethod(
                    recipeType.getClass(),
                    "addToMainCategory",
                    builtRecipe.getClass()
            );
            invoke(recipeType, addToMainCategory, builtRecipe);
            categoryAttached = true;
        } finally {
            if (!categoryAttached) {
                recipesMap.remove(runtimeId);
            }
        }

        if (!recipesMap.containsKey(runtimeId)) {
            throw new IllegalStateException(
                    "Recipe '" + runtimeId + "' disappeared from the type map after attach"
            );
        }

        return new RegisteredRecipe(runtimeId, typeId, recipeType, builtRecipe);
    }

    private static void configureRecipe(Object builder, JsonObject json, String label) throws Exception {
        if (!json.has("duration")) {
            throw new IllegalArgumentException("Missing required field 'duration' in " + label);
        }

        int duration = requirePositiveInt(json, "duration", label);
        invokeExact(builder, "duration", new Class<?>[]{int.class}, duration);

        if (json.has("eut")) {
            invokeExact(builder, "EUt", new Class<?>[]{long.class}, json.get("eut").getAsLong());
        }
        if (json.has("circuit")) {
            invokeExact(builder, "circuitMeta", new Class<?>[]{int.class}, json.get("circuit").getAsInt());
        }
        if (json.has("priority")) {
            invokeExact(builder, "priority", new Class<?>[]{int.class}, json.get("priority").getAsInt());
        }
        if (json.has("blast_furnace_temp")) {
            invokeExact(builder, "blastFurnaceTemp", new Class<?>[]{int.class}, json.get("blast_furnace_temp").getAsInt());
        }
        if (json.has("heat")) {
            invokeExact(builder, "heat", new Class<?>[]{int.class}, json.get("heat").getAsInt());
        }
        if (json.has("temperature")) {
            invokeExact(builder, "temperature", new Class<?>[]{int.class}, json.get("temperature").getAsInt());
        }
        if (json.has("mana_per_tick")) {
            invokeExact(builder, "MANAt", new Class<?>[]{long.class}, json.get("mana_per_tick").getAsLong());
        } else if (json.has("manat")) {
            invokeExact(builder, "MANAt", new Class<?>[]{long.class}, json.get("manat").getAsLong());
        }
        if (json.has("cwu_per_tick")) {
            invokeExact(builder, "CWUt", new Class<?>[]{int.class}, json.get("cwu_per_tick").getAsInt());
        }
        if (json.has("total_cwu")) {
            invokeExact(builder, "totalCWU", new Class<?>[]{int.class}, json.get("total_cwu").getAsInt());
        }
        if (json.has("fusion_start_eu")) {
            invokeExact(builder, "fusionStartEU", new Class<?>[]{long.class}, json.get("fusion_start_eu").getAsLong());
        }
        if (json.has("solder_multiplier")) {
            invokeExact(builder, "solderMultiplier", new Class<?>[]{int.class}, json.get("solder_multiplier").getAsInt());
        }
        if (json.has("research_scan")) {
            invokeExact(builder, "researchScan", new Class<?>[]{boolean.class}, json.get("research_scan").getAsBoolean());
        }
        if (json.has("duration_is_total_cwu")) {
            invokeExact(
                    builder,
                    "durationIsTotalCWU",
                    new Class<?>[]{boolean.class},
                    json.get("duration_is_total_cwu").getAsBoolean()
            );
        }
        if (json.has("hide_duration")) {
            invokeExact(builder, "hideDuration", new Class<?>[]{boolean.class}, json.get("hide_duration").getAsBoolean());
        }

        if (json.has("item_inputs")) {
            configureItemInputs(builder, requireArray(json, "item_inputs", label), label);
        }
        if (json.has("item_outputs")) {
            configureItemOutputs(builder, requireArray(json, "item_outputs", label), label);
        }
        if (json.has("fluid_inputs")) {
            configureFluidInputs(builder, requireArray(json, "fluid_inputs", label), label);
        }
        if (json.has("fluid_outputs")) {
            configureFluidOutputs(builder, requireArray(json, "fluid_outputs", label), label);
        }
    }

    private static void configureItemInputs(Object builder, JsonArray inputs, String label) throws Exception {
        for (int i = 0; i < inputs.size(); i++) {
            JsonObject input = requireObject(inputs.get(i), "item_inputs[" + i + "]", label);
            int count = getPositiveInt(input, "count", 1, label);
            boolean notConsumable = getBoolean(input, "not_consumable", false);
            Integer chance = getOptionalChance(input, label);
            int tierChanceBoost = getInt(input, "tier_chance_boost", 0);

            if (notConsumable && chance != null) {
                throw new IllegalArgumentException(
                        "item_inputs[" + i + "] cannot use both not_consumable and chance in " + label
                );
            }

            boolean hasItem = input.has("item");
            boolean hasTag = input.has("tag");
            if (hasItem == hasTag) {
                throw new IllegalArgumentException(
                        "item_inputs[" + i + "] must contain exactly one of 'item' or 'tag' in " + label
                );
            }

            if (hasItem) {
                Item item = resolveItem(requireString(input, "item", label));
                String nbt = getOptionalString(input, "nbt");

                if (chance != null) {
                    if (nbt == null) {
                        invokeExact(
                                builder,
                                "chancedInput",
                                new Class<?>[]{Item.class, int.class, int.class, int.class},
                                item,
                                count,
                                chance,
                                tierChanceBoost
                        );
                    } else {
                        ItemStack stack = createItemStack(item, count, nbt, label);
                        invokeExact(
                                builder,
                                "chancedInput",
                                new Class<?>[]{ItemStack.class, int.class, int.class},
                                stack,
                                chance,
                                tierChanceBoost
                        );
                    }
                    continue;
                }

                if (notConsumable) {
                    if (nbt == null) {
                        invokeExact(
                                builder,
                                "notConsumable",
                                new Class<?>[]{Item.class, int.class},
                                item,
                                count
                        );
                    } else {
                        ItemStack stack = createItemStack(item, count, nbt, label);
                        invokeExact(
                                builder,
                                "notConsumable",
                                new Class<?>[]{ItemStack.class},
                                stack
                        );
                    }
                } else if (nbt == null) {
                    invokeExact(
                            builder,
                            "inputItems",
                            new Class<?>[]{Item.class, int.class},
                            item,
                            count
                    );
                } else {
                    ItemStack stack = createItemStack(item, count, nbt, label);
                    invokeExact(
                            builder,
                            "inputItems",
                            new Class<?>[]{ItemStack.class},
                            stack
                    );
                }
            } else {
                if (chance != null) {
                    throw new IllegalArgumentException(
                            "Chanced tag inputs are not supported: item_inputs[" + i + "] in " + label
                    );
                }

                ResourceLocation tagId = parseResourceLocation(requireString(input, "tag", label), "tag", label);
                TagKey<Item> tag = TagKey.create(Registries.ITEM, tagId);

                if (notConsumable) {
                    if (count != 1) {
                        throw new IllegalArgumentException(
                                "not_consumable tag inputs currently require count=1: item_inputs[" + i + "] in " + label
                        );
                    }
                    Ingredient ingredient = Ingredient.of(tag);
                    invokeExact(
                            builder,
                            "notConsumable",
                            new Class<?>[]{Ingredient.class},
                            ingredient
                    );
                } else {
                    invokeExact(
                            builder,
                            "inputItems",
                            new Class<?>[]{TagKey.class, int.class},
                            tag,
                            count
                    );
                }
            }
        }
    }

    private static void configureItemOutputs(Object builder, JsonArray outputs, String label) throws Exception {
        for (int i = 0; i < outputs.size(); i++) {
            JsonObject output = requireObject(outputs.get(i), "item_outputs[" + i + "]", label);
            Item item = resolveItem(requireString(output, "item", label));
            int count = getPositiveInt(output, "count", 1, label);
            Integer chance = getOptionalChance(output, label);
            int tierChanceBoost = getInt(output, "tier_chance_boost", 0);
            String nbt = getOptionalString(output, "nbt");

            if (chance == null) {
                if (nbt == null) {
                    invokeExact(
                            builder,
                            "outputItems",
                            new Class<?>[]{Item.class, int.class},
                            item,
                            count
                    );
                } else {
                    ItemStack stack = createItemStack(item, count, nbt, label);
                    invokeExact(
                            builder,
                            "outputItems",
                            new Class<?>[]{ItemStack.class},
                            stack
                    );
                }
            } else if (nbt == null) {
                invokeExact(
                        builder,
                        "chancedOutput",
                        new Class<?>[]{Item.class, int.class, int.class, int.class},
                        item,
                        count,
                        chance,
                        tierChanceBoost
                );
            } else {
                ItemStack stack = createItemStack(item, count, nbt, label);
                invokeExact(
                        builder,
                        "chancedOutput",
                        new Class<?>[]{ItemStack.class, int.class, int.class},
                        stack,
                        chance,
                        tierChanceBoost
                );
            }
        }
    }

    private static void configureFluidInputs(Object builder, JsonArray inputs, String label) throws Exception {
        for (int i = 0; i < inputs.size(); i++) {
            JsonObject input = requireObject(inputs.get(i), "fluid_inputs[" + i + "]", label);
            Fluid fluid = resolveFluid(requireString(input, "fluid", label));
            long amount = getPositiveLong(input, "amount", 1000L, label);
            boolean notConsumable = getBoolean(input, "not_consumable", false);
            Integer chance = getOptionalChance(input, label);
            int tierChanceBoost = getInt(input, "tier_chance_boost", 0);

            if (notConsumable && chance != null) {
                throw new IllegalArgumentException(
                        "fluid_inputs[" + i + "] cannot use both not_consumable and chance in " + label
                );
            }

            if (chance != null) {
                FluidStack stack = createFluidStack(fluid, amount, label);
                invokeExact(
                        builder,
                        "chancedInput",
                        new Class<?>[]{FluidStack.class, int.class, int.class},
                        stack,
                        chance,
                        tierChanceBoost
                );
            } else if (notConsumable) {
                FluidStack stack = createFluidStack(fluid, amount, label);
                invokeExact(
                        builder,
                        "notConsumableFluid",
                        new Class<?>[]{FluidStack.class},
                        stack
                );
            } else {
                invokeExact(
                        builder,
                        "inputFluids",
                        new Class<?>[]{Fluid.class, long.class},
                        fluid,
                        amount
                );
            }
        }
    }

    private static void configureFluidOutputs(Object builder, JsonArray outputs, String label) throws Exception {
        for (int i = 0; i < outputs.size(); i++) {
            JsonObject output = requireObject(outputs.get(i), "fluid_outputs[" + i + "]", label);
            Fluid fluid = resolveFluid(requireString(output, "fluid", label));
            long amount = getPositiveLong(output, "amount", 1000L, label);
            Integer chance = getOptionalChance(output, label);
            int tierChanceBoost = getInt(output, "tier_chance_boost", 0);

            if (chance == null) {
                invokeExact(
                        builder,
                        "outputFluids",
                        new Class<?>[]{Fluid.class, long.class},
                        fluid,
                        amount
                );
            } else {
                FluidStack stack = createFluidStack(fluid, amount, label);
                invokeExact(
                        builder,
                        "chancedOutput",
                        new Class<?>[]{FluidStack.class, int.class, int.class},
                        stack,
                        chance,
                        tierChanceBoost
                );
            }
        }
    }

    public static boolean recipeTypeExists(ResourceLocation typeId) {
        if (typeId == null) {
            return false;
        }
        try {
            return findRecipeType(typeId) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object findRecipeType(ResourceLocation typeId) throws Exception {
        ClassLoader loader = GtoCustomRecipeLoader.class.getClassLoader();
        Class<?> gtRegistriesClass = Class.forName(
                "com.gregtechceu.gtceu.api.registry.GTRegistries",
                false,
                loader
        );

        Field recipeTypesField = gtRegistriesClass.getField("RECIPE_TYPES");
        Object recipeTypesRegistry = recipeTypesField.get(null);
        if (recipeTypesRegistry == null) {
            throw new IllegalStateException("GTRegistries.RECIPE_TYPES is null");
        }

        Method registryMethod = findExactMethod(recipeTypesRegistry.getClass(), "registry");
        Object registryObject = invoke(recipeTypesRegistry, registryMethod);
        if (!(registryObject instanceof Map<?, ?> registryMap)) {
            throw new IllegalStateException("GTRegistries.RECIPE_TYPES.registry() is not a Map");
        }

        return registryMap.get(typeId);
    }

    private static Item resolveItem(String idText) {
        ResourceLocation id = parseResourceLocation(idText, "item", idText);
        if (!ForgeRegistries.ITEMS.containsKey(id)) {
            throw new IllegalArgumentException("Unknown item '" + id + "'");
        }
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null) {
            throw new IllegalArgumentException("Item registry returned null for '" + id + "'");
        }
        return item;
    }

    private static Fluid resolveFluid(String idText) {
        ResourceLocation id = parseResourceLocation(idText, "fluid", idText);
        if (!ForgeRegistries.FLUIDS.containsKey(id)) {
            throw new IllegalArgumentException("Unknown fluid '" + id + "'");
        }
        Fluid fluid = ForgeRegistries.FLUIDS.getValue(id);
        if (fluid == null) {
            throw new IllegalArgumentException("Fluid registry returned null for '" + id + "'");
        }
        return fluid;
    }

    private static ItemStack createItemStack(Item item, int count, String snbt, String label) throws Exception {
        ItemStack stack = new ItemStack(item, count);
        if (snbt != null && !snbt.isBlank()) {
            CompoundTag tag = TagParser.parseTag(snbt);
            stack.setTag(tag);
        }
        return stack;
    }

    private static FluidStack createFluidStack(Fluid fluid, long amount, String label) {
        if (amount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Fluid amount " + amount + " is too large for FluidStack in " + label
            );
        }
        return new FluidStack(fluid, (int) amount);
    }

    private static void invokeExact(Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = findExactMethod(target.getClass(), name, parameterTypes);
        invoke(target, method, args);
    }

    private static Object invoke(Object target, Method method, Object... args) throws Exception {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause == null ? exception : cause);
        }
    }

    private static void tryInvokeOneArgIfPresent(Object target, String name, Object argument) {
        if (target == null || argument == null) {
            return;
        }
        try {
            Method method = findCompatibleOneArgMethod(target.getClass(), name, argument.getClass());
            invoke(target, method, argument);
        } catch (Throwable ignored) {
        }
    }

    private static Method findExactMethod(Class<?> owner, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        MethodKey key = new MethodKey(owner, name, List.of(parameterTypes));
        Method cached = METHOD_CACHE.get(key);
        if (cached != null) {
            return cached;
        }

        Method bridgeFallback = null;
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name)) {
                continue;
            }
            if (!Arrays.equals(method.getParameterTypes(), parameterTypes)) {
                continue;
            }
            if (!method.isBridge()) {
                METHOD_CACHE.put(key, method);
                return method;
            }
            bridgeFallback = method;
        }

        if (bridgeFallback != null) {
            METHOD_CACHE.put(key, bridgeFallback);
            return bridgeFallback;
        }

        throw new NoSuchMethodException(owner.getName() + "." + name + Arrays.toString(parameterTypes));
    }

    private static Method findCompatibleOneArgMethod(Class<?> owner, String name, Class<?> argumentClass)
            throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> parameter = method.getParameterTypes()[0];
            if (parameter.isAssignableFrom(argumentClass)) {
                return method;
            }
        }
        throw new NoSuchMethodException(
                "No compatible " + owner.getName() + "." + name + "(" + argumentClass.getName() + ")"
        );
    }

    private record MethodKey(Class<?> owner, String name, List<Class<?>> parameterTypes) {
    }

    private static String normalizeBuilderId(String configuredId, String label) {
        String trimmed = configuredId.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Recipe id is empty in " + label);
        }

        if (trimmed.contains(":")) {
            ResourceLocation id = parseResourceLocation(trimmed, "id", label);
            return id.getNamespace() + "/" + id.getPath();
        }

        if (!ResourceLocation.isValidPath(trimmed)) {
            throw new IllegalArgumentException(
                    "Invalid recipe id path '" + configuredId + "' in " + label
            );
        }
        return trimmed;
    }

    private static ResourceLocation requireResourceLocation(JsonObject object, String field, String label) {
        return parseResourceLocation(requireString(object, field, label), field, label);
    }

    private static ResourceLocation parseResourceLocation(String value, String field, String label) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) {
            throw new IllegalArgumentException(
                    "Invalid ResourceLocation in field '" + field + "': '" + value + "' (" + label + ")"
            );
        }
        return id;
    }

    private static String requireString(JsonObject object, String field, String label) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            throw new IllegalArgumentException("Missing required field '" + field + "' in " + label);
        }
        String value = object.get(field).getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Field '" + field + "' is empty in " + label);
        }
        return value;
    }

    private static String getOptionalString(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        return object.get(field).getAsString();
    }

    private static JsonArray requireArray(JsonObject object, String field, String label) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonArray()) {
            throw new IllegalArgumentException("Field '" + field + "' must be an array in " + label);
        }
        return element.getAsJsonArray();
    }

    private static JsonObject requireObject(JsonElement element, String field, String label) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(field + " must be an object in " + label);
        }
        return element.getAsJsonObject();
    }

    private static int requirePositiveInt(JsonObject object, String field, String label) {
        int value = object.get(field).getAsInt();
        if (value <= 0) {
            throw new IllegalArgumentException("Field '" + field + "' must be > 0 in " + label);
        }
        return value;
    }

    private static int getPositiveInt(JsonObject object, String field, int defaultValue, String label) {
        if (!object.has(field)) {
            return defaultValue;
        }
        int value = object.get(field).getAsInt();
        if (value <= 0) {
            throw new IllegalArgumentException("Field '" + field + "' must be > 0 in " + label);
        }
        return value;
    }

    private static long getPositiveLong(JsonObject object, String field, long defaultValue, String label) {
        if (!object.has(field)) {
            return defaultValue;
        }
        long value = object.get(field).getAsLong();
        if (value <= 0L) {
            throw new IllegalArgumentException("Field '" + field + "' must be > 0 in " + label);
        }
        return value;
    }

    private static int getInt(JsonObject object, String field, int defaultValue) {
        return object.has(field) ? object.get(field).getAsInt() : defaultValue;
    }

    private static boolean getBoolean(JsonObject object, String field, boolean defaultValue) {
        return object.has(field) ? object.get(field).getAsBoolean() : defaultValue;
    }

    private static Integer getOptionalChance(JsonObject object, String label) {
        if (!object.has("chance")) {
            return null;
        }
        int chance = object.get("chance").getAsInt();
        if (chance < 0 || chance > 10_000) {
            throw new IllegalArgumentException("chance must be between 0 and 10000 in " + label);
        }
        return chance;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            current = invocation.getCause();
        }
        return current;
    }

    private static void writeExampleIfMissing() {
        Path example = RECIPE_DIRECTORY.resolve(EXAMPLE_FILE_NAME);
        if (Files.exists(example)) {
            return;
        }

        String text = """
                {
                  "recipes": [
                    {
                      "enabled": true,
                      "type": "gtceu:assembler",
                      "id": "cointcoregto:iron_to_diamond_example",
                      "duration": 200,
                      "eut": 16,
                      "item_inputs": [
                        { "item": "minecraft:iron_ingot", "count": 1 }
                      ],
                      "item_outputs": [
                        { "item": "minecraft:diamond", "count": 1 }
                      ]
                    }
                  ]
                }
                """;

        try {
            Files.writeString(example, text, StandardCharsets.UTF_8);
        } catch (IOException exception) {
        }
    }
}
