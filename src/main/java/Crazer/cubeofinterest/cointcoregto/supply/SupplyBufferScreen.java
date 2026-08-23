package Crazer.cubeofinterest.cointcoregto.supply;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import dev.emi.emi.api.render.EmiRender;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;

public class SupplyBufferScreen extends AbstractContainerScreen<SupplyBufferMenu> {
    private static final int GUI_WIDTH = 232;
    private static final int GUI_HEIGHT = 322;

    private static final int FILTER_X = SupplyBufferMenu.FILTER_ROW_X;
    private static final int ITEM_FILTER_Y = 55;
    private static final int FLUID_FILTER_Y = 96;
    private static final int SLOT_SIZE = 18;

    private Button itemBelowButton;
    private Button itemTargetButton;
    private Button fluidBelowButton;
    private Button fluidTargetButton;

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

        itemTargetButton = addRenderableWidget(Button.builder(
                Component.empty(),
                button -> sendAction(1)
        ).bounds(leftPos + 154, topPos + 31, 68, 20).build());

        fluidBelowButton = addRenderableWidget(Button.builder(
                Component.empty(),
                button -> sendAction(2)
        ).bounds(leftPos + 82, topPos + 72, 68, 20).build());

        fluidTargetButton = addRenderableWidget(Button.builder(
                Component.empty(),
                button -> sendAction(3)
        ).bounds(leftPos + 154, topPos + 72, 68, 20).build());

        refreshButtons();
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
        sendFilter(
                SupplyBufferDatabase.ResourceType.FLUID,
                filterIndex,
                SupplyKeyCodec.encode(key)
        );
        return true;
    }

    public int itemFilterAt(double mouseX, double mouseY) {
        return filterAt(mouseX, mouseY, ITEM_FILTER_Y);
    }

    public int fluidFilterAt(double mouseX, double mouseY) {
        return filterAt(mouseX, mouseY, FLUID_FILTER_Y);
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
        refreshButtons();
    }

    private void refreshButtons() {
        boolean active = menu.canEdit() && menu.getRole() == SupplyBufferRole.REMOTE;
        if (itemBelowButton != null) {
            itemBelowButton.active = active;
            itemBelowButton.setMessage(Component.literal("< " + menu.getItemRefillBelowPercent() + "%"));
        }
        if (itemTargetButton != null) {
            itemTargetButton.active = active;
            itemTargetButton.setMessage(Component.literal("→ " + menu.getItemRefillToPercent() + "%"));
        }
        if (fluidBelowButton != null) {
            fluidBelowButton.active = active;
            fluidBelowButton.setMessage(Component.literal("< " + menu.getFluidRefillBelowPercent() + "%"));
        }
        if (fluidTargetButton != null) {
            fluidTargetButton.active = active;
            fluidTargetButton.setMessage(Component.literal("→ " + menu.getFluidRefillToPercent() + "%"));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (menu.canEdit() && menu.getRole() == SupplyBufferRole.REMOTE) {
            int itemFilter = itemFilterAt(mouseX, mouseY);
            if (itemFilter >= 0) {
                if (button == 1) {
                    sendFilter(SupplyBufferDatabase.ResourceType.ITEM, itemFilter, "");
                    return true;
                }
                if (button == 0) {
                    ItemStack carried = menu.getCarried();
                    if (!carried.isEmpty()) {
                        setItemFilterFromExternal(itemFilter, carried);
                        return true;
                    }
                }
            }

            int fluidFilter = fluidFilterAt(mouseX, mouseY);
            if (fluidFilter >= 0) {
                if (button == 1) {
                    sendFilter(SupplyBufferDatabase.ResourceType.FLUID, fluidFilter, "");
                    return true;
                }
                if (button == 0) {
                    ItemStack carried = menu.getCarried();
                    if (!carried.isEmpty()) {
                        FluidStack fluid = FluidUtil.getFluidContained(carried).orElse(FluidStack.EMPTY);
                        if (!fluid.isEmpty()) {
                            setFluidFilterFromExternal(fluidFilter, fluid);
                            return true;
                        }
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        renderCustomFilterTooltips(graphics, mouseX, mouseY);
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

        EmiStack emiStack = fluid.hasTag()
                ? EmiStack.of(fluid.getFluid(), fluid.getTag().copy(), 1L)
                : EmiStack.of(fluid.getFluid(), 1L);
        EmiRender.renderIngredientIcon(emiStack, graphics, x, y);
    }

    private static void drawSlotBackground(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF101318);
        graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF343B45);
    }

    private static void drawGhostSlotBackground(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF101318);
        graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF2B333D);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 7, 0xFFFFFF, false);

        String status = statusText();
        int statusColor = menu.isLinkOnline() ? 0x55FF55 : 0xFF5555;
        graphics.drawString(font, status, 8, 19, statusColor, false);

        graphics.drawString(font, Component.literal("Предметы"), 8, 37, 0xC8D1DA, false);
        graphics.drawString(font, Component.literal("Жидкости"), 8, 78, 0xC8D1DA, false);

        graphics.drawString(font, Component.literal("Запрошенные предметы"), 35, 122, 0xFFFFFF, false);
        graphics.drawString(font, Component.literal("Отправка в главную ME"), 35, 161, 0xFFFFFF, false);

        graphics.drawString(
                font,
                Component.literal("ПКМ — очистить • Drag из EMI"),
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
            int count = menu.getSupplyItemCount(itemFilter);
            int capacity = menu.getSupplyItemCapacity(itemFilter);
            String amount = capacity <= 0 ? "не настроено" : count + " / " + capacity;
            graphics.renderTooltip(
                    font,
                    Component.literal(name + " • запас: " + amount),
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
                    + formatFluid(menu.getFluidCapacity(fluidFilter));
            graphics.renderTooltip(
                    font,
                    Component.literal(name + " • запас: " + amount),
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

    private static String formatFluid(int amount) {
        if (amount >= 1_000_000) {
            return String.format(java.util.Locale.ROOT, "%.1fM mB", amount / 1_000_000.0D);
        }
        if (amount >= 1_000) {
            return String.format(java.util.Locale.ROOT, "%.1fk mB", amount / 1_000.0D);
        }
        return amount + " mB";
    }

}
