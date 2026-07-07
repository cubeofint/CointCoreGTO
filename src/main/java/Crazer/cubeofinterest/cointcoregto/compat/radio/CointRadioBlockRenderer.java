package Crazer.cubeofinterest.cointcoregto.compat.radio;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public class CointRadioBlockRenderer implements BlockEntityRenderer<CointRadioBlockEntity> {
    public CointRadioBlockRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(
            CointRadioBlockEntity radio,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay
    ) {
        BlockState radioState = radio.getBlockState();

        boolean active = false;

        if (radioState.hasProperty(CointRadioBlock.ACTIVE)) {
            active = radioState.getValue(CointRadioBlock.ACTIVE);
        }

        BlockState renderState = active
                ? Blocks.JUKEBOX.defaultBlockState()
                : Blocks.NOTE_BLOCK.defaultBlockState();

        Minecraft.getInstance()
                .getBlockRenderer()
                .renderSingleBlock(
                        renderState,
                        poseStack,
                        bufferSource,
                        LightTexture.FULL_BRIGHT,
                        packedOverlay
                );
    }
}