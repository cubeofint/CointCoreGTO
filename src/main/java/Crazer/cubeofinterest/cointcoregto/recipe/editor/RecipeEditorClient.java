package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import Crazer.cubeofinterest.cointcoregto.compat.emi.CointExchangerEmiPlugin;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class RecipeEditorClient {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:RecipeSyncClient");
    private static boolean emiDirectSyncPending;
    private static int emiDirectSyncWaitTicks;

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
                if (RecipeEditorFileService.isCraftingRecipeJson(packet.normalizedJson())) {
                    message = message + " Рецепт хранится на сервере; локальный JSON клиенту не нужен.";
                } else {
                    try {
                        RecipeEditorFileService.saveClientCopy(packet.relativePath(), packet.normalizedJson());
                        message = message + " Локальная копия: "
                                + RecipeEditorFileService.clientDisplayPath(packet.relativePath(), packet.normalizedJson());
                    } catch (Exception exception) {
                        message = message + " Сервер сохранил рецепт, но локальную копию записать не удалось: "
                                + exception.getMessage();
                    }
                }
            }

            if (minecraft.screen instanceof RecipeEditorScreen screen) {
                screen.onSaveResult(packet.success(), message, packet.relativePath());
            } else if (minecraft.screen instanceof CraftingRecipeEditorScreen screen) {
                screen.onSaveResult(packet.success(), message, packet.relativePath());
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

    public static void handleServerFilesList(RecipeEditorServerFilesListPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.screen instanceof RecipeEditorServerBrowserScreen screen) {
                screen.onList(packet);
            }
        });
    }

    public static void handleServerFileContent(RecipeEditorServerFileContentPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.screen instanceof RecipeEditorServerBrowserScreen screen) {
                screen.onContent(packet);
            }
        });
    }

    public static void handleServerFileDeleteResult(RecipeEditorServerFileDeleteResultPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (packet.success()) {
                try {
                    RecipeEditorFileService.deleteClientCopy(packet.crafting(), packet.relativePath());
                } catch (Exception exception) {
                    LOGGER.warn("Server recipe was deleted, but local mirror cleanup failed for {}",
                            packet.relativePath(),
                            exception);
                }
            }
            if (minecraft.screen instanceof RecipeEditorServerBrowserScreen screen) {
                screen.onDeleteResult(packet);
            }
        });
    }

    public static void handleCraftingSync(RecipeEditorCraftingSyncPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            switch (packet.action()) {
                case RESET -> RecipeEditorCraftingSyncState.begin();
                case ENTRY -> RecipeEditorCraftingSyncState.accept(packet.json());
                case APPLY -> {
                    RecipeEditorCraftingSyncState.apply();
                    scheduleDirectEmiSync();
                }
            }
        });
    }

    public static void handleGtoSync(RecipeEditorGtoSyncPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            switch (packet.action()) {
                case RESET -> RecipeEditorGtoSyncState.begin();
                case ENTRY -> RecipeEditorGtoSyncState.accept(packet.json());
                case APPLY -> {
                    RecipeEditorGtoSyncState.apply();
                    scheduleDirectEmiSync();
                }
            }
        });
    }

    private static void scheduleDirectEmiSync() {
        if (isIntegratedSingleplayer()) {
            emiDirectSyncPending = false;
            emiDirectSyncWaitTicks = 0;
            return;
        }

        emiDirectSyncPending = true;
        emiDirectSyncWaitTicks = 0;
    }

    private static boolean isIntegratedSingleplayer() {
        try {
            return Minecraft.getInstance().getSingleplayerServer() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void resetCraftingSyncLifecycle() {
        emiDirectSyncPending = false;
        emiDirectSyncWaitTicks = 0;
    }

    public static void tickCraftingSyncLifecycle() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            return;
        }
        if (!emiDirectSyncPending) {
            return;
        }

        emiDirectSyncWaitTicks++;
        int status = emiStatus();
        if (status == Integer.MIN_VALUE) {
            emiDirectSyncPending = false;
            return;
        }
        if (status != 2 || emiDirectSyncWaitTicks < 10) {
            return;
        }

        emiDirectSyncPending = false;
        try {
            CointExchangerEmiPlugin.injectSyncedRecipesIntoLiveManager();
        } catch (Throwable throwable) {
            LOGGER.error("Unable to apply server-synced recipes to EMI", throwable);
        }
    }

    private static int emiStatus() {
        try {
            Class<?> reloadManager = Class.forName("dev.emi.emi.runtime.EmiReloadManager");
            Object value = reloadManager.getMethod("getStatus").invoke(null);
            return value instanceof Number number ? number.intValue() : 0;
        } catch (ClassNotFoundException ignored) {
            return Integer.MIN_VALUE;
        } catch (Throwable throwable) {
            LOGGER.warn("Unable to query EMI reload status", throwable);
            return 0;
        }
    }

}
