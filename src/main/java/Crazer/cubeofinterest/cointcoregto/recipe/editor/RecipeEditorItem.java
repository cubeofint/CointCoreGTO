package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class RecipeEditorItem extends Item {
    public RecipeEditorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
            Level level,
            Player player,
            InteractionHand hand
    ) {
        ItemStack stack = player.getItemInHand(hand);

        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            if (!canEdit(serverPlayer)) {
                serverPlayer.sendSystemMessage(
                        Component.literal("Recipe Editor доступен только в Creative или игрокам с правами OP.")
                                .withStyle(ChatFormatting.RED)
                );
                return InteractionResultHolder.fail(stack);
            }

            openEditor(serverPlayer, RecipeEditorMenu.DEFAULT_RECIPE_TYPE);
        }

        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    public static void openCraftingEditor(ServerPlayer player) {
        NetworkHooks.openScreen(
                player,
                new SimpleMenuProvider(
                        (containerId, inventory, ignored) -> new CraftingRecipeEditorMenu(containerId, inventory),
                        Component.literal("Coint Crafting Recipe Editor")
                )
        );
    }

    public static void openEditor(ServerPlayer player, ResourceLocation recipeType) {
        openEditor(player, recipeType == null ? RecipeEditorMenu.DEFAULT_RECIPE_TYPE : recipeType.toString());
    }

    public static void openEditor(ServerPlayer player, String recipeType) {
        String normalizedType = recipeType == null || recipeType.isBlank()
                ? RecipeEditorMenu.DEFAULT_RECIPE_TYPE
                : recipeType.trim();

        NetworkHooks.openScreen(
                player,
                new SimpleMenuProvider(
                        (containerId, inventory, ignored) -> new RecipeEditorMenu(
                                containerId,
                                inventory,
                                normalizedType
                        ),
                        Component.literal("Coint Recipe Editor")
                ),
                buffer -> buffer.writeUtf(normalizedType, 160)
        );
    }

    public static boolean canEdit(ServerPlayer player) {
        return player.isCreative() || player.hasPermissions(2);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.literal("Coint Recipe Editor");
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            @Nullable Level level,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        tooltip.add(Component.literal("Визуальный редактор GT/GTO рецептов")
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal("ПКМ по GT/GTO механизму: автоматически выбрать recipe type")
                .withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.literal("ПКМ по обычному верстаку: режим shaped/shapeless crafting")
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.literal("ПКМ в воздух: открыть с типом gtceu:assembler")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("JSON: config/cointcoregto/gto_recipes/editor/")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("После сохранения нужен полный перезапуск клиента и сервера")
                .withStyle(ChatFormatting.YELLOW));
    }
}
