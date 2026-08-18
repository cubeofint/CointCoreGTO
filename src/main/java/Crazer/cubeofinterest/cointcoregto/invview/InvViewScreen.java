package Crazer.cubeofinterest.cointcoregto.invview;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

public final class InvViewScreen extends AbstractContainerScreen<InvViewMenu> {
    private Button mainButton;
    private Button enderButton;
    private Button curiosButton;
    private Button previousButton;
    private Button nextButton;

    public InvViewScreen(InvViewMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 220;
        imageHeight = menu.showsViewerInventory() ? 226 : 144;
    }

    @Override
    protected void init() {
        imageWidth = 220;
        imageHeight = menu.showsViewerInventory() ? 226 : 144;
        super.init();

        mainButton = addRenderableWidget(Button.builder(Component.literal("Инвентарь"), button -> switchMode(InvViewMode.MAIN, 0))
                .bounds(leftPos + 8, topPos + 20, 68, 20)
                .build());
        enderButton = addRenderableWidget(Button.builder(Component.literal("Эндер"), button -> switchMode(InvViewMode.ENDER, 0))
                .bounds(leftPos + 78, topPos + 20, 58, 20)
                .build());
        curiosButton = addRenderableWidget(Button.builder(Component.literal("Curios"), button -> switchMode(InvViewMode.CURIOS, 0))
                .bounds(leftPos + 138, topPos + 20, 54, 20)
                .build());

        if (menu.mode() == InvViewMode.CURIOS && menu.pageCount() > 1) {
            previousButton = addRenderableWidget(Button.builder(Component.literal("<"), button -> changePage(-1))
                    .bounds(leftPos + 174, topPos + 2, 18, 16)
                    .build());
            nextButton = addRenderableWidget(Button.builder(Component.literal(">"), button -> changePage(1))
                    .bounds(leftPos + 194, topPos + 2, 18, 16)
                    .build());
        }
        updateButtons();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateButtons();
    }

    private void updateButtons() {
        if (mainButton != null) {
            mainButton.active = menu.mode() != InvViewMode.MAIN;
        }
        if (enderButton != null) {
            enderButton.active = menu.mode() != InvViewMode.ENDER;
        }
        if (curiosButton != null) {
            curiosButton.active = menu.mode() != InvViewMode.CURIOS;
        }
        if (previousButton != null) {
            previousButton.active = menu.page() > 0;
        }
        if (nextButton != null) {
            nextButton.active = menu.page() + 1 < menu.pageCount();
        }
    }

    private void switchMode(InvViewMode mode, int page) {
        InvViewNetwork.CHANNEL.sendToServer(new InvViewSwitchPacket(menu.targetId(), menu.targetName(), mode, page));
    }

    private void changePage(int delta) {
        int page = Math.max(0, Math.min(menu.pageCount() - 1, menu.page() + delta));
        if (page != menu.page()) {
            switchMode(InvViewMode.CURIOS, page);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xEE10151F);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 2, 0xFF6DC8FF);
        graphics.fill(leftPos + 7, topPos + 43, leftPos + 213, topPos + 140, 0xFF171F2C);
        if (menu.showsViewerInventory()) {
            graphics.fill(leftPos + 7, topPos + 140, leftPos + 175, topPos + 223, 0xFF151C28);
        }

        for (Slot slot : menu.slots) {
            int x = leftPos + slot.x - 1;
            int y = topPos + slot.y - 1;
            graphics.fill(x, y, x + 18, y + 18, 0xFF5A6472);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF202936);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        String state = menu.offline() ? "OFFLINE" : "ONLINE";
        int stateColor = menu.offline() ? 0xFFB45F : 0x62FF85;
        graphics.drawString(font, menu.targetName(), 9, 7, 0xFFFFFF, false);
        graphics.drawString(font, state, 84, 7, stateColor, false);
        graphics.drawString(font, menu.editable() ? "Редактирование" : "Только просмотр", 9, 123,
                menu.editable() ? 0x7DFF9A : 0xFFD36F, false);
        if (menu.showsViewerInventory()) {
            graphics.drawString(font, "Ваш инвентарь", 9, 135, 0xBFC9D8, false);
        }

        if (menu.mode() == InvViewMode.CURIOS) {
            if (menu.pageCount() > 1) {
                graphics.drawString(font, (menu.page() + 1) + "/" + menu.pageCount(), 145, 7, 0xBFC9D8, false);
            }
            if (menu.targetSlotCount() == 0) {
                graphics.drawString(font, "Curios-слоты не найдены", 12, 54, 0xBFC9D8, false);
            } else if (hoveredSlot != null) {
                int index = menu.slots.indexOf(hoveredSlot);
                String label = menu.targetSlotLabel(index);
                if (!label.isBlank()) {
                    graphics.drawString(font, font.plainSubstrByWidth(label, 118), 88, 123, 0x8FD9FF, false);
                }
            }
        }
    }
}
