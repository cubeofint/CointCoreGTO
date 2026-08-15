package Crazer.cubeofinterest.cointcoregto.compat.emi;

import Crazer.cubeofinterest.cointcoregto.exchanger.ExchangerBlockEntity;
import Crazer.cubeofinterest.cointcoregto.exchanger.ExchangerScreen;
import Crazer.cubeofinterest.cointcoregto.recipe.CraftingRecipeLoader;
import Crazer.cubeofinterest.cointcoregto.recipe.editor.CraftingRecipeEditorMenu;
import Crazer.cubeofinterest.cointcoregto.recipe.editor.CraftingRecipeEditorScreen;
import Crazer.cubeofinterest.cointcoregto.recipe.editor.RecipeEditorCraftingSyncState;
import Crazer.cubeofinterest.cointcoregto.recipe.editor.RecipeEditorMenu;
import Crazer.cubeofinterest.cointcoregto.recipe.editor.RecipeEditorScreen;
import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiCraftingRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@EmiEntrypoint
public final class CointExchangerEmiPlugin implements EmiPlugin {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:EMI");

    @Override
    public void register(EmiRegistry registry) {
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

        registerSyncedCraftingRecipes(registry);
    }

    private static void registerSyncedCraftingRecipes(EmiRegistry registry) {
        Set<ResourceLocation> shadowed = new LinkedHashSet<>(CraftingRecipeLoader.discoverConfiguredRecipeIds());
        shadowed.addAll(RecipeEditorCraftingSyncState.shadowedRecipeIds());
        for (ResourceLocation id : shadowed) {
            registry.removeRecipes(id);
        }

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

        int registered = 0;
        for (Map.Entry<ResourceLocation, JsonObject> entry : recipes.entrySet()) {
            try {
                JsonObject root = entry.getValue();
                ResourceLocation type = requiredResourceLocation(root, "type");
                List<EmiIngredient> inputs = CraftingRecipeLoader.CRAFTING_SHAPED.equals(type)
                        ? parseShapedInputs(root)
                        : parseShapelessInputs(root);
                EmiStack output = parseCraftingOutput(root);
                registry.addRecipe(new EmiCraftingRecipe(
                        inputs,
                        output,
                        entry.getKey(),
                        CraftingRecipeLoader.CRAFTING_SHAPELESS.equals(type)
                ));
                registered++;
            } catch (Throwable throwable) {
                rejected++;
                LOGGER.warn("Unable to register server-synced crafting recipe {} in EMI", entry.getKey(), throwable);
            }
        }

        LOGGER.info("EMI server crafting sync: received={}, parsed={}, registered={}, rejected={}",
                syncedJson.size(), recipes.size(), registered, rejected);
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