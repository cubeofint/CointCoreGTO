package Crazer.cubeofinterest.cointcoregto.pricecalc.client;

import Crazer.cubeofinterest.cointcoregto.pricecalc.PriceCalcResolver;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PriceRecipePickerScreen extends Screen {
    private static final int PAGE_SIZE = 4;
    private static final int CARD_HEIGHT = 54;
    private static final int ICON_STEP = 20;
    private static final int MAX_MACHINE_ICONS = 2;
    private static final int MAX_INPUT_ICONS = 8;
    private static final int SEARCH_TOP = 50;
    private static final int CARDS_TOP = 86;

    private final Screen parent;
    private final EmiStack target;
    private final String preferenceKey;
    private final List<EmiRecipe> recipes;
    private final List<Button> dynamicButtons = new ArrayList<>();
    private List<SearchEntry> indexedRecipes = List.of();
    private List<SearchEntry> filteredRecipes = List.of();
    private EditBox searchBox;
    private String searchQuery = "";
    private int page;
    private boolean chosen;
    private Component hoveredIconTooltip;

    public PriceRecipePickerScreen(Screen parent, EmiStack target, String preferenceKey, List<EmiRecipe> recipes) {
        super(Component.literal("Выбор рецепта для расчёта цены"));
        this.parent = parent;
        this.target = target;
        this.preferenceKey = preferenceKey;
        this.recipes = List.copyOf(recipes);
    }

    @Override
    protected void init() {
        dynamicButtons.clear();
        int cardWidth = Math.min(430, width - 32);
        int left = (width - cardWidth) / 2;

        searchBox = new EditBox(font, left, SEARCH_TOP, cardWidth, 20, Component.literal("Поиск рецептов"));
        searchBox.setMaxLength(160);
        searchBox.setHint(Component.literal("Поиск по машине, входам, выходам, количеству или ID"));
        searchBox.setValue(searchQuery);
        searchBox.setResponder(this::setSearchQuery);
        addRenderableWidget(searchBox);

        indexedRecipes = recipes.stream().map(this::indexRecipe).toList();
        applySearch();
        rebuildButtons();
    }

    @Override
    public void tick() {
        super.tick();
        if (searchBox != null) {
            searchBox.tick();
        }
    }

    private void setSearchQuery(String value) {
        String next = value == null ? "" : value;
        if (next.equals(searchQuery)) {
            return;
        }
        searchQuery = next;
        page = 0;
        applySearch();
        rebuildButtons();
    }

    private void applySearch() {
        String query = normalize(searchQuery).trim();
        if (query.isEmpty()) {
            filteredRecipes = indexedRecipes;
            return;
        }
        filteredRecipes = indexedRecipes.stream()
                .filter(entry -> matchesSearch(entry, query))
                .toList();
    }

    private boolean matchesSearch(SearchEntry entry, String query) {
        String[] terms = query.split("\\s+");
        for (String term : terms) {
            if (term.isEmpty()) {
                continue;
            }
            int colon = term.indexOf(':');
            String prefix = colon > 0 ? term.substring(0, colon) : "";
            String value = colon > 0 ? term.substring(colon + 1) : term;
            if (value.isEmpty()) {
                continue;
            }
            String field = switch (prefix) {
                case "machine", "m", "машина", "станок", "тип", "category" -> entry.machine();
                case "input", "in", "вход", "ингредиент", "ingredient" -> entry.input();
                case "output", "out", "выход", "результат", "result" -> entry.output();
                case "id", "recipe", "рецепт" -> entry.id();
                case "count", "amount", "qty", "кол", "количество" -> entry.amount();
                case "incount", "inputcount", "входкол" -> entry.inputAmount();
                case "outcount", "outputcount", "выходкол" -> entry.outputAmount();
                default -> entry.all();
            };
            if (!field.contains(value)) {
                return false;
            }
        }
        return true;
    }

    private SearchEntry indexRecipe(EmiRecipe recipe) {
        String id = normalize(PriceCalcResolver.recipeKey(recipe));
        StringBuilder machine = new StringBuilder();
        StringBuilder input = new StringBuilder();
        StringBuilder output = new StringBuilder();
        StringBuilder inputAmount = new StringBuilder();
        StringBuilder outputAmount = new StringBuilder();

        try {
            append(machine, recipe.getCategory().getName().getString());
        } catch (Throwable ignored) {
        }

        try {
            List<EmiIngredient> workstations = EmiApi.getRecipeManager().getWorkstations(recipe.getCategory());
            appendIngredients(machine, null, workstations);
        } catch (Throwable ignored) {
        }

        try {
            appendIngredients(input, inputAmount, recipe.getInputs());
        } catch (Throwable ignored) {
        }

        try {
            appendStacks(output, outputAmount, recipe.getOutputs());
        } catch (Throwable ignored) {
        }

        String amount = normalize(inputAmount + " " + outputAmount);
        String all = normalize(id + " " + machine + " " + input + " " + output + " " + amount);
        return new SearchEntry(
                recipe,
                all,
                normalize(machine.toString()),
                normalize(input.toString()),
                normalize(output.toString()),
                id,
                amount,
                normalize(inputAmount.toString()),
                normalize(outputAmount.toString())
        );
    }

    private static void appendIngredients(StringBuilder text, StringBuilder amounts, List<EmiIngredient> ingredients) {
        if (ingredients == null) {
            return;
        }
        for (EmiIngredient ingredient : ingredients) {
            if (ingredient == null || ingredient.isEmpty()) {
                continue;
            }
            try {
                appendStacks(text, amounts, ingredient.getEmiStacks());
            } catch (Throwable ignored) {
            }
        }
    }

    private static void appendStacks(StringBuilder text, StringBuilder amounts, List<EmiStack> stacks) {
        if (stacks == null) {
            return;
        }
        for (EmiStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            try {
                append(text, stack.getName().getString());
            } catch (Throwable ignored) {
            }
            try {
                append(text, stack.getId().toString());
            } catch (Throwable ignored) {
            }
            try {
                String amount = Long.toString(stack.getAmount());
                append(text, amount);
                if (amounts != null) {
                    append(amounts, amount);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void append(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(' ');
        }
        builder.append(value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private void rebuildButtons() {
        for (Button button : dynamicButtons) {
            removeWidget(button);
        }
        dynamicButtons.clear();

        int pageCount = pageCount();
        page = Math.max(0, Math.min(pageCount - 1, page));
        int start = page * PAGE_SIZE;
        int end = Math.min(filteredRecipes.size(), start + PAGE_SIZE);
        int cardWidth = Math.min(430, width - 32);
        int left = (width - cardWidth) / 2;

        for (int i = start; i < end; i++) {
            SearchEntry entry = filteredRecipes.get(i);
            EmiRecipe recipe = entry.recipe();
            int y = CARDS_TOP + (i - start) * CARD_HEIGHT;
            String label = recipe.getCategory().getName().getString();
            String id = PriceCalcResolver.recipeKey(recipe);
            int maxIdLength = Math.max(16, Math.min(54, (cardWidth - 110) / 6));
            if (id.length() > maxIdLength) {
                id = id.substring(0, Math.max(1, maxIdLength - 3)) + "...";
            }
            Button button = Button.builder(
                            Component.literal(label + "  §8" + id),
                            ignored -> select(recipe))
                    .bounds(left, y, cardWidth, 20)
                    .build();
            dynamicButtons.add(button);
            addRenderableWidget(button);
        }

        if (page > 0) {
            Button previous = Button.builder(Component.literal("<"), button -> changePage(-1))
                    .bounds(width / 2 - 70, height - 32, 40, 20)
                    .build();
            dynamicButtons.add(previous);
            addRenderableWidget(previous);
        }
        if ((page + 1) * PAGE_SIZE < filteredRecipes.size()) {
            Button next = Button.builder(Component.literal(">"), button -> changePage(1))
                    .bounds(width / 2 + 30, height - 32, 40, 20)
                    .build();
            dynamicButtons.add(next);
            addRenderableWidget(next);
        }
    }

    private int pageCount() {
        return Math.max(1, (filteredRecipes.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private boolean changePage(int direction) {
        int nextPage = Math.max(0, Math.min(pageCount() - 1, page + direction));
        if (nextPage == page) {
            return false;
        }
        page = nextPage;
        rebuildButtons();
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (delta > 0.0D && changePage(-1)) {
            return true;
        }
        if (delta < 0.0D && changePage(1)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void select(EmiRecipe recipe) {
        chosen = true;
        minecraft.setScreen(parent);
        PriceCalcClient.chooseRecipe(preferenceKey, PriceCalcResolver.recipeKey(recipe));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        hoveredIconTooltip = null;
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFF);
        graphics.drawCenteredString(
                font,
                Component.literal("Выберите способ получения: " + target.getName().getString()),
                width / 2,
                21,
                0xE0E0E0
        );
        graphics.drawCenteredString(
                font,
                Component.literal("Выбор сохранится в preferred_recipes.json"),
                width / 2,
                34,
                0x909090
        );

        int cardWidth = Math.min(430, width - 32);
        int left = (width - cardWidth) / 2;
        String found = "Найдено: " + filteredRecipes.size() + " / " + recipes.size();
        graphics.drawString(font, found, left, 73, 0xA0A0A0, false);

        int start = page * PAGE_SIZE;
        int end = Math.min(filteredRecipes.size(), start + PAGE_SIZE);
        for (int i = start; i < end; i++) {
            EmiRecipe recipe = filteredRecipes.get(i).recipe();
            int y = CARDS_TOP + (i - start) * CARD_HEIGHT;
            renderRecipePreview(graphics, recipe, left + 5, y + 24, cardWidth - 10, mouseX, mouseY);
        }

        if (filteredRecipes.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal("Ничего не найдено"), width / 2, CARDS_TOP + 20, 0xB0B0B0);
        }

        graphics.drawCenteredString(
                font,
                Component.literal((page + 1) + " / " + pageCount()),
                width / 2,
                height - 27,
                0xA0A0A0
        );
        super.render(graphics, mouseX, mouseY, partialTick);

        if (hoveredIconTooltip != null) {
            graphics.renderTooltip(font, hoveredIconTooltip, mouseX, mouseY);
        }
    }

    private void renderRecipePreview(
            GuiGraphics graphics,
            EmiRecipe recipe,
            int x,
            int y,
            int availableWidth,
            int mouseX,
            int mouseY
    ) {
        int cursor = x;
        List<EmiIngredient> workstations;
        try {
            workstations = EmiApi.getRecipeManager().getWorkstations(recipe.getCategory());
        } catch (Throwable ignored) {
            workstations = List.of();
        }

        boolean hasVisibleMachine = false;
        if (workstations != null && !workstations.isEmpty()) {
            int shownMachines = 0;
            for (EmiIngredient workstation : workstations) {
                if (shownMachines >= MAX_MACHINE_ICONS) {
                    graphics.drawString(font, "…", cursor, y + 4, 0xB0B0B0, false);
                    cursor += 10;
                    break;
                }
                renderIngredientSafe(workstation, graphics, cursor, y);
                captureIngredientHover(workstation, cursor, y, mouseX, mouseY);
                cursor += ICON_STEP;
                shownMachines++;
                hasVisibleMachine = true;
            }
        }
        if (hasVisibleMachine && !recipe.getInputs().isEmpty()) {
            cursor += 6;
        }

        int reserveForArrowAndOutput = 44;
        int maxInputsByWidth = Math.max(1, (availableWidth - (cursor - x) - reserveForArrowAndOutput) / ICON_STEP);
        int inputLimit = Math.min(MAX_INPUT_ICONS, maxInputsByWidth);
        int shownInputs = 0;
        for (EmiIngredient input : recipe.getInputs()) {
            if (shownInputs >= inputLimit) {
                graphics.drawString(font, "…", cursor, y + 4, 0xB0B0B0, false);
                cursor += 10;
                break;
            }
            renderIngredientSafe(input, graphics, cursor, y);
            captureIngredientHover(input, cursor, y, mouseX, mouseY);
            cursor += ICON_STEP;
            shownInputs++;
        }

        graphics.drawString(font, "→", cursor + 2, y + 4, 0xFFFFFF, false);
        cursor += ICON_STEP;
        renderTargetOutput(graphics, recipe, cursor, y, mouseX, mouseY);
    }

    private void captureIngredientHover(
            EmiIngredient ingredient,
            int x,
            int y,
            int mouseX,
            int mouseY
    ) {
        if (hoveredIconTooltip != null || !isInsideIcon(mouseX, mouseY, x, y)) {
            return;
        }
        try {
            if (ingredient == null || ingredient.isEmpty()) {
                return;
            }
            List<EmiStack> stacks = ingredient.getEmiStacks();
            if (stacks == null || stacks.isEmpty()) {
                return;
            }
            EmiStack stack = stacks.get(0);
            if (stack != null && !stack.isEmpty()) {
                hoveredIconTooltip = stack.getName();
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isInsideIcon(int mouseX, int mouseY, int x, int y) {
        return mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
    }

    private static void renderIngredientSafe(EmiIngredient ingredient, GuiGraphics graphics, int x, int y) {
        try {
            if (ingredient != null && !ingredient.isEmpty()) {
                ingredient.render(graphics, x, y, 0.0F, EmiIngredient.RENDER_ICON | EmiIngredient.RENDER_AMOUNT);
            }
        } catch (Throwable ignored) {
        }
    }

    private void renderTargetOutput(
            GuiGraphics graphics,
            EmiRecipe recipe,
            int x,
            int y,
            int mouseX,
            int mouseY
    ) {
        PriceCalcResolver.PriceKey targetKey = PriceCalcResolver.PriceKey.of(target);
        if (targetKey == null) {
            return;
        }
        try {
            for (EmiStack output : recipe.getOutputs()) {
                PriceCalcResolver.PriceKey outputKey = PriceCalcResolver.PriceKey.of(output);
                if (targetKey.equals(outputKey)) {
                    output.render(graphics, x, y, 0.0F, EmiIngredient.RENDER_ICON | EmiIngredient.RENDER_AMOUNT);
                    if (hoveredIconTooltip == null && isInsideIcon(mouseX, mouseY, x, y)) {
                        hoveredIconTooltip = output.getName();
                    }
                    return;
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

    private record SearchEntry(
            EmiRecipe recipe,
            String all,
            String machine,
            String input,
            String output,
            String id,
            String amount,
            String inputAmount,
            String outputAmount
    ) {
    }
}
