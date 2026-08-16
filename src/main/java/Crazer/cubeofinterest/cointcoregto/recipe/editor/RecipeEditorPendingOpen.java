package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CointCoreGTO.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecipeEditorPendingOpen {
    private static boolean pending;
    private static boolean crafting;
    private static String relativePath = "";
    private static String json = "";

    private RecipeEditorPendingOpen() {
    }

    public static void queue(boolean craftingMode, String path, String rawJson) {
        pending = true;
        crafting = craftingMode;
        relativePath = path == null ? "" : path;
        json = rawJson == null ? "" : rawJson;
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!pending) {
            return;
        }

        Screen screen = event.getScreen();
        if (crafting && screen instanceof CraftingRecipeEditorScreen craftingScreen) {
            pending = false;
            craftingScreen.loadServerRecipe(relativePath, json);
        } else if (!crafting && screen instanceof RecipeEditorScreen gtoScreen) {
            pending = false;
            gtoScreen.loadServerRecipe(relativePath, json);
        }

        if (!pending) {
            relativePath = "";
            json = "";
        }
    }
}
