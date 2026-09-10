package Crazer.cubeofinterest.cointcoregto.bosssim;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class ApotheosisSalvageBridge {
    private static final String SALVAGING_MENU =
            "dev.shadowsoffire.apotheosis.adventure.affix.salvaging.SalvagingMenu";

    private static volatile Method findMatch;
    private static volatile Method salvageItem;

    private ApotheosisSalvageBridge() {}

    public static boolean canSalvage(Level level, ItemStack stack) {
        if (level == null || stack == null || stack.isEmpty()) return false;
        try {
            ensureMethods();
            return findMatch.invoke(null, level, stack) != null;
        } catch (Throwable throwable) {
            throw new IllegalStateException("Не удалось проверить рецепт разборки Apotheosis", unwrap(throwable));
        }
    }

    public static List<ItemStack> salvage(Level level, ItemStack stack) {
        if (level == null || stack == null || stack.isEmpty()) return List.of();
        try {
            ensureMethods();
            Object value = salvageItem.invoke(null, level, stack);
            if (!(value instanceof List<?> list)) return List.of();

            List<ItemStack> result = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof ItemStack out && !out.isEmpty()) {
                    result.add(out.copy());
                }
            }
            return result;
        } catch (Throwable throwable) {
            throw new IllegalStateException("Не удалось выполнить разборку Apotheosis", unwrap(throwable));
        }
    }

    private static void ensureMethods() throws ReflectiveOperationException {
        if (findMatch != null && salvageItem != null) return;
        synchronized (ApotheosisSalvageBridge.class) {
            if (findMatch != null && salvageItem != null) return;
            Class<?> menu = Class.forName(SALVAGING_MENU);
            Method find = menu.getMethod("findMatch", Level.class, ItemStack.class);
            Method salvage = menu.getMethod("salvageItem", Level.class, ItemStack.class);
            findMatch = find;
            salvageItem = salvage;
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        return cursor;
    }
}
