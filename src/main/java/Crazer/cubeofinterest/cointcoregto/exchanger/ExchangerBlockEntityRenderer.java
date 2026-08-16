package Crazer.cubeofinterest.cointcoregto.exchanger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

public final class ExchangerBlockEntityRenderer implements BlockEntityRenderer<ExchangerBlockEntity> {
    private static final float SCREEN_MIN_X = 3.2F / 16.0F;
    private static final float SCREEN_MAX_X = 12.8F / 16.0F;
    private static final float SCREEN_MIN_Y = 5.7F / 16.0F;
    private static final float SCREEN_MAX_Y = 12.7F / 16.0F;
    private static final float SCREEN_FACE_Z = 8.2F / 16.0F;
    private static final float SCREEN_WIDTH = SCREEN_MAX_X - SCREEN_MIN_X;
    private static final float SCREEN_HEIGHT = SCREEN_MAX_Y - SCREEN_MIN_Y;
    private static final float SCREEN_CENTER_X = (SCREEN_MIN_X + SCREEN_MAX_X) * 0.5F;
    private static final float SCREEN_CENTER_Y = (SCREEN_MIN_Y + SCREEN_MAX_Y) * 0.5F;
    private static final float BACKGROUND_DEPTH = 0.0025F;
    private static final float ITEM_CENTER_Z = SCREEN_FACE_Z - 0.092F;
    private static final float ITEM_SCALE = 0.36F;

    public ExchangerBlockEntityRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(
            ExchangerBlockEntity exchanger,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            int packedOverlay
    ) {
        ItemStack product = exchanger.getDisplayProduct();
        if (product.isEmpty()) {
            return;
        }

        poseStack.pushPose();
        rotateToFront(exchanger, poseStack);
        renderScreenBackground(poseStack, bufferSource, packedOverlay);
        renderProduct(exchanger, product, poseStack, bufferSource, packedOverlay);
        poseStack.popPose();
    }

    private static void rotateToFront(ExchangerBlockEntity exchanger, PoseStack poseStack) {
        float angle = switch (exchanger.getBlockState().getValue(ExchangerBlock.FACING)) {
            case EAST -> -90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 90.0F;
            default -> 0.0F;
        };

        poseStack.translate(0.5D, 0.0D, 0.5D);
        poseStack.mulPose(Axis.YP.rotationDegrees(angle));
        poseStack.translate(-0.5D, 0.0D, -0.5D);
    }

    private static void renderScreenBackground(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedOverlay
    ) {
        poseStack.pushPose();
        poseStack.translate(
                SCREEN_MIN_X,
                SCREEN_MIN_Y,
                SCREEN_FACE_Z - BACKGROUND_DEPTH
        );
        poseStack.scale(SCREEN_WIDTH, SCREEN_HEIGHT, BACKGROUND_DEPTH);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                Blocks.BLACK_CONCRETE.defaultBlockState(),
                poseStack,
                bufferSource,
                LightTexture.FULL_BRIGHT,
                packedOverlay
        );
        poseStack.popPose();
    }

    private static void renderProduct(
            ExchangerBlockEntity exchanger,
            ItemStack product,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedOverlay
    ) {
        ItemStack display = product.copy();
        display.setCount(1);

        poseStack.pushPose();
        poseStack.translate(SCREEN_CENTER_X, SCREEN_CENTER_Y, ITEM_CENTER_Z);
        poseStack.scale(ITEM_SCALE, ITEM_SCALE, ITEM_SCALE);

        Minecraft.getInstance().getItemRenderer().renderStatic(
                display,
                ItemDisplayContext.FIXED,
                LightTexture.FULL_BRIGHT,
                packedOverlay,
                poseStack,
                bufferSource,
                exchanger.getLevel(),
                (int) exchanger.getBlockPos().asLong()
        );
        poseStack.popPose();
    }
}
