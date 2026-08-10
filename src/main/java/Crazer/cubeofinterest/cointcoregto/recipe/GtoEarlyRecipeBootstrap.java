package Crazer.cubeofinterest.cointcoregto.recipe;

import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLModContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.function.Consumer;

public final class GtoEarlyRecipeBootstrap {
    private static final Logger LOGGER = LogManager.getLogger("CointCoreGTO:GTORecipeBootstrap");

    private static boolean busHookInstallAttempted;
    private static boolean gtoCommonSetupSeen;
    private static boolean buildCallbackInstalled;
    private static boolean customRecipesLoadAttempted;

    private GtoEarlyRecipeBootstrap() {
    }
    public static synchronized void installGtoCorePreCommonSetupHook() {
        if (busHookInstallAttempted) {
            return;
        }
        busHookInstallAttempted = true;

        try {
            Optional<? extends ModContainer> optionalContainer =
                    ModList.get().getModContainerById("gtocore");

            if (optionalContainer.isEmpty()) {
                LOGGER.error("[GTO-RECIPES] GTOCore ModContainer was not found; custom recipes are disabled");
                return;
            }

            ModContainer container = optionalContainer.get();
            if (!(container instanceof FMLModContainer fmlContainer)) {
                LOGGER.error(
                        "[GTO-RECIPES] GTOCore container is not an FMLModContainer: {}",
                        container.getClass().getName()
                );
                return;
            }

            IEventBus gtoCoreModBus = fmlContainer.getEventBus();
            gtoCoreModBus.addListener(
                    EventPriority.HIGHEST,
                    GtoEarlyRecipeBootstrap::beforeGtoCoreCommonSetup
            );

            LOGGER.info("[GTO-RECIPES] Early GTOCore recipe hook installed");
        } catch (Throwable throwable) {
            LOGGER.error("[GTO-RECIPES] Failed to install early GTOCore recipe hook", throwable);
        }
    }

    private static synchronized void beforeGtoCoreCommonSetup(FMLCommonSetupEvent event) {
        if (gtoCommonSetupSeen) {
            return;
        }
        gtoCommonSetupSeen = true;

        try {
            installAssemblerBuildCallbackByField();
            buildCallbackInstalled = true;
            LOGGER.info("[GTO-RECIPES] Recipe generation callback installed before GTOCore Data.init()");
        } catch (Throwable throwable) {
            LOGGER.error("[GTO-RECIPES] Failed to install recipe generation callback", throwable);
        }
    }

    @SuppressWarnings("unchecked")
    private static void installAssemblerBuildCallbackByField() throws Exception {
        ClassLoader loader = GtoEarlyRecipeBootstrap.class.getClassLoader();

        Class<?> gtRecipeTypesClass = Class.forName(
                "com.gregtechceu.gtceu.common.data.GTRecipeTypes",
                true,
                loader
        );

        Field assemblerField = gtRecipeTypesClass.getField("ASSEMBLER_RECIPES");
        Object assemblerRecipeType = assemblerField.get(null);
        if (assemblerRecipeType == null) {
            throw new IllegalStateException("GTRecipeTypes.ASSEMBLER_RECIPES is null");
        }

        Class<?> gtRecipeTypeClass = Class.forName(
                "com.gregtechceu.gtceu.api.recipe.GTRecipeType",
                false,
                loader
        );

        Field recipeBuilderField = gtRecipeTypeClass.getDeclaredField("recipeBuilder");
        recipeBuilderField.setAccessible(true);

        Object templateBuilder = recipeBuilderField.get(assemblerRecipeType);
        if (templateBuilder == null) {
            throw new IllegalStateException("Assembler recipeBuilder template is null");
        }

        Class<?> gtRecipeBuilderClass = Class.forName(
                "com.gregtechceu.gtceu.data.recipe.builder.GTRecipeBuilder",
                false,
                loader
        );

        Field onSaveField = gtRecipeBuilderClass.getField("onSave");
        Object previousOnSaveObject = onSaveField.get(templateBuilder);

        final Consumer<Object> previousOnSave;
        if (previousOnSaveObject == null) {
            previousOnSave = null;
        } else if (previousOnSaveObject instanceof Consumer<?>) {
            previousOnSave = (Consumer<Object>) previousOnSaveObject;
        } else {
            throw new IllegalStateException(
                    "Existing assembler onSave is not a Consumer: " + previousOnSaveObject.getClass().getName()
            );
        }

        Consumer<Object> chainedCallback = triggerBuilder -> {
            // Never replace GTOCore's own assembler handling.
            if (previousOnSave != null) {
                previousOnSave.accept(triggerBuilder);
            }

            if (customRecipesLoadAttempted) {
                return;
            }
            customRecipesLoadAttempted = true;

            try {
                GtoCustomRecipeLoader.LoadResult result = GtoCustomRecipeLoader.loadAndRegisterAll();
                LOGGER.warn(
                        "[GTO-RECIPES] Custom recipe load complete: loaded={}, skipped={}, failed={}, files={}",
                        result.loaded(),
                        result.skipped(),
                        result.failed(),
                        result.files()
                );
            } catch (Throwable throwable) {
                // One broken custom recipe set must not take down GTO's stock recipe load.
                LOGGER.error("[GTO-RECIPES] Custom recipe loader failed", throwable);
            }
        };

        onSaveField.set(templateBuilder, chainedCallback);

        if (onSaveField.get(templateBuilder) == null) {
            throw new IllegalStateException("Assembler template onSave remained null after callback installation");
        }
    }

    public static synchronized boolean isBuildCallbackInstalled() {
        return buildCallbackInstalled;
    }
}
