package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class CointCoreGTOItemKeyShare {
    private CointCoreGTOItemKeyShare() {
    }

    @SubscribeEvent
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!CointCoreGTOKeyMappings.isShareItemKey(event.getKeyCode(), event.getScanCode())) {
            return;
        }

        Screen screen = event.getScreen();
        if (screen == null) {
            return;
        }

        if (isTextInputFocused(screen)) {
            return;
        }

        ItemStack stack = findHoveredStack(screen);

        if (stack == null || stack.isEmpty()) {
            return;
        }

        CointCoreGTOItemShare.sendToServer(stack.copy());
        event.setCanceled(true);
    }

    private static ItemStack findHoveredStack(Screen screen) {
        ItemStack vanilla = findVanillaHoveredSlotStack(screen);
        if (!vanilla.isEmpty()) {
            return vanilla.copy();
        }

        ItemStack reflected = findReflectedHoveredStackByMethods(screen);
        if (!reflected.isEmpty()) {
            return reflected.copy();
        }

        if (isMouseOverEmiArea(screen)) {
            ItemStack emi = findEmiHoveredStack();
            if (!emi.isEmpty()) {
                return emi.copy();
            }
        }

        return ItemStack.EMPTY;
    }

    private static boolean isMouseOverEmiArea(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || mc.mouseHandler == null) {
            return false;
        }

        double mouseX = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
        double mouseY = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();

        if (isMouseOverEmiByReflection(mouseX, mouseY)) {
            return true;
        }

        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();

        boolean rightSide = mouseX > width * 0.55D;
        boolean notBottomSearch = mouseY < height - 28;

        return rightSide && notBottomSearch;
    }

    private static boolean isMouseOverEmiByReflection(double mouseX, double mouseY) {
        try {
            Class<?> managerClass = Class.forName("dev.emi.emi.screen.EmiScreenManager");

            Object manager = null;

            for (String fieldName : new String[]{"INSTANCE", "instance"}) {
                try {
                    Field field = managerClass.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    manager = field.get(null);
                    if (manager != null) {
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }

            if (manager == null) {
                manager = managerClass;
            }

            String[] methodNames = {
                    "isMouseOver",
                    "isMouseOverEmi",
                    "isMouseOverSidebar",
                    "isMouseOverStack",
                    "isMouseOverPanel",
                    "contains"
            };

            for (String methodName : methodNames) {
                Class<?> type = manager instanceof Class<?> cls ? cls : manager.getClass();

                while (type != null) {
                    for (Method method : type.getDeclaredMethods()) {
                        if (!method.getName().equals(methodName)) {
                            continue;
                        }

                        Class<?>[] params = method.getParameterTypes();

                        if (params.length != 2 || !isNumberType(params[0]) || !isNumberType(params[1])) {
                            continue;
                        }

                        try {
                            method.setAccessible(true);

                            Object result = method.invoke(
                                    manager instanceof Class<?> ? null : manager,
                                    castNumber(mouseX, params[0]),
                                    castNumber(mouseY, params[1])
                            );

                            if (result instanceof Boolean bool) {
                                return bool;
                            }
                        } catch (Throwable ignored) {
                        }
                    }

                    type = type.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static ItemStack findVanillaHoveredSlotStack(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return ItemStack.EMPTY;
        }

        Slot hoveredSlot = findHoveredSlot(containerScreen);
        if (hoveredSlot == null || !hoveredSlot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = hoveredSlot.getItem();
        return stack == null ? ItemStack.EMPTY : stack.copy();
    }

    private static ItemStack findEmiHoveredStack() {
        try {
            Class<?> emiApiClass = Class.forName("dev.emi.emi.api.EmiApi");

            try {
                Method method = emiApiClass.getMethod("getHoveredStack", Boolean.TYPE);

                Object result = method.invoke(null, false);

                ItemStack stack = extractEmiHoveredStack(result);
                if (!stack.isEmpty()) {
                    return stack.copy();
                }

                result = method.invoke(null, true);

                stack = extractEmiHoveredStack(result);
                if (!stack.isEmpty()) {
                    return stack.copy();
                }
            } catch (Throwable ignored) {
            }

            try {
                Method method = emiApiClass.getMethod("getHoveredStack");

                Object result = method.invoke(null);

                ItemStack stack = extractEmiHoveredStack(result);
                if (!stack.isEmpty()) {
                    return stack.copy();
                }
            } catch (Throwable ignored) {
            }

            try {
                Method method = emiApiClass.getMethod("getHoveredIngredient");

                Object result = method.invoke(null);

                ItemStack stack = extractEmiHoveredStack(result);
                if (!stack.isEmpty()) {
                    return stack.copy();
                }
            } catch (Throwable ignored) {
            }
        } catch (Throwable error) {
        }

        return ItemStack.EMPTY;
    }

    private static ItemStack extractEmiHoveredStack(Object hovered) {
        if (hovered == null) {
            return ItemStack.EMPTY;
        }

        ItemStack byEmiKey = extractFromEmiKey(hovered, 0, new HashSet<>());
        if (!byEmiKey.isEmpty()) {
            return byEmiKey.copy();
        }

        ItemStack fluidBucket = extractFluidBucketFromObject(hovered, 0, new HashSet<>());
        if (!fluidBucket.isEmpty()) {
            return fluidBucket.copy();
        }

        ItemStack itemStack = extractItemStackFromIngredient(hovered, 0, new HashSet<>());
        if (!itemStack.isEmpty()) {
            return itemStack.copy();
        }

        return ItemStack.EMPTY;
    }

    private static ItemStack extractFromEmiKey(Object value, int depth, Set<Object> visited) {
        if (value == null || depth > 6) {
            return ItemStack.EMPTY;
        }

        if (visited.contains(value)) {
            return ItemStack.EMPTY;
        }

        visited.add(value);

        if (value instanceof ItemStack stack) {
            if (stack.isEmpty()) {
                return ItemStack.EMPTY;
            }

            if (isStoneStack(stack)) {
                return ItemStack.EMPTY;
            }

            return stack.copy();
        }

        if (value instanceof Item item) {
            ItemStack stack = new ItemStack(item);
            return stack.isEmpty() || isStoneStack(stack) ? ItemStack.EMPTY : stack;
        }

        if (value instanceof FluidStack fluidStack) {
            return bucketFromFluid(fluidStack.getFluid());
        }

        if (value instanceof Fluid fluid) {
            return bucketFromFluid(fluid);
        }

        if (value instanceof Optional<?> optional) {
            if (optional.isEmpty()) {
                return ItemStack.EMPTY;
            }

            return extractFromEmiKey(optional.get(), depth + 1, visited);
        }

        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                ItemStack stack = extractFromEmiKey(element, depth + 1, visited);
                if (!stack.isEmpty()) {
                    return stack.copy();
                }
            }
        }

        String[] methods = {
                "getKey",
                "key",
                "getStack",
                "stack",
                "getEmiStack",
                "emiStack",
                "getIngredient",
                "ingredient"
        };

        for (String methodName : methods) {
            Class<?> type = value.getClass();

            while (type != null) {
                try {
                    Method method = type.getDeclaredMethod(methodName);
                    method.setAccessible(true);

                    if (method.getParameterCount() != 0) {
                        type = type.getSuperclass();
                        continue;
                    }

                    Object result = method.invoke(value);
                    ItemStack stack = extractFromEmiKey(result, depth + 1, visited);

                    if (!stack.isEmpty()) {
                        return stack.copy();
                    }
                } catch (Throwable ignored) {
                }

                type = type.getSuperclass();
            }
        }

        if (looksLikeEmiStackObject(value)) {
            Class<?> type = value.getClass();

            while (type != null) {
                for (Field field : type.getDeclaredFields()) {
                    try {
                        field.setAccessible(true);

                        Object fieldValue = field.get(value);
                        ItemStack stack = extractFromEmiKey(fieldValue, depth + 1, visited);

                        if (!stack.isEmpty()) {
                            return stack.copy();
                        }
                    } catch (Throwable ignored) {
                    }
                }

                type = type.getSuperclass();
            }
        }

        return ItemStack.EMPTY;
    }

    private static boolean looksLikeEmiStackObject(Object value) {
        if (value == null) {
            return false;
        }

        String name = value.getClass().getName().toLowerCase(java.util.Locale.ROOT);

        return name.contains("emi")
                || name.contains("stack")
                || name.contains("ingredient")
                || name.contains("interaction");
    }

    private static boolean isStoneStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }

        try {
            return "minecraft:stone".equals(String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem())));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isSuspiciousStoneFallback(ItemStack stack, Object source) {
        if (stack == null || stack.isEmpty() || source == null) {
            return false;
        }

        String stackId = "";

        try {
            stackId = String.valueOf(net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem()));
        } catch (Throwable ignored) {
        }

        if (!"minecraft:stone".equals(stackId)) {
            return false;
        }

        String sourceText = "";
        String sourceClass = source.getClass().getName().toLowerCase(java.util.Locale.ROOT);

        try {
            sourceText = String.valueOf(source).toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable ignored) {
        }
        return sourceClass.contains("emi")
                && (sourceText.contains("fluid")
                || sourceText.contains("molten")
                || sourceText.contains("жидкость")
                || sourceText.contains("bucket")
                || sourceText.contains("ведро"));
    }

    private static ItemStack extractFluidBucketFromObject(Object value, int depth, Set<Object> visited) {
        if (value == null || depth > 6) {
            return ItemStack.EMPTY;
        }

        if (visited.contains(value)) {
            return ItemStack.EMPTY;
        }

        visited.add(value);

        if (value instanceof FluidStack fluidStack) {
            return bucketFromFluid(fluidStack.getFluid());
        }

        if (value instanceof Fluid fluid) {
            return bucketFromFluid(fluid);
        }

        if (value instanceof Optional<?> optional) {
            if (optional.isEmpty()) {
                return ItemStack.EMPTY;
            }

            return extractFluidBucketFromObject(optional.get(), depth + 1, visited);
        }

        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                ItemStack stack = extractFluidBucketFromObject(element, depth + 1, visited);
                if (!stack.isEmpty()) {
                    return stack.copy();
                }
            }
        }

        ItemStack byMethods = extractFluidBucketByKnownMethods(value, depth, visited);
        if (!byMethods.isEmpty()) {
            return byMethods.copy();
        }

        if (looksLikeFluidOrEmiObject(value)) {
            ItemStack byFields = scanFluidFields(value, depth + 1, visited);
            if (!byFields.isEmpty()) {
                return byFields.copy();
            }
        }

        return ItemStack.EMPTY;
    }

    private static ItemStack extractFluidBucketByKnownMethods(Object value, int depth, Set<Object> visited) {
        String[] methodNames = {
                "getFluidStack",
                "getFluid",
                "getKey",
                "getIngredient",
                "getStack"
        };

        for (String methodName : methodNames) {
            Class<?> type = value.getClass();

            while (type != null) {
                try {
                    Method method = type.getDeclaredMethod(methodName);
                    method.setAccessible(true);

                    if (method.getParameterCount() != 0) {
                        type = type.getSuperclass();
                        continue;
                    }

                    Object result = method.invoke(value);
                    ItemStack stack = extractFluidBucketFromObject(result, depth + 1, visited);

                    if (!stack.isEmpty()) {
                        return stack.copy();
                    }
                } catch (Throwable ignored) {
                }

                type = type.getSuperclass();
            }
        }

        return ItemStack.EMPTY;
    }

    private static ItemStack scanFluidFields(Object value, int depth, Set<Object> visited) {
        if (value == null || depth > 6) {
            return ItemStack.EMPTY;
        }

        Class<?> type = value.getClass();

        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);

                    Object fieldValue = field.get(value);
                    ItemStack stack = extractFluidBucketFromObject(fieldValue, depth + 1, visited);

                    if (!stack.isEmpty()) {
                        return stack.copy();
                    }
                } catch (Throwable ignored) {
                }
            }

            type = type.getSuperclass();
        }

        return ItemStack.EMPTY;
    }

    private static boolean looksLikeFluidOrEmiObject(Object value) {
        String name = value.getClass().getName().toLowerCase(java.util.Locale.ROOT);

        return name.contains("fluid")
                || name.contains("emi")
                || name.contains("ingredient")
                || name.contains("stack")
                || name.contains("interaction")
                || name.contains("gtceu")
                || name.contains("gregtech");
    }

    private static ItemStack bucketFromFluid(Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) {
            return ItemStack.EMPTY;
        }

        try {
            Item bucket = fluid.getBucket();
            if (bucket == null) {
                return ItemStack.EMPTY;
            }

            ItemStack stack = new ItemStack(bucket);
            return stack.isEmpty() ? ItemStack.EMPTY : stack;
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }
    private static void debugEmiObject(String source, Object object) {
        long now = System.currentTimeMillis();


        String className = object.getClass().getName();
        String text;

        try {
            text = String.valueOf(object);
        } catch (Throwable ignored) {
            text = "";
        }

        if (text.length() > 120) {
            text = text.substring(0, 120);
        }

        debugText("§e[EMI Debug] §f" + source + " -> §b" + className + " §7| §f" + text);
    }

    private static void debugText(String message) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(message), false);
            }
        } catch (Throwable ignored) {
        }
    }
    private static ItemStack findReflectedHoveredStackByMethods(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || mc.mouseHandler == null) {
            return ItemStack.EMPTY;
        }

        double mouseX = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
        double mouseY = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();

        String[] noArgMethods = {
                "getHoveredStack",
                "getHoveredItemStack",
                "getHoveredItem",
                "getHoveredIngredient",
                "getHovered",
                "getStackUnderMouse"
        };

        for (String methodName : noArgMethods) {
            ItemStack stack = invokeNoArgAndExtract(screen, methodName);
            if (!stack.isEmpty()) {
                return stack.copy();
            }
        }

        String[] mouseMethods = {
                "getHoveredStack",
                "getHoveredItemStack",
                "getHoveredItem",
                "getHoveredIngredient",
                "getHovered",
                "getStackUnderMouse",
                "findSlot",
                "findSlotAt"
        };

        for (String methodName : mouseMethods) {
            ItemStack stack = invokeMouseMethodAndExtract(screen, methodName, mouseX, mouseY);
            if (!stack.isEmpty()) {
                return stack.copy();
            }
        }

        return ItemStack.EMPTY;
    }

    private static ItemStack invokeNoArgAndExtract(Object target, String methodName) {
        Class<?> type = target.getClass();

        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(methodName);
                method.setAccessible(true);

                if (method.getParameterCount() != 0) {
                    type = type.getSuperclass();
                    continue;
                }

                Object result = method.invoke(target);
                ItemStack stack = extractItemStackFromIngredient(result, 0, new HashSet<>());

                if (!stack.isEmpty()) {
                    return stack.copy();
                }
            } catch (Throwable ignored) {
            }

            type = type.getSuperclass();
        }

        return ItemStack.EMPTY;
    }

    private static ItemStack invokeMouseMethodAndExtract(Object target, String methodName, double mouseX, double mouseY) {
        Class<?> type = target.getClass();

        while (type != null) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }

                Class<?>[] parameters = method.getParameterTypes();

                try {
                    method.setAccessible(true);

                    Object result = null;

                    if (parameters.length == 2
                            && isNumberType(parameters[0])
                            && isNumberType(parameters[1])) {
                        result = method.invoke(
                                target,
                                castNumber(mouseX, parameters[0]),
                                castNumber(mouseY, parameters[1])
                        );
                    } else if (parameters.length == 3
                            && isNumberType(parameters[0])
                            && isNumberType(parameters[1])
                            && parameters[2] == Boolean.TYPE) {
                        result = method.invoke(
                                target,
                                castNumber(mouseX, parameters[0]),
                                castNumber(mouseY, parameters[1]),
                                false
                        );
                    }

                    ItemStack stack = extractItemStackFromIngredient(result, 0, new HashSet<>());
                    if (!stack.isEmpty()) {
                        return stack.copy();
                    }
                } catch (Throwable ignored) {
                }
            }

            type = type.getSuperclass();
        }

        return ItemStack.EMPTY;
    }

    private static ItemStack extractItemStackFromIngredient(Object value, int depth, Set<Object> visited) {
        if (value == null || depth > 6) {
            return ItemStack.EMPTY;
        }

        if (value instanceof ItemStack stack) {
            return stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
        }

        if (visited.contains(value)) {
            return ItemStack.EMPTY;
        }

        visited.add(value);

        if (isReportedEmpty(value)) {
            return ItemStack.EMPTY;
        }

        if (value instanceof Optional<?> optional) {
            if (optional.isEmpty()) {
                return ItemStack.EMPTY;
            }

            return extractItemStackFromIngredient(optional.get(), depth + 1, visited);
        }

        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                ItemStack stack = extractItemStackFromIngredient(element, depth + 1, visited);
                if (!stack.isEmpty()) {
                    return stack.copy();
                }
            }
        }

        ItemStack byKnownMethods = extractByKnownMethods(value, depth, visited);
        if (!byKnownMethods.isEmpty()) {
            return byKnownMethods.copy();
        }
        if (looksLikeIngredientObject(value)) {
            ItemStack byFields = scanIngredientFields(value, depth + 1, visited);
            if (!byFields.isEmpty()) {
                return byFields.copy();
            }
        }

        return ItemStack.EMPTY;
    }

    private static boolean isReportedEmpty(Object value) {
        String[] methods = {
                "isEmpty",
                "isEmptyStack",
                "isBlank"
        };

        for (String methodName : methods) {
            Class<?> type = value.getClass();

            while (type != null) {
                try {
                    Method method = type.getDeclaredMethod(methodName);
                    method.setAccessible(true);

                    if (method.getParameterCount() != 0) {
                        type = type.getSuperclass();
                        continue;
                    }

                    Object result = method.invoke(value);
                    if (result instanceof Boolean bool) {
                        return bool;
                    }
                } catch (Throwable ignored) {
                }

                type = type.getSuperclass();
            }
        }

        return false;
    }

    private static ItemStack extractByKnownMethods(Object value, int depth, Set<Object> visited) {
        String[] methodNames = {
                "getItemStack",
                "getItemStackRepresentation",
                "getStack",
                "getDisplayStack",
                "getDisplayedStack",
                "getIngredient",
                "getEmiStack",
                "getEmiStacks",
                "getStacks",
                "getAEStack",
                "getWhat",
                "getInput",
                "getOutput"
        };

        for (String methodName : methodNames) {
            Class<?> type = value.getClass();

            while (type != null) {
                try {
                    Method method = type.getDeclaredMethod(methodName);
                    method.setAccessible(true);

                    if (method.getParameterCount() != 0) {
                        type = type.getSuperclass();
                        continue;
                    }

                    Object result = method.invoke(value);
                    ItemStack stack = extractItemStackFromIngredient(result, depth + 1, visited);

                    if (!stack.isEmpty()) {
                        return stack.copy();
                    }
                } catch (Throwable ignored) {
                }

                type = type.getSuperclass();
            }
        }

        return ItemStack.EMPTY;
    }

    private static boolean looksLikeIngredientObject(Object value) {
        String name = value.getClass().getName().toLowerCase(java.util.Locale.ROOT);

        return name.contains("emi")
                || name.contains("ingredient")
                || name.contains("stack")
                || name.contains("entry")
                || name.contains("slot")
                || name.contains("ae2")
                || name.contains("appeng");
    }

    private static ItemStack scanIngredientFields(Object value, int depth, Set<Object> visited) {
        if (value == null || depth > 6) {
            return ItemStack.EMPTY;
        }

        Class<?> type = value.getClass();

        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);

                    Object fieldValue = field.get(value);
                    ItemStack stack = extractItemStackFromIngredient(fieldValue, depth + 1, visited);

                    if (!stack.isEmpty()) {
                        return stack.copy();
                    }
                } catch (Throwable ignored) {
                }
            }

            type = type.getSuperclass();
        }

        return ItemStack.EMPTY;
    }

    private static boolean isTextInputFocused(Screen screen) {
        try {
            return screen.getFocused() instanceof EditBox;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Slot findHoveredSlot(AbstractContainerScreen<?> screen) {
        Slot byExactField = findHoveredSlotByExactField(screen);
        if (byExactField != null) {
            return byExactField;
        }

        return findHoveredSlotByMousePosition(screen);
    }

    private static Slot findHoveredSlotByExactField(AbstractContainerScreen<?> screen) {
        Field field = findField(
                AbstractContainerScreen.class,
                "hoveredSlot",
                "f_97738_",
                "field_2787"
        );

        if (field == null) {
            return null;
        }

        try {
            field.setAccessible(true);
            Object value = field.get(screen);
            if (value instanceof Slot slot) {
                return slot;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static Slot findHoveredSlotByMousePosition(AbstractContainerScreen<?> screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getWindow() == null || mc.mouseHandler == null) {
            return null;
        }

        double mouseX = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
        double mouseY = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();

        int left = getScreenInt(screen, "getGuiLeft", "leftPos", "f_97735_");
        int top = getScreenInt(screen, "getGuiTop", "topPos", "f_97736_");

        for (Slot slot : screen.getMenu().slots) {
            if (slot == null || !slot.isActive() || !slot.hasItem()) {
                continue;
            }

            int slotX = left + slot.x;
            int slotY = top + slot.y;

            if (mouseX >= slotX && mouseX < slotX + 16 && mouseY >= slotY && mouseY < slotY + 16) {
                return slot;
            }
        }

        return null;
    }

    private static int getScreenInt(AbstractContainerScreen<?> screen, String methodName, String fieldName, String obfuscatedFieldName) {
        try {
            Method method = AbstractContainerScreen.class.getMethod(methodName);
            Object value = method.invoke(screen);
            if (value instanceof Integer integer) {
                return integer;
            }
        } catch (Throwable ignored) {
        }

        Field field = findField(AbstractContainerScreen.class, fieldName, obfuscatedFieldName);
        if (field != null) {
            try {
                field.setAccessible(true);
                return field.getInt(screen);
            } catch (Throwable ignored) {
            }
        }

        return 0;
    }

    private static Field findField(Class<?> type, String... names) {
        Class<?> current = type;

        while (current != null) {
            for (String name : names) {
                try {
                    return current.getDeclaredField(name);
                } catch (Throwable ignored) {
                }
            }

            current = current.getSuperclass();
        }

        return null;
    }

    private static boolean isNumberType(Class<?> type) {
        return type == Double.TYPE
                || type == Float.TYPE
                || type == Integer.TYPE
                || type == Long.TYPE
                || type == double.class
                || type == float.class
                || type == int.class
                || type == long.class;
    }

    private static Object castNumber(double value, Class<?> type) {
        if (type == Double.TYPE || type == double.class) {
            return value;
        }

        if (type == Float.TYPE || type == float.class) {
            return (float) value;
        }

        if (type == Integer.TYPE || type == int.class) {
            return (int) value;
        }

        if (type == Long.TYPE || type == long.class) {
            return (long) value;
        }

        return value;
    }
}