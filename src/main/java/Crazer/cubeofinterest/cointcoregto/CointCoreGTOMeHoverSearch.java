package Crazer.cubeofinterest.cointcoregto;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD
)
public final class CointCoreGTOMeHoverSearch {
    private static final String KEY_CATEGORY = "key.categories.cointcoregto";

    public static final KeyMapping SEARCH_HOVERED_IN_ME = new KeyMapping(
            "Найти наведённый предмет в МЭ-сети",
            KeyConflictContext.GUI,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Y,
            KEY_CATEGORY
    );

    private static volatile Method cointHoveredStackMethod;

    private CointCoreGTOMeHoverSearch() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(SEARCH_HOVERED_IN_ME);
    }

    @Mod.EventBusSubscriber(
            modid = CointCoreGTO.MODID,
            value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.FORGE
    )
    public static final class ForgeEvents {
        private ForgeEvents() {
        }

        @SubscribeEvent
        public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
            if (!SEARCH_HOVERED_IN_ME.matches(event.getKeyCode(), event.getScanCode())) {
                return;
            }

            Screen screen = event.getScreen();
            if (!isMeStorageScreen(screen)) {
                return;
            }

            ItemStack hovered = findHoveredStack(screen);
            if (hovered.isEmpty()) {
                return;
            }

            String searchText = getSearchText(hovered);
            if (searchText == null || searchText.isBlank()) {
                return;
            }

            if (setMeSearchText(screen, searchText)) {
                event.setCanceled(true);
            }
        }
    }

    private static String getSearchText(ItemStack stack) {
        try {
            var containedFluid = FluidUtil.getFluidContained(stack);
            if (containedFluid.isPresent()) {
                FluidStack fluidStack = containedFluid.get();
                if (!fluidStack.isEmpty()) {
                    String fluidName = fluidStack.getDisplayName().getString();
                    if (fluidName != null && !fluidName.isBlank()) {
                        return fluidName;
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        String itemName = stack.getHoverName().getString();
        if (itemName == null || itemName.isBlank()) {
            itemName = stack.getItem().getDescription().getString();
        }
        return itemName;
    }

    private static boolean isMeStorageScreen(Screen screen) {
        if (screen == null) {
            return false;
        }

        Class<?> type = screen.getClass();
        while (type != null) {
            String name = type.getName();
            if ("appeng.client.gui.me.common.MEStorageScreen".equals(name)) {
                return true;
            }
            type = type.getSuperclass();
        }

        return false;
    }

    private static ItemStack findHoveredStack(Screen screen) {
        ItemStack fromExistingHandler = findHoveredStackViaCointHandler(screen);
        if (!fromExistingHandler.isEmpty()) {
            return fromExistingHandler;
        }

        return findVanillaHoveredSlot(screen);
    }

    private static ItemStack findHoveredStackViaCointHandler(Screen screen) {
        try {
            Method method = cointHoveredStackMethod;
            if (method == null) {
                method = CointCoreGTOItemKeyShare.class.getDeclaredMethod("findHoveredStack", Screen.class);
                method.setAccessible(true);
                cointHoveredStackMethod = method;
            }

            Object result = method.invoke(null, screen);
            if (result instanceof ItemStack stack && !stack.isEmpty()) {
                return stack.copy();
            }
        } catch (Throwable ignored) {
        }

        return ItemStack.EMPTY;
    }

    private static ItemStack findVanillaHoveredSlot(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return ItemStack.EMPTY;
        }

        Field hoveredSlotField = findField(
                AbstractContainerScreen.class,
                "hoveredSlot",
                "f_97738_",
                "field_2787"
        );
        if (hoveredSlotField == null) {
            return ItemStack.EMPTY;
        }

        try {
            hoveredSlotField.setAccessible(true);
            Object value = hoveredSlotField.get(containerScreen);
            if (value instanceof Slot slot && slot.hasItem()) {
                ItemStack stack = slot.getItem();
                return stack == null || stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
            }
        } catch (Throwable ignored) {
        }

        return ItemStack.EMPTY;
    }

    private static boolean setMeSearchText(Screen screen, String text) {
        Object searchWidget = findSearchWidget(screen);
        if (searchWidget != null && setWidgetText(searchWidget, text)) {
            return true;
        }

        if (invokeStringSetter(screen, text,
                "setSearchString",
                "setSearchText",
                "setSearchQuery",
                "updateSearch")) {
            return true;
        }

        return setRepoSearchString(screen, text);
    }

    private static Object findSearchWidget(Screen screen) {
        String[] preferredNames = {
                "searchField",
                "searchBox",
                "searchTextField",
                "searchWidget",
                "search"
        };

        for (String name : preferredNames) {
            Field field = findField(screen.getClass(), name);
            Object value = readField(field, screen);
            if (value != null && canAcceptText(value)) {
                return value;
            }
        }

        Class<?> type = screen.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                String fieldName = field.getName().toLowerCase(Locale.ROOT);
                String typeName = field.getType().getName().toLowerCase(Locale.ROOT);
                if (!fieldName.contains("search") && !typeName.contains("search")) {
                    continue;
                }

                Object value = readField(field, screen);
                if (value != null && canAcceptText(value)) {
                    return value;
                }
            }
            type = type.getSuperclass();
        }

        return null;
    }

    private static boolean canAcceptText(Object value) {
        if (value instanceof EditBox) {
            return true;
        }

        return findMethod(value.getClass(), "setValue", String.class) != null
                || findMethod(value.getClass(), "setText", String.class) != null
                || findMethod(value.getClass(), "setSearchString", String.class) != null;
    }

    private static boolean setWidgetText(Object widget, String text) {
        if (widget instanceof EditBox editBox) {
            editBox.setValue(text);
            editBox.setFocused(true);
            editBox.setCursorPosition(text.length());
            editBox.setHighlightPos(0);
            return true;
        }

        if (!invokeStringSetter(widget, text, "setValue", "setText", "setSearchString")) {
            return false;
        }

        invokeBooleanSetter(widget, true, "setFocused", "setFocus");
        return true;
    }

    private static boolean setRepoSearchString(Screen screen, String text) {
        Class<?> type = screen.getClass();
        while (type != null) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                String fieldName = field.getName().toLowerCase(Locale.ROOT);
                String typeName = field.getType().getName().toLowerCase(Locale.ROOT);
                if (!fieldName.contains("repo") && !typeName.contains("repo")) {
                    continue;
                }

                Object value = readField(field, screen);
                if (value == null) {
                    continue;
                }

                if (invokeStringSetter(value, text, "setSearchString", "setSearchText")) {
                    return true;
                }
            }
            type = type.getSuperclass();
        }

        return false;
    }

    private static boolean invokeStringSetter(Object target, String value, String... names) {
        if (target == null) {
            return false;
        }

        for (String name : names) {
            Method method = findMethod(target.getClass(), name, String.class);
            if (method == null) {
                continue;
            }

            try {
                method.setAccessible(true);
                method.invoke(target, value);
                return true;
            } catch (Throwable ignored) {
            }
        }

        return false;
    }

    private static void invokeBooleanSetter(Object target, boolean value, String... names) {
        if (target == null) {
            return;
        }

        for (String name : names) {
            Method method = findMethod(target.getClass(), name, boolean.class);
            if (method == null) {
                method = findMethod(target.getClass(), name, Boolean.class);
            }
            if (method == null) {
                continue;
            }

            try {
                method.setAccessible(true);
                method.invoke(target, value);
                return;
            } catch (Throwable ignored) {
            }
        }
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

    private static Object readField(Field field, Object target) {
        if (field == null || target == null) {
            return null;
        }

        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (Throwable ignored) {
            }
            current = current.getSuperclass();
        }
        return null;
    }
}