package Crazer.cubeofinterest.cointcoregto.bosssim.gt;

import Crazer.cubeofinterest.cointcoregto.bosssim.BossSimulationMode;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;
import com.lowdragmc.lowdraglib.utils.TrackedDummyWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public final class BossSimulationChamberRenderer extends WorkableCasingMachineRenderer {

    private static final ResourceLocation ANCHOR_BLOCKSTATE =
            new ResourceLocation(BossSimulationMode.MOD_ID, "blockstates/boss_simulation_render_anchor.json");
    private static final ResourceLocation FORMED_MODEL_JSON =
            new ResourceLocation(BossSimulationMode.MOD_ID, "models/block/boss_simulation_chamber_formed.json");
    private static final ResourceLocation FORMED_TEXTURE =
            new ResourceLocation(BossSimulationMode.MOD_ID,
                    "textures/block/boss_simulation_chamber/texturebaked.png");

    private static boolean missingModelWarningPrinted;

    public BossSimulationChamberRenderer() {
        super(
                new ResourceLocation(BossSimulationMode.MOD_ID, "block/boss_simulation_chamber/texture_controller_base"),
                new ResourceLocation(BossSimulationMode.MOD_ID, "block/machines/boss_simulation_controller"),
                false
        );
    }

    @Override
    public void renderMachine(
            List<BakedQuad> quads,
            MachineDefinition definition,
            MetaMachine machine,
            Direction frontFacing,
            Direction side,
            RandomSource random,
            Direction modelFacing,
            ModelState rotation) {

        if (machine == null || machine.getLevel() instanceof TrackedDummyWorld) {
            if (renderControllerProxy(quads, side, random)) return;
            super.renderMachine(quads, definition, machine, frontFacing, side, random, modelFacing, rotation);
            return;
        }

        if (machine instanceof BossSimulationChamberMachine chamber && !chamber.isFormed()) {
            if (renderControllerProxy(quads, side, random)) return;
        }

        if (machine instanceof BossSimulationChamberMachine chamber && chamber.isFormed()) {
            Minecraft minecraft = Minecraft.getInstance();
            Direction facing = frontFacing.getAxis().isHorizontal() ? frontFacing : Direction.NORTH;
            BlockState anchorState = BossSimulationBlocks.BOSS_SIMULATION_RENDER_ANCHOR.get()
                    .defaultBlockState()
                    .setValue(HorizontalDirectionalBlock.FACING, facing);
            BakedModel formedModel = minecraft.getBlockRenderer().getBlockModel(anchorState);
            BakedModel missingModel = minecraft.getModelManager().getMissingModel();

            if (formedModel != missingModel) {
                float moveX = -facing.getStepX();
                float moveY = 0.0F;
                float moveZ = -facing.getStepZ();
                for (BakedQuad quad : formedModel.getQuads(anchorState, side, random)) {
                    quads.add(translateQuad(quad, moveX, moveY, moveZ));
                }
                return;
            }

            if (!missingModelWarningPrinted) {
                missingModelWarningPrinted = true;
                boolean anchorBlockstatePresent = minecraft.getResourceManager().getResource(ANCHOR_BLOCKSTATE).isPresent();
                boolean formedJsonPresent = minecraft.getResourceManager().getResource(FORMED_MODEL_JSON).isPresent();
                boolean formedTexturePresent = minecraft.getResourceManager().getResource(FORMED_TEXTURE).isPresent();

                System.err.println(
                        "[CointCoreGTO] Boss Simulation Chamber v5.12.35 render anchor resolved to missing model. "
                                + "facing=" + facing
                                + ", anchorBlockstatePresent=" + anchorBlockstatePresent
                                + ", formedJsonPresent=" + formedJsonPresent
                                + ", formedTexturePresent=" + formedTexturePresent
                );
            }
        }

        super.renderMachine(quads, definition, machine, frontFacing, side, random, modelFacing, rotation);
    }

    private static boolean renderControllerProxy(
            List<BakedQuad> quads,
            Direction side,
            RandomSource random) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockState previewState = BossSimulationBlocks.BOSS_SIMULATION_PREVIEW_CONTROLLER.get().defaultBlockState();
        BakedModel previewModel = minecraft.getBlockRenderer().getBlockModel(previewState);
        if (previewModel == minecraft.getModelManager().getMissingModel()) return false;
        quads.addAll(previewModel.getQuads(previewState, side, random));
        return true;
    }

    private static BakedQuad translateQuad(BakedQuad quad, float x, float y, float z) {
        int[] vertices = quad.getVertices().clone();
        int stride = vertices.length / 4;
        for (int vertex = 0; vertex < 4; vertex++) {
            int base = vertex * stride;
            vertices[base] = Float.floatToRawIntBits(Float.intBitsToFloat(vertices[base]) + x);
            vertices[base + 1] = Float.floatToRawIntBits(Float.intBitsToFloat(vertices[base + 1]) + y);
            vertices[base + 2] = Float.floatToRawIntBits(Float.intBitsToFloat(vertices[base + 2]) + z);
        }
        return new BakedQuad(
                vertices,
                quad.getTintIndex(),
                quad.getDirection(),
                quad.getSprite(),
                quad.isShade()
        );
    }
}
