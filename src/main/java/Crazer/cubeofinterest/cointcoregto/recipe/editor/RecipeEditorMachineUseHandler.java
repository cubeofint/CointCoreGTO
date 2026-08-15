package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
@Mod.EventBusSubscriber(modid = CointCoreGTO.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RecipeEditorMachineUseHandler {
    private RecipeEditorMachineUseHandler() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        ItemStack held = event.getItemStack();
        if (held.isEmpty() || held.getItem() != CointRecipeEditorRegistry.RECIPE_EDITOR.get()) {
            return;
        }

        if (event.getLevel().getBlockState(event.getPos()).is(Blocks.CRAFTING_TABLE)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);

            if (!event.getLevel().isClientSide && event.getEntity() instanceof ServerPlayer player) {
                if (!RecipeEditorItem.canEdit(player)) {
                    player.sendSystemMessage(RecipeEditorItem.accessDeniedMessage());
                    return;
                }
                RecipeEditorItem.openCraftingEditor(player);
                player.sendSystemMessage(
                        Component.literal("Открыт редактор обычного верстака (Shaped/Shapeless)")
                                .withStyle(ChatFormatting.AQUA)
                );
            }
            return;
        }

        ResourceLocation recipeType = RecipeEditorMachineResolver.resolveRecipeType(
                event.getLevel(),
                event.getPos()
        );
        if (recipeType == null) {
            return;
        }

        
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);

        if (event.getLevel().isClientSide) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (!RecipeEditorItem.canEdit(player)) {
            player.sendSystemMessage(RecipeEditorItem.accessDeniedMessage());
            return;
        }

        RecipeEditorItem.openEditor(player, recipeType);
        player.sendSystemMessage(
                Component.literal("Recipe type выбран по механизму: " + recipeType)
                        .withStyle(ChatFormatting.AQUA)
        );
    }
}