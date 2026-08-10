package Crazer.cubeofinterest.cointcoregto.recipe.editor;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

public final class RecipeEditorMenu extends AbstractContainerMenu {
    public static final int INPUT_SLOT_COUNT = 16;
    public static final int OUTPUT_SLOT_COUNT = 16;
    public static final int GHOST_SLOT_COUNT = INPUT_SLOT_COUNT + OUTPUT_SLOT_COUNT;
    public static final String DEFAULT_RECIPE_TYPE = "gtceu:assembler";

    private static final int PLAYER_INVENTORY_START = GHOST_SLOT_COUNT;
    private static final int PLAYER_INVENTORY_END = PLAYER_INVENTORY_START + 27;
    private static final int HOTBAR_START = PLAYER_INVENTORY_END;
    private static final int HOTBAR_END = HOTBAR_START + 9;

    private final SimpleContainer ghostItems = new SimpleContainer(GHOST_SLOT_COUNT);
    private final String initialRecipeType;

    public RecipeEditorMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, DEFAULT_RECIPE_TYPE);
    }

    public RecipeEditorMenu(int containerId, Inventory inventory, String initialRecipeType) {
        super(CointRecipeEditorRegistry.RECIPE_EDITOR_MENU.get(), containerId);
        this.initialRecipeType = normalizeRecipeType(initialRecipeType);

        
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                int index = row * 4 + column;
                addSlot(new GhostSlot(
                        ghostItems,
                        index,
                        14 + column * 18,
                        58 + row * 18
                ));
            }
        }

        
        
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                int index = INPUT_SLOT_COUNT + row * 4 + column;
                addSlot(new GhostSlot(
                        ghostItems,
                        index,
                        404 + column * 18,
                        58 + row * 18
                ));
            }
        }

        addPlayerInventory(inventory);
        addPlayerHotbar(inventory);
    }

    public String getInitialRecipeType() {
        return initialRecipeType;
    }

    public ItemStack getGhostItem(int slot) {
        if (!isGhostSlot(slot)) {
            return ItemStack.EMPTY;
        }
        return ghostItems.getItem(slot);
    }

    public void setGhostItem(int slot, ItemStack stack) {
        if (!isGhostSlot(slot)) {
            return;
        }
        ghostItems.setItem(slot, sanitizeTemplate(stack));
    }

    public void clearGhostItems() {
        for (int slot = 0; slot < GHOST_SLOT_COUNT; slot++) {
            ghostItems.setItem(slot, ItemStack.EMPTY);
        }
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (isGhostSlot(slotId)) {
            handleGhostClick(slotId, button, clickType, player);
            return;
        }
        super.clicked(slotId, button, clickType, player);
    }

    private void handleGhostClick(int slotId, int button, ClickType clickType, Player player) {
        if (clickType == ClickType.PICKUP) {
            ItemStack carried = getCarried();
            if (carried.isEmpty()) {
                setGhostItem(slotId, ItemStack.EMPTY);
                return;
            }

            ItemStack template = carried.copy();
            template.setCount(1);
            setGhostItem(slotId, template);
            return;
        }

        if (clickType == ClickType.SWAP && button >= 0 && button < 9) {
            ItemStack template = player.getInventory().getItem(button).copy();
            if (!template.isEmpty()) {
                template.setCount(1);
            }
            setGhostItem(slotId, template);
            return;
        }

        if (clickType == ClickType.THROW) {
            setGhostItem(slotId, ItemStack.EMPTY);
        }
    }

    private static ItemStack sanitizeTemplate(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        
        
        
        ItemStack result = stack.copy();
        result.setCount(1);
        return result;
    }

    private static String normalizeRecipeType(String value) {
        if (value == null) {
            return DEFAULT_RECIPE_TYPE;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? DEFAULT_RECIPE_TYPE : trimmed;
    }

    public static boolean isGhostSlot(int slotId) {
        return slotId >= 0 && slotId < GHOST_SLOT_COUNT;
    }

    public static boolean isInputGhostSlot(int slotId) {
        return slotId >= 0 && slotId < INPUT_SLOT_COUNT;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        if (slot != null) {
            int menuIndex = slots.indexOf(slot);
            if (isGhostSlot(menuIndex)) {
                return false;
            }
        }
        return super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    public @NotNull ItemStack quickMoveStack(Player player, int index) {
        if (index < PLAYER_INVENTORY_START || index >= HOTBAR_END || index >= slots.size()) {
            return ItemStack.EMPTY;
        }

        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack source = slot.getItem();
        ItemStack result = source.copy();
        boolean moved;

        if (index < PLAYER_INVENTORY_END) {
            moved = moveItemStackTo(source, HOTBAR_START, HOTBAR_END, false);
        } else {
            moved = moveItemStackTo(source, PLAYER_INVENTORY_START, PLAYER_INVENTORY_END, false);
        }

        if (!moved) {
            return ItemStack.EMPTY;
        }

        if (source.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        if (source.getCount() == result.getCount()) {
            return ItemStack.EMPTY;
        }

        slot.onTake(player, source);
        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isAlive();
    }

    private void addPlayerInventory(Inventory inventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(
                        inventory,
                        column + row * 9 + 9,
                        162 + column * 18,
                        274 + row * 18
                ));
            }
        }
    }

    private void addPlayerHotbar(Inventory inventory) {
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(
                    inventory,
                    column,
                    162 + column * 18,
                    332
            ));
        }
    }

    private static final class GhostSlot extends Slot {
        private GhostSlot(SimpleContainer container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }
}