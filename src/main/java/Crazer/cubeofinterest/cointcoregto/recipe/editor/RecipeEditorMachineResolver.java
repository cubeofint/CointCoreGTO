package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
public final class RecipeEditorMachineResolver {
    private RecipeEditorMachineResolver() {
    }

    public static ResourceLocation resolveRecipeType(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }

        try {
            ClassLoader loader = RecipeEditorMachineResolver.class.getClassLoader();

            Class<?> metaMachineClass = Class.forName(
                    "com.gregtechceu.gtceu.api.machine.MetaMachine",
                    false,
                    loader
            );
            Method getMachine = metaMachineClass.getMethod("getMachine", BlockGetter.class, BlockPos.class);
            Object machine = getMachine.invoke(null, level, pos);
            if (machine == null) {
                return null;
            }

            Class<?> recipeLogicMachineClass = Class.forName(
                    "com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine",
                    false,
                    loader
            );
            if (!recipeLogicMachineClass.isInstance(machine)) {
                return null;
            }

            Class<?> dummyMachineClass = Class.forName(
                    "com.gtolib.api.machine.DummyMachine",
                    false,
                    loader
            );
            Method createDummy = findCompatibleStaticOneArgMethod(
                    dummyMachineClass,
                    "createDummyMachine",
                    machine.getClass()
            );
            Object dummyMachine = createDummy.invoke(null, machine);
            if (dummyMachine == null) {
                return null;
            }

            
            Field recipeTypeField = dummyMachineClass.getField("recipeType");
            Object recipeType = recipeTypeField.get(dummyMachine);
            if (recipeType == null) {
                return null;
            }

            
            
            Field registryNameField = recipeType.getClass().getField("registryName");
            Object registryName = registryNameField.get(recipeType);
            return registryName instanceof ResourceLocation id ? id : null;
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static Method findCompatibleStaticOneArgMethod(
            Class<?> owner,
            String name,
            Class<?> argumentClass
    ) throws NoSuchMethodException {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name)
                    || method.getParameterCount() != 1
                    || !Modifier.isStatic(method.getModifiers())) {
                continue;
            }

            Class<?> parameter = method.getParameterTypes()[0];
            if (parameter.isAssignableFrom(argumentClass)) {
                return method;
            }
        }

        throw new NoSuchMethodException(
                "No compatible static " + owner.getName() + "." + name
                        + "(" + argumentClass.getName() + ")"
        );
    }
}