package Crazer.cubeofinterest.cointcoregto.mixin;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
@Pseudo
@Mixin(targets = "com.fast.fastcollection.OpenCacheHashSet", remap = false)
public abstract class GtoRecipeLifecycleMixin {
    private static final Logger COINT_GTO_RECIPE_LOGGER =
            LogManager.getLogger("CointCoreGTO:GTORecipeHook");

    private static boolean cointcoregto$recipeAttempted;
    private static boolean cointcoregto$recipeRegistered;

    @Inject(
            method = "clear",
            at = @At("HEAD"),
            remap = false,
            require = 0
    )
    private void cointcoregto$beforeOpenCacheHashSetClear(CallbackInfo ci) {
        if (cointcoregto$recipeAttempted) {
            return;
        }

        Object self = this;

        try {
            if (!cointcoregto$isGtoDataCommonInitOnStack()) {
                return;
            }

            if (!cointcoregto$isGenerateDisassemblySet(self)) {
                return;
            }

            cointcoregto$recipeAttempted = true;

            COINT_GTO_RECIPE_LOGGER.info("[GTO-RECIPE] ===== SAFE EARLY RECIPE HOOK START =====");
            COINT_GTO_RECIPE_LOGGER.info("[GTO-RECIPE] Hook point: GenerateDisassembly set clear via OpenCacheHashSet.clear()");
            COINT_GTO_RECIPE_LOGGER.info("[GTO-RECIPE] Protected GTO/GTCEu recipe classes are NOT mixin targets");

            registerAssemblerTestRecipe();
            cointcoregto$recipeRegistered = true;

            COINT_GTO_RECIPE_LOGGER.info("[GTO-RECIPE] Registration call completed");
            COINT_GTO_RECIPE_LOGGER.info("[GTO-RECIPE] ===== SAFE EARLY RECIPE HOOK END =====");
        } catch (Throwable throwable) {
            // Never interrupt the collection clear or GTO data initialization.
            COINT_GTO_RECIPE_LOGGER.error(
                    "[GTO-RECIPE] Early recipe registration failed; continuing GTO startup",
                    throwable
            );
        }
    }

    private static boolean cointcoregto$isGtoDataCommonInitOnStack() {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            if ("com.gtocore.data.Data".equals(element.getClassName())
                    && "commonInit".equals(element.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean cointcoregto$isGenerateDisassemblySet(Object candidate) {
        try {
            Class<?> generateDisassembly = Class.forName(
                    "com.gtocore.data.recipe.generated.GenerateDisassembly",
                    false,
                    GtoRecipeLifecycleMixin.class.getClassLoader()
            );

            Field recordField = generateDisassembly.getField("DISASSEMBLY_RECORD");
            Field blacklistField = generateDisassembly.getField("DISASSEMBLY_BLACKLIST");

            Object record = recordField.get(null);
            Object blacklist = blacklistField.get(null);

            return candidate == record || candidate == blacklist;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void registerAssemblerTestRecipe() throws Exception {
        ClassLoader loader = GtoRecipeLifecycleMixin.class.getClassLoader();

        Class<?> gtRecipeTypesClass = Class.forName(
                "com.gregtechceu.gtceu.common.data.GTRecipeTypes",
                true,
                loader
        );
        Field assemblerField = gtRecipeTypesClass.getField("ASSEMBLER_RECIPES");
        Object assemblerRecipeType = assemblerField.get(null);

        COINT_GTO_RECIPE_LOGGER.info(
                "[GTO-RECIPE] Recipe type: {} ({})",
                assemblerRecipeType,
                assemblerRecipeType.getClass().getName()
        );

        Method builderMethod = findExactMethod(
                assemblerRecipeType.getClass(),
                "builder",
                String.class,
                Object[].class
        );

        Object builder = builderMethod.invoke(
                assemblerRecipeType,
                "cointcoregto_iron_to_diamond_test",
                new Object[0]
        );

        COINT_GTO_RECIPE_LOGGER.info(
                "[GTO-RECIPE] Builder: {}",
                builder.getClass().getName()
        );

        Method inputItems = findExactMethod(
                builder.getClass(),
                "inputItems",
                Item.class,
                int.class
        );
        Method outputItems = findExactMethod(
                builder.getClass(),
                "outputItems",
                Item.class,
                int.class
        );
        Method duration = findExactMethod(
                builder.getClass(),
                "duration",
                int.class
        );
        Method eut = findExactMethod(
                builder.getClass(),
                "EUt",
                long.class
        );
        Method save = findExactMethod(
                builder.getClass(),
                "save"
        );

        inputItems.invoke(builder, Items.IRON_INGOT, 1);
        outputItems.invoke(builder, Items.DIAMOND, 1);
        duration.invoke(builder, 200);
        eut.invoke(builder, 16L);

        COINT_GTO_RECIPE_LOGGER.info(
                "[GTO-RECIPE] Calling save() inside GTO recipe initialization window"
        );

        Object savedRecipe = save.invoke(builder);

        COINT_GTO_RECIPE_LOGGER.info(
                "[GTO-RECIPE] save() returned: {}",
                savedRecipe
        );

        if (savedRecipe != null) {
            COINT_GTO_RECIPE_LOGGER.info(
                    "[GTO-RECIPE] Saved recipe class: {}",
                    savedRecipe.getClass().getName()
            );

            try {
                Field idField = savedRecipe.getClass().getField("id");
                COINT_GTO_RECIPE_LOGGER.info(
                        "[GTO-RECIPE] Saved recipe id: {}",
                        idField.get(savedRecipe)
                );
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    private static Method findExactMethod(
            Class<?> owner,
            String methodName,
            Class<?>... parameterTypes
    ) throws NoSuchMethodException {
        Method bridgeFallback = null;

        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            if (!Arrays.equals(method.getParameterTypes(), parameterTypes)) {
                continue;
            }

            if (!method.isBridge()) {
                return method;
            }
            bridgeFallback = method;
        }

        if (bridgeFallback != null) {
            return bridgeFallback;
        }

        throw new NoSuchMethodException(
                owner.getName() + "." + methodName + Arrays.toString(parameterTypes)
        );
    }
}
