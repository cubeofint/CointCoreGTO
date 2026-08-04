package Crazer.cubeofinterest.cointcoregto.exchanger;

import Crazer.cubeofinterest.cointcoregto.currency.CurrencyService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public class ExchangerScreen extends AbstractContainerScreen<ExchangerMenu> {
    private static final int EMI_BOTTOM_RESERVED = 22;
    private EditBox dealsBox;
    private Button buyButton;
    private Button switchModeButton;
    private Button aeModeButton;
    private EditBox currencyPriceBox;
    private Button saveCurrencyButton;
    private Button requiredTierButton;

    private boolean buyerMode;
    private boolean buyerAeMode;

    public ExchangerScreen(ExchangerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, Component.literal("Обменник"));
        this.imageWidth = 200;
        this.imageHeight = 206;
        this.buyerMode = menu.isBuyerMode();
    }

    @Override
    protected void init() {
        this.imageWidth = 200;
        this.imageHeight = 206;
        super.init();
        this.topPos = Math.max(0, Math.min(
                this.topPos,
                this.height - this.imageHeight - EMI_BOTTOM_RESERVED
        ));

        this.dealsBox = new EditBox(
                this.font,
                this.leftPos + 44,
                this.topPos + 91,
                32,
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
        ).bounds(this.leftPos + 80, this.topPos + 90, 52, 20).build();
        this.addRenderableWidget(this.buyButton);

        this.aeModeButton = Button.builder(
                Component.literal("AE: выкл"),
                button -> toggleAeMode()
        ).bounds(this.leftPos + 136, this.topPos + 90, 54, 20).build();
        this.addRenderableWidget(this.aeModeButton);

        this.currencyPriceBox = new EditBox(
                this.font,
                this.leftPos + 91,
                this.topPos + 89,
                55,
                18,
                Component.literal("Монет за сделку")
        );
        this.currencyPriceBox.setValue(Long.toString(this.menu.getCurrencyPricePerDeal()));
        this.currencyPriceBox.setMaxLength(19);
        this.currencyPriceBox.setFilter(value -> value.matches("[0-9]*"));
        this.addRenderableWidget(this.currencyPriceBox);

        this.saveCurrencyButton = Button.builder(
                Component.literal("ОК"),
                button -> saveCurrencyPrice()
        ).bounds(this.leftPos + 150, this.topPos + 88, 40, 20).build();
        this.addRenderableWidget(this.saveCurrencyButton);

        this.requiredTierButton = Button.builder(
                Component.literal("Без ограничения"),
                button -> cycleRequiredTier(Screen.hasShiftDown() ? -1 : 1)
        ).bounds(this.leftPos + 80, this.topPos + 105, 110, 18).build();
        this.addRenderableWidget(this.requiredTierButton);

        this.switchModeButton = Button.builder(
                Component.literal("К покупателю"),
                button -> switchMode()
        ).bounds(this.leftPos + this.imageWidth - 96, this.topPos + 12, 86, 20).build();
        this.addRenderableWidget(this.switchModeButton);

        refreshModeWidgets();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (!this.buyerMode
                && this.currencyPriceBox != null
                && !this.currencyPriceBox.isFocused()) {
            String synced = Long.toString(this.menu.getCurrencyPricePerDeal());
            if (!synced.equals(this.currencyPriceBox.getValue())) {
                this.currencyPriceBox.setValue(synced);
            }
        }
        updateRequiredTierButton();
        updateBuyButtonState();
    }

    private void toggleAeMode() {
        this.buyerAeMode = !this.buyerAeMode;
        this.aeModeButton.setMessage(Component.literal(
                this.buyerAeMode ? "AE: вкл" : "AE: выкл"
        ));
    }

    private void switchMode() {
        if (!this.menu.canEdit()) {
            return;
        }

        this.buyerMode = !this.buyerMode;
        this.menu.setEditMode(!this.buyerMode);
        if (!this.buyerMode && this.currencyPriceBox != null) {
            this.currencyPriceBox.setValue(Long.toString(this.menu.getCurrencyPricePerDeal()));
        }
        CointExchangerNetwork.CHANNEL.sendToServer(
                new ExchangerSetModePacket(this.menu.getBlockPos(), !this.buyerMode)
        );
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
        this.currencyPriceBox.visible = canEdit && !this.buyerMode;
        this.currencyPriceBox.active = canEdit && !this.buyerMode;
        this.saveCurrencyButton.visible = canEdit && !this.buyerMode;
        this.saveCurrencyButton.active = canEdit && !this.buyerMode;
        this.requiredTierButton.visible = canEdit && !this.buyerMode;
        this.requiredTierButton.active = this.menu.canEditTier() && !this.buyerMode;
        this.switchModeButton.visible = canEdit;
        this.switchModeButton.active = canEdit;

        if (this.buyerMode) {
            this.switchModeButton.setMessage(Component.literal("Настройка"));
        } else {
            this.switchModeButton.setMessage(Component.literal("К покупателю"));
        }

        this.switchModeButton.setX(this.leftPos + this.imageWidth - 96);
        this.switchModeButton.setY(this.topPos + 12);
        this.switchModeButton.setWidth(86);
        updateRequiredTierButton();
        updateBuyButtonState();
    }

    private void cycleRequiredTier(int direction) {
        CointExchangerNetwork.CHANNEL.sendToServer(
                new ExchangerCycleRequiredTierPacket(this.menu.getBlockPos(), direction)
        );
    }

    private void updateRequiredTierButton() {
        if (this.requiredTierButton == null) {
            return;
        }
        int requiredTier = this.menu.getRequiredTierIndex();
        this.requiredTierButton.setMessage(Component.literal(
                requiredTier < 0
                        ? "Без ограничения"
                        : "Эпоха: " + this.menu.getRequiredTierName()
        ));
    }

    private void updateBuyButtonState() {
        if (this.buyButton == null || !this.buyerMode) {
            return;
        }
        ExchangerProgression.Status status = this.menu.getProgressionStatus();
        this.buyButton.active = !this.menu.isViewerOwner()
                && status != ExchangerProgression.Status.BELOW_REQUIRED
                && status != ExchangerProgression.Status.INVALID_REQUIRED_TIER;
    }

    private void saveCurrencyPrice() {
        long amount = 0L;
        try {
            String value = this.currencyPriceBox.getValue();
            if (value != null && !value.isBlank()) {
                amount = Long.parseLong(value);
            }
        } catch (NumberFormatException ignored) {
            this.currencyPriceBox.setValue("0");
            return;
        }
        if (amount < 0L) {
            amount = 0L;
        }
        CointExchangerNetwork.CHANNEL.sendToServer(
                new ExchangerSetCurrencyPricePacket(this.menu.getBlockPos(), amount)
        );
        this.currencyPriceBox.setValue(Long.toString(amount));
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

    public boolean canAcceptEmiTemplates() {
        return this.menu.isEditMode() && !this.buyerMode;
    }

    public int getTemplateSlotScreenX(int slot) {
        return this.leftPos + this.menu.getSlot(slot).x;
    }

    public int getTemplateSlotScreenY(int slot) {
        return this.topPos + this.menu.getSlot(slot).y;
    }

    public void setTemplateFromEmi(int slot, ItemStack stack) {
        if (!canAcceptEmiTemplates()) {
            return;
        }

        sendTemplateUpdate(slot, stack);
    }

    private void sendTemplateUpdate(int slot, ItemStack stack) {
        CointExchangerNetwork.CHANNEL.sendToServer(
                new ExchangerSetTemplatePacket(this.menu.getBlockPos(), slot, stack)
        );
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (canAcceptEmiTemplates()) {
            int slot = findTemplateSlot(mouseX, mouseY);
            if (slot >= 0) {
                ItemStack current = this.menu.getSlot(slot).getItem();
                if (!current.isEmpty()) {
                    ItemStack changed = current.copy();
                    int direction = delta > 0.0D ? 1 : -1;
                    int maximum = Math.max(1, Math.min(64, changed.getMaxStackSize()));
                    int count;

                    if (Screen.hasShiftDown()) {
                        count = getShiftCount(changed.getCount(), maximum, direction);
                    } else {
                        count = Math.max(1, Math.min(maximum, changed.getCount() + direction));
                    }

                    if (count != changed.getCount()) {
                        changed.setCount(count);
                        sendTemplateUpdate(slot, changed);
                    }
                    return true;
                }
            }
        }

        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private static int getShiftCount(int current, int maximum, int direction) {
        int[] steps = {1, 16, 32, 48, 64};

        if (direction > 0) {
            for (int step : steps) {
                if (step > current && step <= maximum) {
                    return step;
                }
            }
            return maximum;
        }

        for (int index = steps.length - 1; index >= 0; index--) {
            int step = steps[index];
            if (step < current && step <= maximum) {
                return step;
            }
        }
        return 1;
    }

    private int findTemplateSlot(double mouseX, double mouseY) {
        for (int slot = 0; slot < 2; slot++) {
            int x = getTemplateSlotScreenX(slot) - 1;
            int y = getTemplateSlotScreenY(slot) - 1;
            if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) {
                return slot;
            }
        }

        return -1;
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
        graphics.fill(x, y + 4, x + w, y + 36, 0xFF161B28);
        graphics.renderOutline(x, y, w, h, 0xFF5C6B86);

        graphics.fill(x + 8, y + 40, x + w - 8, y + 113, 0xAA242A3A);
        graphics.renderOutline(x + 8, y + 40, w - 16, 73, 0xFF39445C);

        graphics.fill(x + 8, y + 117, x + w - 8, y + h - 3, 0xAA1E2432);
        graphics.renderOutline(x + 8, y + 117, w - 16, h - 120, 0xFF39445C);

        drawSlotFrame(graphics, x + 48, y + 44);
        drawSlotFrame(graphics, x + 136, y + 44);
        drawExchangeArrow(graphics, x, y);
        drawPlayerInventorySlots(graphics, x, y);
    }

    private void drawExchangeArrow(GuiGraphics graphics, int x, int y) {
        int color = 0xFF62DCEB;
        int centerY = y + 52;
        graphics.fill(x + 71, centerY - 1, x + 124, centerY + 2, color);
        graphics.fill(x + 121, centerY - 6, x + 124, centerY + 7, color);
        graphics.fill(x + 124, centerY - 4, x + 127, centerY + 5, color);
        graphics.fill(x + 127, centerY - 2, x + 130, centerY + 3, color);
    }

    private void drawPlayerInventorySlots(GuiGraphics graphics, int x, int y) {
        int inventoryX = x + 19;
        int inventoryY = y + 131;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                drawSlotFrame(
                        graphics,
                        inventoryX + column * 18,
                        inventoryY + row * 18
                );
            }
        }

        int hotbarX = x + 19;
        int hotbarY = y + 187;
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
        graphics.drawString(this.font, "Обменник", 14, 12, 0xF2F6FF, false);

        if (this.buyerMode) {
            graphics.drawString(this.font, "Покупатель", 14, 24, 0x73E8F2, false);
        } else {
            graphics.drawString(this.font, "Владелец", 14, 24, 0xFFD36A, false);
        }

        graphics.drawString(this.font, "Товар", 44, 67, 0xD6DCEB, false);
        graphics.drawString(this.font, "Цена", 132, 67, 0xD6DCEB, false);

        if (this.buyerMode) {
            graphics.drawString(this.font, "Сделок:", 10, 96, 0xD6DCEB, false);
            long availableItems = this.menu.getAvailableProductCount();
            int perDeal = Math.max(
                    1,
                    this.menu.getSlot(ExchangerBlockEntity.SLOT_PRODUCT).getItem().getCount()
            );
            long availableDeals = availableItems / perDeal;
            String stockText = "В наличии: " + formatAmount(availableItems)
                    + " шт. (" + formatAmount(availableDeals) + " сделок)";
            graphics.drawCenteredString(
                    this.font,
                    stockText,
                    this.imageWidth / 2,
                    79,
                    availableItems > 0 ? 0x8FE59A : 0xF27D7D
            );
            String currencyText;
            int currencyColor;
            if (this.menu.isViewerOwner()) {
                currencyText = "Свой обменник: покупка запрещена";
                currencyColor = 0xF27D7D;
            } else if (this.menu.getProgressionStatus() == ExchangerProgression.Status.BELOW_REQUIRED) {
                currencyText = "Нужно: " + this.menu.getRequiredTierName()
                        + " | Ваша: " + this.menu.getViewerTierName();
                currencyColor = 0xF27D7D;
            } else if (this.menu.getProgressionStatus() == ExchangerProgression.Status.INVALID_REQUIRED_TIER) {
                currencyText = "Ошибка минимальной эпохи";
                currencyColor = 0xF27D7D;
            } else {
                long basePrice = this.menu.getCurrencyPricePerDeal();
                long effectivePrice = this.menu.getEffectiveCurrencyPricePerDeal();
                currencyText = basePrice > 0L
                        ? "Монеты: " + CurrencyService.format(effectivePrice)
                        : "Монеты: нет";
                if (this.menu.getDiscountBasisPoints() > 0 && basePrice > 0L) {
                    currencyText += " (-" + formatPercent(this.menu.getDiscountBasisPoints()) + "%)";
                }
                currencyText += " | Баланс: " + CurrencyService.format(this.menu.getViewerCurrencyBalance());
                currencyColor = 0xFFD36A;
            }
            graphics.drawCenteredString(this.font, currencyText, this.imageWidth / 2, 108, currencyColor);
        } else {
            graphics.drawString(this.font, "Цена ресурсами — слот справа.", 12, 80, 0xD6DCEB, false);
            graphics.drawString(this.font, "Монет/сделку:", 12, 94, 0xFFD36A, false);
            graphics.drawString(this.font, "Мин. эпоха:", 12, 110, 0x8C93A6, false);
        }

        graphics.drawString(this.font, "Инвентарь", 19, 120, 0xD6DCEB, false);
    }

    private static String formatPercent(int basisPoints) {
        if (basisPoints % 100 == 0) {
            return Integer.toString(basisPoints / 100);
        }
        if (basisPoints % 10 == 0) {
            return String.format(java.util.Locale.ROOT, "%.1f", basisPoints / 100.0D);
        }
        return String.format(java.util.Locale.ROOT, "%.2f", basisPoints / 100.0D);
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