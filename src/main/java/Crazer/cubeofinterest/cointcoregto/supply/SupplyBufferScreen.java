package Crazer.cubeofinterest.cointcoregto.supply;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

public class SupplyBufferScreen extends AbstractContainerScreen<SupplyBufferMenu> {
    private static final int GUI_WIDTH = 232;
    private static final int GUI_HEIGHT = 322;

    private static final int FILTER_X = SupplyBufferMenu.FILTER_ROW_X;
    private static final int ITEM_FILTER_Y = 55;
    private static final int FLUID_FILTER_Y = 96;
    private static final int SLOT_SIZE = 18;

    private Button itemBelowButton;
    private Button fluidBelowButton;
    private EditBox itemTargetBox;
    private EditBox fluidTargetBox;
    private int selectedItemFilter = 0;
    private int selectedFluidFilter = 0;

    public SupplyBufferScreen(
            SupplyBufferMenu menu,
            Inventory playerInventory,
            Component title
    ) {
        super(menu, playerInventory, Component.literal("Межсерверный буфер снабжения"));
        this.imageWidth = GUI_WIDTH;
        this.imageHeight = GUI_HEIGHT;
        this.inventoryLabelX = SupplyBufferMenu.FILTER_ROW_X;
        this.inventoryLabelY = 227;
    }

    @Override
    protected void init() {
        this.imageWidth = GUI_WIDTH;
        this.imageHeight = GUI_HEIGHT;
        super.init();

        itemBelowButton = addRenderableWidget(Button.builder(
                Component.empty(),
                button -> sendAction(0)
        ).bounds(leftPos + 82, topPos + 31, 68, 20).build());

        itemTargetBox = addRenderableWidget(new EditBox(
                font,
                leftPos + 154,
                topPos + 31,
                68,
                20,
                Component.literal("Цель предметов")
        ));
        itemTargetBox.setMaxLength(24);
        itemTargetBox.setHint(Component.literal("кол-во"));

        fluidBelowButton = addRenderableWidget(Button.builder(
                Component.empty(),
                button -> sendAction(2)
        ).bounds(leftPos + 82, topPos + 72, 68, 20).build());

        fluidTargetBox = addRenderableWidget(new EditBox(
                font,
                leftPos + 154,
                topPos + 72,
                68,
                20,
                Component.literal("Цель жидкости")
        ));
        fluidTargetBox.setMaxLength(24);
        fluidTargetBox.setHint(Component.literal("mB"));

        selectedItemFilter = firstConfiguredItemFilter();
        selectedFluidFilter = firstConfiguredFluidFilter();
        refreshControls(true);
    }

    private int firstConfiguredItemFilter() {
        for (int index = 0; index < SupplyBufferMenu.FILTER_COUNT; index++) {
            if (!menu.getSupplyBuffer().getConfiguredItemStack(index).isEmpty()) {
                return index;
            }
        }
        return 0;
    }

    private int firstConfiguredFluidFilter() {
        for (int index = 0; index < SupplyBufferMenu.FILTER_COUNT; index++) {
            if (!menu.getSupplyBuffer().getConfiguredFluidStack(index).isEmpty()) {
                return index;
            }
        }
        return 0;
    }

    private void sendAction(int action) {
        SupplyBufferNetwork.CHANNEL.sendToServer(
                new SupplyBufferSettingsPacket(menu.getBlockPos(), action)
        );
    }

    private void sendFilter(
            SupplyBufferDatabase.ResourceType type,
            int filterIndex,
            String payload
    ) {
        if (!menu.canEdit() || menu.getRole() != SupplyBufferRole.REMOTE) {
            return;
        }
        SupplyBufferNetwork.CHANNEL.sendToServer(new SupplyBufferFilterPacket(
                menu.getBlockPos(),
                type,
                filterIndex,
                payload == null ? "" : payload
        ));
    }

    private void sendTarget(
            SupplyBufferDatabase.ResourceType type,
            int filterIndex,
            long target
    ) {
        if (!menu.canEdit()
                || menu.getRole() != SupplyBufferRole.REMOTE
                || !validFilterIndex(filterIndex)
                || target <= 0L) {
            return;
        }
        SupplyBufferNetwork.CHANNEL.sendToServer(new SupplyBufferTargetPacket(
                menu.getBlockPos(),
                type,
                filterIndex,
                Math.min(SupplyBufferBlockEntity.MAX_VIRTUAL_AMOUNT, target)
        ));
    }

    public boolean setItemFilterFromExternal(int filterIndex, ItemStack stack) {
        if (!validFilterIndex(filterIndex)
                || stack == null
                || stack.isEmpty()
                || !menu.canEdit()
                || menu.getRole() != SupplyBufferRole.REMOTE) {
            return false;
        }
        AEItemKey key = AEItemKey.of(stack);
        if (key == null) {
            return false;
        }
        selectedItemFilter = filterIndex;
        sendFilter(
                SupplyBufferDatabase.ResourceType.ITEM,
                filterIndex,
                SupplyKeyCodec.encode(key)
        );
        return true;
    }

    public boolean setFluidFilterFromExternal(int filterIndex, FluidStack stack) {
        if (!validFilterIndex(filterIndex)
                || stack == null
                || stack.isEmpty()
                || !menu.canEdit()
                || menu.getRole() != SupplyBufferRole.REMOTE) {
            return false;
        }
        AEFluidKey key = AEFluidKey.of(stack);
        if (key == null) {
            return false;
        }
        selectedFluidFilter = filterIndex;
        sendFilter(
                SupplyBufferDatabase.ResourceType.FLUID,
                filterIndex,
                SupplyKeyCodec.encode(key)
        );
        return true;
    }

    public boolean canAcceptEmiFilterDrops() {
        return menu.canEdit() && menu.getRole() == SupplyBufferRole.REMOTE;
    }

    public int getFilterScreenX(int filterIndex) {
        if (!validFilterIndex(filterIndex)) {
            return -10_000;
        }
        return leftPos + FILTER_X + filterIndex * SLOT_SIZE;
    }

    public int getItemFilterScreenY() {
        return topPos + ITEM_FILTER_Y;
    }

    public int getFluidFilterScreenY() {
        return topPos + FLUID_FILTER_Y;
    }

    public int getFilterSlotSize() {
        return SLOT_SIZE;
    }

    public int itemFilterAt(double mouseX, double mouseY) {
        return filterAt(mouseX, mouseY, ITEM_FILTER_Y);
    }

    public int fluidFilterAt(double mouseX, double mouseY) {
        return filterAt(mouseX, mouseY, FLUID_FILTER_Y);
    }

    private int supplySlotAt(double mouseX, double mouseY) {
        return filterAt(mouseX, mouseY, SupplyBufferMenu.SUPPLY_ROW_Y);
    }

    private int filterAt(double mouseX, double mouseY, int rowY) {
        double localX = mouseX - leftPos;
        double localY = mouseY - topPos;
        if (localY < rowY || localY >= rowY + SLOT_SIZE) {
            return -1;
        }
        if (localX < FILTER_X || localX >= FILTER_X + SupplyBufferMenu.FILTER_COUNT * SLOT_SIZE) {
            return -1;
        }
        int index = (int) ((localX - FILTER_X) / SLOT_SIZE);
        return validFilterIndex(index) ? index : -1;
    }

    private static boolean validFilterIndex(int filterIndex) {
        return filterIndex >= 0 && filterIndex < SupplyBufferMenu.FILTER_COUNT;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        refreshControls(false);
    }

    private void refreshControls(boolean forceText) {
        boolean active = menu.canEdit() && menu.getRole() == SupplyBufferRole.REMOTE;
        if (itemBelowButton != null) {
            itemBelowButton.active = active;
            itemBelowButton.setMessage(Component.literal("< " + menu.getItemRefillBelowPercent() + "%"));
        }
        if (fluidBelowButton != null) {
            fluidBelowButton.active = active;
            fluidBelowButton.setMessage(Component.literal("< " + menu.getFluidRefillBelowPercent() + "%"));
        }

        boolean itemConfigured = validFilterIndex(selectedItemFilter)
                && !menu.getSupplyBuffer().getConfiguredItemStack(selectedItemFilter).isEmpty();
        if (itemTargetBox != null) {
            itemTargetBox.setEditable(active && itemConfigured);
            if ((forceText || !itemTargetBox.isFocused()) && itemConfigured) {
                setBoxValueIfDifferent(itemTargetBox, Long.toString(menu.getItemTargetAmount(selectedItemFilter)));
                itemTargetBox.setTextColor(0xFFFFFF);
            } else if ((forceText || !itemTargetBox.isFocused()) && !itemConfigured) {
                setBoxValueIfDifferent(itemTargetBox, "");
            }
        }

        boolean fluidConfigured = validFilterIndex(selectedFluidFilter)
                && !menu.getSupplyBuffer().getConfiguredFluidStack(selectedFluidFilter).isEmpty();
        if (fluidTargetBox != null) {
            fluidTargetBox.setEditable(active && fluidConfigured);
            if ((forceText || !fluidTargetBox.isFocused()) && fluidConfigured) {
                setBoxValueIfDifferent(fluidTargetBox, Long.toString(menu.getFluidTargetAmount(selectedFluidFilter)));
                fluidTargetBox.setTextColor(0xFFFFFF);
            } else if ((forceText || !fluidTargetBox.isFocused()) && !fluidConfigured) {
                setBoxValueIfDifferent(fluidTargetBox, "");
            }
        }
    }

    private static void setBoxValueIfDifferent(EditBox box, String value) {
        if (!box.getValue().equals(value)) {
            box.setValue(value);
        }
    }

    private boolean commitItemTarget() {
        if (itemTargetBox == null || !validFilterIndex(selectedItemFilter)) {
            return false;
        }
        long value = parseAmount(itemTargetBox.getValue());
        if (value <= 0L) {
            itemTargetBox.setTextColor(0xFF5555);
            return false;
        }
        itemTargetBox.setTextColor(0xFFFFFF);
        itemTargetBox.setValue(Long.toString(value));
        sendTarget(SupplyBufferDatabase.ResourceType.ITEM, selectedItemFilter, value);
        return true;
    }

    private boolean commitFluidTarget() {
        if (fluidTargetBox == null || !validFilterIndex(selectedFluidFilter)) {
            return false;
        }
        long value = parseAmount(fluidTargetBox.getValue());
        if (value <= 0L) {
            fluidTargetBox.setTextColor(0xFF5555);
            return false;
        }
        fluidTargetBox.setTextColor(0xFFFFFF);
        fluidTargetBox.setValue(Long.toString(value));
        sendTarget(SupplyBufferDatabase.ResourceType.FLUID, selectedFluidFilter, value);
        return true;
    }

    private static long parseAmount(String text) {
        if (text == null) return -1L;
        String value = text.trim().toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("_", "")
                .replace(",", "");
        if (value.isEmpty()) return -1L;

        if (value.endsWith("mb")) {
            value = value.substring(0, value.length() - 2);
        }

        long multiplier = 1L;
        if (!value.isEmpty()) {
            char suffix = value.charAt(value.length() - 1);
            multiplier = switch (suffix) {
                case 'k' -> 1_000L;
                case 'm' -> 1_000_000L;
                case 'b' -> 1_000_000_000L;
                case 't' -> 1_000_000_000_000L;
                case 'q' -> 1_000_000_000_000_000L;
                default -> 1L;
            };
            if (multiplier != 1L) {
                value = value.substring(0, value.length() - 1);
            }
        }

        try {
            long base = Long.parseLong(value);
            if (base <= 0L) return -1L;
            if (base > SupplyBufferBlockEntity.MAX_VIRTUAL_AMOUNT / multiplier) {
                return SupplyBufferBlockEntity.MAX_VIRTUAL_AMOUNT;
            }
            return Math.min(SupplyBufferBlockEntity.MAX_VIRTUAL_AMOUNT, base * multiplier);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (itemTargetBox != null && itemTargetBox.isFocused() && !itemTargetBox.isMouseOver(mouseX, mouseY)) {
            commitItemTarget();
            itemTargetBox.setFocused(false);
        }
        if (fluidTargetBox != null && fluidTargetBox.isFocused() && !fluidTargetBox.isMouseOver(mouseX, mouseY)) {
            commitFluidTarget();
            fluidTargetBox.setFocused(false);
        }

        if (menu.canEdit() && menu.getRole() == SupplyBufferRole.REMOTE) {
            int itemFilter = itemFilterAt(mouseX, mouseY);
            if (itemFilter >= 0) {
                selectedItemFilter = itemFilter;
                if (button == 1) {
                    sendFilter(SupplyBufferDatabase.ResourceType.ITEM, itemFilter, "");
                    refreshControls(true);
                    return true;
                }
                if (button == 0) {
                    ItemStack carried = menu.getCarried();
                    if (!carried.isEmpty()) {
                        setItemFilterFromExternal(itemFilter, carried);
                    }
                    refreshControls(true);
                    return true;
                }
            }

            int fluidFilter = fluidFilterAt(mouseX, mouseY);
            if (fluidFilter >= 0) {
                selectedFluidFilter = fluidFilter;
                if (button == 1) {
                    sendFilter(SupplyBufferDatabase.ResourceType.FLUID, fluidFilter, "");
                    refreshControls(true);
                    return true;
                }
                if (button == 0) {
                    ItemStack carried = menu.getCarried();
                    if (!carried.isEmpty()) {
                        FluidStack fluid = FluidUtil.getFluidContained(carried).orElse(FluidStack.EMPTY);
                        if (!fluid.isEmpty()) {
                            setFluidFilterFromExternal(fluidFilter, fluid);
                        }
                    }
                    refreshControls(true);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (itemTargetBox != null && itemTargetBox.isFocused()) {
                commitItemTarget();
                itemTargetBox.setFocused(false);
                return true;
            }
            if (fluidTargetBox != null && fluidTargetBox.isFocused()) {
                commitFluidTarget();
                fluidTargetBox.setFocused(false);
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderVirtualSupplySlots(graphics);
        if (supplySlotAt(mouseX, mouseY) < 0) {
            renderTooltip(graphics, mouseX, mouseY);
        }
        renderCustomFilterTooltips(graphics, mouseX, mouseY);
        renderControlTooltips(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xEE171A1F);
        graphics.fill(x + 4, y + 4, x + imageWidth - 4, y + imageHeight - 4, 0xFF252A31);

        for (int filter = 0; filter < SupplyBufferMenu.FILTER_COUNT; filter++) {
            drawGhostSlotBackground(graphics, x + FILTER_X - 1 + filter * 18, y + ITEM_FILTER_Y - 1);
            drawGhostSlotBackground(graphics, x + FILTER_X - 1 + filter * 18, y + FLUID_FILTER_Y - 1);
            if (filter == selectedItemFilter) {
                drawSelection(graphics, x + FILTER_X - 1 + filter * 18, y + ITEM_FILTER_Y - 1);
            }
            if (filter == selectedFluidFilter) {
                drawSelection(graphics, x + FILTER_X - 1 + filter * 18, y + FLUID_FILTER_Y - 1);
            }
            renderItemFilter(graphics, filter, x + FILTER_X + filter * 18, y + ITEM_FILTER_Y);
            renderFluidFilter(graphics, filter, x + FILTER_X + filter * 18, y + FLUID_FILTER_Y);
        }

        for (int column = 0; column < 9; column++) {
            drawSlotBackground(
                    graphics,
                    x + SupplyBufferMenu.FILTER_ROW_X - 1 + column * 18,
                    y + SupplyBufferMenu.SUPPLY_ROW_Y - 1
            );
        }
        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 9; column++) {
                drawSlotBackground(
                        graphics,
                        x + SupplyBufferMenu.FILTER_ROW_X - 1 + column * 18,
                        y + SupplyBufferMenu.EXPORT_ROW_Y - 1 + row * 18
                );
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                drawSlotBackground(
                        graphics,
                        x + SupplyBufferMenu.FILTER_ROW_X - 1 + column * 18,
                        y + SupplyBufferMenu.PLAYER_INVENTORY_Y - 1 + row * 18
                );
            }
        }
        for (int column = 0; column < 9; column++) {
            drawSlotBackground(
                    graphics,
                    x + SupplyBufferMenu.FILTER_ROW_X - 1 + column * 18,
                    y + SupplyBufferMenu.HOTBAR_Y - 1
            );
        }
    }

    private void renderItemFilter(GuiGraphics graphics, int filterIndex, int x, int y) {
        ItemStack stack = menu.getSupplyBuffer().getConfiguredItemStack(filterIndex);
        if (stack.isEmpty()) {
            graphics.drawString(font, "+", x + 5, y + 4, 0x777F89, false);
            return;
        }
        graphics.renderItem(stack, x, y);
    }

    private void renderFluidFilter(GuiGraphics graphics, int filterIndex, int x, int y) {
        FluidStack fluid = menu.getSupplyBuffer().getConfiguredFluidStack(filterIndex);
        if (fluid.isEmpty()) {
            graphics.drawString(font, "+", x + 5, y + 4, 0x668899, false);
            return;
        }

        try {
            IClientFluidTypeExtensions extension = IClientFluidTypeExtensions.of(fluid.getFluid());
            ResourceLocation texture = extension.getStillTexture(fluid);
            if (texture == null) {
                return;
            }
            TextureAtlasSprite sprite = minecraft.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
            int tint = extension.getTintColor(fluid);
            float alpha = ((tint >>> 24) & 0xFF) / 255.0F;
            if (alpha <= 0.0F) alpha = 1.0F;
            float red = ((tint >>> 16) & 0xFF) / 255.0F;
            float green = ((tint >>> 8) & 0xFF) / 255.0F;
            float blue = (tint & 0xFF) / 255.0F;
            graphics.blit(x, y, 100, 16, 16, sprite, red, green, blue, alpha);
        } catch (RuntimeException ignored) {
        }
    }

    private void renderVirtualSupplySlots(GuiGraphics graphics) {
        int y = topPos + SupplyBufferMenu.SUPPLY_ROW_Y;
        for (int filter = 0; filter < SupplyBufferMenu.FILTER_COUNT; filter++) {
            int x = leftPos + SupplyBufferMenu.FILTER_ROW_X + filter * 18;

            // AbstractContainerScreen has already rendered the real extraction slot.
            // Draw it again as a virtual cell so the vanilla stack count (for example 64)
            // never overlaps our huge long-backed amount.
            drawSlotBackground(graphics, x - 1, y - 1);

            long amount = menu.getSupplyItemCount(filter);
            ItemStack icon = menu.getSupplyBuffer().getConfiguredItemStack(filter);
            if (amount <= 0L || icon.isEmpty()) {
                continue;
            }

            ItemStack one = icon.copy();
            one.setCount(1);
            graphics.renderItem(one, x, y);

            long target = Math.max(1L, menu.getItemTargetAmount(filter));
            long threshold = Math.max(0L, target * (long) menu.getItemRefillBelowPercent() / 100L);
            double ratio = Math.max(0.0D, Math.min(1.0D, amount / (double) target));
            int fillWidth = (int) Math.round(16.0D * ratio);
            int color = amount < threshold ? 0xFFFF5555 : amount < target ? 0xFFFFC94A : 0xFF55FF55;

            graphics.fill(x, y + 14, x + 16, y + 16, 0xD0000000);
            if (fillWidth > 0) {
                graphics.fill(x, y + 14, x + fillWidth, y + 16, color);
            }
        }
    }

    private static void drawSlotBackground(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF101318);
        graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF343B45);
    }

    private static void drawGhostSlotBackground(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF101318);
        graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF2B333D);
    }

    private static void drawSelection(GuiGraphics graphics, int x, int y) {
        int color = 0xFF55FFFF;
        graphics.fill(x, y, x + 18, y + 1, color);
        graphics.fill(x, y + 17, x + 18, y + 18, color);
        graphics.fill(x, y, x + 1, y + 18, color);
        graphics.fill(x + 17, y, x + 18, y + 18, color);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 7, 0xFFFFFF, false);

        String status = statusText();
        int statusColor = menu.isLinkOnline() ? 0x55FF55 : 0xFF5555;
        graphics.drawString(font, status, 8, 19, statusColor, false);

        graphics.drawString(font, Component.literal("Предметы"), 8, 37, 0xC8D1DA, false);
        graphics.drawString(font, Component.literal("Жидкости"), 8, 78, 0xC8D1DA, false);

        graphics.drawString(font, Component.literal("Виртуальный запас предметов"), 35, 122, 0xFFFFFF, false);
        graphics.drawString(font, Component.literal("Отправка в главную ME"), 35, 161, 0xFFFFFF, false);

        graphics.drawString(
                font,
                Component.literal("ЛКМ выбор • ПКМ удалить • Enter цель • EMI drag"),
                8,
                216,
                0x8F99A6,
                false
        );

        graphics.drawString(font, Component.literal("Инвентарь"), 35, 229, 0xC8D1DA, false);

        if (menu.getPendingTransferCount() > 0) {
            graphics.drawString(
                    font,
                    Component.literal("Операций в ожидании: " + menu.getPendingTransferCount()),
                    35,
                    205,
                    0xFFCC55,
                    false
            );
        }
    }

    private void renderCustomFilterTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        int itemFilter = itemFilterAt(mouseX, mouseY);
        if (itemFilter >= 0) {
            ItemStack stack = menu.getSupplyBuffer().getConfiguredItemStack(itemFilter);
            String name = stack.isEmpty() ? "Пустой фильтр предмета" : stack.getHoverName().getString();
            long count = menu.getSupplyItemCount(itemFilter);
            long target = menu.getItemTargetAmount(itemFilter);
            String amount = target <= 0L ? "не настроено" : formatNumber(count) + " / " + formatNumber(target);
            graphics.renderTooltip(
                    font,
                    Component.literal(name + " • запас: " + amount + " • порог: " + menu.getItemRefillBelowPercent() + "%"),
                    mouseX,
                    mouseY
            );
            return;
        }

        int fluidFilter = fluidFilterAt(mouseX, mouseY);
        if (fluidFilter >= 0) {
            FluidStack fluid = menu.getSupplyBuffer().getConfiguredFluidStack(fluidFilter);
            String name = fluid.isEmpty() ? "Пустой фильтр жидкости" : fluid.getDisplayName().getString();
            String amount = formatFluid(menu.getFluidAmount(fluidFilter))
                    + " / "
                    + formatFluid(menu.getFluidTargetAmount(fluidFilter));
            graphics.renderTooltip(
                    font,
                    Component.literal(name + " • запас: " + amount + " • порог: " + menu.getFluidRefillBelowPercent() + "%"),
                    mouseX,
                    mouseY
            );
            return;
        }

        int supplySlot = supplySlotAt(mouseX, mouseY);
        if (supplySlot >= 0 && menu.getSupplyItemCount(supplySlot) > 0L) {
            ItemStack stack = menu.getSupplyBuffer().getConfiguredItemStack(supplySlot);
            String name = stack.isEmpty() ? "Ресурс" : stack.getHoverName().getString();
            graphics.renderTooltip(
                    font,
                    Component.literal(
                            name + " • запас: " + formatNumber(menu.getSupplyItemCount(supplySlot))
                                    + " / " + formatNumber(menu.getItemTargetAmount(supplySlot))
                    ),
                    mouseX,
                    mouseY
            );
        }
    }

    private void renderControlTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        if (itemTargetBox != null && itemTargetBox.isMouseOver(mouseX, mouseY)) {
            graphics.renderTooltip(
                    font,
                    Component.literal("Целевой запас выбранного предмета. Можно писать 250000, 250k, 2m. Enter — применить."),
                    mouseX,
                    mouseY
            );
        } else if (fluidTargetBox != null && fluidTargetBox.isMouseOver(mouseX, mouseY)) {
            graphics.renderTooltip(
                    font,
                    Component.literal("Целевой запас выбранной жидкости в mB. Можно писать 12000000, 12m. Enter — применить."),
                    mouseX,
                    mouseY
            );
        }
    }

    private String statusText() {
        if (!menu.isClusterEnabled()) {
            return "КЛАСТЕР ВЫКЛЮЧЕН";
        }
        return switch (menu.getRole()) {
            case UNLINKED -> "НЕ ПРИВЯЗАН — используй карту связи";
            case PROVIDER -> menu.isLinkOnline() ? "PROVIDER — ME ОНЛАЙН" : "PROVIDER — ME ОФФЛАЙН";
            case REMOTE -> menu.isLinkOnline() ? "REMOTE — СВЯЗЬ ОНЛАЙН" : "REMOTE — СВЯЗЬ ОФФЛАЙН";
        };
    }

    private static String formatFluid(long amount) {
        if (amount >= 1_000_000_000_000_000L) {
            return String.format(Locale.ROOT, "%.2fQ mB", amount / 1_000_000_000_000_000.0D);
        }
        if (amount >= 1_000_000_000_000L) {
            return String.format(Locale.ROOT, "%.2fT mB", amount / 1_000_000_000_000.0D);
        }
        if (amount >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.2fB mB", amount / 1_000_000_000.0D);
        }
        if (amount >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.2fM mB", amount / 1_000_000.0D);
        }
        if (amount >= 1_000L) {
            return String.format(Locale.ROOT, "%.1fk mB", amount / 1_000.0D);
        }
        return amount + " mB";
    }

    private static String formatNumber(long amount) {
        return String.format(Locale.ROOT, "%,d", Math.max(0L, amount)).replace(',', ' ');
    }

    private static String compactAmount(long amount) {
        if (amount >= 1_000_000_000_000_000L) return trimCompact(amount / 1_000_000_000_000_000.0D) + "Q";
        if (amount >= 1_000_000_000_000L) return trimCompact(amount / 1_000_000_000_000.0D) + "T";
        if (amount >= 1_000_000_000L) return trimCompact(amount / 1_000_000_000.0D) + "B";
        if (amount >= 1_000_000L) return trimCompact(amount / 1_000_000.0D) + "M";
        if (amount >= 1_000L) return trimCompact(amount / 1_000.0D) + "K";
        return Long.toString(amount);
    }

    private static String trimCompact(double value) {
        if (value >= 100.0D) return String.format(Locale.ROOT, "%.0f", value);
        if (value >= 10.0D) return String.format(Locale.ROOT, "%.1f", value);
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
