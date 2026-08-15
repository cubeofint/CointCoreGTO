package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Arrays;

public final class CraftingRecipeEditorScreen extends AbstractContainerScreen<CraftingRecipeEditorMenu> {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private EditBox idBox;
    private EditBox resultCountBox;
    private Button modeButton;
    private Button saveButton;
    private Button clearButton;
    private Button serverFilesButton;

    /** Null = concrete item; otherwise the slot keeps the original tag ingredient. */
    private final String[] ingredientTags = new String[CraftingRecipeEditorMenu.INPUT_SLOT_COUNT];

    private boolean shapeless;
    private int selectedGhostSlot;
    private int resultCount = 1;
    private String craftingCategory = "misc";
    private boolean showNotification = true;
    private JsonObject preservedRecipeFields;
    private String editingRelativePath = "";
    private String suspendedIdValue = "cointcoregto:my_crafting_recipe";
    private boolean restoreSuspendedState;
    private String statusText = "";
    private boolean statusSuccess;

    public CraftingRecipeEditorScreen(CraftingRecipeEditorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 410;
        this.imageHeight = 282;
        this.inventoryLabelX = 122;
        this.inventoryLabelY = 178;
    }

    @Override
    protected void init() {
        this.imageWidth = 410;
        this.imageHeight = 282;
        super.init();

        idBox = new EditBox(font, leftPos + 18, topPos + 27, 218, 20, Component.empty());
        idBox.setMaxLength(160);
        idBox.setValue("cointcoregto:my_crafting_recipe");
        addRenderableWidget(idBox);

        modeButton = Button.builder(Component.literal("Shaped 3x3"), button -> toggleMode())
                .bounds(leftPos + 246, topPos + 27, 92, 20)
                .build();
        addRenderableWidget(modeButton);

        serverFilesButton = Button.builder(Component.literal("Сервер"), button -> openServerBrowser())
                .bounds(leftPos + 342, topPos + 27, 58, 20)
                .build();
        addRenderableWidget(serverFilesButton);

        resultCountBox = new EditBox(font, leftPos + 346, topPos + 98, 44, 20, Component.empty());
        resultCountBox.setMaxLength(2);
        resultCountBox.setFilter(value -> value.matches("[0-9]*"));
        resultCountBox.setValue("1");
        resultCountBox.setResponder(value -> {
            int parsed = parseInt(value, -1);
            if (parsed > 0 && parsed <= 64) {
                resultCount = parsed;
            }
        });
        addRenderableWidget(resultCountBox);

        saveButton = Button.builder(Component.literal("Сохранить рецепт"), button -> saveRecipe())
                .bounds(leftPos + 118, topPos + 150, 108, 20)
                .build();
        addRenderableWidget(saveButton);

        clearButton = Button.builder(Component.literal("Очистить"), button -> clearEditor())
                .bounds(leftPos + 234, topPos + 150, 76, 20)
                .build();
        addRenderableWidget(clearButton);

        if (restoreSuspendedState) {
            idBox.setValue(suspendedIdValue);
            modeButton.setMessage(Component.literal(shapeless ? "Shapeless" : "Shaped 3x3"));
            resultCountBox.setValue(Integer.toString(resultCount));
            restoreSuspendedState = false;
        } else if (statusText.isBlank()) {
            statusSuccess = true;
            statusText = "Режим верстака выбран автоматически по ПКМ";
        }
    }

    private void openServerBrowser() {
        suspendedIdValue = idBox == null ? "cointcoregto:my_crafting_recipe" : idBox.getValue();
        restoreSuspendedState = true;
        Minecraft.getInstance().setScreen(new RecipeEditorServerBrowserScreen(this, true));
    }

    private void toggleMode() {
        shapeless = !shapeless;
        modeButton.setMessage(Component.literal(shapeless ? "Shapeless" : "Shaped 3x3"));
        statusSuccess = true;
        statusText = shapeless
                ? "Shapeless: положение ингредиентов не важно"
                : "Shaped: положение ингредиентов в сетке 3x3 сохраняется";
    }

    private void saveRecipe() {
        try {
            JsonObject recipe = buildRecipeJson();
            RecipeEditorNetwork.CHANNEL.sendToServer(new RecipeEditorSavePacket(
                    GSON.toJson(recipe),
                    true,
                    editingRelativePath
            ));
            statusSuccess = true;
            statusText = editingRelativePath.isBlank()
                    ? "Отправлено на сохранение..."
                    : "Отправлено обновление серверного файла...";
        } catch (IllegalArgumentException exception) {
            statusSuccess = false;
            statusText = exception.getMessage();
        }
    }

    private JsonObject buildRecipeJson() {
        String id = idBox.getValue().trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Укажи ID рецепта");
        }

        ItemStack resultStack = menu.getGhostItem(CraftingRecipeEditorMenu.OUTPUT_SLOT);
        if (resultStack.isEmpty()) {
            throw new IllegalArgumentException("Добавь результат крафта в OUTPUT");
        }
        if (resultStack.hasTag()) {
            throw new IllegalArgumentException("Обычный vanilla crafting result в этом редакторе пока без NBT");
        }

        int count = parseInt(resultCountBox.getValue(), resultCount);
        if (count <= 0 || count > 64) {
            throw new IllegalArgumentException("Количество результата должно быть 1..64");
        }
        resultCount = count;

        JsonObject recipe = preservedRecipeFields == null ? new JsonObject() : preservedRecipeFields.deepCopy();
        removeManagedFields(recipe);
        recipe.addProperty("enabled", true);
        recipe.addProperty("id", id);
        recipe.addProperty("type", shapeless ? "minecraft:crafting_shapeless" : "minecraft:crafting_shaped");
        recipe.addProperty("category", craftingCategory == null || craftingCategory.isBlank() ? "misc" : craftingCategory);

        if (shapeless) {
            JsonArray ingredients = new JsonArray();
            for (int slot = 0; slot < CraftingRecipeEditorMenu.INPUT_SLOT_COUNT; slot++) {
                if (hasIngredient(slot)) {
                    ingredients.add(ingredient(menu.getGhostItem(slot), slot));
                }
            }
            if (ingredients.size() == 0) {
                throw new IllegalArgumentException("Добавь хотя бы один ингредиент");
            }
            recipe.add("ingredients", ingredients);
        } else {
            addShapedPattern(recipe);
            recipe.addProperty("show_notification", showNotification);
        }

        ResourceLocation resultId = ForgeRegistries.ITEMS.getKey(resultStack.getItem());
        if (resultId == null) {
            throw new IllegalArgumentException("Не удалось определить ID результата");
        }
        JsonObject result = new JsonObject();
        result.addProperty("item", resultId.toString());
        if (count != 1) {
            result.addProperty("count", count);
        }
        recipe.add("result", result);
        return recipe;
    }

    private void addShapedPattern(JsonObject recipe) {
        int minRow = 3;
        int maxRow = -1;
        int minCol = 3;
        int maxCol = -1;

        for (int slot = 0; slot < CraftingRecipeEditorMenu.INPUT_SLOT_COUNT; slot++) {
            if (!hasIngredient(slot)) {
                continue;
            }
            int row = slot / 3;
            int col = slot % 3;
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);
        }

        if (maxRow < 0) {
            throw new IllegalArgumentException("Добавь хотя бы один ингредиент");
        }

        JsonArray pattern = new JsonArray();
        JsonObject key = new JsonObject();
        char nextSymbol = 'A';

        for (int row = minRow; row <= maxRow; row++) {
            StringBuilder line = new StringBuilder();
            for (int col = minCol; col <= maxCol; col++) {
                int slot = row * 3 + col;
                if (!hasIngredient(slot)) {
                    line.append(' ');
                    continue;
                }

                char symbol = nextSymbol++;
                line.append(symbol);
                key.add(String.valueOf(symbol), ingredient(menu.getGhostItem(slot), slot));
            }
            pattern.add(line.toString());
        }

        recipe.add("pattern", pattern);
        recipe.add("key", key);
    }

    private boolean hasIngredient(int slot) {
        return slot >= 0
                && slot < CraftingRecipeEditorMenu.INPUT_SLOT_COUNT
                && (ingredientTags[slot] != null || !menu.getGhostItem(slot).isEmpty());
    }

    private JsonObject ingredient(ItemStack stack, int slot) {
        JsonObject ingredient = new JsonObject();
        if (slot >= 0 && slot < ingredientTags.length && ingredientTags[slot] != null) {
            ingredient.addProperty("tag", ingredientTags[slot]);
            return ingredient;
        }

        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("Вход " + (slot + 1) + " пуст");
        }
        if (stack.hasTag()) {
            throw new IllegalArgumentException("Вход " + (slot + 1) + ": vanilla crafting ingredient пока без NBT");
        }
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) {
            throw new IllegalArgumentException("Не удалось определить ID ингредиента " + (slot + 1));
        }
        ingredient.addProperty("item", itemId.toString());
        return ingredient;
    }

    public boolean loadServerRecipe(String relativePath, String rawJson) {
        try {
            JsonObject recipe = JsonParser.parseString(rawJson).getAsJsonObject();
            String type = requiredString(recipe, "type");
            boolean loadedShapeless;
            if ("minecraft:crafting_shaped".equals(type)) {
                loadedShapeless = false;
            } else if ("minecraft:crafting_shapeless".equals(type)) {
                loadedShapeless = true;
            } else {
                throw new IllegalArgumentException("Это не shaped/shapeless crafting-рецепт: " + type);
            }

            JsonObject result = recipe.getAsJsonObject("result");
            if (result == null) {
                throw new IllegalArgumentException("В JSON нет result");
            }
            ItemStack resultStack = stackFromItemId(requiredString(result, "item"));
            int loadedResultCount = result.has("count") ? result.get("count").getAsInt() : 1;
            if (loadedResultCount < 1 || loadedResultCount > 64) {
                throw new IllegalArgumentException("result.count должен быть 1..64");
            }

            clearGhostData();
            preservedRecipeFields = recipe.deepCopy();
            idBox.setValue(requiredString(recipe, "id"));
            shapeless = loadedShapeless;
            modeButton.setMessage(Component.literal(shapeless ? "Shapeless" : "Shaped 3x3"));
            craftingCategory = recipe.has("category") ? recipe.get("category").getAsString() : "misc";
            showNotification = !recipe.has("show_notification") || recipe.get("show_notification").getAsBoolean();

            if (shapeless) {
                JsonArray ingredients = recipe.getAsJsonArray("ingredients");
                if (ingredients == null || ingredients.size() < 1 || ingredients.size() > 9) {
                    throw new IllegalArgumentException("Shapeless ingredients должен содержать 1..9 записей");
                }
                for (int i = 0; i < ingredients.size(); i++) {
                    loadIngredient(i, ingredients.get(i));
                }
            } else {
                JsonArray pattern = recipe.getAsJsonArray("pattern");
                JsonObject key = recipe.getAsJsonObject("key");
                if (pattern == null || key == null || pattern.size() < 1 || pattern.size() > 3) {
                    throw new IllegalArgumentException("Некорректный shaped pattern/key");
                }
                for (int row = 0; row < pattern.size(); row++) {
                    String line = pattern.get(row).getAsString();
                    if (line.length() > 3) {
                        throw new IllegalArgumentException("Строка shaped pattern длиннее 3 символов");
                    }
                    for (int col = 0; col < line.length(); col++) {
                        char symbol = line.charAt(col);
                        if (symbol == ' ') {
                            continue;
                        }
                        JsonElement value = key.get(String.valueOf(symbol));
                        if (value == null) {
                            throw new IllegalArgumentException("В key нет символа " + symbol);
                        }
                        loadIngredient(row * 3 + col, value);
                    }
                }
            }

            menu.setGhostItem(CraftingRecipeEditorMenu.OUTPUT_SLOT, resultStack);
            RecipeEditorNetwork.CHANNEL.sendToServer(new RecipeEditorGhostUpdatePacket(
                    CraftingRecipeEditorMenu.OUTPUT_SLOT,
                    resultStack
            ));
            resultCount = loadedResultCount;
            resultCountBox.setValue(Integer.toString(resultCount));
            selectedGhostSlot = 0;
            editingRelativePath = relativePath == null ? "" : relativePath;
            statusSuccess = true;
            statusText = "Открыт серверный файл: " + editingRelativePath;
            return true;
        } catch (Throwable throwable) {
            statusSuccess = false;
            statusText = throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
            return false;
        }
    }

    private void loadIngredient(int slot, JsonElement element) {
        if (slot < 0 || slot >= CraftingRecipeEditorMenu.INPUT_SLOT_COUNT || element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("Некорректный crafting ingredient");
        }
        JsonObject object = element.getAsJsonObject();
        String tag = object.has("tag") ? object.get("tag").getAsString() : null;
        ingredientTags[slot] = tag;

        ItemStack display = ItemStack.EMPTY;
        try {
            ItemStack[] options = Ingredient.fromJson(element).getItems();
            if (options.length > 0) {
                display = options[0].copy();
                display.setCount(1);
            }
        } catch (Throwable ignored) {
        }

        if (tag == null && display.isEmpty()) {
            display = stackFromItemId(requiredString(object, "item"));
        }

        menu.setGhostItem(slot, display);
        RecipeEditorNetwork.CHANNEL.sendToServer(new RecipeEditorGhostUpdatePacket(slot, display));
    }

    private ItemStack stackFromItemId(String rawId) {
        ResourceLocation id = ResourceLocation.tryParse(rawId);
        if (id == null) {
            throw new IllegalArgumentException("Некорректный ID предмета: " + rawId);
        }
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null) {
            throw new IllegalArgumentException("Неизвестный предмет: " + id);
        }
        return new ItemStack(item, 1);
    }

    private void clearEditor() {
        idBox.setValue("cointcoregto:my_crafting_recipe");
        shapeless = false;
        modeButton.setMessage(Component.literal("Shaped 3x3"));
        resultCount = 1;
        resultCountBox.setValue("1");
        craftingCategory = "misc";
        showNotification = true;
        preservedRecipeFields = null;
        editingRelativePath = "";
        clearGhostData();
        selectedGhostSlot = 0;
        statusSuccess = true;
        statusText = "Форма очищена";
    }

    private void clearGhostData() {
        Arrays.fill(ingredientTags, null);
        for (int slot = 0; slot < CraftingRecipeEditorMenu.GHOST_SLOT_COUNT; slot++) {
            menu.setGhostItem(slot, ItemStack.EMPTY);
            RecipeEditorNetwork.CHANNEL.sendToServer(new RecipeEditorGhostUpdatePacket(slot, ItemStack.EMPTY));
        }
    }

    public int getItemTargetScreenX(int index) {
        Slot slot = menu.getSlot(index);
        return leftPos + slot.x - 1;
    }

    public int getItemTargetScreenY(int index) {
        Slot slot = menu.getSlot(index);
        return topPos + slot.y - 1;
    }

    public boolean setItemFromEmi(int index, ItemStack stack, long amount) {
        if (!CraftingRecipeEditorMenu.isGhostSlot(index) || stack == null || stack.isEmpty()) {
            return false;
        }
        ItemStack template = stack.copy();
        template.setCount(1);
        menu.setGhostItem(index, template);
        RecipeEditorNetwork.CHANNEL.sendToServer(new RecipeEditorGhostUpdatePacket(index, template));
        selectedGhostSlot = index;
        if (CraftingRecipeEditorMenu.isInputSlot(index)) {
            ingredientTags[index] = null;
        }
        if (index == CraftingRecipeEditorMenu.OUTPUT_SLOT) {
            resultCount = (int) Math.max(1L, Math.min(64L, amount <= 0L ? 1L : amount));
            resultCountBox.setValue(Integer.toString(resultCount));
        }
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(template.getItem());
        statusSuccess = true;
        statusText = (index == CraftingRecipeEditorMenu.OUTPUT_SLOT ? "Output: " : "Input " + (index + 1) + ": ")
                + (itemId == null ? "?" : itemId.toString());
        return true;
    }

    public void onSaveResult(boolean success, String message, String relativePath) {
        statusSuccess = success;
        statusText = message == null ? "" : message;
        if (success && relativePath != null && !relativePath.isBlank()) {
            editingRelativePath = relativePath;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // AbstractContainerScreen normally treats the inventory key (E by
        // default) as "close screen". When an EditBox is focused we consume
        // that key so typing 'e' remains ordinary text input.
        if (getFocused() instanceof EditBox
                && minecraft != null
                && minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int ghostSlot = findGhostSlot(mouseX, mouseY);
        ItemStack carriedBefore = ghostSlot >= 0 ? menu.getCarried().copy() : ItemStack.EMPTY;
        if (ghostSlot >= 0) {
            selectedGhostSlot = ghostSlot;
            if (CraftingRecipeEditorMenu.isInputSlot(ghostSlot)) {
                // A manual click changes/removes the displayed template; from
                // this point the slot is a concrete item rather than a loaded tag.
                ingredientTags[ghostSlot] = null;
            }
        }

        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (ghostSlot == CraftingRecipeEditorMenu.OUTPUT_SLOT) {
            if (!carriedBefore.isEmpty()) {
                resultCount = Math.max(1, Math.min(64, carriedBefore.getCount()));
                resultCountBox.setValue(Integer.toString(resultCount));
            } else if (menu.getGhostItem(ghostSlot).isEmpty()) {
                resultCount = 1;
                resultCountBox.setValue("1");
            }
        }
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int ghostSlot = findGhostSlot(mouseX, mouseY);
        if (ghostSlot == CraftingRecipeEditorMenu.OUTPUT_SLOT && !menu.getGhostItem(ghostSlot).isEmpty()) {
            resultCount = Math.max(1, Math.min(64, resultCount + (delta > 0.0D ? 1 : -1)));
            resultCountBox.setValue(Integer.toString(resultCount));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private int findGhostSlot(double mouseX, double mouseY) {
        for (int slotIndex = 0; slotIndex < CraftingRecipeEditorMenu.GHOST_SLOT_COUNT; slotIndex++) {
            Slot slot = menu.getSlot(slotIndex);
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                return slotIndex;
            }
        }
        return -1;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xF0121824);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 2, 0xFF65E6FF);
        graphics.fill(leftPos + 102, topPos + 66, leftPos + 184, topPos + 138, 0xFF1B2638);
        graphics.fill(leftPos + 278, topPos + 84, leftPos + 326, topPos + 122, 0xFF2D2618);
        graphics.fill(leftPos + 116, topPos + 181, leftPos + 286, topPos + 274, 0xFF172132);

        for (int slotIndex = 0; slotIndex < CraftingRecipeEditorMenu.GHOST_SLOT_COUNT; slotIndex++) {
            Slot slot = menu.getSlot(slotIndex);
            int x = leftPos + slot.x - 1;
            int y = topPos + slot.y - 1;
            int border = slotIndex == selectedGhostSlot ? 0xFFFFD36F : 0xFF48566C;
            graphics.fill(x, y, x + 18, y + 18, border);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF202B3C);
        }

        graphics.drawString(font, "→", leftPos + 228, topPos + 97, 0xFFFFFF, false);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, "Coint Crafting Recipe Editor", 10, 8, 0xFFFFFF, false);
        graphics.drawString(font, "Recipe ID", 18, 17, 0xA9B7CC, false);
        graphics.drawString(font, "Mode", 246, 17, 0xA9B7CC, false);
        graphics.drawString(font, "INPUT 3x3", 106, 55, 0x6FE9FF, false);
        graphics.drawString(font, "OUTPUT", 278, 73, 0xFFD36F, false);
        graphics.drawString(font, "Count", 346, 87, 0xA9B7CC, false);

        String[] help = getHelp(mouseX, mouseY);
        if (help != null) {
            graphics.drawString(font, help[0], 18, 129, 0xD7E4F5, false);
            graphics.drawString(font, help[1], 18, 140, 0x9FB0C6, false);
        } else if (!statusText.isBlank()) {
            String visible = statusText.length() > 62 ? statusText.substring(0, 62) + "..." : statusText;
            graphics.drawString(font, visible, 18, 135, statusSuccess ? 0x6FFF8B : 0xFF7070, false);
        } else {
            graphics.drawString(font, "Drag предметов из EMI прямо в сетку и результат", 18, 135, 0x8291A6, false);
        }

        graphics.drawString(font, "Inventory", inventoryLabelX, inventoryLabelY, 0xCCCCCC, false);
    }

    private String[] getHelp(int mouseX, int mouseY) {
        int x = mouseX - leftPos;
        int y = mouseY - topPos;
        if (inside(x, y, 16, 15, 224, 34)) {
            return new String[]{"Recipe ID", "Уникальный ID. Рецепт загружается CointCoreGTO напрямую, без KubeJS."};
        }
        if (inside(x, y, 244, 15, 96, 34)) {
            return new String[]{"Shaped / Shapeless", "Shaped хранит позиции 3x3; Shapeless использует только набор ингредиентов."};
        }
        if (inside(x, y, 340, 15, 62, 34)) {
            return new String[]{"Сервер", "Удалённый список JSON: просмотр, редактирование и удаление с двойным подтверждением."};
        }
        if (inside(x, y, 100, 53, 88, 88)) {
            return new String[]{"INPUT 3x3", "Обычный верстак расходует по 1 предмету из каждой занятой клетки."};
        }
        if (inside(x, y, 276, 71, 116, 55)) {
            return new String[]{"OUTPUT + Count", "Результат крафта. Count 1..64; колесо над output меняет количество."};
        }
        if (inside(x, y, 116, 148, 112, 24)) {
            return new String[]{"Сохранить рецепт", "Файл хранится на сервере. После рестарта рецепт автоматически придет в EMI."};
        }
        return null;
    }

    private static void removeManagedFields(JsonObject recipe) {
        String[] fields = {
                "enabled", "id", "type", "category", "ingredients",
                "pattern", "key", "show_notification", "result"
        };
        for (String field : fields) {
            recipe.remove(field);
        }
    }

    private static String requiredString(JsonObject object, String field) {
        if (object == null || !object.has(field)) {
            throw new IllegalArgumentException("В JSON нет поля " + field);
        }
        String value = object.get(field).getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Поле " + field + " пустое");
        }
        return value;
    }

    private static boolean inside(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static int parseInt(String value, int fallback) {
        try {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
