package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import Crazer.cubeofinterest.cointcoregto.recipe.CraftingRecipeLoader;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RecipeEditorCraftingSyncState {
    private static final List<String> PENDING = new ArrayList<>();
    private static List<String> active = List.of();
    private static final Set<ResourceLocation> SHADOWED_IDS = new LinkedHashSet<>();

    private RecipeEditorCraftingSyncState() {
    }

    public static synchronized void clear() {
        PENDING.clear();
        active = List.of();
        SHADOWED_IDS.clear();
    }

    public static synchronized void begin() {
        PENDING.clear();
    }

    public static synchronized void accept(String json) {
        if (json == null || json.isBlank()) {
            return;
        }
        PENDING.add(json);
    }

    public static synchronized int apply() {
        active = List.copyOf(PENDING);
        PENDING.clear();
        SHADOWED_IDS.clear();

        for (String json : active) {
            ResourceLocation id = readRecipeId(json);
            if (id != null) {
                SHADOWED_IDS.add(id);
            }
        }
        return active.size();
    }

    public static synchronized List<String> activeJson() {
        return List.copyOf(active);
    }

    public static synchronized Set<ResourceLocation> shadowedRecipeIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(SHADOWED_IDS));
    }

    private static ResourceLocation readRecipeId(String json) {
        try {
            JsonElement element = JsonParser.parseString(json);
            if (!element.isJsonObject()) {
                return null;
            }
            JsonObject root = element.getAsJsonObject();
            if (root.has("enabled") && !root.get("enabled").getAsBoolean()) {
                return null;
            }
            if (!root.has("id") || !root.has("type")) {
                return null;
            }
            ResourceLocation type = ResourceLocation.tryParse(root.get("type").getAsString());
            ResourceLocation id = ResourceLocation.tryParse(root.get("id").getAsString());
            if (type == null || id == null || !CraftingRecipeLoader.isSupportedType(type)) {
                return null;
            }
            return id;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
