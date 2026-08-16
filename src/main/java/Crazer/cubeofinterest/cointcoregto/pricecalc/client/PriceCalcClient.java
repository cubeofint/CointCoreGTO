package Crazer.cubeofinterest.cointcoregto.pricecalc.client;

import Crazer.cubeofinterest.cointcoregto.pricecalc.PriceCalcResolver;
import Crazer.cubeofinterest.cointcoregto.pricecalc.PriceCalcResultEntry;
import Crazer.cubeofinterest.cointcoregto.pricecalc.PriceCalcStorage;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiStackInteraction;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

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

    private static EmiStack findTarget(Minecraft minecraft) {
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

    private static void message(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(text), false);
        }
    }
}
