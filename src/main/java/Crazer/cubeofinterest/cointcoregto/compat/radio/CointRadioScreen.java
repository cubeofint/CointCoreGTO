package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.client.gui.GuiGraphics;
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

    public CointRadioScreen(BlockPos pos, List<String> stations, String currentStation) {
        super(Component.literal("Радио"));
        this.pos = pos;
        this.stations = new ArrayList<>(stations);
        this.currentStation = currentStation == null ? "" : currentStation;
    }

    @Override
    protected void init() {
        super.init();

        int buttonWidth = 220;
        int buttonHeight = 20;
        int spacing = 24;

        int visibleCount = Math.min(stations.size(), 8);
        int startY = this.height / 2 - visibleCount * spacing / 2;

        for (int i = 0; i < stations.size(); i++) {
            String stationId = stations.get(i);

            Component label;

            if (stationId.equalsIgnoreCase(currentStation)) {
                label = Component.literal("§a▶ " + stationId);
            } else {
                label = Component.literal("§f" + stationId);
            }

            int y = startY + i * spacing;

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
                25,
                0xFFFFFF
        );

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}