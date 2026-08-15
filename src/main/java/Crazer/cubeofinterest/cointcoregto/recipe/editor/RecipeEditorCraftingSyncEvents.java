package Crazer.cubeofinterest.cointcoregto.recipe.editor;

/**
 * Intentionally no early PlayerLoggedInEvent sync.
 *
 * The client requests both crafting and GT/GTO recipe sets after its world and
 * RecipeManager are live. This avoids racing EMI's initial reload and avoids
 * sending the same GT/GTO recipes twice during one login.
 */
public final class RecipeEditorCraftingSyncEvents {
    private RecipeEditorCraftingSyncEvents() {
    }
}
