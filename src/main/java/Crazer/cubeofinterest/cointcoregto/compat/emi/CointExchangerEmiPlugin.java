package Crazer.cubeofinterest.cointcoregto.compat.emi;

import Crazer.cubeofinterest.cointcoregto.exchanger.ExchangerBlockEntity;
import Crazer.cubeofinterest.cointcoregto.exchanger.ExchangerScreen;
import Crazer.cubeofinterest.cointcoregto.recipe.CraftingRecipeLoader;
import Crazer.cubeofinterest.cointcoregto.recipe.GtoCustomRecipeLoader;
import Crazer.cubeofinterest.cointcoregto.recipe.editor.CraftingRecipeEditorMenu;
import Crazer.cubeofinterest.cointcoregto.recipe.editor.CraftingRecipeEditorScreen;
import Crazer.cubeofinterest.cointcoregto.recipe.editor.RecipeEditorCraftingSyncState;
import Crazer.cubeofinterest.cointcoregto.recipe.editor.RecipeEditorGtoSyncState;
import Crazer.cubeofinterest.cointcoregto.recipe.editor.RecipeEditorMenu;
import Crazer.cubeofinterest.cointcoregto.recipe.editor.RecipeEditorScreen;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiCraftingRecipe;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeManager;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@EmiEntrypoint
public final class CointExchangerEmiPlugin implements EmiPlugin {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:EMI");


    private static final class SyncedCraftingEmiRecipe extends EmiCraftingRecipe {
        private SyncedCraftingEmiRecipe(
                List<EmiIngredient> inputs,
                EmiStack output,
                ResourceLocation id,
                boolean shapeless
        ) {
            super(inputs, output, id, shapeless);
        }
    }

    @Override
    public void register(EmiRegistry registry) {
        LOGGER.warn(
                "CointCoreGTO EMI plugin register entered: craftingFiles={}, gtoFiles={}",
                RecipeEditorCraftingSyncState.activeJson().size(),
                RecipeEditorGtoSyncState.activeJson().size()
        );

        registerSyncedCraftingRecipes(registry);
        registerSyncedGtoRecipes(registry);

        try {
            registry.addDragDropHandler(
                    ExchangerScreen.class,
                    new EmiDragDropHandler.BoundsBased<ExchangerScreen>((screen, addTarget) -> {
                        if (!screen.canAcceptEmiTemplates()) {
                            return;
                        }

                        addTarget.accept(
                                new Bounds(
                                        screen.getTemplateSlotScreenX(ExchangerBlockEntity.SLOT_PRODUCT) - 1,
                                        screen.getTemplateSlotScreenY(ExchangerBlockEntity.SLOT_PRODUCT) - 1,
                                        18,
                                        18
                                ),
                                ingredient -> screen.setTemplateFromEmi(
                                        ExchangerBlockEntity.SLOT_PRODUCT,
                                        toItemStack(ingredient)
                                )
                        );

                        addTarget.accept(
                                new Bounds(
                                        screen.getTemplateSlotScreenX(ExchangerBlockEntity.SLOT_PRICE) - 1,
                                        screen.getTemplateSlotScreenY(ExchangerBlockEntity.SLOT_PRICE) - 1,
                                        18,
                                        18
                                ),
                                ingredient -> screen.setTemplateFromEmi(
                                        ExchangerBlockEntity.SLOT_PRICE,
                                        toItemStack(ingredient)
                                )
                        );
                    })
            );
        } catch (Throwable throwable) {
            LOGGER.warn("Unable to register exchanger EMI drag/drop handler", throwable);
        }

        try {
            registry.addDragDropHandler(
                    RecipeEditorScreen.class,
                    new EmiDragDropHandler.BoundsBased<RecipeEditorScreen>((screen, addTarget) -> {
                        for (int index = 0; index < RecipeEditorMenu.GHOST_SLOT_COUNT; index++) {
                            final int slotIndex = index;
                            addTarget.accept(
                                    new Bounds(
                                            screen.getItemTargetScreenX(slotIndex),
                                            screen.getItemTargetScreenY(slotIndex),
                                            screen.getItemTargetWidth(),
                                            screen.getItemTargetHeight()
                                    ),
                                    ingredient -> {
                                        ItemDrop item = toItemDrop(ingredient);
                                        if (item != null) {
                                            screen.setItemFromEmi(slotIndex, item.stack(), item.amount());
                                        }
                                    }
                            );
                        }

                        for (int index = 0; index < RecipeEditorScreen.FLUID_SLOT_COUNT; index++) {
                            final int fluidIndex = index;
                            addTarget.accept(
                                    new Bounds(
                                            screen.getFluidTargetScreenX(fluidIndex),
                                            screen.getFluidTargetScreenY(fluidIndex),
                                            screen.getFluidTargetWidth(),
                                            screen.getFluidTargetHeight()
                                    ),
                                    ingredient -> {
                                        FluidDrop fluid = toFluid(ingredient);
                                        if (fluid != null) {
                                            screen.setFluidFromEmi(fluidIndex, fluid.id(), fluid.amount());
                                        }
                                    }
                            );
                        }
                    })
            );
        } catch (Throwable throwable) {
            LOGGER.warn("Unable to register recipe editor EMI drag/drop handler", throwable);
        }

        try {
            registry.addDragDropHandler(
                    CraftingRecipeEditorScreen.class,
                    new EmiDragDropHandler.BoundsBased<CraftingRecipeEditorScreen>((screen, addTarget) -> {
                        for (int index = 0; index < CraftingRecipeEditorMenu.GHOST_SLOT_COUNT; index++) {
                            final int slotIndex = index;
                            addTarget.accept(
                                    new Bounds(
                                            screen.getItemTargetScreenX(slotIndex),
                                            screen.getItemTargetScreenY(slotIndex),
                                            18,
                                            18
                                    ),
                                    ingredient -> {
                                        ItemDrop item = toItemDrop(ingredient);
                                        if (item != null) {
                                            screen.setItemFromEmi(slotIndex, item.stack(), item.amount());
                                        }
                                    }
                            );
                        }
                    })
            );
        } catch (Throwable throwable) {
            LOGGER.warn("Unable to register crafting recipe editor EMI drag/drop handler", throwable);
        }
    }

    private static void registerSyncedCraftingRecipes(EmiRegistry registry) {
        List<String> syncedJson = RecipeEditorCraftingSyncState.activeJson();
        Map<ResourceLocation, JsonObject> recipes = new LinkedHashMap<>();
        int rejected = 0;

        for (String json : syncedJson) {
            try {
                JsonElement element = JsonParser.parseString(json);
                if (!element.isJsonObject()) {
                    rejected++;
                    LOGGER.warn("Ignoring synced crafting JSON because its root is not an object");
                    continue;
                }
                JsonObject root = element.getAsJsonObject();
                if (root.has("enabled") && !root.get("enabled").getAsBoolean()) {
                    continue;
                }

                ResourceLocation id = requiredResourceLocation(root, "id");
                ResourceLocation type = requiredResourceLocation(root, "type");
                if (!CraftingRecipeLoader.isSupportedType(type)) {
                    rejected++;
                    LOGGER.warn("Ignoring synced recipe {} because type {} is not supported by crafting EMI sync", id, type);
                    continue;
                }
                recipes.put(id, root);
            } catch (Throwable throwable) {
                rejected++;
                LOGGER.warn("Unable to parse a server-synced crafting recipe for EMI", throwable);
            }
        }

        if (isIntegratedSingleplayer()) {
            LOGGER.info(
                    "EMI server crafting sync: received={}, parsed={}, singleplayerNative=true, nativePresent={}, registered=0, rejected={}",
                    syncedJson.size(),
                    recipes.size(),
                    recipes.size(),
                    rejected
            );
            return;
        }

        final int receivedCount = syncedJson.size();
        final int parsedCount = recipes.size();
        final int parseRejected = rejected;
        final Map<ResourceLocation, JsonObject> deferredRecipes = new LinkedHashMap<>();
        int nativePresent = 0;

        for (Map.Entry<ResourceLocation, JsonObject> entry : recipes.entrySet()) {
            if (localCraftingRuntimeContains(entry.getKey())) {
                nativePresent++;
            } else {
                deferredRecipes.put(entry.getKey(), entry.getValue());
            }
        }

        final int nativeAtQueue = nativePresent;
        LOGGER.warn(
                "Dedicated crafting EMI sync queued: received={}, parsed={}, nativePresent={}, queued={}, rejected={}",
                receivedCount,
                parsedCount,
                nativeAtQueue,
                deferredRecipes.size(),
                parseRejected
        );

        registry.addDeferredRecipes(addRecipe -> {
            int registered = 0;
            int nativeLate = 0;
            int failed = 0;

            for (Map.Entry<ResourceLocation, JsonObject> entry : deferredRecipes.entrySet()) {
                ResourceLocation originalId = entry.getKey();
                try {
                    if (localCraftingRuntimeContains(originalId)) {
                        nativeLate++;
                        continue;
                    }

                    JsonObject root = entry.getValue();
                    ResourceLocation type = requiredResourceLocation(root, "type");
                    List<EmiIngredient> inputs = CraftingRecipeLoader.CRAFTING_SHAPED.equals(type)
                            ? parseShapedInputs(root)
                            : parseShapelessInputs(root);
                    EmiStack output = parseCraftingOutput(root);
                    addRecipe.accept(new SyncedCraftingEmiRecipe(
                            inputs,
                            output,
                            syncedCraftingViewerId(originalId),
                            CraftingRecipeLoader.CRAFTING_SHAPELESS.equals(type)
                    ));
                    registered++;
                } catch (Throwable throwable) {
                    failed++;
                    LOGGER.warn("Unable to register server-synced crafting recipe {} in EMI", originalId, throwable);
                }
            }

            LOGGER.warn(
                    "Dedicated crafting EMI sync applied: received={}, parsed={}, nativePresent={}, registered={}, rejected={}",
                    receivedCount,
                    parsedCount,
                    nativeAtQueue + nativeLate,
                    registered,
                    parseRejected + failed
            );
        });
    }

    public static ResourceLocation syncedCraftingViewerId(ResourceLocation originalId) {
        return new ResourceLocation(
                "cointcoregto",
                "server_sync/crafting/" + originalId.getNamespace() + "/" + originalId.getPath()
        );
    }

    public record DirectSyncResult(
            int craftingRegistered,
            int craftingNative,
            int craftingRejected,
            int gtoRegistered,
            int gtoNative,
            int gtoRejected
    ) {
    }

    public static DirectSyncResult injectSyncedRecipesIntoLiveManager() {
        EmiRecipeManager current = EmiApi.getRecipeManager();
        if (current == null) {
            throw new IllegalStateException("EMI recipe manager is unavailable");
        }

        List<EmiRecipeCategory> categories = new ArrayList<>(current.getCategories());
        Map<EmiRecipeCategory, List<EmiIngredient>> workstations = new LinkedHashMap<>();
        for (EmiRecipeCategory category : categories) {
            workstations.put(category, new ArrayList<>(current.getWorkstations(category)));
        }

        List<EmiRecipe> liveRecipes = new ArrayList<>(current.getRecipes());
        liveRecipes.removeIf(CointExchangerEmiPlugin::isSyncedViewerRecipe);

        int craftingRegistered = 0;
        int craftingNative = 0;
        int craftingRejected = 0;

        for (String json : RecipeEditorCraftingSyncState.activeJson()) {
            try {
                JsonElement element = JsonParser.parseString(json);
                if (!element.isJsonObject()) {
                    craftingRejected++;
                    continue;
                }
                JsonObject root = element.getAsJsonObject();
                if (root.has("enabled") && !root.get("enabled").getAsBoolean()) {
                    continue;
                }

                ResourceLocation originalId = requiredResourceLocation(root, "id");
                ResourceLocation type = requiredResourceLocation(root, "type");
                if (!CraftingRecipeLoader.isSupportedType(type)) {
                    craftingRejected++;
                    continue;
                }

                if (localCraftingRuntimeContains(originalId)) {
                    craftingNative++;
                    continue;
                }

                List<EmiIngredient> inputs = CraftingRecipeLoader.CRAFTING_SHAPED.equals(type)
                        ? parseShapedInputs(root)
                        : parseShapelessInputs(root);
                EmiStack output = parseCraftingOutput(root);
                liveRecipes.add(new SyncedCraftingEmiRecipe(
                        inputs,
                        output,
                        syncedCraftingViewerId(originalId),
                        CraftingRecipeLoader.CRAFTING_SHAPELESS.equals(type)
                ));
                craftingRegistered++;
            } catch (Throwable throwable) {
                craftingRejected++;
                LOGGER.warn("Unable to inject a server-synced crafting recipe into live EMI", throwable);
            }
        }

        int gtoRegistered = 0;
        int gtoNative = 0;
        int gtoRejected = 0;
        Map<ResourceLocation, JsonObject> gtoRecipes = new LinkedHashMap<>();

        List<String> syncedGto = RecipeEditorGtoSyncState.activeJson();
        for (int fileIndex = 0; fileIndex < syncedGto.size(); fileIndex++) {
            try {
                JsonElement root = JsonParser.parseString(syncedGto.get(fileIndex));
                gtoRejected += collectGtoRecipeObjects(root, gtoRecipes, "direct-sync#" + (fileIndex + 1));
            } catch (Throwable throwable) {
                gtoRejected++;
                LOGGER.warn("Unable to parse server-synced GT/GTO recipe file #{} for direct EMI injection", fileIndex + 1, throwable);
            }
        }

        for (Map.Entry<ResourceLocation, JsonObject> entry : gtoRecipes.entrySet()) {
            ResourceLocation recipeId = entry.getKey();
            JsonObject json = entry.getValue();
            try {
                ResourceLocation typeId = requiredResourceLocation(json, "type");
                Object recipeType = GtoCustomRecipeLoader.findRecipeTypeForViewer(typeId);
                if (recipeType == null) {
                    throw new IllegalStateException("GTCEu recipe type is unavailable: " + typeId);
                }

                Object nativeProbe = GtoCustomRecipeLoader.buildRecipeForViewer(
                        json,
                        "emi-direct-sync-probe:" + recipeId
                );
                if (GtoCustomRecipeLoader.isRecipeAlreadyRegisteredForViewer(recipeType, nativeProbe)) {
                    gtoNative++;
                    continue;
                }

                JsonObject viewerJson = json.deepCopy();
                viewerJson.addProperty("id", syncedGtoViewerId(recipeId).toString());
                Object gtRecipe = GtoCustomRecipeLoader.buildRecipeForViewer(
                        viewerJson,
                        "emi-direct-sync-viewer:" + recipeId
                );

                EmiRecipeCategory category = findNativeGtoCategory(typeId, recipeType);
                if (category == null) {
                    throw new IllegalStateException("Native GTCEu EMI category was not found for recipe type " + typeId);
                }

                liveRecipes.add(createNativeGtoEmiRecipe(gtRecipe, category));
                gtoRegistered++;
            } catch (Throwable throwable) {
                gtoRejected++;
                LOGGER.warn("Unable to inject server-synced GT/GTO recipe {} into live EMI", recipeId, throwable);
            }
        }

        replaceLiveRecipeManager(categories, workstations, liveRecipes);
        LOGGER.warn(
                "Direct EMI sync applied: craftingRegistered={}, craftingNative={}, craftingRejected={}, gtoRegistered={}, gtoNative={}, gtoRejected={}, totalRecipes={}",
                craftingRegistered,
                craftingNative,
                craftingRejected,
                gtoRegistered,
                gtoNative,
                gtoRejected,
                liveRecipes.size()
        );

        return new DirectSyncResult(
                craftingRegistered,
                craftingNative,
                craftingRejected,
                gtoRegistered,
                gtoNative,
                gtoRejected
        );
    }

    private static boolean isSyncedViewerRecipe(EmiRecipe recipe) {
        if (recipe == null) {
            return false;
        }
        ResourceLocation id = recipe.getId();
        if (id == null || !"cointcoregto".equals(id.getNamespace())) {
            return false;
        }
        String path = id.getPath();
        return path.startsWith("server_sync/crafting/") || path.startsWith("server_sync/gto/");
    }

    private static void replaceLiveRecipeManager(
            List<EmiRecipeCategory> categories,
            Map<EmiRecipeCategory, List<EmiIngredient>> workstations,
            List<EmiRecipe> recipes
    ) {
        try {
            ClassLoader loader = CointExchangerEmiPlugin.class.getClassLoader();
            Class<?> managerClass = Class.forName("dev.emi.emi.registry.EmiRecipes$Manager", false, loader);
            Constructor<?> constructor = managerClass.getDeclaredConstructor(
                    List.class,
                    Map.class,
                    List.class,
                    boolean.class
            );
            if (!constructor.canAccess(null) && !constructor.trySetAccessible()) {
                throw new IllegalStateException("Unable to access EMI recipe manager constructor");
            }
            Object replacement = constructor.newInstance(categories, workstations, recipes, false);

            Class<?> recipesClass = Class.forName("dev.emi.emi.registry.EmiRecipes", false, loader);
            Field managerField = recipesClass.getField("manager");
            managerField.set(null, replacement);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Unable to replace the live EMI recipe manager", throwable);
        }
    }

    private static boolean isIntegratedSingleplayer() {
        try {
            return Minecraft.getInstance().getSingleplayerServer() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean localCraftingRuntimeContains(ResourceLocation id) {
        try {
            Minecraft minecraft = Minecraft.getInstance();

            if (minecraft.level != null
                    && minecraft.level.getRecipeManager().byKey(id).isPresent()) {
                return true;
            }

            var integratedServer = minecraft.getSingleplayerServer();
            return integratedServer != null
                    && integratedServer.getRecipeManager().byKey(id).isPresent();
        } catch (Throwable throwable) {
            LOGGER.debug("Unable to check live RecipeManager for {}", id, throwable);
            return false;
        }
    }

    private static void registerSyncedGtoRecipes(EmiRegistry registry) {
        List<String> syncedJson = RecipeEditorGtoSyncState.activeJson();
        if (syncedJson.isEmpty()) {
            return;
        }

        Map<ResourceLocation, JsonObject> recipes = new LinkedHashMap<>();
        int rejected = 0;

        for (int fileIndex = 0; fileIndex < syncedJson.size(); fileIndex++) {
            String json = syncedJson.get(fileIndex);
            try {
                JsonElement root = JsonParser.parseString(json);
                rejected += collectGtoRecipeObjects(root, recipes, "server-sync#" + (fileIndex + 1));
            } catch (Throwable throwable) {
                rejected++;
                LOGGER.warn("Unable to parse server-synced GT/GTO recipe file #{} for EMI", fileIndex + 1, throwable);
            }
        }

        if (recipes.isEmpty()) {
            LOGGER.info("EMI server GT/GTO sync: files={}, parsed=0, queued=0, rejected={}",
                    syncedJson.size(), rejected);
            return;
        }


        final int fileCount = syncedJson.size();
        final int parsedCount = recipes.size();
        final int parseRejected = rejected;
        final Map<ResourceLocation, JsonObject> deferredRecipes = new LinkedHashMap<>(recipes);

        registry.addDeferredRecipes(addRecipe -> {
            int registered = 0;
            int nativePresent = 0;
            int failed = 0;

            for (Map.Entry<ResourceLocation, JsonObject> entry : deferredRecipes.entrySet()) {
                ResourceLocation recipeId = entry.getKey();
                JsonObject json = entry.getValue();
                try {
                    ResourceLocation typeId = requiredResourceLocation(json, "type");
                    Object recipeType = GtoCustomRecipeLoader.findRecipeTypeForViewer(typeId);
                    if (recipeType == null) {
                        throw new IllegalStateException("GTCEu recipe type is unavailable: " + typeId);
                    }

                    Object nativeProbe = GtoCustomRecipeLoader.buildRecipeForViewer(
                            json,
                            "emi-server-sync-probe:" + recipeId
                    );

                    if (GtoCustomRecipeLoader.isRecipeAlreadyRegisteredForViewer(recipeType, nativeProbe)) {
                        nativePresent++;
                        continue;
                    }

                    JsonObject viewerJson = json.deepCopy();
                    viewerJson.addProperty("id", syncedGtoViewerId(recipeId).toString());
                    Object gtRecipe = GtoCustomRecipeLoader.buildRecipeForViewer(
                            viewerJson,
                            "emi-server-sync-viewer:" + recipeId
                    );

                    EmiRecipeCategory category = findNativeGtoCategory(typeId, recipeType);
                    if (category == null) {
                        throw new IllegalStateException(
                                "Native GTCEu EMI category was not found for recipe type " + typeId
                        );
                    }

                    EmiRecipe nativeRecipe = createNativeGtoEmiRecipe(gtRecipe, category);
                    addRecipe.accept(nativeRecipe);
                    registered++;
                } catch (Throwable throwable) {
                    failed++;
                    LOGGER.warn(
                            "Unable to attach server-synced GT/GTO recipe {} to its native GTCEu EMI machine category",
                            recipeId,
                            throwable
                    );
                }
            }

            LOGGER.warn(
                    "Dedicated GT/GTO EMI sync applied: files={}, parsed={}, nativePresent={}, registered={}, rejected={}",
                    fileCount,
                    parsedCount,
                    nativePresent,
                    registered,
                    parseRejected + failed
            );
        });

        LOGGER.warn(
                "Dedicated GT/GTO EMI sync queued: recipes={}, files={}, rejected={}",
                recipes.size(),
                syncedJson.size(),
                rejected
        );
    }


    private static int collectGtoRecipeObjects(
            JsonElement root,
            Map<ResourceLocation, JsonObject> recipes,
            String label
    ) {
        int rejected = 0;
        if (root.isJsonArray()) {
            JsonArray array = root.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement element = array.get(i);
                if (!element.isJsonObject()) {
                    rejected++;
                    LOGGER.warn("Ignoring GT/GTO entry {}#{} because it is not an object", label, i + 1);
                    continue;
                }
                rejected += collectSingleGtoRecipe(element.getAsJsonObject(), recipes, label + "#" + (i + 1));
            }
            return rejected;
        }

        if (!root.isJsonObject()) {
            LOGGER.warn("Ignoring GT/GTO file {} because root is neither object nor array", label);
            return 1;
        }

        JsonObject object = root.getAsJsonObject();
        if (object.has("recipes")) {
            if (!object.get("recipes").isJsonArray()) {
                LOGGER.warn("Ignoring GT/GTO file {} because 'recipes' is not an array", label);
                return 1;
            }
            JsonArray array = object.getAsJsonArray("recipes");
            for (int i = 0; i < array.size(); i++) {
                JsonElement element = array.get(i);
                if (!element.isJsonObject()) {
                    rejected++;
                    continue;
                }
                rejected += collectSingleGtoRecipe(element.getAsJsonObject(), recipes, label + "#" + (i + 1));
            }
            return rejected;
        }

        return collectSingleGtoRecipe(object, recipes, label);
    }

    private static int collectSingleGtoRecipe(
            JsonObject recipe,
            Map<ResourceLocation, JsonObject> recipes,
            String label
    ) {
        try {
            if (recipe.has("enabled") && !recipe.get("enabled").getAsBoolean()) {
                return 0;
            }
            ResourceLocation id = requiredResourceLocation(recipe, "id");
            requiredResourceLocation(recipe, "type");
            recipes.put(id, recipe);
            return 0;
        } catch (Throwable throwable) {
            LOGGER.warn("Ignoring invalid GT/GTO recipe {} in EMI sync", label, throwable);
            return 1;
        }
    }


    private static ResourceLocation syncedGtoViewerId(ResourceLocation originalId) {
        return new ResourceLocation(
                "cointcoregto",
                "server_sync/gto/" + originalId.getNamespace() + "/" + originalId.getPath()
        );
    }

    private static EmiRecipeCategory findNativeGtoCategory(
            ResourceLocation typeId,
            Object recipeType
    ) throws Exception {
        Class<?> emiRecipesClass = Class.forName(
                "dev.emi.emi.registry.EmiRecipes",
                false,
                CointExchangerEmiPlugin.class.getClassLoader()
        );
        Field categoriesField = emiRecipesClass.getField("categories");
        Object rawCategories = categoriesField.get(null);
        if (!(rawCategories instanceof List<?> categories)) {
            throw new IllegalStateException("EMI category registry is unavailable");
        }


        for (Object value : categories) {
            if (value instanceof EmiRecipeCategory category && typeId.equals(category.getId())) {
                return category;
            }
        }


        for (Object value : categories) {
            if (!(value instanceof EmiRecipeCategory category)) {
                continue;
            }
            String className = category.getClass().getName();
            if (!className.contains("GTRecipe") && !className.contains("RecipeEMI")) {
                continue;
            }
            if (instanceReferences(category, recipeType)) {
                return category;
            }
        }
        return null;
    }

    private static boolean instanceReferences(Object owner, Object expected) {
        for (Class<?> type = owner.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    if (!field.canAccess(owner) && !field.trySetAccessible()) {
                        continue;
                    }
                    Object value = field.get(owner);
                    if (value == expected || (value != null && value.equals(expected))) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }


    private static EmiRecipe createNativeGtoEmiRecipe(
            Object gtRecipe,
            EmiRecipeCategory category
    ) throws Exception {
        ClassLoader loader = CointExchangerEmiPlugin.class.getClassLoader();
        String[] candidates = {
                "com.gregtechceu.gtceu.integration.recipeviewer.emi.recipe.GTEmiRecipe",
                "com.gregtechceu.gtceu.integration.emi.recipe.GTEmiRecipe"
        };

        Throwable lastFailure = null;
        for (String className : candidates) {
            Class<?> wrapperClass;
            try {
                wrapperClass = Class.forName(className, false, loader);
            } catch (ClassNotFoundException exception) {
                lastFailure = exception;
                continue;
            }

            for (Constructor<?> constructor : wrapperClass.getDeclaredConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length != 2
                        || !parameters[0].isInstance(gtRecipe)
                        || !parameters[1].isInstance(category)) {
                    continue;
                }
                if (!constructor.canAccess(null) && !constructor.trySetAccessible()) {
                    continue;
                }
                Object value = constructor.newInstance(gtRecipe, category);
                if (value instanceof EmiRecipe emiRecipe) {
                    return emiRecipe;
                }
                throw new IllegalStateException(className + " does not implement EmiRecipe");
            }

            lastFailure = new NoSuchMethodException(
                    "No compatible (GTRecipe, EmiRecipeCategory) constructor in " + className
            );
        }

        throw new IllegalStateException("GTCEu native GTEmiRecipe wrapper was not found", lastFailure);
    }

    private static List<EmiIngredient> parseShapedInputs(JsonObject root) {
        JsonArray pattern = requiredArray(root, "pattern");
        JsonObject key = requiredObject(root, "key");
        if (pattern.size() < 1 || pattern.size() > 3) {
            throw new IllegalArgumentException("Invalid crafting pattern height");
        }

        List<EmiIngredient> inputs = new ArrayList<>(Collections.nCopies(9, EmiStack.EMPTY));
        int width = -1;
        for (int y = 0; y < pattern.size(); y++) {
            String row = pattern.get(y).getAsString();
            if (row.length() < 1 || row.length() > 3) {
                throw new IllegalArgumentException("Invalid crafting pattern width");
            }
            if (width < 0) {
                width = row.length();
            } else if (width != row.length()) {
                throw new IllegalArgumentException("Crafting pattern rows have different widths");
            }

            for (int x = 0; x < row.length(); x++) {
                char symbol = row.charAt(x);
                if (symbol == ' ') {
                    continue;
                }
                JsonElement ingredient = key.get(String.valueOf(symbol));
                if (ingredient == null) {
                    throw new IllegalArgumentException("Missing crafting key symbol " + symbol);
                }
                inputs.set(y * 3 + x, parseCraftingIngredient(ingredient));
            }
        }
        return inputs;
    }

    private static List<EmiIngredient> parseShapelessInputs(JsonObject root) {
        JsonArray ingredients = requiredArray(root, "ingredients");
        if (ingredients.size() < 1 || ingredients.size() > 9) {
            throw new IllegalArgumentException("Invalid shapeless ingredient count");
        }

        List<EmiIngredient> inputs = new ArrayList<>(ingredients.size());
        for (JsonElement ingredient : ingredients) {
            inputs.add(parseCraftingIngredient(ingredient));
        }
        return inputs;
    }

    private static EmiIngredient parseCraftingIngredient(JsonElement element) {
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Crafting ingredient is not an object");
        }
        JsonObject object = element.getAsJsonObject();
        boolean hasItem = object.has("item");
        boolean hasTag = object.has("tag");
        if (hasItem == hasTag) {
            throw new IllegalArgumentException("Crafting ingredient must contain exactly item or tag");
        }

        if (hasItem) {
            ResourceLocation id = ResourceLocation.tryParse(object.get("item").getAsString());
            if (id == null) {
                throw new IllegalArgumentException("Invalid crafting item id " + object.get("item").getAsString());
            }
            Item item = ForgeRegistries.ITEMS.getValue(id);
            if (item == null) {
                throw new IllegalArgumentException("Unknown crafting item " + id);
            }
            return EmiStack.of(item);
        }

        ResourceLocation id = ResourceLocation.tryParse(object.get("tag").getAsString());
        if (id == null) {
            throw new IllegalArgumentException("Invalid crafting tag id " + object.get("tag").getAsString());
        }
        return EmiIngredient.of(TagKey.create(Registries.ITEM, id));
    }

    private static EmiStack parseCraftingOutput(JsonObject root) {
        JsonObject result = requiredObject(root, "result");
        ResourceLocation id = requiredResourceLocation(result, "item");
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null) {
            throw new IllegalArgumentException("Unknown crafting result " + id);
        }

        int count = result.has("count") ? result.get("count").getAsInt() : 1;
        if (count < 1 || count > 64) {
            throw new IllegalArgumentException("Invalid crafting result count");
        }
        return EmiStack.of(new ItemStack(item, count));
    }

    private static JsonArray requiredArray(JsonObject root, String name) {
        if (!root.has(name) || !root.get(name).isJsonArray()) {
            throw new IllegalArgumentException("Missing array " + name);
        }
        return root.getAsJsonArray(name);
    }

    private static JsonObject requiredObject(JsonObject root, String name) {
        if (!root.has(name) || !root.get(name).isJsonObject()) {
            throw new IllegalArgumentException("Missing object " + name);
        }
        return root.getAsJsonObject(name);
    }

    private static String requiredString(JsonObject root, String name) {
        if (!root.has(name)) {
            throw new IllegalArgumentException("Missing string " + name);
        }
        String value = root.get(name).getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Empty string " + name);
        }
        return value;
    }

    private static ResourceLocation requiredResourceLocation(JsonObject root, String name) {
        String value = requiredString(root, name);
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) {
            throw new IllegalArgumentException("Invalid ResourceLocation in " + name + ": " + value);
        }
        return id;
    }

    private static ItemDrop toItemDrop(EmiIngredient ingredient) {
        for (EmiStack emiStack : ingredient.getEmiStacks()) {
            ItemStack stack = emiStack.getItemStack();
            if (stack.isEmpty()) {
                continue;
            }

            ItemStack template = stack.copy();
            template.setCount(1);

            long amount = emiStack.getAmount();
            if (amount <= 0L) {
                amount = Math.max(1, stack.getCount());
            }
            return new ItemDrop(template, Math.max(1L, amount));
        }
        return null;
    }

    private static FluidDrop toFluid(EmiIngredient ingredient) {
        for (EmiStack emiStack : ingredient.getEmiStacks()) {
            Object key = emiStack.getKey();
            if (!(key instanceof Fluid fluid)) {
                continue;
            }

            ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);
            if (id == null) {
                continue;
            }


            long amount = emiStack.getAmount();
            if (amount <= 0L) {
                amount = 1000L;
            }
            return new FluidDrop(id, amount);
        }
        return null;
    }

    private static ItemStack toItemStack(EmiIngredient ingredient) {
        ItemDrop drop = toItemDrop(ingredient);
        if (drop == null) {
            return ItemStack.EMPTY;
        }

        ItemStack result = drop.stack().copy();
        int maximum = Math.max(1, Math.min(64, result.getMaxStackSize()));
        result.setCount((int) Math.min(drop.amount(), maximum));
        return result;
    }

    private record ItemDrop(ItemStack stack, long amount) {
    }

    private record FluidDrop(ResourceLocation id, long amount) {
    }
}