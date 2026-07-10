package Crazer.cubeofinterest.cointcoregto.compat.emi;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class CointManaOverlayEditScreen extends Screen {

    public CointManaOverlayEditScreen() {
        super(Component.literal("Настройка GUI маны"));
    }

    @Override
    protected void init() {
        this.addRenderableWidget(
                Button.builder(
                                Component.literal("Сбросить позиции"),
                                button -> CointManaOverlayClientSettings.reset()
                        )
                        .bounds(this.width / 2 - 80, this.height - 56, 160, 20)
                        .build()
        );

        this.addRenderableWidget(
                Button.builder(
                                Component.literal("Готово"),
                                button -> this.onClose()
                        )
                        .bounds(this.width / 2 - 80, this.height - 30, 160, 20)
                        .build()
        );
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        graphics.drawCenteredString(
                this.font,
                "Настройка GUI маны",
                this.width / 2,
                20,
                0xFFFFFFFF
        );

        int x = this.width / 2 - 190;
        int y = 55;

        graphics.drawString(this.font, "§bКак настроить позицию:", x, y, 0xFFFFFFFF, true);
        graphics.drawString(this.font, "§f1. Открой нужный реальный рецепт в EMI.", x, y + 18, 0xFFFFFFFF, true);
        graphics.drawString(this.font, "§f2. Нажми назначенную кнопку §e«Настроить GUI маны»§f ещё раз.", x, y + 34, 0xFFFFFFFF, true);
        graphics.drawString(this.font, "§f3. Поверх рецепта появятся рамки.", x, y + 50, 0xFFFFFFFF, true);
        graphics.drawString(this.font, "§f4. Перетащи реальную надпись мышкой.", x, y + 66, 0xFFFFFFFF, true);
        graphics.drawString(this.font, "§f5. Нажми кнопку ещё раз, чтобы сохранить и выйти из режима.", x, y + 82, 0xFFFFFFFF, true);

        graphics.drawString(this.font, "§7Так позиция настраивается прямо на настоящем рецепте,", x, y + 116, 0xFFFFFFFF, true);
        graphics.drawString(this.font, "§7а не на фейковом превью.", x, y + 132, 0xFFFFFFFF, true);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        CointManaOverlayClientSettings.save();
        super.onClose();
    }
}