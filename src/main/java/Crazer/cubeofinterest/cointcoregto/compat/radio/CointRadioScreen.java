package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

@OnlyIn(Dist.CLIENT)
public class CointRadioScreen extends Screen {
    private final BlockPos pos;
    private final List<String> stations;
    private final String currentStation;
    private final boolean active;
    private final int radius;
    private int volumePercent;

    public CointRadioScreen(
            BlockPos pos,
            List<String> stations,
            String currentStation,
            boolean active,
            int radius
    ) {
        super(Component.literal("Радио"));
        this.pos = pos;
        this.stations = new ArrayList<>(stations);
        this.currentStation = currentStation == null ? "" : currentStation;
        this.active = active;
        this.radius = radius;
        this.volumePercent = CointRadioPlayer.getVolumePercent();
    }

    @Override
    protected void init() {
        super.init();

        int buttonWidth = 220;
        int buttonHeight = 20;
        int spacing = 24;

        int startY = 70;

        this.addRenderableWidget(
                Button.builder(
                                Component.literal(active ? "§cВыключить радиоблок" : "§aВключить радиоблок"),
                                button -> {
                                    CointRadioNetwork.sendToggleActiveToServer(pos);
                                    this.onClose();
                                }
                        )
                        .bounds(this.width / 2 - buttonWidth / 2, startY, buttonWidth, buttonHeight)
                        .build()
        );

        this.addRenderableWidget(
                new AbstractSliderButton(
                        this.width / 2 - buttonWidth / 2,
                        startY + 24,
                        buttonWidth,
                        buttonHeight,
                        Component.literal("§eГромкость: §f" + volumePercent + "%"),
                        volumePercent / 100.0D
                ) {
                    @Override
                    protected void updateMessage() {
                        int percent = (int) Math.round(this.value * 100.0D);
                        this.setMessage(Component.literal("§eГромкость: §f" + percent + "%"));
                    }

                    @Override
                    protected void applyValue() {
                        int percent = (int) Math.round(this.value * 100.0D);
                        volumePercent = percent;
                        CointRadioPlayer.setVolumePercent(percent);
                    }
                }
        );

        int stationStartY = startY + 60;

        for (int i = 0; i < stations.size(); i++) {
            String stationId = stations.get(i);

            Component label;

            if (stationId.equalsIgnoreCase(currentStation)) {
                label = Component.literal("§a▶ " + stationId);
            } else {
                label = Component.literal("§f" + stationId);
            }

            int y = stationStartY + i * spacing;

            this.addRenderableWidget(
                    Button.builder(label, button -> {
                                CointRadioNetwork.sendSelectStationToServer(pos, stationId);
                                this.onClose();
                            })
                            .bounds(this.width / 2 - buttonWidth / 2, y, buttonWidth, buttonHeight)
                            .build()
            );
        }

        this.addRenderableWidget(
                Button.builder(Component.literal("Закрыть"), button -> this.onClose())
                        .bounds(this.width / 2 - buttonWidth / 2, this.height - 45, buttonWidth, buttonHeight)
                        .build()
        );
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        graphics.drawCenteredString(
                this.font,
                "Выбор станции",
                this.width / 2,
                15,
                0xFFFFFF
        );

        graphics.drawCenteredString(
                this.font,
                "Станция: " + currentStation,
                this.width / 2,
                32,
                0xDDDDDD
        );

        graphics.drawCenteredString(
                this.font,
                active ? "Состояние: включено" : "Состояние: выключено",
                this.width / 2,
                44,
                active ? 0x55FF55 : 0xFF5555
        );

        graphics.drawCenteredString(
                this.font,
                "Радиус: " + radius,
                this.width / 2,
                56,
                0xDDDDDD
        );

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}