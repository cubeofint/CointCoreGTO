package Crazer.cubeofinterest.cointcoregto.pricecalc.client;

import Crazer.cubeofinterest.cointcoregto.pricecalc.PriceCalcResolver;
import Crazer.cubeofinterest.cointcoregto.pricecalc.PriceCalcResultEntry;
import Crazer.cubeofinterest.cointcoregto.pricecalc.PriceCalcStorage;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiStackInteraction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class PriceCalcClient {
    private static final DecimalFormat PRICE_FORMAT = new DecimalFormat("#,##0.####", DecimalFormatSymbols.getInstance(Locale.US));
    private static EmiStack pendingRoot;

    private PriceCalcClient() {
    }

    public static void calculateHoveredOrHeld() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return;
        }

        PriceCalcStorage.ensureLoaded();
        EmiStack target = findTarget(minecraft);
        if (target == null || target.isEmpty()) {
            message("§c[PriceCalc] Наведи курсор на предмет/жидкость в EMI или возьми предмет в руку.");
            return;
        }

        pendingRoot = target.copy().setAmount(1);
        runPendingCalculation();
    }

    public static void runPendingCalculation() {
        EmiStack root = pendingRoot;
        if (root == null || root.isEmpty()) {
            return;
        }

        PriceCalcResolver.PriceKey key = PriceCalcResolver.PriceKey.of(root);
        if (key == null) {
            message("§c[PriceCalc] Этот тип не поддерживается.");
            pendingRoot = null;
            return;
        }

        PriceCalcResolver resolver = new PriceCalcResolver();
        PriceCalcResolver.Resolution result;
        try {
            result = resolver.resolve(root);
        } catch (Throwable throwable) {
            message("§c[PriceCalc] Ошибка расчёта: §f" + throwable.getClass().getSimpleName());
            pendingRoot = null;
            return;
        }

        if (result.status == PriceCalcResolver.Status.NEEDS_CHOICE) {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.setScreen(new PriceRecipePickerScreen(minecraft.screen, result.choiceTarget, result.preferenceKey, result.choices));
            return;
        }

        if (result.status == PriceCalcResolver.Status.SUCCESS) {
            Map<String, PriceCalcResultEntry> completed = new LinkedHashMap<>(resolver.getStagedResults());
            completed.put(key.storageKey(), new PriceCalcResultEntry(result.price, result.recipeId));
            PriceCalcStorage.putComputedPrices(completed);
            message("§6[PriceCalc] §f" + root.getName().getString() + "§7: §a" + formatPrice(result.price));
            pendingRoot = null;
            return;
        }

        switch (result.status) {
            case MISSING_BASE -> message("§e[PriceCalc] Не хватает базовой цены для §f" + result.detail);
            case MISSING_TAG_BASE -> message("§e[PriceCalc] Нужна ручная цена тега §f" + result.detail);
            case CYCLE -> message("§c[PriceCalc] Обнаружен цикл: §f" + result.detail);
            case DEPTH_LIMIT -> message("§c[PriceCalc] " + result.detail);
            case INVALID_RECIPE -> message("§c[PriceCalc] Некорректный рецепт: §f" + result.detail);
            default -> message("§c[PriceCalc] Невозможно посчитать: §f" + result.detail);
        }
        pendingRoot = null;
    }

    public static void chooseRecipe(String preferenceKey, String recipeKey) {
        PriceCalcStorage.setPreferredRecipe(preferenceKey, recipeKey);
        runPendingCalculation();
    }

    public static void cancelPendingChoice() {
        pendingRoot = null;
    }

    public static String formatPrice(double price) {
        synchronized (PRICE_FORMAT) {
            return PRICE_FORMAT.format(price).replace(',', ' ');
        }
    }

    static boolean hasHoveredTarget() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.screen == null) {
            return false;
        }
        EmiStack target = findHoveredTarget(minecraft);
        return target != null && !target.isEmpty();
    }

    private static EmiStack findTarget(Minecraft minecraft) {
        EmiStack hovered = findHoveredTarget(minecraft);
        if (hovered != null && !hovered.isEmpty()) {
            return hovered;
        }

        ItemStack mainHand = minecraft.player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!mainHand.isEmpty()) {
            return EmiStack.of(mainHand);
        }
        ItemStack offHand = minecraft.player.getItemInHand(InteractionHand.OFF_HAND);
        if (!offHand.isEmpty()) {
            return EmiStack.of(offHand);
        }
        return null;
    }


    private static EmiStack findHoveredTarget(Minecraft minecraft) {
        try {
            EmiStackInteraction interaction = EmiApi.getHoveredStack(false);
            if (interaction != null && !interaction.isEmpty()) {
                EmiIngredient ingredient = interaction.getStack();
                if (ingredient instanceof EmiStack stack && !stack.isEmpty()) {
                    return stack.copy();
                }
                if (ingredient != null && ingredient.getEmiStacks().size() == 1) {
                    EmiStack stack = ingredient.getEmiStacks().get(0);
                    if (!stack.isEmpty()) {
                        return stack.copy();
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        ItemStack vanillaStack = findVanillaHoveredSlotStack(minecraft.screen);
        if (!vanillaStack.isEmpty()) {
            return EmiStack.of(vanillaStack);
        }
        return null;
    }

    private static ItemStack findVanillaHoveredSlotStack(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return ItemStack.EMPTY;
        }

        Slot hoveredSlot = findHoveredSlotByMousePosition(containerScreen);
        if (hoveredSlot == null || !hoveredSlot.isActive() || !hoveredSlot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = hoveredSlot.getItem();
        return stack.isEmpty() ? ItemStack.EMPTY : stack.copy();
    }

    private static Slot findHoveredSlotByMousePosition(AbstractContainerScreen<?> screen) {
        Minecraft minecraft = Minecraft.getInstance();
        int mouseX = getScaledMouseX(minecraft);
        int mouseY = getScaledMouseY(minecraft);
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

    private static int getScaledMouseX(Minecraft minecraft) {
        return (int) (minecraft.mouseHandler.xpos()
                * minecraft.getWindow().getGuiScaledWidth()
                / minecraft.getWindow().getScreenWidth());
    }

    private static int getScaledMouseY(Minecraft minecraft) {
        return (int) (minecraft.mouseHandler.ypos()
                * minecraft.getWindow().getGuiScaledHeight()
                / minecraft.getWindow().getScreenHeight());
    }

    private static int getScreenInt(
            AbstractContainerScreen<?> screen,
            String methodName,
            String fieldName,
            String obfuscatedFieldName
    ) {
        try {
            Method method = AbstractContainerScreen.class.getMethod(methodName);
            Object result = method.invoke(screen);
            if (result instanceof Integer integer) {
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

    private static Field findField(Class<?> startClass, String... names) {
        Class<?> currentClass = startClass;
        while (currentClass != null) {
            for (String name : names) {
                try {
                    return currentClass.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                }
            }
            currentClass = currentClass.getSuperclass();
        }
        return null;
    }

    private static void message(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(text), false);
        }
    }
}
