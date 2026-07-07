package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
    private final String customUrl;

    private int volumePercent;
    private EditBox urlBox;

    public CointRadioScreen(
            BlockPos pos,
            List<String> stations,
            String currentStation,
            boolean active,
            int radius,
            String customUrl
    ) {
        super(Component.literal("Радио"));
        this.pos = pos;
        this.stations = new ArrayList<>(stations);
        this.currentStation = currentStation == null ? "" : currentStation;
        this.active = active;
        this.radius = radius;
        this.customUrl = customUrl == null ? "" : customUrl;
        this.volumePercent = CointRadioPlayer.getVolumePercent();
    }

    @Override
    protected void init() {
        super.init();

        int buttonWidth = 220;
        int buttonHeight = 20;
        int startY = 78;

        this.urlBox = new EditBox(
                this.font,
                this.width / 2 - buttonWidth / 2,
                startY,
                buttonWidth,
                buttonHeight,
                Component.literal("URL")
        );

        this.urlBox.setMaxLength(2048);
        this.urlBox.setValue(customUrl);
        this.urlBox.setHint(Component.literal("https://radio.example.com/stream"));
        this.addRenderableWidget(this.urlBox);

        this.addRenderableWidget(
                Button.builder(
                                Component.literal("§aПрименить URL"),
                                button -> {
                                    CointRadioNetwork.sendSetCustomUrlToServer(pos, urlBox.getValue());
                                    this.onClose();
                                }
                        )
                        .bounds(this.width / 2 - buttonWidth / 2, startY + 24, buttonWidth, buttonHeight)
                        .build()
        );

        this.addRenderableWidget(
                Button.builder(
                                Component.literal("§cОчистить URL"),
                                button -> {
                                    CointRadioNetwork.sendClearCustomUrlToServer(pos);
                                    this.onClose();
                                }
                        )
                        .bounds(this.width / 2 - buttonWidth / 2, startY + 48, buttonWidth, buttonHeight)
                        .build()
        );

        this.addRenderableWidget(
                Button.builder(
                                Component.literal(active ? "§cВыключить радиоблок" : "§aВключить радиоблок"),
                                button -> {
                                    CointRadioNetwork.sendToggleActiveToServer(pos);
                                    this.onClose();
                                }
                        )
                        .bounds(this.width / 2 - buttonWidth / 2, startY + 78, buttonWidth, buttonHeight)
                        .build()
        );

        this.addRenderableWidget(
                new AbstractSliderButton(
                        this.width / 2 - buttonWidth / 2,
                        startY + 102,
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

        int stationStartY = startY + 136;

        for (int i = 0; i < stations.size(); i++) {
            String stationEntry = stations.get(i);
            String stationId = stationIdFromEntry(stationEntry);
            String stationName = stationNameFromEntry(stationEntry);

            Component label;

            if (!customUrl.isBlank()) {
                label = Component.literal("§8" + stationName);
            } else if (stationId.equalsIgnoreCase(currentStation)) {
                label = Component.literal("§a▶ " + stationName);
            } else {
                label = Component.literal("§f" + stationName);
            }

            int y = stationStartY + i * 24;

            Button stationButton = Button.builder(label, button -> {
                        CointRadioNetwork.sendSelectStationToServer(pos, stationId);
                        this.onClose();
                    })
                    .bounds(this.width / 2 - buttonWidth / 2, y, buttonWidth, buttonHeight)
                    .build();

            if (!customUrl.isBlank()) {
                stationButton.active = false;
            }

            this.addRenderableWidget(stationButton);
        }

        this.addRenderableWidget(
                Button.builder(Component.literal("Закрыть"), button -> this.onClose())
                        .bounds(this.width / 2 - buttonWidth / 2, this.height - 32, buttonWidth, buttonHeight)
                        .build()
        );
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        graphics.drawCenteredString(
                this.font,
                "Радио",
                this.width / 2,
                12,
                0xFFFFFF
        );

        String stationText = customUrl.isBlank() ? getCurrentStationDisplayName() : "custom URL";

        graphics.drawCenteredString(
                this.font,
                "Источник: " + stationText,
                this.width / 2,
                28,
                0xDDDDDD
        );

        graphics.drawCenteredString(
                this.font,
                active ? "Состояние: включено" : "Состояние: выключено",
                this.width / 2,
                40,
                active ? 0x55FF55 : 0xFF5555
        );

        graphics.drawCenteredString(
                this.font,
                "Радиус: " + radius,
                this.width / 2,
                52,
                0xDDDDDD
        );

        graphics.drawCenteredString(
                this.font,
                "Ссылка или online-radio stream:",
                this.width / 2,
                66,
                0xAAAAAA
        );

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private static String stationIdFromEntry(String entry) {
        if (entry == null || entry.isBlank()) {
            return "";
        }

        int separator = entry.indexOf('\u001F');

        if (separator < 0) {
            return entry;
        }

        return entry.substring(0, separator);
    }

    private static String stationNameFromEntry(String entry) {
        if (entry == null || entry.isBlank()) {
            return "";
        }

        int separator = entry.indexOf('\u001F');

        if (separator < 0 || separator >= entry.length() - 1) {
            return entry;
        }

        return entry.substring(separator + 1);
    }

    private String getCurrentStationDisplayName() {
        for (String stationEntry : stations) {
            String stationId = stationIdFromEntry(stationEntry);

            if (stationId.equalsIgnoreCase(currentStation)) {
                return stationNameFromEntry(stationEntry);
            }
        }

        return currentStation;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}