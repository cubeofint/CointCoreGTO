package Crazer.cubeofinterest.cointcoregto.coremod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime hook for the DimensionCondition Forge CoreMod.
 *
 * Supports both:
 * - GTOCore 0.5.5 / GTCEu 1.8.0 (RecipeLogic -> machine -> self())
 * - GTOCore 0.5.6+ / GTCEu 26.x (IRecipeHandlerHolder -> self())
 *
 * Deliberately has no compile-time dependency on GTCEu, GTOCore or Minecraft.
 */
public final class DimensionConditionCoremodHook {
    private static volatile Field dimensionField;
    private static volatile Field machineField;
    private static volatile Method contextSelfMethod;
    private static volatile Method machineSelfMethod;
    private static volatile Method getLevelMethod;
    private static volatile Method levelDimensionMethod;

    private static final AtomicBoolean aliasLogged = new AtomicBoolean();
    private static final AtomicBoolean oldApiLogged = new AtomicBoolean();
    private static final AtomicBoolean newApiLogged = new AtomicBoolean();
    private static final AtomicBoolean errorLogged = new AtomicBoolean();

    private DimensionConditionCoremodHook() {}

    public static boolean testCondition(Object condition, Object context) {
        try {
            if (condition == null || context == null) return false;

            Field df = dimensionField;
            if (df == null || !df.getDeclaringClass().isAssignableFrom(condition.getClass())) {
                df = condition.getClass().getField("dimension");
                dimensionField = df;
            }
            Object expected = df.get(condition);
            if (expected == null) return false;

            Object metaMachine = resolveMetaMachine(context);
            if (metaMachine == null) return false;

            Method glm = getLevelMethod;
            if (glm == null || !glm.getDeclaringClass().isAssignableFrom(metaMachine.getClass())) {
                glm = metaMachine.getClass().getMethod("getLevel");
                getLevelMethod = glm;
            }
            Object level = glm.invoke(metaMachine);
            if (level == null) return false;

            Method ldm = levelDimensionMethod;
            if (ldm == null || !ldm.getDeclaringClass().isAssignableFrom(level.getClass())) {
                try {
                    ldm = level.getClass().getMethod("m_46472_");
                } catch (NoSuchMethodException ignored) {
                    ldm = level.getClass().getMethod("dimension");
                }
                levelDimensionMethod = ldm;
            }
            Object actual = ldm.invoke(level);
            if (actual == null) return false;

            // Preserve the original DimensionCondition semantics first: ResourceKey identity equality.
            if (expected == actual) return true;

            String expectedText = String.valueOf(expected);
            if (!expectedText.contains("minecraft:overworld")) return false;

            String actualText = String.valueOf(actual);
            boolean personalSpace = actualText.contains("personalspace:personal_space_dimensions/");
            if (personalSpace && aliasLogged.compareAndSet(false, true)) {
                System.err.println(
                        "[CointCoreGTO FMLCoremod] SUCCESS: PersonalSpace accepted as minecraft:overworld "
                                + "in DimensionCondition; actual=" + actualText
                );
            }
            return personalSpace;
        } catch (Throwable t) {
            if (errorLogged.compareAndSet(false, true)) {
                System.err.println("[CointCoreGTO FMLCoremod] DimensionCondition hook failed: " + t);
                t.printStackTrace(System.err);
            }
            return false;
        }
    }

    private static Object resolveMetaMachine(Object context) throws Exception {
        // GTOCore 0.5.6+ / GTCEu 26.x: IRecipeHandlerHolder itself is an IMachineFeature.
        // It exposes self() -> MetaMachine directly.
        try {
            Method self = contextSelfMethod;
            if (self == null || !self.getDeclaringClass().isAssignableFrom(context.getClass())) {
                self = context.getClass().getMethod("self");
                contextSelfMethod = self;
            }
            Object metaMachine = self.invoke(context);
            if (metaMachine != null) {
                if (newApiLogged.compareAndSet(false, true)) {
                    System.err.println("[CointCoreGTO FMLCoremod] DimensionCondition runtime API: GTCEu 26.x holder/self");
                }
                return metaMachine;
            }
        } catch (NoSuchMethodException ignored) {
            // Expected on GTCEu 1.8.0 RecipeLogic; fall through to the legacy path.
        }

        // GTOCore 0.5.5 / GTCEu 1.8.0: RecipeLogic has a public 'machine' field;
        // that machine feature exposes self() -> MetaMachine.
        Field mf = machineField;
        if (mf == null || !mf.getDeclaringClass().isAssignableFrom(context.getClass())) {
            try {
                mf = context.getClass().getField("machine");
            } catch (NoSuchFieldException publicMissing) {
                mf = findField(context.getClass(), "machine");
                if (mf == null) throw publicMissing;
                mf.setAccessible(true);
            }
            machineField = mf;
        }
        Object machine = mf.get(context);
        if (machine == null) return null;

        Method sm = machineSelfMethod;
        if (sm == null || !sm.getDeclaringClass().isAssignableFrom(machine.getClass())) {
            sm = machine.getClass().getMethod("self");
            machineSelfMethod = sm;
        }
        Object metaMachine = sm.invoke(machine);
        if (metaMachine != null && oldApiLogged.compareAndSet(false, true)) {
            System.err.println("[CointCoreGTO FMLCoremod] DimensionCondition runtime API: GTCEu 1.8 RecipeLogic/machine/self");
        }
        return metaMachine;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }
}
