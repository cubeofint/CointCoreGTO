package Crazer.cubeofinterest.cointcoregto.pricecalc.client;

import Crazer.cubeofinterest.cointcoregto.pricecalc.PriceCalcResolver;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class PriceRecipePickerScreen extends Screen {
    private static final int PAGE_SIZE = 5;
    private final Screen parent;
    private final EmiStack target;
    private final String preferenceKey;
    private final List<EmiRecipe> recipes;
    private int page;
    private boolean chosen;

    public PriceRecipePickerScreen(Screen parent, EmiStack target, String preferenceKey, List<EmiRecipe> recipes) {
        super(Component.literal("Выбор рецепта для расчёта цены"));
        this.parent = parent;
        this.target = target;
        this.preferenceKey = preferenceKey;
        this.recipes = List.copyOf(recipes);
    }

    @Override
    protected void init() {
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearWidgets();
        int start = page * PAGE_SIZE;
        int end = Math.min(recipes.size(), start + PAGE_SIZE);
        int cardWidth = Math.min(360, width - 40);
        int left = (width - cardWidth) / 2;
        int top = 48;

        for (int i = start; i < end; i++) {
            EmiRecipe recipe = recipes.get(i);
            int y = top + (i - start) * 42;
            String label = recipe.getCategory().getName().getString();
            String id = PriceCalcResolver.recipeKey(recipe);
            if (id.length() > 46) {
                id = id.substring(0, 43) + "...";
            }
            int index = i;
            addRenderableWidget(Button.builder(
                            Component.literal(label + "  §8" + id),
                            button -> select(index))
                    .bounds(left, y, cardWidth, 20)
                    .build());
        }

        if (page > 0) {
            addRenderableWidget(Button.builder(Component.literal("<"), button -> {
                page--;
                rebuildButtons();
            }).bounds(width / 2 - 70, height - 32, 40, 20).build());
        }
        if ((page + 1) * PAGE_SIZE < recipes.size()) {
            addRenderableWidget(Button.builder(Component.literal(">"), button -> {
                page++;
                rebuildButtons();
            }).bounds(width / 2 + 30, height - 32, 40, 20).build());
        }
    }

    private void select(int index) {
        if (index < 0 || index >= recipes.size()) {
            return;
        }
        chosen = true;
        EmiRecipe recipe = recipes.get(index);
        minecraft.setScreen(parent);
        PriceCalcClient.chooseRecipe(preferenceKey, PriceCalcResolver.recipeKey(recipe));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 14, 0xFFFFFF);
        graphics.drawCenteredString(font, Component.literal("Выберите способ получения: " + target.getName().getString()), width / 2, 28, 0xE0E0E0);

        int start = page * PAGE_SIZE;
        int end = Math.min(recipes.size(), start + PAGE_SIZE);
        int cardWidth = Math.min(360, width - 40);
        int left = (width - cardWidth) / 2;
        int top = 48;

        for (int i = start; i < end; i++) {
            EmiRecipe recipe = recipes.get(i);
            int y = top + (i - start) * 42;
            renderRecipePreview(graphics, recipe, left + 4, y + 22);
        }

        graphics.drawCenteredString(font, Component.literal((page + 1) + " / " + Math.max(1, (recipes.size() + PAGE_SIZE - 1) / PAGE_SIZE)), width / 2, height - 27, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderRecipePreview(GuiGraphics graphics, EmiRecipe recipe, int x, int y) {
        int cursor = x;
        int shown = 0;
        for (EmiIngredient input : recipe.getInputs()) {
            if (shown >= 8) {
                graphics.drawString(font, "+", cursor, y + 4, 0xB0B0B0, false);
                break;
            }
            try {
                input.render(graphics, cursor, y, 0.0F, EmiIngredient.RENDER_ICON | EmiIngredient.RENDER_AMOUNT);
            } catch (Throwable ignored) {
            }
            cursor += 20;
            shown++;
        }
        graphics.drawString(font, "→", cursor + 2, y + 4, 0xFFFFFF, false);
        cursor += 20;
        try {
            for (EmiStack output : recipe.getOutputs()) {
                if (PriceCalcResolver.PriceKey.of(output) != null && output.getId().equals(target.getId())) {
                    output.render(graphics, cursor, y, 0.0F, EmiIngredient.RENDER_ICON | EmiIngredient.RENDER_AMOUNT);
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onClose() {
        if (!chosen) {
            PriceCalcClient.cancelPendingChoice();
        }
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
