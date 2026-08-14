package Crazer.cubeofinterest.cointcoregto.coremod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime hook used by the Forge CoreMod test.
 * Deliberately has no compile-time dependency on GTCEu, GTOCore or Minecraft.
 */
public final class DimensionConditionCoremodHook {
    private static volatile Field dimensionField;
    private static volatile Field machineField;
    private static volatile Method selfMethod;
    private static volatile Method getLevelMethod;
    private static volatile Method levelDimensionMethod;

    private static final AtomicBoolean aliasLogged = new AtomicBoolean();
    private static final AtomicBoolean errorLogged = new AtomicBoolean();

    private DimensionConditionCoremodHook() {}

    public static boolean testCondition(Object condition, Object recipeLogic) {
        try {
            if (condition == null || recipeLogic == null) return false;

            Field df = dimensionField;
            if (df == null) {
                df = condition.getClass().getField("dimension");
                dimensionField = df;
            }
            Object expected = df.get(condition);
            if (expected == null) return false;

            Field mf = machineField;
            if (mf == null) {
                mf = recipeLogic.getClass().getField("machine");
                machineField = mf;
            }
            Object machine = mf.get(recipeLogic);
            if (machine == null) return false;

            Method sm = selfMethod;
            if (sm == null || !sm.getDeclaringClass().isAssignableFrom(machine.getClass())) {
                sm = machine.getClass().getMethod("self");
                selfMethod = sm;
            }
            Object metaMachine = sm.invoke(machine);
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
                System.err.println("[CointCoreGTO FMLCoremod] SUCCESS: PersonalSpace accepted as minecraft:overworld in DimensionCondition; actual=" + actualText);
            }
            return personalSpace;
        } catch (Throwable t) {
            if (errorLogged.compareAndSet(false, true)) {
                System.err.println("[CointCoreGTO FMLCoremod] hook failed: " + t);
                t.printStackTrace(System.err);
            }
            return false;
        }
    }
}
