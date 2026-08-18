package Crazer.cubeofinterest.cointcoregto.invview;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class InvViewCuriosBridge {
    record SlotRef(IItemHandler handler, int index, String label) {
    }

    private InvViewCuriosBridge() {
    }

    static List<SlotRef> collect(ServerPlayer player) {
        List<SlotRef> result = new ArrayList<>();
        try {
            Class<?> apiClass = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Method getInventory = apiClass.getMethod("getCuriosInventory", LivingEntity.class);
            Object lazyValue = getInventory.invoke(null, player);
            if (!(lazyValue instanceof LazyOptional<?> lazy)) {
                return result;
            }
            Optional<?> resolved = lazy.resolve();
            if (resolved.isEmpty()) {
                return result;
            }
            Object curios = resolved.get();
            Object mapValue = curios.getClass().getMethod("getCurios").invoke(curios);
            if (!(mapValue instanceof Map<?, ?> map)) {
                return result;
            }

            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            for (Map.Entry<?, ?> entry : entries) {
                String id = String.valueOf(entry.getKey());
                Object stacksHandler = entry.getValue();
                if (stacksHandler == null) {
                    continue;
                }
                addHandler(result, id, stacksHandler, false);
                boolean hasCosmetic = invokeBoolean(stacksHandler, "hasCosmetic");
                if (hasCosmetic) {
                    addHandler(result, id, stacksHandler, true);
                }
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static void addHandler(List<SlotRef> output, String id, Object stacksHandler, boolean cosmetic) {
        try {
            String methodName = cosmetic ? "getCosmeticStacks" : "getStacks";
            Object itemHandler = stacksHandler.getClass().getMethod(methodName).invoke(stacksHandler);
            if (!(itemHandler instanceof IItemHandler handler)) {
                return;
            }
            int slots = handler.getSlots();
            String prefix = cosmetic ? id + " (косм.)" : id;
            for (int slot = 0; slot < slots; slot++) {
                output.add(new SlotRef(handler, slot, prefix + " " + (slot + 1)));
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean invokeBoolean(Object target, String method) {
        try {
            Object value = target.getClass().getMethod(method).invoke(target);
            return value instanceof Boolean bool && bool;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
