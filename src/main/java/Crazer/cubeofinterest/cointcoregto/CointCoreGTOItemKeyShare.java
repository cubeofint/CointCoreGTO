package Crazer.cubeofinterest.cointcoregto;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiStackInteraction;
import dev.emi.emi.screen.EmiScreenManager;
import dev.emi.emi.screen.EmiScreenManager.ScreenSpace;
import dev.emi.emi.screen.EmiScreenManager.SidebarPanel;
import dev.emi.emi.screen.RecipeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

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
        ItemShareChannel channel =
                CointCoreGTOKeyMappings.getItemShareChannel(
                        event.getKeyCode(),
                        event.getScanCode()
                );

        if (channel == null) {
            return;
        }

        Screen screen = event.getScreen();

        if (screen == null || isTextInputFocused(screen)) {
            return;
        }

        ItemStack stack = findHoveredStack(screen);

        if (stack.isEmpty()) {
            return;
        }

        CointCoreGTOItemShare.sendToServer(stack.copy(), channel);
        event.setCanceled(true);
    }

    private static ItemStack findHoveredStack(Screen screen) {
        if (screen instanceof RecipeScreen recipeScreen) {
            EmiIngredient ingredient = recipeScreen.getHoveredStack();

            if (ingredient == null || ingredient.isEmpty()) {
                return ItemStack.EMPTY;
            }

            return extractIngredientStack(ingredient);
        }
        ItemStack vanillaStack = findVanillaHoveredSlotStack(screen);

        if (!vanillaStack.isEmpty()) {
            return vanillaStack;
        }
        return findEmiSidebarHoveredStack();
    }

    private static ItemStack findVanillaHoveredSlotStack(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return ItemStack.EMPTY;
        }

        Slot hoveredSlot = findHoveredSlotByMousePosition(containerScreen);

        if (hoveredSlot == null
                || !hoveredSlot.isActive()
                || !hoveredSlot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = hoveredSlot.getItem();

        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        return stack.copy();
    }

    private static ItemStack findEmiSidebarHoveredStack() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.screen == null
                || EmiApi.isSearchFocused()
                || EmiScreenManager.isDisabled()) {
            return ItemStack.EMPTY;
        }

        int mouseX = getScaledMouseX(minecraft);
        int mouseY = getScaledMouseY(minecraft);

        try {
            SidebarPanel panel =
                    EmiScreenManager.getHoveredPanel(mouseX, mouseY);
            if (panel == null || !panel.isVisible()) {
                return ItemStack.EMPTY;
            }

            ScreenSpace space = panel.getHoveredSpace(mouseX, mouseY);
            if (space == null
                    || !space.contains(mouseX, mouseY)
                    || !space.containsNotExcluded(mouseX, mouseY)) {
                return ItemStack.EMPTY;
            }
            EmiStackInteraction interaction =
                    EmiScreenManager.getHoveredStack(
                            mouseX,
                            mouseY,
                            false,
                            false
                    );

            if (interaction == null || interaction.isEmpty()) {
                return ItemStack.EMPTY;
            }

            EmiIngredient ingredient = interaction.getStack();

            if (ingredient == null || ingredient.isEmpty()) {
                return ItemStack.EMPTY;
            }

            return extractIngredientStack(ingredient);
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static ItemStack extractIngredientStack(
            EmiIngredient ingredient
    ) {
        if (ingredient == null || ingredient.isEmpty()) {
            return ItemStack.EMPTY;
        }
        for (EmiStack emiStack : ingredient.getEmiStacks()) {
            if (emiStack == null || emiStack.isEmpty()) {
                continue;
            }

            ItemStack itemStack = emiStack.getItemStack();

            if (itemStack != null && !itemStack.isEmpty()) {
                return itemStack.copy();
            }
        }
        for (EmiStack emiStack : ingredient.getEmiStacks()) {
            ItemStack fluidContainer =
                    createFluidRepresentative(emiStack);

            if (!fluidContainer.isEmpty()) {
                return fluidContainer;
            }
        }

        return ItemStack.EMPTY;
    }

    private static ItemStack createFluidRepresentative(
            EmiStack emiStack
    ) {
        if (emiStack == null || emiStack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        Object key = emiStack.getKey();

        if (!(key instanceof Fluid fluid)
                || fluid == Fluids.EMPTY) {
            return ItemStack.EMPTY;
        }

        Item bucket = fluid.getBucket();

        if (bucket == null || bucket == Items.AIR) {
            return ItemStack.EMPTY;
        }

        ItemStack result = new ItemStack(bucket);

        return result.isEmpty()
                ? ItemStack.EMPTY
                : result;
    }

    private static Slot findHoveredSlotByMousePosition(
            AbstractContainerScreen<?> screen
    ) {
        Minecraft minecraft = Minecraft.getInstance();

        int mouseX = getScaledMouseX(minecraft);
        int mouseY = getScaledMouseY(minecraft);

        int left = getScreenInt(
                screen,
                "getGuiLeft",
                "leftPos",
                "f_97735_"
        );

        int top = getScreenInt(
                screen,
                "getGuiTop",
                "topPos",
                "f_97736_"
        );

        for (Slot slot : screen.getMenu().slots) {
            if (slot == null
                    || !slot.isActive()
                    || !slot.hasItem()) {
                continue;
            }

            int slotX = left + slot.x;
            int slotY = top + slot.y;

            if (mouseX >= slotX
                    && mouseX < slotX + 16
                    && mouseY >= slotY
                    && mouseY < slotY + 16) {
                return slot;
            }
        }

        return null;
    }

    private static int getScaledMouseX(Minecraft minecraft) {
        return (int) (
                minecraft.mouseHandler.xpos()
                        * minecraft.getWindow().getGuiScaledWidth()
                        / minecraft.getWindow().getScreenWidth()
        );
    }

    private static int getScaledMouseY(Minecraft minecraft) {
        return (int) (
                minecraft.mouseHandler.ypos()
                        * minecraft.getWindow().getGuiScaledHeight()
                        / minecraft.getWindow().getScreenHeight()
        );
    }

    private static int getScreenInt(
            AbstractContainerScreen<?> screen,
            String methodName,
            String fieldName,
            String obfuscatedFieldName
    ) {
        try {
            Method method =
                    AbstractContainerScreen.class.getMethod(methodName);

            Object result = method.invoke(screen);

            if (result instanceof Integer integer) {
                return integer;
            }
        } catch (Throwable ignored) {
        }

        Field field = findField(
                AbstractContainerScreen.class,
                fieldName,
                obfuscatedFieldName
        );

        if (field != null) {
            try {
                field.setAccessible(true);
                return field.getInt(screen);
            } catch (Throwable ignored) {
            }
        }

        return 0;
    }

    private static boolean isTextInputFocused(Screen screen) {
        try {
            if (EmiApi.isSearchFocused()) {
                return true;
            }
        } catch (Throwable ignored) {
        }

        try {
            Object focused = screen.getFocused();

            if (focused == null) {
                return false;
            }

            if (focused instanceof EditBox) {
                return true;
            }

            String className = focused
                    .getClass()
                    .getName()
                    .toLowerCase(Locale.ROOT);

            if (className.contains("editbox")
                    || className.contains("textfield")
                    || className.contains("text_field")
                    || className.contains("searchfield")
                    || className.contains("search_field")
                    || className.contains("textbox")
                    || className.contains("inputfield")
                    || className.contains("input_field")) {
                return true;
            }

            for (String methodName : new String[]{
                    "isEditing",
                    "isTyping",
                    "isTextInputActive"
            }) {
                Class<?> currentClass = focused.getClass();

                while (currentClass != null) {
                    try {
                        Method method =
                                currentClass.getDeclaredMethod(methodName);

                        if (method.getParameterCount() == 0
                                && (method.getReturnType() == boolean.class
                                || method.getReturnType() == Boolean.class)) {
                            method.setAccessible(true);

                            if (Boolean.TRUE.equals(
                                    method.invoke(focused)
                            )) {
                                return true;
                            }
                        }
                    } catch (NoSuchMethodException ignored) {
                    } catch (Throwable ignored) {
                        break;
                    }

                    currentClass = currentClass.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static Field findField(
            Class<?> startClass,
            String... names
    ) {
        Class<?> currentClass = startClass;

        while (currentClass != null) {
            for (String name : names) {
                try {
                    return currentClass.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                } catch (Throwable ignored) {
                    return null;
                }
            }

            currentClass = currentClass.getSuperclass();
        }

        return null;
    }
}