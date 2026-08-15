package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CointCoreGTO.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecipeEditorCraftingSyncEvents {
    private RecipeEditorCraftingSyncEvents() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            RecipeEditorCraftingSyncService.sendTo(player);
        }
    }
}
