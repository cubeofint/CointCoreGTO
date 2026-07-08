package Crazer.cubeofinterest.cointcoregto.compat.radio;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class CointRadioHudOverlay {
    private CointRadioHudOverlay() {
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            return;
        }

        if (minecraft.options.hideGui) {
            return;
        }

        if (!(minecraft.hitResult instanceof BlockHitResult blockHitResult)) {
            return;
        }

        if (blockHitResult.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockPos pos = blockHitResult.getBlockPos();
        BlockState state = minecraft.level.getBlockState(pos);

        if (!(state.getBlock() instanceof CointRadioBlock)) {
            return;
        }

        BlockEntity blockEntity = minecraft.level.getBlockEntity(pos);

        if (!(blockEntity instanceof CointRadioBlockEntity radio)) {
            return;
        }

        boolean active = false;

        if (state.hasProperty(CointRadioBlock.ACTIVE)) {
            active = state.getValue(CointRadioBlock.ACTIVE);
        }

        String station = radio.getStationDisplayName();
        int radius = radio.getRadius();

        drawRadioInfo(
                event.getGuiGraphics(),
                minecraft,
                station,
                active,
                radius
        );
    }

    private static void drawRadioInfo(
            GuiGraphics graphics,
            Minecraft minecraft,
            String station,
            boolean active,
            int radius
    ) {
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();

        int boxWidth = 180;
        int boxHeight = 50;

        int x = screenWidth / 2 - boxWidth / 2;
        int y = 48;

        graphics.fill(x, y, x + boxWidth, y + boxHeight, 0xAA000000);
        graphics.fill(x, y, x + boxWidth, y + 1, 0xFF555555);
        graphics.fill(x, y + boxHeight - 1, x + boxWidth, y + boxHeight, 0xFF222222);
        graphics.fill(x, y, x + 1, y + boxHeight, 0xFF555555);
        graphics.fill(x + boxWidth - 1, y, x + boxWidth, y + boxHeight, 0xFF222222);

        int textX = x + 8;
        int textY = y + 7;

        graphics.drawString(
                minecraft.font,
                "§6Радио",
                textX,
                textY,
                0xFFFFFF,
                true
        );

        graphics.drawString(
                minecraft.font,
                "§7Станция: §f" + station,
                textX,
                textY + 12,
                0xFFFFFF,
                true
        );

        graphics.drawString(
                minecraft.font,
                "§7Состояние: " + (active ? "§aвключено" : "§cвыключено"),
                textX,
                textY + 24,
                0xFFFFFF,
                true
        );

        graphics.drawString(
                minecraft.font,
                "§7Радиус: §f" + radius,
                textX,
                textY + 36,
                0xFFFFFF,
                true
        );
    }
}