package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.Set;

public final class RecipeEditorClient {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:RecipeSyncClient");
    private static final int MAX_EMI_RELOAD_ATTEMPTS = 3;

    private static boolean emiReloadPending;
    private static int emiReloadWaitTicks;
    private static int emiReloadAttempts;
    private static boolean emiVerificationPending;
    private static int emiVerificationTicks;

    private static int lastSyncedCraftingCount;
    private static int lastSyncedGtoFileCount;

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
                    lastSyncedCraftingCount = RecipeEditorCraftingSyncState.apply();
                    LOGGER.info("Received {} server crafting recipe JSON files", lastSyncedCraftingCount);
                    scheduleEmiReload();
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
                    lastSyncedGtoFileCount = RecipeEditorGtoSyncState.apply();


                    LOGGER.info(
                            "Received {} server GT/GTO recipe JSON files; they will be attached to native GTCEu EMI categories",
                            lastSyncedGtoFileCount
                    );
                    scheduleEmiReload();
                }
            }
        });
    }

    private static void scheduleEmiReload() {


        if (isIntegratedSingleplayer()) {
            emiReloadPending = false;
            emiReloadWaitTicks = 0;
            emiVerificationPending = false;
            emiVerificationTicks = 0;
            LOGGER.info("Integrated server detected; skipping forced EMI server-recipe reload");
            return;
        }

        emiReloadPending = true;
        emiReloadWaitTicks = 0;
        emiVerificationPending = false;
        emiVerificationTicks = 0;
    }

    private static boolean isIntegratedSingleplayer() {
        try {
            return Minecraft.getInstance().getSingleplayerServer() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void resetCraftingSyncLifecycle() {
        emiReloadPending = false;
        emiReloadWaitTicks = 0;
        emiReloadAttempts = 0;
        emiVerificationPending = false;
        emiVerificationTicks = 0;
        lastSyncedCraftingCount = 0;
        lastSyncedGtoFileCount = 0;
    }


    public static void tickCraftingSyncLifecycle() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            return;
        }

        if (emiReloadPending) {
            emiReloadWaitTicks++;
            int status = emiStatus();


            if (status == 1) {
                return;
            }

            if (status == 2 || emiReloadWaitTicks >= 60) {
                emiReloadPending = false;
                emiReloadAttempts++;
                LOGGER.info(
                        "Reloading EMI after server recipe sync: craftingFiles={}, gtoFiles={}, attempt={}, status={}",
                        lastSyncedCraftingCount,
                        lastSyncedGtoFileCount,
                        emiReloadAttempts,
                        status
                );
                reloadEmi();
                emiVerificationPending = true;
                emiVerificationTicks = 0;
            }
            return;
        }

        if (!emiVerificationPending) {
            return;
        }

        emiVerificationTicks++;
        int status = emiStatus();
        if (status == 1 || emiVerificationTicks < 5) {
            return;
        }

        if (status == Integer.MIN_VALUE) {

            emiVerificationPending = false;
            return;
        }

        if (status != 2) {
            if (emiReloadAttempts < MAX_EMI_RELOAD_ATTEMPTS && emiVerificationTicks >= 60) {
                LOGGER.warn("EMI did not reach loaded state after recipe sync (status={}); retrying", status);
                emiVerificationPending = false;
                emiReloadPending = true;
                emiReloadWaitTicks = 0;
            }
            return;
        }

        Set<ResourceLocation> expected = RecipeEditorCraftingSyncState.shadowedRecipeIds();
        int present = countCraftingRecipesPresentInEmi(expected);
        LOGGER.info(
                "EMI sync verification: crafting={}/{}, GT/GTO synced files={}",
                present,
                expected.size(),
                lastSyncedGtoFileCount
        );

        if (present < expected.size() && emiReloadAttempts < MAX_EMI_RELOAD_ATTEMPTS) {
            LOGGER.warn(
                    "EMI is missing {} synced crafting recipes after reload; retrying",
                    expected.size() - present
            );
            emiVerificationPending = false;
            emiReloadPending = true;
            emiReloadWaitTicks = 0;
            return;
        }

        emiVerificationPending = false;
    }

    private static int countCraftingRecipesPresentInEmi(Set<ResourceLocation> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        try {
            Class<?> apiClass = Class.forName("dev.emi.emi.api.EmiApi");
            Object recipeManager = apiClass.getMethod("getRecipeManager").invoke(null);
            if (recipeManager == null) {
                return 0;
            }
            Class<?> managerInterface = Class.forName("dev.emi.emi.api.recipe.EmiRecipeManager");
            Method getRecipe = managerInterface.getMethod("getRecipe", ResourceLocation.class);
            int present = 0;
            for (ResourceLocation id : ids) {
                Object recipe = getRecipe.invoke(recipeManager, id);
                if (recipe != null) {
                    present++;
                }
            }
            return present;
        } catch (ClassNotFoundException ignored) {
            return 0;
        } catch (Throwable throwable) {
            LOGGER.warn("Unable to verify synced crafting recipes in EMI", throwable);
            return 0;
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

        } catch (Throwable throwable) {
            LOGGER.error("Unable to reload EMI after server recipe sync", throwable);
        }
    }
}
