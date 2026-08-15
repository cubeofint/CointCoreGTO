package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class RecipeEditorClient {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:RecipeSyncClient");

    private static boolean emiReloadPending;
    private static int emiReloadWaitTicks;
    private static int lastSyncedCraftingCount;

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

    public static void handleCraftingSync(RecipeEditorCraftingSyncPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            switch (packet.action()) {
                case RESET -> RecipeEditorCraftingSyncState.begin();
                case ENTRY -> RecipeEditorCraftingSyncState.accept(packet.json());
                case APPLY -> {
                    lastSyncedCraftingCount = RecipeEditorCraftingSyncState.apply();
                    emiReloadPending = true;
                    emiReloadWaitTicks = 0;
                    LOGGER.info("Received {} server crafting recipe JSON files; waiting for EMI to become ready",
                            lastSyncedCraftingCount);
                }
            }
        });
    }

    public static void resetCraftingSyncLifecycle() {
        emiReloadPending = false;
        emiReloadWaitTicks = 0;
        lastSyncedCraftingCount = 0;
    }

    /** Called from the client tick event after the player/world exist. */
    public static void tickCraftingSyncLifecycle() {
        if (!emiReloadPending) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            return;
        }

        emiReloadWaitTicks++;
        int status = emiStatus();

        // EMI status: 2 = loaded, 1 = loading, 0 = not started, -1 = failed.
        // If it is currently loading, let that reload finish first and then run one
        // authoritative reload using the server-synced recipe state.
        if (status == 1) {
            return;
        }

        if (status == 2 || emiReloadWaitTicks >= 40) {
            emiReloadPending = false;
            LOGGER.info("Reloading EMI with {} server crafting recipes (EMI status={})",
                    lastSyncedCraftingCount,
                    status);
            reloadEmi();
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

    private static void reloadEmi() {
        try {
            Class<?> reloadManager = Class.forName("dev.emi.emi.runtime.EmiReloadManager");
            reloadManager.getMethod("reload").invoke(null);
        } catch (ClassNotFoundException ignored) {
            // EMI is optional.
        } catch (Throwable throwable) {
            LOGGER.error("Unable to reload EMI after server crafting sync", throwable);
        }
    }
}
