package Crazer.cubeofinterest.cointcoregto.trade;

import Crazer.cubeofinterest.cointcoregto.currency.CurrencyService;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class TradeScreen extends AbstractContainerScreen<TradeMenu> {
    private EditBox currencyBox;
    private Button saveCurrencyButton;
    private Button readyButton;
    private Button cancelButton;

    public TradeScreen(TradeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 244;
        imageHeight = 214;
        inventoryLabelY = 120;
    }

    @Override
    protected void init() {
        imageWidth = 244;
        imageHeight = 214;
        super.init();

        currencyBox = new EditBox(
                font,
                leftPos + 24,
                topPos + 87,
                76,
                18,
                Component.literal("Валюта")
        );
        currencyBox.setValue(Long.toString(menu.localCurrency()));
        currencyBox.setMaxLength(19);
        currencyBox.setFilter(value -> value.matches("[0-9]*"));
        addRenderableWidget(currencyBox);

        saveCurrencyButton = Button.builder(Component.literal("ОК"), button -> saveCurrency())
                .bounds(leftPos + 104, topPos + 86, 32, 20)
                .build();
        addRenderableWidget(saveCurrencyButton);

        readyButton = Button.builder(Component.literal("Готов"), button -> toggleReady())
                .bounds(leftPos + 144, topPos + 86, 48, 20)
                .build();
        addRenderableWidget(readyButton);

        cancelButton = Button.builder(Component.literal("Отмена"), button -> cancel())
                .bounds(leftPos + 196, topPos + 86, 40, 20)
                .build();
        addRenderableWidget(cancelButton);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (!currencyBox.isFocused()) {
            String synced = Long.toString(menu.localCurrency());
            if (!synced.equals(currencyBox.getValue())) {
                currencyBox.setValue(synced);
            }
        }
        readyButton.setMessage(Component.literal(menu.localReady() ? "Не готов" : "Готов"));
        boolean editable = menu.status() == TradeStatus.OPEN;
        currencyBox.active = editable;
        saveCurrencyButton.active = editable;
        readyButton.active = editable;
        cancelButton.active = menu.status() == TradeStatus.OPEN || menu.status() == TradeStatus.INVITED;
    }

    private void saveCurrency() {
        long amount;
        try {
            amount = currencyBox.getValue().isBlank() ? 0L : Long.parseLong(currencyBox.getValue());
        } catch (NumberFormatException exception) {
            currencyBox.setValue(Long.toString(menu.localCurrency()));
            return;
        }
        TradeNetwork.CHANNEL.sendToServer(new TradeSetCurrencyPacket(menu.tradeId(), Math.max(0L, amount)));
    }

    private void toggleReady() {
        saveCurrency();
        TradeNetwork.CHANNEL.sendToServer(new TradeReadyPacket(menu.tradeId(), !menu.localReady()));
    }

    private void cancel() {
        TradeNetwork.CHANNEL.sendToServer(new TradeCancelPacket(menu.tradeId()));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xEE111722);
        graphics.fill(leftPos + 8, topPos + 30, leftPos + 112, topPos + 82, 0xFF1B2638);
        graphics.fill(leftPos + 132, topPos + 30, leftPos + 236, topPos + 82, 0xFF1B2638);
        graphics.fill(leftPos + 8, topPos + 111, leftPos + 236, topPos + 210, 0xFF172132);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 2, 0xFF65E6FF);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 10, 10, 0xFFFFFF, false);
        graphics.drawString(font, "Ваше предложение", 18, 32, 0x6FE9FF, false);
        graphics.drawString(font, "Предложение игрока", 142, 32, 0xFFD36F, false);
        graphics.drawString(font, "Монеты: " + CurrencyService.format(menu.remoteCurrency()), 142, 70, 0xFFD36F, false);
        graphics.drawString(font, "Вы: " + (menu.localReady() ? "готов" : "не готовы"), 18, 108,
                menu.localReady() ? 0x66FF88 : 0xFFAA66, false);
        graphics.drawString(font, "Игрок: " + (menu.remoteReady() ? "готов" : "не готов"), 132, 108,
                menu.remoteReady() ? 0x66FF88 : 0xFFAA66, false);
        graphics.drawString(font, "Статус: " + statusName(menu.status()), 10, 119, 0xCCCCCC, false);
        graphics.drawString(font, playerInventoryTitle, 41, 122, 0xCCCCCC, false);
    }

    private static String statusName(TradeStatus status) {
        return switch (status) {
            case INVITED -> "приглашение";
            case OPEN -> "настройка";
            case PREPARING -> "проверка";
            case SETTLING -> "перевод валюты";
            case COMMITTING -> "выдача предметов";
            case COMPLETED -> "завершено";
            case CANCELLED -> "отменено";
            case DENIED -> "отклонено";
            case EXPIRED -> "истекло";
        };
    }
}
