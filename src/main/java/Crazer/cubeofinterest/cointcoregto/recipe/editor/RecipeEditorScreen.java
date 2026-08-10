package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Arrays;

public final class RecipeEditorScreen extends AbstractContainerScreen<RecipeEditorMenu> {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_ITEM_COUNT = Integer.MAX_VALUE;

    public static final int FLUID_INPUT_SLOT_COUNT = 9;
    public static final int FLUID_OUTPUT_SLOT_COUNT = 9;
    public static final int FLUID_SLOT_COUNT = FLUID_INPUT_SLOT_COUNT + FLUID_OUTPUT_SLOT_COUNT;

    private static final int ITEM_INPUT_GRID_X = 14;
    private static final int ITEM_OUTPUT_GRID_X = 404;
    private static final int ITEM_GRID_Y = 58;
    private static final int FLUID_INPUT_GRID_X = 14;
    private static final int FLUID_OUTPUT_GRID_X = 422;
    private static final int FLUID_GRID_Y = 158;

    private final int[] itemCounts = new int[RecipeEditorMenu.GHOST_SLOT_COUNT];
    private final int[] itemChances = new int[RecipeEditorMenu.GHOST_SLOT_COUNT];
    private final int[] itemTierChanceBoosts = new int[RecipeEditorMenu.GHOST_SLOT_COUNT];
    private final boolean[] itemNotConsumable = new boolean[RecipeEditorMenu.INPUT_SLOT_COUNT];

    private final ResourceLocation[] fluidIds = new ResourceLocation[FLUID_SLOT_COUNT];
    private final long[] fluidAmounts = new long[FLUID_SLOT_COUNT];
    private final int[] fluidChances = new int[FLUID_SLOT_COUNT];
    private final int[] fluidTierChanceBoosts = new int[FLUID_SLOT_COUNT];
    private final boolean[] fluidNotConsumable = new boolean[FLUID_INPUT_SLOT_COUNT];

    private EditBox idBox;
    private EditBox typeBox;
    private EditBox durationBox;
    private EditBox eutBox;
    private EditBox circuitBox;
    private EditBox priorityBox;
    private EditBox amountBox;
    private EditBox blastBox;
    private EditBox heatBox;
    private EditBox manaBox;
    private EditBox temperatureBox;
    private EditBox chanceBox;
    private EditBox tierBoostBox;
    private Button notConsumableButton;
    private Button saveButton;
    private Button clearButton;

    private int selectedGhostSlot = 0;
    private int selectedFluidSlot = 0;
    private boolean fluidSelection = false;
    private boolean updatingSelectionControls;
    private String statusText = "";
    private boolean statusSuccess;

    public RecipeEditorScreen(RecipeEditorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 486;
        this.imageHeight = 350;
        this.inventoryLabelX = 162;
        this.inventoryLabelY = 263;
        Arrays.fill(itemCounts, 1);
        Arrays.fill(itemChances, 10_000);
        Arrays.fill(fluidAmounts, 1_000L);
        Arrays.fill(fluidChances, 10_000);
    }

    @Override
    protected void init() {
        this.imageWidth = 486;
        this.imageHeight = 350;
        super.init();

        idBox = addBox(leftPos + 12, topPos + 24, 200, 18, "cointcoregto:my_recipe", 160, null);
        typeBox = addBox(leftPos + 224, topPos + 24, 250, 18, menu.getInitialRecipeType(), 160, null);

        durationBox = addBox(leftPos + 92, topPos + 57, 66, 18, "200", 10, RecipeEditorScreen::digitsOnly);
        eutBox = addBox(leftPos + 164, topPos + 57, 72, 18, "16", 20, RecipeEditorScreen::signedDigitsOnly);
        circuitBox = addBox(leftPos + 242, topPos + 57, 58, 18, "0", 2, RecipeEditorScreen::digitsOnly);
        priorityBox = addBox(leftPos + 306, topPos + 57, 72, 18, "0", 11, RecipeEditorScreen::signedDigitsOnly);

        amountBox = addBox(leftPos + 92, topPos + 84, 66, 18, "1", 20, RecipeEditorScreen::digitsOnly);
        chanceBox = addBox(leftPos + 164, topPos + 84, 72, 18, "10000", 5, RecipeEditorScreen::digitsOnly);
        tierBoostBox = addBox(leftPos + 242, topPos + 84, 58, 18, "0", 11, RecipeEditorScreen::signedDigitsOnly);

        blastBox = addBox(leftPos + 92, topPos + 111, 66, 18, "0", 10, RecipeEditorScreen::digitsOnly);
        heatBox = addBox(leftPos + 164, topPos + 111, 66, 18, "0", 10, RecipeEditorScreen::digitsOnly);
        manaBox = addBox(leftPos + 236, topPos + 111, 76, 18, "0", 20, RecipeEditorScreen::signedDigitsOnly);
        temperatureBox = addBox(leftPos + 318, topPos + 111, 60, 18, "0", 10, RecipeEditorScreen::digitsOnly);

        amountBox.setResponder(value -> {
            if (updatingSelectionControls || value == null || value.isBlank()) {
                return;
            }
            if (fluidSelection) {
                long parsed = parseLong(value, -1L);
                if (parsed > 0L) {
                    fluidAmounts[selectedFluidSlot] = parsed;
                }
            } else {
                int parsed = parseInt(value, -1);
                if (parsed > 0) {
                    itemCounts[selectedGhostSlot] = parsed;
                }
            }
        });
        chanceBox.setResponder(value -> {
            if (updatingSelectionControls) {
                return;
            }
            int valueParsed = clamp(parseInt(value, 10_000), 0, 10_000);
            if (fluidSelection) {
                fluidChances[selectedFluidSlot] = valueParsed;
            } else {
                itemChances[selectedGhostSlot] = valueParsed;
            }
        });
        tierBoostBox.setResponder(value -> {
            if (updatingSelectionControls) {
                return;
            }
            int valueParsed = parseInt(value, 0);
            if (fluidSelection) {
                fluidTierChanceBoosts[selectedFluidSlot] = valueParsed;
            } else {
                itemTierChanceBoosts[selectedGhostSlot] = valueParsed;
            }
        });

        notConsumableButton = Button.builder(Component.literal("NC: OFF"), button -> toggleNotConsumable())
                .bounds(leftPos + 306, topPos + 84, 72, 18)
                .build();
        addRenderableWidget(notConsumableButton);

        saveButton = Button.builder(Component.literal("Сохранить JSON"), button -> saveRecipe())
                .bounds(leftPos + 151, topPos + 213, 92, 20)
                .build();
        addRenderableWidget(saveButton);

        clearButton = Button.builder(Component.literal("Очистить"), button -> clearEditor())
                .bounds(leftPos + 249, topPos + 213, 76, 20)
                .build();
        addRenderableWidget(clearButton);

        refreshSelectedControls();

        if (!RecipeEditorMenu.DEFAULT_RECIPE_TYPE.equals(menu.getInitialRecipeType())) {
            statusSuccess = true;
            statusText = "Recipe type автоматически выбран по механизму: " + menu.getInitialRecipeType();
        }
    }

    private EditBox addBox(
            int x,
            int y,
            int width,
            int height,
            String value,
            int maxLength,
            java.util.function.Predicate<String> filter
    ) {
        EditBox box = new EditBox(font, x, y, width, height, Component.empty());
        box.setMaxLength(maxLength);
        if (filter != null) {
            box.setFilter(filter);
        }
        box.setValue(value);
        addRenderableWidget(box);
        return box;
    }

    private static boolean digitsOnly(String value) {
        return value.matches("[0-9]*");
    }

    private static boolean signedDigitsOnly(String value) {
        return value.matches("-?[0-9]*");
    }

    private void toggleNotConsumable() {
        if (fluidSelection) {
            if (!isFluidInput(selectedFluidSlot)) {
                return;
            }
            fluidNotConsumable[selectedFluidSlot] = !fluidNotConsumable[selectedFluidSlot];
            if (fluidNotConsumable[selectedFluidSlot]) {
                fluidChances[selectedFluidSlot] = 10_000;
            }
        } else {
            if (!RecipeEditorMenu.isInputGhostSlot(selectedGhostSlot)) {
                return;
            }
            itemNotConsumable[selectedGhostSlot] = !itemNotConsumable[selectedGhostSlot];
            if (itemNotConsumable[selectedGhostSlot]) {
                itemChances[selectedGhostSlot] = 10_000;
            }
        }
        refreshSelectedControls();
    }

    private void refreshSelectedControls() {
        if (amountBox == null || chanceBox == null || tierBoostBox == null || notConsumableButton == null) {
            return;
        }

        updatingSelectionControls = true;
        try {
            boolean input;
            boolean nc;
            if (fluidSelection) {
                amountBox.setValue(Long.toString(Math.max(1L, fluidAmounts[selectedFluidSlot])));
                chanceBox.setValue(Integer.toString(fluidChances[selectedFluidSlot]));
                tierBoostBox.setValue(Integer.toString(fluidTierChanceBoosts[selectedFluidSlot]));
                input = isFluidInput(selectedFluidSlot);
                nc = input && fluidNotConsumable[selectedFluidSlot];
            } else {
                amountBox.setValue(Integer.toString(Math.max(1, itemCounts[selectedGhostSlot])));
                chanceBox.setValue(Integer.toString(itemChances[selectedGhostSlot]));
                tierBoostBox.setValue(Integer.toString(itemTierChanceBoosts[selectedGhostSlot]));
                input = RecipeEditorMenu.isInputGhostSlot(selectedGhostSlot);
                nc = input && itemNotConsumable[selectedGhostSlot];
            }

            notConsumableButton.active = input;
            notConsumableButton.setMessage(Component.literal(input ? (nc ? "NC: ON" : "NC: OFF") : "NC: N/A"));
            chanceBox.active = !input || !nc;
        } finally {
            updatingSelectionControls = false;
        }
    }

    private void commitSelectedAmount() {
        if (fluidSelection) {
            fluidAmounts[selectedFluidSlot] = parsePositiveLong(amountBox.getValue(), "Amount (mB)");
        } else {
            itemCounts[selectedGhostSlot] = parsePositiveInt(amountBox.getValue(), "Count");
        }
    }

    private void saveRecipe() {
        statusText = "";

        try {
            JsonObject recipe = buildRecipeJson();
            String json = GSON.toJson(recipe);
            RecipeEditorNetwork.CHANNEL.sendToServer(new RecipeEditorSavePacket(json));
            statusSuccess = true;
            statusText = "Отправлено на сохранение...";
        } catch (IllegalArgumentException exception) {
            statusSuccess = false;
            statusText = exception.getMessage();
        }
    }

    private JsonObject buildRecipeJson() {
        commitSelectedAmount();

        String id = idBox.getValue().trim();
        String type = typeBox.getValue().trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("Укажи ID рецепта");
        }
        if (type.isEmpty()) {
            throw new IllegalArgumentException("Укажи тип рецепта");
        }

        JsonObject recipe = new JsonObject();
        recipe.addProperty("enabled", true);
        recipe.addProperty("type", type);
        recipe.addProperty("id", id);

        recipe.addProperty("duration", parsePositiveInt(durationBox.getValue(), "Duration"));
        recipe.addProperty("eut", parseLong(eutBox.getValue(), 0L));

        int circuit = parseInt(circuitBox.getValue(), 0);
        if (circuit < 0 || circuit > 32) {
            throw new IllegalArgumentException("Circuit должен быть от 0 до 32");
        }
        if (circuit != 0) {
            recipe.addProperty("circuit", circuit);
        }

        int priority = parseInt(priorityBox.getValue(), 0);
        if (priority != 0) {
            recipe.addProperty("priority", priority);
        }

        int blast = parseNonNegativeInt(blastBox.getValue(), "EBF/Coil K");
        if (blast > 0) {
            recipe.addProperty("blast_furnace_temp", blast);
        }

        int heat = parseNonNegativeInt(heatBox.getValue(), "Нагрев K");
        if (heat > 0) {
            recipe.addProperty("heat", heat);
        }

        long mana = parseLong(manaBox.getValue(), 0L);
        if (mana != 0L) {
            recipe.addProperty("mana_per_tick", mana);
        }

        int temperature = parseNonNegativeInt(temperatureBox.getValue(), "Темп. K");
        if (temperature > 0) {
            recipe.addProperty("temperature", temperature);
        }

        JsonArray itemInputs = new JsonArray();
        for (int slot = 0; slot < RecipeEditorMenu.INPUT_SLOT_COUNT; slot++) {
            ItemStack stack = menu.getGhostItem(slot);
            if (!stack.isEmpty()) {
                itemInputs.add(itemEntry(stack, slot, true));
            }
        }
        if (itemInputs.size() > 0) {
            recipe.add("item_inputs", itemInputs);
        }

        JsonArray itemOutputs = new JsonArray();
        for (int slot = RecipeEditorMenu.INPUT_SLOT_COUNT; slot < RecipeEditorMenu.GHOST_SLOT_COUNT; slot++) {
            ItemStack stack = menu.getGhostItem(slot);
            if (!stack.isEmpty()) {
                itemOutputs.add(itemEntry(stack, slot, false));
            }
        }
        if (itemOutputs.size() > 0) {
            recipe.add("item_outputs", itemOutputs);
        }

        JsonArray fluidInputs = new JsonArray();
        for (int slot = 0; slot < FLUID_INPUT_SLOT_COUNT; slot++) {
            if (fluidIds[slot] != null) {
                fluidInputs.add(fluidEntry(slot, true));
            }
        }
        if (fluidInputs.size() > 0) {
            recipe.add("fluid_inputs", fluidInputs);
        }

        JsonArray fluidOutputs = new JsonArray();
        for (int slot = FLUID_INPUT_SLOT_COUNT; slot < FLUID_SLOT_COUNT; slot++) {
            if (fluidIds[slot] != null) {
                fluidOutputs.add(fluidEntry(slot, false));
            }
        }
        if (fluidOutputs.size() > 0) {
            recipe.add("fluid_outputs", fluidOutputs);
        }

        if (itemInputs.size() == 0 && fluidInputs.size() == 0) {
            throw new IllegalArgumentException("Добавь хотя бы один вход");
        }
        if (itemOutputs.size() == 0 && fluidOutputs.size() == 0) {
            throw new IllegalArgumentException("Добавь хотя бы один выход");
        }

        return recipe;
    }

    private JsonObject itemEntry(ItemStack stack, int slot, boolean input) {
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) {
            throw new IllegalArgumentException("Не удалось определить ID предмета в слоте " + (slot + 1));
        }

        int count = itemCounts[slot];
        if (count <= 0) {
            throw new IllegalArgumentException("Count в item-слоте " + (slot + 1) + " должен быть > 0");
        }

        JsonObject entry = new JsonObject();
        entry.addProperty("item", itemId.toString());
        entry.addProperty("count", count);
        if (stack.hasTag() && stack.getTag() != null) {
            entry.addProperty("nbt", stack.getTag().toString());
        }

        int chance = clamp(itemChances[slot], 0, 10_000);
        if (input && itemNotConsumable[slot]) {
            entry.addProperty("not_consumable", true);
        } else if (chance != 10_000) {
            entry.addProperty("chance", chance);
            if (itemTierChanceBoosts[slot] != 0) {
                entry.addProperty("tier_chance_boost", itemTierChanceBoosts[slot]);
            }
        }
        return entry;
    }

    private JsonObject fluidEntry(int slot, boolean input) {
        ResourceLocation fluidId = fluidIds[slot];
        if (fluidId == null) {
            throw new IllegalArgumentException("Пустой fluid slot " + (slot + 1));
        }

        long amount = fluidAmounts[slot];
        if (amount <= 0L) {
            throw new IllegalArgumentException("Fluid amount должен быть > 0");
        }

        JsonObject entry = new JsonObject();
        entry.addProperty("fluid", fluidId.toString());
        entry.addProperty("amount", amount);

        int chance = clamp(fluidChances[slot], 0, 10_000);
        if (input && fluidNotConsumable[slot]) {
            entry.addProperty("not_consumable", true);
        } else if (chance != 10_000) {
            entry.addProperty("chance", chance);
            if (fluidTierChanceBoosts[slot] != 0) {
                entry.addProperty("tier_chance_boost", fluidTierChanceBoosts[slot]);
            }
        }
        return entry;
    }

    private void clearEditor() {
        idBox.setValue("cointcoregto:my_recipe");
        typeBox.setValue(menu.getInitialRecipeType());
        durationBox.setValue("200");
        eutBox.setValue("16");
        circuitBox.setValue("0");
        priorityBox.setValue("0");
        blastBox.setValue("0");
        heatBox.setValue("0");
        manaBox.setValue("0");
        temperatureBox.setValue("0");

        Arrays.fill(itemCounts, 1);
        Arrays.fill(itemChances, 10_000);
        Arrays.fill(itemTierChanceBoosts, 0);
        Arrays.fill(itemNotConsumable, false);
        Arrays.fill(fluidIds, null);
        Arrays.fill(fluidAmounts, 1_000L);
        Arrays.fill(fluidChances, 10_000);
        Arrays.fill(fluidTierChanceBoosts, 0);
        Arrays.fill(fluidNotConsumable, false);

        for (int slot = 0; slot < RecipeEditorMenu.GHOST_SLOT_COUNT; slot++) {
            menu.setGhostItem(slot, ItemStack.EMPTY);
            RecipeEditorNetwork.CHANNEL.sendToServer(new RecipeEditorGhostUpdatePacket(slot, ItemStack.EMPTY));
        }

        selectedGhostSlot = 0;
        selectedFluidSlot = 0;
        fluidSelection = false;
        refreshSelectedControls();
        statusSuccess = true;
        statusText = "Форма очищена";
    }

    public int getItemTargetScreenX(int index) {
        Slot slot = menu.getSlot(index);
        return leftPos + slot.x - 1;
    }

    public int getItemTargetScreenY(int index) {
        Slot slot = menu.getSlot(index);
        return topPos + slot.y - 1;
    }

    public int getItemTargetWidth() {
        return 18;
    }

    public int getItemTargetHeight() {
        return 18;
    }

    public boolean setItemFromEmi(int index, ItemStack stack, long amount) {
        if (!RecipeEditorMenu.isGhostSlot(index) || stack == null || stack.isEmpty()) {
            return false;
        }

        ItemStack template = stack.copy();
        template.setCount(1);
        menu.setGhostItem(index, template);
        RecipeEditorNetwork.CHANNEL.sendToServer(new RecipeEditorGhostUpdatePacket(index, template));

        itemCounts[index] = normalizeItemAmount(amount);
        selectedGhostSlot = index;
        fluidSelection = false;
        refreshSelectedControls();

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(template.getItem());
        statusSuccess = true;
        statusText = "Item "
                + (RecipeEditorMenu.isInputGhostSlot(index) ? "input " : "output ")
                + (RecipeEditorMenu.isInputGhostSlot(index)
                ? index + 1
                : index - RecipeEditorMenu.INPUT_SLOT_COUNT + 1)
                + ": " + (itemId == null ? "?" : itemId)
                + " x" + itemCounts[index];
        return true;
    }

    public int getFluidTargetScreenX(int index) {
        int local = isFluidInput(index) ? index : index - FLUID_INPUT_SLOT_COUNT;
        int column = local % 3;
        return leftPos + (isFluidInput(index) ? FLUID_INPUT_GRID_X : FLUID_OUTPUT_GRID_X) + column * 18 - 1;
    }

    public int getFluidTargetScreenY(int index) {
        int local = isFluidInput(index) ? index : index - FLUID_INPUT_SLOT_COUNT;
        int row = local / 3;
        return topPos + FLUID_GRID_Y + row * 18 - 1;
    }

    public int getFluidTargetWidth() {
        return 18;
    }

    public int getFluidTargetHeight() {
        return 18;
    }

    public boolean setFluidFromEmi(int index, ResourceLocation fluidId, long amount) {
        if (index < 0 || index >= FLUID_SLOT_COUNT || fluidId == null) {
            return false;
        }

        fluidIds[index] = fluidId;
        fluidAmounts[index] = amount <= 0L ? 1_000L : Math.max(1L, amount);
        selectedFluidSlot = index;
        fluidSelection = true;
        refreshSelectedControls();

        statusSuccess = true;
        statusText = (isFluidInput(index) ? "Fluid input " : "Fluid output ")
                + (isFluidInput(index) ? index + 1 : index - FLUID_INPUT_SLOT_COUNT + 1)
                + ": " + fluidId
                + " x" + fluidAmounts[index] + " mB";
        return true;
    }

    public void onSaveResult(boolean success, String message) {
        this.statusSuccess = success;
        this.statusText = message == null ? "" : message;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int fluidSlot = findFluidSlot(mouseX, mouseY);
        if (fluidSlot >= 0) {
            selectedFluidSlot = fluidSlot;
            fluidSelection = true;
            if (button == 1) {
                fluidIds[fluidSlot] = null;
                fluidAmounts[fluidSlot] = 1_000L;
                fluidChances[fluidSlot] = 10_000;
                fluidTierChanceBoosts[fluidSlot] = 0;
                if (isFluidInput(fluidSlot)) {
                    fluidNotConsumable[fluidSlot] = false;
                }
                statusSuccess = true;
                statusText = "Fluid slot очищен";
            }
            refreshSelectedControls();
            return true;
        }

        int ghostSlot = findGhostSlot(mouseX, mouseY);
        ItemStack carriedBefore = ghostSlot >= 0 ? menu.getCarried().copy() : ItemStack.EMPTY;

        if (ghostSlot >= 0) {
            selectedGhostSlot = ghostSlot;
            fluidSelection = false;
            refreshSelectedControls();
        }

        boolean handled = super.mouseClicked(mouseX, mouseY, button);

        if (ghostSlot >= 0) {
            if (!carriedBefore.isEmpty()) {
                itemCounts[ghostSlot] = button == 1 ? 1 : Math.max(1, carriedBefore.getCount());
            } else if (menu.getGhostItem(ghostSlot).isEmpty()) {
                itemCounts[ghostSlot] = 1;
            }
            refreshSelectedControls();
        }

        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int fluidSlot = findFluidSlot(mouseX, mouseY);
        if (fluidSlot >= 0 && fluidIds[fluidSlot] != null) {
            long step = Screen.hasShiftDown() ? 100_000L : 1_000L;
            int direction = delta > 0.0D ? 1 : -1;
            fluidAmounts[fluidSlot] = addClampedPositive(fluidAmounts[fluidSlot], direction, step);
            selectedFluidSlot = fluidSlot;
            fluidSelection = true;
            refreshSelectedControls();
            return true;
        }

        int ghostSlot = findGhostSlot(mouseX, mouseY);
        if (ghostSlot >= 0 && !menu.getGhostItem(ghostSlot).isEmpty()) {
            int direction = delta > 0.0D ? 1 : -1;
            long step = Screen.hasShiftDown() ? 64L : 1L;
            long changed = (long) itemCounts[ghostSlot] + direction * step;
            itemCounts[ghostSlot] = (int) Math.max(1L, Math.min(MAX_ITEM_COUNT, changed));

            selectedGhostSlot = ghostSlot;
            fluidSelection = false;
            refreshSelectedControls();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private int findGhostSlot(double mouseX, double mouseY) {
        for (int slotIndex = 0; slotIndex < RecipeEditorMenu.GHOST_SLOT_COUNT; slotIndex++) {
            Slot slot = menu.getSlot(slotIndex);
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                return slotIndex;
            }
        }
        return -1;
    }

    private int findFluidSlot(double mouseX, double mouseY) {
        for (int index = 0; index < FLUID_SLOT_COUNT; index++) {
            int x = getFluidTargetScreenX(index) + 1;
            int y = getFluidTargetScreenY(index) + 1;
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                return index;
            }
        }
        return -1;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderLogicalItemCounts(graphics);
        renderFluidContents(graphics);
        renderTooltip(graphics, mouseX, mouseY);
    }

    private void renderLogicalItemCounts(GuiGraphics graphics) {
        for (int slotIndex = 0; slotIndex < RecipeEditorMenu.GHOST_SLOT_COUNT; slotIndex++) {
            if (menu.getGhostItem(slotIndex).isEmpty()) {
                continue;
            }

            int count = itemCounts[slotIndex];
            if (count <= 1) {
                continue;
            }

            Slot slot = menu.getSlot(slotIndex);
            String text = formatCountOverlay(count);
            int x = leftPos + slot.x + 17 - font.width(text);
            int y = topPos + slot.y + 9;
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 400.0F);
            graphics.drawString(font, text, x, y, 0xFFFFFF, true);
            graphics.pose().popPose();
        }
    }

    private void renderFluidContents(GuiGraphics graphics) {
        for (int index = 0; index < FLUID_SLOT_COUNT; index++) {
            ResourceLocation id = fluidIds[index];
            if (id == null) {
                continue;
            }

            Fluid fluid = ForgeRegistries.FLUIDS.getValue(id);
            if (fluid == null) {
                continue;
            }

            int x = getFluidTargetScreenX(index) + 1;
            int y = getFluidTargetScreenY(index) + 1;
            EmiStack.of(fluid, Math.max(1L, fluidAmounts[index]))
                    .render(graphics, x, y, 0.0F, EmiIngredient.RENDER_ICON);

            String text = formatFluidOverlay(fluidAmounts[index]);
            int textX = x + 17 - font.width(text);
            int textY = y + 9;
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 400.0F);
            graphics.drawString(font, text, textX, textY, 0xFFFFFF, true);
            graphics.pose().popPose();
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xF0121824);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 2, 0xFF65E6FF);
        graphics.fill(leftPos + 8, topPos + 47, leftPos + 84, topPos + 132, 0xFF1B2638);
        graphics.fill(leftPos + 398, topPos + 47, leftPos + 478, topPos + 132, 0xFF1B2638);
        graphics.fill(leftPos + 86, topPos + 47, leftPos + 398, topPos + 238, 0xFF172132);
        graphics.fill(leftPos + 8, topPos + 147, leftPos + 74, topPos + 216, 0xFF16283A);
        graphics.fill(leftPos + 412, topPos + 147, leftPos + 478, topPos + 216, 0xFF2D2618);
        graphics.fill(leftPos + 156, topPos + 266, leftPos + 330, topPos + 348, 0xFF172132);

        for (int slotIndex = 0; slotIndex < RecipeEditorMenu.GHOST_SLOT_COUNT; slotIndex++) {
            Slot slot = menu.getSlot(slotIndex);
            int x = leftPos + slot.x - 1;
            int y = topPos + slot.y - 1;
            int border = !fluidSelection && slotIndex == selectedGhostSlot ? 0xFFFFD36F : 0xFF48566C;
            graphics.fill(x, y, x + 18, y + 18, border);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF202B3C);
        }

        for (int index = 0; index < FLUID_SLOT_COUNT; index++) {
            int x = getFluidTargetScreenX(index);
            int y = getFluidTargetScreenY(index);
            int border = fluidSelection && index == selectedFluidSlot ? 0xFFFFD36F : 0xFF49677A;
            graphics.fill(x, y, x + 18, y + 18, border);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF152A36);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 10, 8, 0xFFFFFF, false);
        graphics.drawString(font, "Recipe ID", 12, 14, 0xA9B7CC, false);
        graphics.drawString(font, "Recipe type (ПКМ по машине = auto)", 224, 14, 0xA9B7CC, false);
        graphics.drawString(font, "ITEM INPUTS 4x4", 12, 48, 0x6FE9FF, false);
        graphics.drawString(font, "ITEM OUTPUTS 4x4", 398, 48, 0xFFD36F, false);

        graphics.drawString(font, "Duration", 92, 48, 0xA9B7CC, false);
        graphics.drawString(font, "EU/t", 164, 48, 0xA9B7CC, false);
        graphics.drawString(font, "Circuit", 242, 48, 0xA9B7CC, false);
        graphics.drawString(font, "Priority", 306, 48, 0xA9B7CC, false);

        graphics.drawString(font, fluidSelection ? "Amount mB" : "Count", 92, 75, 0xA9B7CC, false);
        graphics.drawString(font, "Chance", 164, 75, 0xA9B7CC, false);
        graphics.drawString(font, "Tier +", 242, 75, 0xA9B7CC, false);

        graphics.drawString(font, "EBF/Coil K", 92, 102, 0xA9B7CC, false);
        graphics.drawString(font, "Нагрев K", 164, 102, 0xA9B7CC, false);
        graphics.drawString(font, "Mana/t", 236, 102, 0xA9B7CC, false);
        graphics.drawString(font, "Темп. K", 318, 102, 0xA9B7CC, false);

        graphics.drawString(font, selectedDescription(), 92, 135, 0xFFFFFF, false);
        graphics.drawString(font, "FLUID IN 3x3", 10, 148, 0x6FE9FF, false);
        graphics.drawString(font, "FLUID OUT 3x3", 410, 148, 0xFFD36F, false);

        String[] parameterHelp = getParameterHelp(mouseX, mouseY);
        if (parameterHelp != null) {
            graphics.drawString(font, parameterHelp[0], 86, 157, 0xD7E4F5, false);
            graphics.drawString(font, parameterHelp[1], 86, 169, 0x9FB0C6, false);
        } else {
            graphics.drawString(font, "Наведи на любое поле — здесь появится пояснение", 86, 157, 0x9FB0C6, false);
            graphics.drawString(font, "Fluid: drag из EMI; ЛКМ выбрать; ПКМ очистить; колесо ±1B, Shift ±100B", 86, 169, 0x8291A6, false);
        }

        if (!statusText.isBlank()) {
            String visible = statusText.length() > 82 ? statusText.substring(0, 82) + "..." : statusText;
            graphics.drawString(font, visible, 86, 190, statusSuccess ? 0x6FFF8B : 0xFF7070, false);
        } else {
            graphics.drawString(font, "Item: drag из EMI; колесо ±1, Shift ±64; Assembly Line поддерживает 16 inputs", 86, 190, 0x8291A6, false);
        }

        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, 0xCCCCCC, false);
    }

    private String selectedDescription() {
        if (fluidSelection) {
            ResourceLocation id = fluidIds[selectedFluidSlot];
            return (isFluidInput(selectedFluidSlot) ? "Selected fluid IN " : "Selected fluid OUT ")
                    + (isFluidInput(selectedFluidSlot)
                    ? selectedFluidSlot + 1
                    : selectedFluidSlot - FLUID_INPUT_SLOT_COUNT + 1)
                    + (id == null ? " (empty)" : ": " + id);
        }
        return RecipeEditorMenu.isInputGhostSlot(selectedGhostSlot)
                ? "Selected item IN " + (selectedGhostSlot + 1)
                : "Selected item OUT " + (selectedGhostSlot - RecipeEditorMenu.INPUT_SLOT_COUNT + 1);
    }

    private String[] getParameterHelp(int mouseX, int mouseY) {
        int x = mouseX - leftPos;
        int y = mouseY - topPos;

        if (inside(x, y, 10, 13, 204, 31)) {
            return help("Recipe ID -> id", "Уникальный ResourceLocation: namespace:path. Например cointcoregto:my_motor.");
        }
        if (inside(x, y, 222, 13, 256, 31)) {
            return help("Recipe type -> type", "Тип машины/рецепта. ПКМ редактором по GT/GTO машине подставляет его автоматически.");
        }
        if (inside(x, y, 90, 47, 70, 31)) {
            return help("Duration -> duration", "Длительность в тиках: 20 ticks = 1 секунда.");
        }
        if (inside(x, y, 162, 47, 76, 31)) {
            return help("EU/t -> eut", "Энергия за тик. Итоговая EU = |EU/t| x duration.");
        }
        if (inside(x, y, 240, 47, 62, 31)) {
            return help("Circuit -> circuit", "Integrated Circuit meta 1..32. 0 = circuit не добавляется.");
        }
        if (inside(x, y, 304, 47, 76, 31)) {
            return help("Priority -> priority", "Приоритет выбора рецепта при совпадающих входах. 0 = стандартный.");
        }
        if (inside(x, y, 90, 74, 70, 31)) {
            return fluidSelection
                    ? help("Amount mB -> fluid amount", "Количество выбранной жидкости. 1000 mB = 1 bucket; поддерживается long.")
                    : help("Count -> item count", "Количество выбранного предмета. Может быть >64, максимум 2 147 483 647.");
        }
        if (inside(x, y, 162, 74, 76, 31)) {
            return help("Chance -> chance", "Шанс 0..10000: 10000=100%, 2500=25%. Применяется к выбранному item/fluid.");
        }
        if (inside(x, y, 240, 74, 62, 31)) {
            return help("Tier + -> tier_chance_boost", "Добавка к chance за уровень машины. Обычно 0, если рецепт не должен масштабировать шанс.");
        }
        if (inside(x, y, 304, 74, 76, 31)) {
            return help("NC -> not_consumable", "Только для входов: ингредиент/жидкость требуется, но не расходуется.");
        }
        if (inside(x, y, 90, 101, 70, 31)) {
            return help("EBF/Coil K -> blast_furnace_temp", "Температура катушек EBF/coil-машин. Для обычного Assembler обычно не используется.");
        }
        if (inside(x, y, 162, 101, 70, 31)) {
            return help("Нагрев K -> heat", "Внешний источник тепла. В EMI: 'External heat source is required: ... K'.");
        }
        if (inside(x, y, 234, 101, 80, 31)) {
            return help("Mana/t -> mana_per_tick", "Положительное = расход маны/t; отрицательное = генерация/выход маны/t.");
        }
        if (inside(x, y, 316, 101, 64, 31)) {
            return help("Темп. K -> temperature", "Внутренняя process temperature; работает только у recipe type, которые читают temperature.");
        }
        if (inside(x, y, 8, 46, 78, 86)) {
            return help("ITEM INPUTS 4x4", "До 16 предметных входов — достаточно для Assembly Line. Drag предмета из EMI в слот.");
        }
        if (inside(x, y, 398, 46, 80, 86)) {
            return help("ITEM OUTPUTS 4x4", "До 16 предметных выходов. Для выбранного слота Amount/Chance/Tier меняются сверху.");
        }
        if (inside(x, y, 8, 146, 68, 72)) {
            return help("FLUID IN 3x3", "До 9 жидкостей-входов. Drag из EMI; Amount в mB; ПКМ по слоту очищает.");
        }
        if (inside(x, y, 410, 146, 68, 72)) {
            return help("FLUID OUT 3x3", "До 9 жидкостей-выходов. Chance/Tier также работают для выбранной жидкости.");
        }
        if (inside(x, y, 149, 211, 96, 24)) {
            return help("Сохранить JSON", "Сохраняет рецепт в config/cointcoregto/gto_recipes/editor/. Нужен полный рестарт.");
        }
        if (inside(x, y, 247, 211, 80, 24)) {
            return help("Очистить", "Сбрасывает форму, item/fluid ghost slots и параметры выбранного рецепта.");
        }
        return null;
    }

    private static boolean inside(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static String[] help(String title, String description) {
        return new String[]{title, description};
    }

    private static String formatCountOverlay(int count) {
        if (count >= 1_000_000_000) {
            return (count / 1_000_000_000) + "B";
        }
        if (count >= 1_000_000) {
            return (count / 1_000_000) + "M";
        }
        if (count >= 10_000) {
            return (count / 1_000) + "K";
        }
        return Integer.toString(count);
    }

    private static String formatFluidOverlay(long amount) {
        if (amount >= 1_000_000_000L) {
            return (amount / 1_000_000_000L) + "G";
        }
        if (amount >= 1_000_000L) {
            return (amount / 1_000_000L) + "M";
        }
        if (amount >= 1_000L) {
            if (amount % 1_000L == 0L) {
                return (amount / 1_000L) + "B";
            }
            return (amount / 1_000L) + "K";
        }
        return amount + "m";
    }

    private static int normalizeItemAmount(long amount) {
        if (amount <= 0L) {
            return 1;
        }
        return (int) Math.min(MAX_ITEM_COUNT, amount);
    }

    private static long addClampedPositive(long current, int direction, long step) {
        if (direction > 0) {
            if (current > Long.MAX_VALUE - step) {
                return Long.MAX_VALUE;
            }
            return current + step;
        }
        return Math.max(1L, current - Math.min(current - 1L, step));
    }

    private static boolean isFluidInput(int slot) {
        return slot >= 0 && slot < FLUID_INPUT_SLOT_COUNT;
    }

    private static int parsePositiveInt(String value, String label) {
        int parsed = parseInt(value, 0);
        if (parsed <= 0) {
            throw new IllegalArgumentException(label + " должен быть > 0");
        }
        return parsed;
    }

    private static int parseNonNegativeInt(String value, String label) {
        int parsed = parseInt(value, 0);
        if (parsed < 0) {
            throw new IllegalArgumentException(label + " должен быть >= 0");
        }
        return parsed;
    }

    private static long parsePositiveLong(String value, String label) {
        long parsed = parseLong(value, 0L);
        if (parsed <= 0L) {
            throw new IllegalArgumentException(label + " должен быть > 0");
        }
        return parsed;
    }

    private static int parseInt(String value, int fallback) {
        try {
            if (value == null || value.isBlank() || "-".equals(value)) {
                return fallback;
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            if (value == null || value.isBlank() || "-".equals(value)) {
                return fallback;
            }
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}