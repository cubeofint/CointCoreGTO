package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import java.util.ArrayList;
import java.util.List;

/** Client-side staging area for server-owned GT/GTO recipe JSON files. */
public final class RecipeEditorGtoSyncState {
    private static final List<String> PENDING = new ArrayList<>();
    private static List<String> active = List.of();

    private RecipeEditorGtoSyncState() {
    }

    public static synchronized void clear() {
        PENDING.clear();
        active = List.of();
    }

    public static synchronized void begin() {
        PENDING.clear();
    }

    public static synchronized void accept(String json) {
        if (json != null && !json.isBlank()) {
            PENDING.add(json);
        }
    }

    public static synchronized int apply() {
        active = List.copyOf(PENDING);
        PENDING.clear();
        return active.size();
    }

    public static synchronized List<String> activeJson() {
        return List.copyOf(active);
    }
}
