package Crazer.cubeofinterest.cointcoregto.pricecalc.client;

import Crazer.cubeofinterest.cointcoregto.pricecalc.PriceCalcStorage;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PriceMachineBlacklistScreen extends Screen {
    private static final int PAGE_SIZE = 8;
    private static final int ROW_HEIGHT = 24;
    private static final int SEARCH_TOP = 40;
    private static final int LIST_TOP = 72;

    private final Screen parent;
    private final List<Button> dynamicButtons = new ArrayList<>();
    private List<CategoryEntry> categories = List.of();
    private List<CategoryEntry> filtered = List.of();
    private EditBox searchBox;
    private String searchQuery = "";
    private String statusText = "";
    private int page;

    public PriceMachineBlacklistScreen(Screen parent) {
        super(Component.literal("Чёрный список машин PriceCalc"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        dynamicButtons.clear();
        int listWidth = Math.min(560, width - 40);
        int left = (width - listWidth) / 2;

        searchBox = new EditBox(font, left, SEARCH_TOP, listWidth, 20, Component.literal("Поиск машин"));
        searchBox.setMaxLength(160);
        searchBox.setHint(Component.literal("Поиск по названию или ID категории EMI"));
        searchBox.setValue(searchQuery);
        searchBox.setResponder(this::setSearchQuery);
        addRenderableWidget(searchBox);

        addRenderableWidget(Button.builder(Component.literal("Назад"), button -> closeToParent())
                .bounds(width / 2 - 50, height - 30, 100, 20)
                .build());

        reloadCategories();
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

    private void reloadCategories() {
        Map<String, CategoryEntry> unique = new LinkedHashMap<>();
        try {
            for (EmiRecipeCategory category : EmiApi.getRecipeManager().getCategories()) {
                if (category == null || category.getId() == null) {
                    continue;
                }
                String id = category.getId().toString();
                String name;
                try {
                    name = category.getName().getString();
                } catch (Throwable ignored) {
                    name = id;
                }
                unique.putIfAbsent(id, new CategoryEntry(id, name));
            }
        } catch (Throwable ignored) {
        }

        for (String id : PriceCalcStorage.getMachineBlacklist()) {
            unique.putIfAbsent(id, new CategoryEntry(id, id));
        }

        ArrayList<CategoryEntry> values = new ArrayList<>(unique.values());
        values.sort(Comparator
                .comparing((CategoryEntry entry) -> normalize(entry.name()))
                .thenComparing(CategoryEntry::id));
        categories = List.copyOf(values);
    }

    private void applySearch() {
        String query = normalize(searchQuery).trim();
        if (query.isEmpty()) {
            filtered = categories;
            return;
        }
        filtered = categories.stream()
                .filter(entry -> normalize(entry.name() + " " + entry.id()).contains(query))
                .toList();
    }

    private void rebuildButtons() {
        for (Button button : dynamicButtons) {
            removeWidget(button);
        }
        dynamicButtons.clear();

        int pageCount = pageCount();
        page = Math.max(0, Math.min(pageCount - 1, page));
        int start = page * PAGE_SIZE;
        int end = Math.min(filtered.size(), start + PAGE_SIZE);
        int listWidth = Math.min(560, width - 40);
        int left = (width - listWidth) / 2;

        for (int i = start; i < end; i++) {
            CategoryEntry entry = filtered.get(i);
            boolean blocked = PriceCalcStorage.isMachineCategoryBlacklisted(entry.id());
            String prefix = blocked ? "§c[ИСКЛ] §f" : "§a[РАЗР] §f";
            String label = prefix + entry.name() + " §8" + entry.id();
            Button button = Button.builder(Component.literal(label), ignored -> toggle(entry))
                    .bounds(left, LIST_TOP + (i - start) * ROW_HEIGHT, listWidth, 20)
                    .build();
            dynamicButtons.add(button);
            addRenderableWidget(button);
        }

        if (page > 0) {
            Button previous = Button.builder(Component.literal("<"), button -> changePage(-1))
                    .bounds(width / 2 - 70, height - 54, 40, 20)
                    .build();
            dynamicButtons.add(previous);
            addRenderableWidget(previous);
        }
        if ((page + 1) * PAGE_SIZE < filtered.size()) {
            Button next = Button.builder(Component.literal(">"), button -> changePage(1))
                    .bounds(width / 2 + 30, height - 54, 40, 20)
                    .build();
            dynamicButtons.add(next);
            addRenderableWidget(next);
        }
    }

    private void toggle(CategoryEntry entry) {
        boolean blocked = PriceCalcStorage.isMachineCategoryBlacklisted(entry.id());
        try {
            Path backup = PriceCalcStorage.setMachineCategoryBlacklistedSafely(entry.id(), !blocked);
            statusText = blocked
                    ? "Разрешено: " + entry.name()
                    : "Исключено: " + entry.name();
            if (backup != null) {
                statusText += " · кеш цен очищен";
            }
            reloadCategories();
            applySearch();
            rebuildButtons();
        } catch (Throwable throwable) {
            String message = throwable.getMessage();
            statusText = "Ошибка: " + (message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message);
        }
    }

    private int pageCount() {
        return Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
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

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        graphics.drawCenteredString(
                font,
                Component.literal("Нажми на тип машины, чтобы разрешить или исключить его из всех расчётов"),
                width / 2,
                24,
                0xC0C0C0
        );

        int listWidth = Math.min(560, width - 40);
        int left = (width - listWidth) / 2;
        graphics.drawString(
                font,
                "Найдено: " + filtered.size() + " · В чёрном списке: " + PriceCalcStorage.getMachineBlacklist().size(),
                left,
                63,
                0xA0A0A0,
                false
        );

        if (filtered.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal("Ничего не найдено"), width / 2, LIST_TOP + 20, 0xB0B0B0);
        }

        if (!statusText.isBlank()) {
            graphics.drawCenteredString(font, Component.literal(statusText), width / 2, height - 76, 0xE0E0E0);
        }

        graphics.drawCenteredString(
                font,
                Component.literal((page + 1) + " / " + pageCount()),
                width / 2,
                height - 49,
                0xA0A0A0
        );
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        closeToParent();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void closeToParent() {
        if (parent instanceof PriceRecipePickerScreen picker) {
            picker.resumeAfterBlacklistChange();
            return;
        }
        minecraft.setScreen(parent);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record CategoryEntry(String id, String name) {
    }
}
