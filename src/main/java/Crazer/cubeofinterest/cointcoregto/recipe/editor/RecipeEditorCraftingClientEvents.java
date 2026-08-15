package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import Crazer.cubeofinterest.cointcoregto.recipe.GtoCustomRecipeLoader;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client-side lifecycle for server-owned crafting recipes. */
@Mod.EventBusSubscriber(modid = CointCoreGTO.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecipeEditorCraftingClientEvents {
    private static final int REQUEST_DELAY_TICKS = 20;

    private static int requestTicks = -1;
    private static boolean requested;

    private RecipeEditorCraftingClientEvents() {
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // Do not rely only on the server's very early PlayerLoggedInEvent packet.
        // Request the authoritative list again after the client has a live world.
        RecipeEditorCraftingSyncState.clear();
        RecipeEditorGtoSyncState.clear();
        GtoCustomRecipeLoader.clearClientSyncedRecipes();
        RecipeEditorClient.resetCraftingSyncLifecycle();
        requestTicks = REQUEST_DELAY_TICKS;
        requested = false;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        RecipeEditorCraftingSyncState.clear();
        RecipeEditorGtoSyncState.clear();
        GtoCustomRecipeLoader.clearClientSyncedRecipes();
        RecipeEditorClient.resetCraftingSyncLifecycle();
        requestTicks = -1;
        requested = false;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            return;
        }

        if (!requested && requestTicks >= 0) {
            if (requestTicks > 0) {
                requestTicks--;
            } else {
                RecipeEditorNetwork.CHANNEL.sendToServer(new RecipeEditorCraftingSyncRequestPacket());
                requested = true;
                requestTicks = -1;
            }
        }

        RecipeEditorClient.tickCraftingSyncLifecycle();
    }
}
