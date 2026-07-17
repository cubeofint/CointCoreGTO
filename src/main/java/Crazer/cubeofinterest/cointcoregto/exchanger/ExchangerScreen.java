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
    private Button aeModeButton;

    private boolean buyerMode;
    private boolean buyerAeMode;

    public ExchangerScreen(ExchangerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, Component.literal("Обменник"));

        this.imageWidth = 236;
        this.imageHeight = 250;

        this.buyerMode = !menu.canEdit();
    }

    @Override
    protected void init() {
        this.imageWidth = 236;
        this.imageHeight = 250;

        super.init();

        this.dealsBox = new EditBox(
                this.font,
                this.leftPos + 68,
                this.topPos + 108,
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
        ).bounds(this.leftPos + 132, this.topPos + 107, 82, 20).build();
        this.addRenderableWidget(this.buyButton);

        this.aeModeButton = Button.builder(
                Component.literal("AE режим: выкл"),
                button -> toggleAeMode()
        ).bounds(this.leftPos + 20, this.topPos + 130, 104, 20).build();
        this.addRenderableWidget(this.aeModeButton);

        this.switchModeButton = Button.builder(
                Component.literal("К покупателю"),
                button -> switchMode()
        ).bounds(this.leftPos + this.imageWidth - 108, this.topPos + 16, 94, 20).build();
        this.addRenderableWidget(this.switchModeButton);

        refreshModeWidgets();
    }

    private void toggleAeMode() {
        this.buyerAeMode = !this.buyerAeMode;
        this.aeModeButton.setMessage(Component.literal(
                this.buyerAeMode ? "AE режим: вкл" : "AE режим: выкл"
        ));
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

        this.aeModeButton.visible = this.buyerMode;
        this.aeModeButton.active = this.buyerMode;

        this.switchModeButton.visible = canEdit;
        this.switchModeButton.active = canEdit;

        if (this.buyerMode) {
            this.switchModeButton.setMessage(Component.literal("Настройка"));
        } else {
            this.switchModeButton.setMessage(Component.literal("К покупателю"));
        }

        this.switchModeButton.setX(this.leftPos + this.imageWidth - 108);
        this.switchModeButton.setY(this.topPos + 16);
        this.switchModeButton.setWidth(94);
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
                new ExchangerBuyPacket(this.menu.getBlockPos(), deals, this.buyerAeMode)
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
        graphics.fill(x, y + 4, x + w, y + 42, 0xFF161B28);

        graphics.renderOutline(x, y, w, h, 0xFF5C6B86);

        graphics.fill(x + 10, y + 48, x + w - 10, y + 152, 0xAA242A3A);
        graphics.renderOutline(x + 10, y + 48, w - 20, 104, 0xFF39445C);

        graphics.fill(x + 10, y + 156, x + w - 10, y + h - 10, 0xAA1E2432);
        graphics.renderOutline(x + 10, y + 156, w - 20, h - 166, 0xFF39445C);

        drawSlotFrame(graphics, x + 66, y + 52);
        drawSlotFrame(graphics, x + 154, y + 52);

        graphics.fill(x + 89, y + 59, x + 139, y + 61, 0xFF62DCEB);
        graphics.fill(x + 135, y + 55, x + 142, y + 60, 0xFF62DCEB);
        graphics.fill(x + 135, y + 60, x + 142, y + 65, 0xFF62DCEB);

        drawPlayerInventorySlots(graphics, x, y);
    }

    private void drawPlayerInventorySlots(GuiGraphics graphics, int x, int y) {
        int inventoryX = x + 37;
        int inventoryY = y + 170;

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                drawSlotFrame(
                        graphics,
                        inventoryX + column * 18,
                        inventoryY + row * 18
                );
            }
        }

        int hotbarX = x + 37;
        int hotbarY = y + 228;

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
        graphics.drawString(this.font, "Обменник", 18, 18, 0xF2F6FF, false);

        if (this.buyerMode) {
            graphics.drawString(this.font, "Режим покупателя", 18, 30, 0x73E8F2, false);
        } else {
            graphics.drawString(this.font, "Настройка владельца", 18, 30, 0xFFD36A, false);
        }

        graphics.drawString(this.font, "Товар", 62, 76, 0xD6DCEB, false);
        graphics.drawString(this.font, "Цена", 150, 76, 0xD6DCEB, false);

        if (this.buyerMode) {
            graphics.drawString(this.font, "Сделок:", 20, 112, 0xD6DCEB, false);

            long availableItems = this.menu.getAvailableProductCount();
            int perDeal = Math.max(1, this.menu.getSlot(ExchangerBlockEntity.SLOT_PRODUCT).getItem().getCount());
            long availableDeals = availableItems / perDeal;
            String stockText = "В наличии: " + formatAmount(availableItems)
                    + " шт. (" + formatAmount(availableDeals) + " сделок)";
            graphics.drawCenteredString(
                    this.font,
                    stockText,
                    this.imageWidth / 2,
                    92,
                    availableItems > 0 ? 0x8FE59A : 0xF27D7D
            );

            graphics.drawString(this.font, this.buyerAeMode ? "Источник: беспроводная ME" : "Источник: инвентарь", 130, 136, 0x8C93A6, false);
        } else {
            graphics.drawString(this.font, "Слева — товар, справа — цена.", 20, 106, 0xD6DCEB, false);
            graphics.drawString(this.font, "Слоты являются шаблонами обмена.", 20, 120, 0x8C93A6, false);
        }

        graphics.drawString(this.font, "Инвентарь", 37, 160, 0xD6DCEB, false);
    }

    private static String formatAmount(long amount) {
        long safeAmount = Math.max(0L, amount);

        if (safeAmount < 1_000L) {
            return Long.toString(safeAmount);
        }

        String[] suffixes = {"k", "M", "G", "T", "P", "E"};
        double value = safeAmount;
        int suffixIndex = -1;

        while (value >= 1_000.0D && suffixIndex < suffixes.length - 1) {
            value /= 1_000.0D;
            suffixIndex++;
        }

        String number;
        if (value >= 100.0D) {
            number = String.format(java.util.Locale.ROOT, "%.0f", value);
        } else if (value >= 10.0D) {
            number = String.format(java.util.Locale.ROOT, "%.1f", value);
        } else {
            number = String.format(java.util.Locale.ROOT, "%.2f", value);
        }

        while (number.contains(".") && (number.endsWith("0") || number.endsWith("."))) {
            number = number.substring(0, number.length() - 1);
        }

        return number + suffixes[suffixIndex];
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }
}