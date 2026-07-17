package Crazer.cubeofinterest.cointcoregto.exchanger;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class ExchangerScreen extends AbstractContainerScreen<ExchangerMenu> {
    private EditBox dealsBox;
    private Button buyButton;
    private Button switchModeButton;

    private boolean buyerMode;

    public ExchangerScreen(ExchangerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, Component.literal("Обменник"));

        this.imageWidth = 260;
        this.imageHeight = 250;

        this.buyerMode = !menu.canEdit();
    }

    @Override
    protected void init() {
        this.imageWidth = 260;
        this.imageHeight = 250;

        super.init();

        this.dealsBox = new EditBox(
                this.font,
                this.leftPos + 78,
                this.topPos + 103,
                54,
                18,
                Component.literal("Сделок")
        );
        this.dealsBox.setValue("1");
        this.dealsBox.setMaxLength(5);
        this.dealsBox.setFilter(value -> value.matches("[0-9]*"));
        this.addRenderableWidget(this.dealsBox);

        this.buyButton = Button.builder(
                Component.literal("Купить"),
                button -> buy()
        ).bounds(this.leftPos + 142, this.topPos + 102, 78, 20).build();
        this.addRenderableWidget(this.buyButton);

        this.switchModeButton = Button.builder(
                Component.literal("К покупателю"),
                button -> switchMode()
        ).bounds(this.leftPos + this.imageWidth - 118, this.topPos + 18, 100, 20).build();
        this.addRenderableWidget(this.switchModeButton);

        refreshModeWidgets();
    }

    private void switchMode() {
        if (!this.menu.canEdit()) {
            return;
        }

        this.buyerMode = !this.buyerMode;
        refreshModeWidgets();
    }

    private void refreshModeWidgets() {
        boolean canEdit = this.menu.canEdit();

        this.dealsBox.visible = this.buyerMode;
        this.dealsBox.active = this.buyerMode;

        this.buyButton.visible = this.buyerMode;
        this.buyButton.active = this.buyerMode;

        this.switchModeButton.visible = canEdit;
        this.switchModeButton.active = canEdit;

        if (this.buyerMode) {
            this.switchModeButton.setMessage(Component.literal("Настройка"));
        } else {
            this.switchModeButton.setMessage(Component.literal("К покупателю"));
        }

        this.switchModeButton.setX(this.leftPos + this.imageWidth - 118);
        this.switchModeButton.setY(this.topPos + 18);
        this.switchModeButton.setWidth(100);
    }

    private void buy() {
        int deals = 1;

        try {
            String value = this.dealsBox.getValue();

            if (value != null && !value.isBlank()) {
                deals = Integer.parseInt(value);
            }
        } catch (NumberFormatException ignored) {
            deals = 1;
        }

        if (deals <= 0) {
            deals = 1;
        }

        CointExchangerNetwork.CHANNEL.sendToServer(
                new ExchangerBuyPacket(this.menu.getBlockPos(), deals)
        );
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        drawPanel(graphics);
    }

    private void drawPanel(GuiGraphics graphics) {
        int x = this.leftPos;
        int y = this.topPos;
        int w = this.imageWidth;
        int h = this.imageHeight;

        graphics.fill(x, y, x + w, y + h, 0xEE10131C);
        graphics.fill(x, y, x + w, y + 4, 0xFF62DCEB);
        graphics.fill(x, y + 4, x + w, y + 46, 0xFF161B28);

        graphics.renderOutline(x, y, w, h, 0xFF5C6B86);

        graphics.fill(x + 12, y + 56, x + w - 12, y + 132, 0xAA242A3A);
        graphics.renderOutline(x + 12, y + 56, w - 24, 76, 0xFF39445C);

        graphics.fill(x + 12, y + 142, x + w - 12, y + h - 12, 0xAA1E2432);
        graphics.renderOutline(x + 12, y + 142, w - 24, h - 154, 0xFF39445C);

        drawSlotFrame(graphics, x + 82, y + 56);
        drawSlotFrame(graphics, x + 176, y + 56);

        graphics.fill(x + 105, y + 63, x + 155, y + 65, 0xFF62DCEB);
        graphics.fill(x + 151, y + 59, x + 158, y + 64, 0xFF62DCEB);
        graphics.fill(x + 151, y + 64, x + 158, y + 69, 0xFF62DCEB);

        drawPlayerInventorySlots(graphics, x, y);
    }

    private void drawPlayerInventorySlots(GuiGraphics graphics, int x, int y) {
        int inventoryX = x + 49;
        int inventoryY = y + 156;

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                drawSlotFrame(
                        graphics,
                        inventoryX + column * 18,
                        inventoryY + row * 18
                );
            }
        }

        int hotbarX = x + 49;
        int hotbarY = y + 214;

        for (int column = 0; column < 9; column++) {
            drawSlotFrame(
                    graphics,
                    hotbarX + column * 18,
                    hotbarY
            );
        }
    }

    private void drawSlotFrame(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF4A556C);
        graphics.fill(x, y, x + 16, y + 16, 0xFF111722);
        graphics.fill(x, y, x + 16, y + 1, 0xFF7D8BA8);
        graphics.fill(x, y, x + 1, y + 16, 0xFF7D8BA8);
        graphics.fill(x + 15, y, x + 16, y + 16, 0xFF0B0E16);
        graphics.fill(x, y + 15, x + 16, y + 16, 0xFF0B0E16);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(this.font, "Обменник", 22, 22, 0xF2F6FF, false);

        if (this.buyerMode) {
            graphics.drawString(this.font, "Режим покупателя", 22, 34, 0x73E8F2, false);
        } else {
            graphics.drawString(this.font, "Настройка владельца", 22, 34, 0xFFD36A, false);
        }

        graphics.drawString(this.font, "Товар", 78, 86, 0xD6DCEB, false);
        graphics.drawString(this.font, "Цена", 172, 86, 0xD6DCEB, false);

        if (this.buyerMode) {
            graphics.drawString(this.font, "Сделок:", 24, 107, 0xD6DCEB, false);
            graphics.drawString(this.font, "Доступно: позже подключим ME/сундук", 24, 124, 0x8C93A6, false);
        } else {
            graphics.drawString(this.font, "Слева — товар, справа — цена.", 24, 106, 0xD6DCEB, false);
            graphics.drawString(this.font, "Слоты являются шаблонами обмена.", 24, 118, 0x8C93A6, false);
        }

        graphics.drawString(this.font, "Инвентарь", 49, 149, 0xD6DCEB, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }
}