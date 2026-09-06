package Crazer.cubeofinterest.cointcoregto.compat.emi.recipefilter;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Mod.EventBusSubscriber(modid = CointCoreGTO.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CointRecipeFilterOverlay {
    private static final String EMI_RECIPE_SCREEN = "dev.emi.emi.screen.RecipeScreen";
    private static final Map<Screen, State> STATES = new WeakHashMap<>();
    private static final long FILTER_DEBOUNCE_MS = 140L;
    private static final ScheduledExecutorService FILTER_EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "CointCoreGTO-RecipeFilter");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private CointRecipeFilterOverlay() {
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        Screen screen = event.getScreen();
        if (!isRecipeScreen(screen)) {
            return;
        }

        State state = state(screen);
        if (state == null) {
            return;
        }

        state.ensureLayout(screen);
        state.syncFocusedCategory(screen);
        renderSearchToggle(event.getGuiGraphics(), state, event.getMouseX(), event.getMouseY());

        if (!state.searchActive || state.search == null) {
            return;
        }

        if (!state.filtering && state.filteredCount == 0 && !state.query.isBlank()) {
            renderNoMatches(event.getGuiGraphics(), state);
        }

        state.search.tick();
        state.search.render(event.getGuiGraphics(), event.getMouseX(), event.getMouseY(), event.getPartialTick());
        renderHelpButton(event.getGuiGraphics(), state, event.getMouseX(), event.getMouseY());

        if (state.helpPinned || state.isHelpHovered(event.getMouseX(), event.getMouseY())) {
            renderHelp(event.getGuiGraphics(), state);
        }
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        Screen screen = event.getScreen();
        if (!isRecipeScreen(screen)) {
            return;
        }

        State state = state(screen);
        if (state == null) {
            return;
        }
        state.ensureLayout(screen);

        if (state.isSearchToggleHovered(event.getMouseX(), event.getMouseY())) {
            state.toggleSearch(screen);
            event.setCanceled(true);
            return;
        }

        if (!state.searchActive) {
            return;
        }

        if (state.isHelpHovered(event.getMouseX(), event.getMouseY())) {
            state.helpPinned = !state.helpPinned;
            event.setCanceled(true);
            return;
        }

        if (state.search != null && state.search.mouseClicked(event.getMouseX(), event.getMouseY(), event.getButton())) {
            state.search.setFocused(true);
            event.setCanceled(true);
            return;
        }

        if (!state.filtering && state.filteredCount == 0 && !state.query.isBlank()
                && state.isRecipeAreaHovered(event.getMouseX(), event.getMouseY())) {
            event.setCanceled(true);
            return;
        }

        if (state.search != null) {
            state.search.setFocused(false);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        Screen screen = event.getScreen();
        if (!isRecipeScreen(screen)) {
            return;
        }

        State state = state(screen);
        if (state == null || !state.searchActive || state.search == null || !state.search.isFocused()) {
            return;
        }

        if (event.getKeyCode() == GLFW.GLFW_KEY_ESCAPE) {
            state.closeSearch(screen);
            event.setCanceled(true);
            return;
        }

        if (event.getKeyCode() == GLFW.GLFW_KEY_ENTER || event.getKeyCode() == GLFW.GLFW_KEY_KP_ENTER) {
            state.search.setFocused(false);
            event.setCanceled(true);
            return;
        }

        if (event.getKeyCode() == GLFW.GLFW_KEY_3
                && (event.getModifiers() & GLFW.GLFW_MOD_SHIFT) != 0) {
            state.search.insertText("#");
            state.suppressNextHashCharacter = true;
            event.setCanceled(true);
            return;
        }

        state.search.keyPressed(event.getKeyCode(), event.getScanCode(), event.getModifiers());
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        Screen screen = event.getScreen();
        if (!isRecipeScreen(screen)) {
            return;
        }
        State state = state(screen);
        if (state != null && state.searchActive && state.search != null && state.search.isFocused()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onCharacterTyped(ScreenEvent.CharacterTyped.Pre event) {
        Screen screen = event.getScreen();
        if (!isRecipeScreen(screen)) {
            return;
        }

        State state = state(screen);
        if (state == null || !state.searchActive || state.search == null || !state.search.isFocused()) {
            return;
        }

        char typed = event.getCodePoint();
        if (state.suppressNextHashCharacter) {
            state.suppressNextHashCharacter = false;
            if (typed == '#' || typed == '\u2116') {
                event.setCanceled(true);
                return;
            }
        }
        if (typed == '\u2116') {
            state.search.insertText("#");
            event.setCanceled(true);
            return;
        }

        state.search.charTyped(typed, event.getModifiers());
        event.setCanceled(true);
    }

    private static State state(Screen screen) {
        State existing = STATES.get(screen);
        if (existing != null) {
            return existing;
        }
        State created = State.capture(screen);
        if (created != null) {
            STATES.put(screen, created);
        }
        return created;
    }

    private static boolean isRecipeScreen(Screen screen) {
        if (screen == null) {
            return false;
        }
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            if (EMI_RECIPE_SCREEN.equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private static void renderNoMatches(GuiGraphics graphics, State state) {
        int left = state.panelX + 4;
        int top = state.panelY + 34;
        int right = state.panelX + state.panelWidth - 4;
        int bottom = state.panelY + state.panelHeight - 4;
        if (right <= left || bottom <= top) {
            return;
        }
        graphics.fill(left, top, right, bottom, 0xEE101010);
        graphics.drawCenteredString(
                Minecraft.getInstance().font,
                "No matching recipes",
                (left + right) / 2,
                top + Math.max(8, (bottom - top) / 2 - 4),
                0xFF7777
        );
    }

    private static void renderSearchToggle(GuiGraphics graphics, State state, int mouseX, int mouseY) {
        boolean hovered = state.isSearchToggleHovered(mouseX, mouseY);
        int bg = hovered || state.searchActive || !state.query.isBlank() ? 0xE0808080 : 0xD0404040;
        int x = state.searchToggleX;
        int y = state.searchToggleY;
        graphics.fill(x, y, x + 12, y + 12, bg);

        int color = 0xFFFFFFFF;
        graphics.fill(x + 3, y + 3, x + 8, y + 4, color);
        graphics.fill(x + 3, y + 4, x + 4, y + 8, color);
        graphics.fill(x + 7, y + 4, x + 8, y + 8, color);
        graphics.fill(x + 4, y + 7, x + 8, y + 8, color);
        graphics.fill(x + 8, y + 8, x + 9, y + 9, color);
        graphics.fill(x + 9, y + 9, x + 11, y + 11, color);
    }

    private static void renderHelpButton(GuiGraphics graphics, State state, int mouseX, int mouseY) {
        boolean hovered = state.isHelpHovered(mouseX, mouseY);
        int bg = hovered || state.helpPinned ? 0xE0808080 : 0xD0404040;
        graphics.fill(state.helpX, state.helpY, state.helpX + 12, state.helpY + 12, bg);
        graphics.drawCenteredString(Minecraft.getInstance().font, "?", state.helpX + 6, state.helpY + 2, 0xFFFFFF);
    }

    private static void renderHelp(GuiGraphics graphics, State state) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        String[] lines = {
                state.filtering ? "Filter Syntax  [searching...]" : "Filter Syntax  [" + state.filteredCount + "/" + state.totalCount + "]",
                "text      - name / ID / mod",
                "<text     - inputs",
                ">text     - outputs",
                "@text     - mod",
                "#text     - tooltip  (RU: №)",
                "$text     - item/fluid tag",
                "&text     - resource ID",
                "=text     - chemical formula",
                "%text     - voltage / tier",
                "-term     - exclude",
                "a|b       - OR",
                "\"text\"  - exact phrase"
        };

        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }
        width += 12;
        int height = lines.length * 11 + 8;

        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int x = Math.min(state.searchX, screenWidth - width - 4);
        int y = state.searchY + 16;
        if (y + height > screenHeight - 4) {
            y = Math.max(4, state.searchY - height - 2);
        }

        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 500.0F);
        graphics.fill(x, y, x + width, y + height, 0xEE101010);
        graphics.fill(x, y, x + width, y + 1, 0xFF7F7F7F);
        graphics.fill(x, y + height - 1, x + width, y + height, 0xFF7F7F7F);

        int ty = y + 5;
        for (int i = 0; i < lines.length; i++) {
            int color = i == 0 ? 0xFFFFFF : syntaxColor(lines[i]);
            graphics.drawString(font, lines[i], x + 6, ty, color, false);
            ty += 11;
        }
        graphics.pose().popPose();
    }

    private static int syntaxColor(String line) {
        if (line.startsWith("<")) return 0x55FF55;
        if (line.startsWith(">")) return 0xFFAA55;
        if (line.startsWith("@")) return 0xFF55FF;
        if (line.startsWith("#")) return 0xFFFF55;
        if (line.startsWith("$")) return 0x55FFFF;
        if (line.startsWith("&")) return 0x55AAFF;
        if (line.startsWith("=")) return 0xAAFFAA;
        if (line.startsWith("%")) return 0xFFAA55;
        if (line.startsWith("-")) return 0xFF7777;
        return 0xDDDDDD;
    }

    private static final class State {
        private final Field recipesField;
        private final Map<EmiRecipeCategory, List<EmiRecipe>> originalRecipes;
        private final EmiRecipeSearchIndex index = new EmiRecipeSearchIndex();

        private EditBox search;
        private String query = "";
        private int totalCount;
        private int filteredCount;
        private int panelX;
        private int panelY;
        private int panelWidth;
        private int panelHeight;
        private int searchToggleX;
        private int searchToggleY;
        private int searchX;
        private int searchY;
        private int searchWidth;
        private int helpX;
        private int helpY;
        private boolean searchActive;
        private boolean helpPinned;
        private boolean refreshing;
        private boolean filtering;
        private boolean suppressNextHashCharacter;
        private volatile long queryGeneration;
        private ScheduledFuture<?> pendingFilter;
        private EmiRecipeCategory currentCategory;
        private EmiRecipeCategory appliedCategory;
        private Object appliedTabObject;

        private State(
                Field recipesField,
                Map<EmiRecipeCategory, List<EmiRecipe>> originalRecipes
        ) {
            this.recipesField = recipesField;
            this.originalRecipes = originalRecipes;
        }

        static State capture(Screen screen) {
            Field field = findField(screen.getClass(), "recipes");
            if (field == null) {
                return null;
            }
            try {
                field.setAccessible(true);
                Object raw = field.get(screen);
                if (!(raw instanceof Map<?, ?> map)) {
                    return null;
                }

                Map<EmiRecipeCategory, List<EmiRecipe>> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof EmiRecipeCategory category)
                            || !(entry.getValue() instanceof List<?> rawList)) {
                        continue;
                    }
                    List<EmiRecipe> recipes = new ArrayList<>();
                    for (Object value : rawList) {
                        if (value instanceof EmiRecipe recipe) {
                            recipes.add(recipe);
                        }
                    }
                    if (!recipes.isEmpty()) {
                        copy.put(category, List.copyOf(recipes));
                    }
                }
                return new State(field, copy);
            } catch (Throwable ignored) {
                return null;
            }
        }

        void ensureLayout(Screen screen) {
            Minecraft minecraft = Minecraft.getInstance();
            int scaledWidth = minecraft.getWindow().getGuiScaledWidth();
            int scaledHeight = minecraft.getWindow().getGuiScaledHeight();

            int currentPanelWidth = readIntField(screen, "backgroundWidth", 352);
            int currentPanelHeight = readIntField(screen, "backgroundHeight", 200);
            int currentPanelX = readIntField(screen, "x", (scaledWidth - currentPanelWidth) / 2);
            int currentPanelY = readIntField(screen, "y", (scaledHeight - currentPanelHeight) / 2);
            int minimumWidth = readIntField(screen, "minimumWidth", currentPanelWidth);
            int buttonOff = readIntField(screen, "buttonOff", Math.max(0, (currentPanelWidth - minimumWidth) / 2));

            panelX = currentPanelX;
            panelY = currentPanelY;
            panelWidth = currentPanelWidth;
            panelHeight = currentPanelHeight;

            int newToggleX = currentPanelX + buttonOff + 20;
            int newToggleY = currentPanelY + 19;
            int newHelpX = currentPanelX + buttonOff + minimumWidth - 32;
            int newHelpY = currentPanelY + 19;
            int newX = newToggleX + 15;
            int newY = currentPanelY + 18;
            int newWidth = Math.max(54, newHelpX - newX - 3);

            if (search != null
                    && newToggleX == searchToggleX
                    && newToggleY == searchToggleY
                    && newX == searchX
                    && newY == searchY
                    && newWidth == searchWidth
                    && newHelpX == helpX
                    && newHelpY == helpY) {
                return;
            }

            boolean focused = searchActive && search != null && search.isFocused();
            searchToggleX = newToggleX;
            searchToggleY = newToggleY;
            searchX = newX;
            searchY = newY;
            searchWidth = newWidth;
            helpX = newHelpX;
            helpY = newHelpY;

            EditBox box = new EditBox(
                    minecraft.font,
                    searchX,
                    searchY,
                    searchWidth,
                    14,
                    Component.literal("Recipe filter")
            );
            box.setMaxLength(256);
            box.setHint(Component.literal("Filter recipes..."));
            box.setValue(query);
            box.setTextColor(filteredCount == 0 && !query.isBlank() ? 0xFF5555 : 0xE0E0E0);
            box.setFocused(focused);
            box.setResponder(value -> onQueryChanged(screen, value));
            search = box;
        }

        void syncFocusedCategory(Screen screen) {
            Object raw = invokeNoArgs(screen, "getFocusedCategory");
            if (!(raw instanceof EmiRecipeCategory category)) {
                return;
            }
            if (currentCategory == category) {
                if (query.isBlank()) {
                    List<EmiRecipe> source = originalRecipes.get(category);
                    totalCount = source == null ? 0 : source.size();
                    filteredCount = totalCount;
                }
                return;
            }

            if (appliedCategory != null && appliedCategory != category) {
                restoreAppliedCategory(screen, false);
            }

            currentCategory = category;
            List<EmiRecipe> source = originalRecipes.get(category);
            totalCount = source == null ? 0 : source.size();
            filteredCount = totalCount;

            if (searchActive && !query.isBlank()) {
                scheduleFilter(screen, query);
            }
        }

        boolean isSearchToggleHovered(double mouseX, double mouseY) {
            return mouseX >= searchToggleX && mouseX < searchToggleX + 12
                    && mouseY >= searchToggleY && mouseY < searchToggleY + 12;
        }

        boolean isHelpHovered(double mouseX, double mouseY) {
            return searchActive
                    && mouseX >= helpX && mouseX < helpX + 12
                    && mouseY >= helpY && mouseY < helpY + 12;
        }

        void toggleSearch(Screen screen) {
            if (searchActive) {
                closeSearch(screen);
                return;
            }
            searchActive = true;
            helpPinned = false;
            syncFocusedCategory(screen);
            if (search != null) {
                search.setFocused(true);
            }
        }

        void closeSearch(Screen screen) {
            searchActive = false;
            helpPinned = false;
            suppressNextHashCharacter = false;
            cancelPendingFilter();
            if (search != null) {
                search.setFocused(false);
                if (!query.isBlank()) {
                    search.setValue("");
                    return;
                }
            }
            queryGeneration++;
            filtering = false;
            restoreAppliedCategory(screen, true);
        }

        boolean isRecipeAreaHovered(double mouseX, double mouseY) {
            int top = panelY + 34;
            return mouseX >= panelX && mouseX < panelX + panelWidth
                    && mouseY >= top && mouseY < panelY + panelHeight;
        }

        private void onQueryChanged(Screen screen, String value) {
            query = value == null ? "" : value;
            syncFocusedCategory(screen);
            suppressNextHashCharacter = false;

            if (query.isBlank()) {
                queryGeneration++;
                filtering = false;
                cancelPendingFilter();
                restoreAppliedCategory(screen, true);
                List<EmiRecipe> source = originalRecipes.get(currentCategory);
                totalCount = source == null ? 0 : source.size();
                filteredCount = totalCount;
                if (search != null) {
                    search.setTextColor(0xE0E0E0);
                }
                return;
            }

            scheduleFilter(screen, query);
        }

        private void scheduleFilter(Screen screen, String querySnapshot) {
            if (currentCategory == null) {
                return;
            }
            cancelPendingFilter();
            long generation = ++queryGeneration;
            EmiRecipeCategory categorySnapshot = currentCategory;
            filtering = true;
            if (search != null) {
                search.setTextColor(0xFFFFAA);
            }

            pendingFilter = FILTER_EXECUTOR.schedule(
                    () -> computeFilter(screen, categorySnapshot, querySnapshot, generation),
                    FILTER_DEBOUNCE_MS,
                    TimeUnit.MILLISECONDS
            );
        }

        private void computeFilter(
                Screen screen,
                EmiRecipeCategory category,
                String querySnapshot,
                long generation
        ) {
            if (generation != queryGeneration) {
                return;
            }

            List<EmiRecipe> source = originalRecipes.get(category);
            final List<EmiRecipe> sourceList = source == null ? List.of() : source;
            RecipeFilterQuery parsed = RecipeFilterQuery.parse(querySnapshot);
            List<EmiRecipe> accepted = new ArrayList<>();

            if (parsed.isEmpty()) {
                accepted.addAll(sourceList);
            } else {
                for (int i = 0; i < sourceList.size(); i++) {
                    if ((i & 127) == 0 && generation != queryGeneration) {
                        return;
                    }
                    EmiRecipe recipe = sourceList.get(i);
                    if (parsed.test(index.document(recipe))) {
                        accepted.add(recipe);
                    }
                }
            }

            if (generation != queryGeneration) {
                return;
            }

            List<EmiRecipe> result = List.copyOf(accepted);
            Minecraft.getInstance().execute(() -> applyFilterResult(
                    screen,
                    category,
                    querySnapshot,
                    generation,
                    sourceList.size(),
                    result
            ));
        }

        private void applyFilterResult(
                Screen screen,
                EmiRecipeCategory category,
                String querySnapshot,
                long generation,
                int sourceCount,
                List<EmiRecipe> result
        ) {
            if (Minecraft.getInstance().screen != screen
                    || generation != queryGeneration
                    || currentCategory != category
                    || !query.equals(querySnapshot)) {
                return;
            }

            filtering = false;
            pendingFilter = null;
            totalCount = sourceCount;
            filteredCount = result.size();
            if (search != null) {
                search.setTextColor(result.isEmpty() ? 0xFF5555 : 0xE0E0E0);
            }

            if (result.isEmpty()) {
                return;
            }

            if (appliedCategory != null && appliedCategory != category) {
                restoreAppliedCategory(screen, false);
            }

            Object newTab = replaceCategoryTab(screen, category, result, true);
            if (newTab != null) {
                appliedCategory = category;
                appliedTabObject = newTab;
                return;
            }

            fallbackReplaceCategory(screen, category, result);
            appliedCategory = category;
            appliedTabObject = null;
        }

        private void restoreAppliedCategory(Screen screen, boolean focusIfCurrent) {
            if (appliedCategory == null) {
                return;
            }
            EmiRecipeCategory category = appliedCategory;
            List<EmiRecipe> source = originalRecipes.get(category);
            appliedCategory = null;
            appliedTabObject = null;
            if (source == null) {
                return;
            }

            boolean focus = focusIfCurrent && currentCategory == category;
            Object restored = replaceCategoryTab(screen, category, source, focus);
            if (restored == null) {
                fallbackReplaceCategory(screen, category, source);
            }
        }

        private Object replaceCategoryTab(
                Screen screen,
                EmiRecipeCategory category,
                List<EmiRecipe> recipes,
                boolean focus
        ) {
            Field tabsField = findField(screen.getClass(), "tabs");
            if (tabsField == null) {
                return null;
            }
            try {
                tabsField.setAccessible(true);
                Object raw = tabsField.get(screen);
                if (!(raw instanceof List<?> rawTabs) || rawTabs.isEmpty()) {
                    return null;
                }

                @SuppressWarnings("unchecked")
                List<Object> tabs = (List<Object>) rawTabs;
                int index = -1;
                for (int i = 0; i < tabs.size(); i++) {
                    Object tab = tabs.get(i);
                    Object tabCategory = readObjectField(tab, "category");
                    if (tabCategory == category || category.equals(tabCategory)) {
                        index = i;
                        break;
                    }
                }

                if (index < 0 && currentCategory == category) {
                    int focused = readIntField(screen, "tab", -1);
                    if (focused >= 0 && focused < tabs.size()) {
                        index = focused;
                    }
                }
                if (index < 0) {
                    return null;
                }

                Object oldTab = tabs.get(index);
                Object newTab = constructRecipeTab(oldTab.getClass(), category, recipes);
                if (newTab == null) {
                    return null;
                }

                int backgroundHeight = readIntField(screen, "backgroundHeight", panelHeight);
                if (!invokeIntMethod(newTab, "bakePages", backgroundHeight)) {
                    return null;
                }

                tabs.set(index, newTab);
                if (focus) {
                    int tabPage = readIntField(screen, "tabPage", 0);
                    if (!invokeThreeIntMethod(screen, "setPage", tabPage, index, 0)) {
                        tabs.set(index, oldTab);
                        return null;
                    }
                }
                return newTab;
            } catch (Throwable ignored) {
                return null;
            }
        }

        private Object constructRecipeTab(
                Class<?> tabClass,
                EmiRecipeCategory category,
                List<EmiRecipe> recipes
        ) {
            for (Constructor<?> constructor : tabClass.getDeclaredConstructors()) {
                Class<?>[] parameters = constructor.getParameterTypes();
                if (parameters.length != 2
                        || !parameters[0].isInstance(category)
                        || !List.class.isAssignableFrom(parameters[1])) {
                    continue;
                }
                try {
                    constructor.setAccessible(true);
                    return constructor.newInstance(category, recipes);
                } catch (Throwable ignored) {
                    return null;
                }
            }
            return null;
        }

        private void fallbackReplaceCategory(
                Screen screen,
                EmiRecipeCategory category,
                List<EmiRecipe> recipes
        ) {
            if (refreshing) {
                return;
            }
            refreshing = true;
            try {
                Map<EmiRecipeCategory, List<EmiRecipe>> replacement = new LinkedHashMap<>();
                for (Map.Entry<EmiRecipeCategory, List<EmiRecipe>> entry : originalRecipes.entrySet()) {
                    replacement.put(
                            entry.getKey(),
                            entry.getKey() == category ? new ArrayList<>(recipes) : new ArrayList<>(entry.getValue())
                    );
                }

                Object focusedCategory = invokeNoArgs(screen, "getFocusedCategory");
                setRecipes(screen, replacement);
                Minecraft minecraft = Minecraft.getInstance();
                screen.resize(
                        minecraft,
                        minecraft.getWindow().getGuiScaledWidth(),
                        minecraft.getWindow().getGuiScaledHeight()
                );
                if (focusedCategory != null && replacement.containsKey(focusedCategory)) {
                    invokeCompatible(screen, "focusCategory", focusedCategory);
                }
            } finally {
                refreshing = false;
            }
        }

        private void setRecipes(Screen screen, Map<EmiRecipeCategory, List<EmiRecipe>> replacement) {
            try {
                recipesField.setAccessible(true);
                recipesField.set(screen, replacement);
                return;
            } catch (Throwable ignored) {
            }

            try {
                Object raw = recipesField.get(screen);
                if (raw instanceof Map<?, ?> current) {
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> mutable = (Map<Object, Object>) current;
                    mutable.clear();
                    mutable.putAll(replacement);
                }
            } catch (Throwable ignored) {
            }
        }

        private void cancelPendingFilter() {
            ScheduledFuture<?> future = pendingFilter;
            pendingFilter = null;
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static int readIntField(Object target, String name, int fallback) {
        Field field = findField(target.getClass(), name);
        if (field == null) {
            return fallback;
        }
        try {
            field.setAccessible(true);
            return field.getInt(target);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Object readObjectField(Object target, String name) {
        if (target == null) {
            return null;
        }
        Field field = findField(target.getClass(), name);
        if (field == null) {
            return null;
        }
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeNoArgs(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean invokeCompatible(Object target, String name, Object argument) {
        if (target == null || argument == null) {
            return false;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!name.equals(method.getName()) || method.getParameterCount() != 1) {
                    continue;
                }
                Class<?> parameter = method.getParameterTypes()[0];
                if (!parameter.isInstance(argument)) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    method.invoke(target, argument);
                    return true;
                } catch (Throwable ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean invokeIntMethod(Object target, String name, int argument) {
        if (target == null) {
            return false;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, int.class);
                method.setAccessible(true);
                method.invoke(target, argument);
                return true;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean invokeThreeIntMethod(Object target, String name, int a, int b, int c) {
        if (target == null) {
            return false;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, int.class, int.class, int.class);
                method.setAccessible(true);
                method.invoke(target, a, b, c);
                return true;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }
}
