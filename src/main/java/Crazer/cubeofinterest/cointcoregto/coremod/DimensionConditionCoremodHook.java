package Crazer.cubeofinterest.cointcoregto.coremod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class DimensionConditionCoremodHook {
    private DimensionConditionCoremodHook() {}

    public static boolean isPersonalSpaceOverworld(Object condition, Object holder) {
        try {
            if (condition == null || holder == null) return false;

            Object expected = getExpectedDimension(condition);
            if (expected == null || !String.valueOf(expected).contains("minecraft:overworld")) return false;

            Method selfMethod = holder.getClass().getMethod("self");
            Object machine = selfMethod.invoke(holder);
            if (machine == null) return false;

            Method getLevelMethod = machine.getClass().getMethod("getLevel");
            Object level = getLevelMethod.invoke(machine);
            if (level == null) return false;

            Object actual = getDimension(level);
            return actual != null && String.valueOf(actual).contains("personalspace:personal_space_dimensions/");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object getExpectedDimension(Object condition) throws Exception {
        try {
            Field field = condition.getClass().getField("dimension");
            return field.get(condition);
        } catch (NoSuchFieldException ignored) {
            Class<?> current = condition.getClass();
            while (current != null) {
                try {
                    Field field = current.getDeclaredField("dimension");
                    field.setAccessible(true);
                    return field.get(condition);
                } catch (NoSuchFieldException ignoredField) {
                    current = current.getSuperclass();
                }
            }
            throw new NoSuchFieldException("dimension");
        }
    }

    private static Object getDimension(Object level) throws Exception {
        try {
            Method method = level.getClass().getMethod("m_46472_");
            return method.invoke(level);
        } catch (NoSuchMethodException ignored) {
            Method method = level.getClass().getMethod("dimension");
            return method.invoke(level);
        }
    }
}
