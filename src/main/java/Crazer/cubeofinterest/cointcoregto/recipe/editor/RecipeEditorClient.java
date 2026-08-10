package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;

public final class RecipeEditorClient {
    private RecipeEditorClient() {
    }

    public static void registerScreens() {
        MenuScreens.register(
                CointRecipeEditorRegistry.RECIPE_EDITOR_MENU.get(),
                RecipeEditorScreen::new
        );
        MenuScreens.register(
                CointRecipeEditorRegistry.CRAFTING_RECIPE_EDITOR_MENU.get(),
                CraftingRecipeEditorScreen::new
        );
    }

    public static void handleSaveResult(RecipeEditorSaveResultPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            String message = packet.message();

            if (packet.success()) {
                try {
                    RecipeEditorFileService.saveClientCopy(packet.relativePath(), packet.normalizedJson());
                    message = message + " Локальная копия: "
                            + RecipeEditorFileService.clientDisplayPath(packet.relativePath(), packet.normalizedJson());
                } catch (Exception exception) {
                    message = message + " Сервер сохранил рецепт, но локальную копию записать не удалось: "
                            + exception.getMessage();
                }
            }

            if (minecraft.screen instanceof RecipeEditorScreen screen) {
                screen.onSaveResult(packet.success(), message);
            } else if (minecraft.screen instanceof CraftingRecipeEditorScreen screen) {
                screen.onSaveResult(packet.success(), message);
            }

            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(
                        Component.literal(message).withStyle(packet.success()
                                ? ChatFormatting.GREEN
                                : ChatFormatting.RED)
                );
            }
        });
    }
}