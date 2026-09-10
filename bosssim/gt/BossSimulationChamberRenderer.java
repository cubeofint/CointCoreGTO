package Crazer.cubeofinterest.cointcoregto.bosssim.gt;

import Crazer.cubeofinterest.cointcoregto.bosssim.BossSimulationMode;
import com.gregtechceu.gtceu.GTCEu;
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
                GTCEu.id("block/casings/solid/machine_casing_solid_steel"),
                GTCEu.id("block/machines/alloy_smelter"),
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

        if (machine.getLevel() instanceof TrackedDummyWorld) {
            super.renderMachine(quads, definition, machine, frontFacing, side, random, modelFacing, rotation);
            return;
        }

        if (machine instanceof BossSimulationChamberMachine chamber && chamber.isFormed()) {
            Minecraft minecraft = Minecraft.getInstance();
            Direction horizontalFacing = frontFacing.getAxis().isHorizontal() ? frontFacing : Direction.SOUTH;
            BlockState anchorState = BossSimulationBlocks.BOSS_SIMULATION_RENDER_ANCHOR.get()
                    .defaultBlockState()
                    .setValue(HorizontalDirectionalBlock.FACING, horizontalFacing);
            BakedModel formedModel = minecraft.getBlockRenderer().getBlockModel(anchorState);
            BakedModel missingModel = minecraft.getModelManager().getMissingModel();

            if (formedModel != missingModel) {
                quads.addAll(formedModel.getQuads(anchorState, side, random));
                return;
            }

            if (!missingModelWarningPrinted) {
                missingModelWarningPrinted = true;
                boolean anchorBlockstatePresent = minecraft.getResourceManager().getResource(ANCHOR_BLOCKSTATE).isPresent();
                boolean formedJsonPresent = minecraft.getResourceManager().getResource(FORMED_MODEL_JSON).isPresent();
                boolean formedTexturePresent = minecraft.getResourceManager().getResource(FORMED_TEXTURE).isPresent();

                System.err.println(
                        "[CointCoreGTO] Boss Simulation Chamber v5.12.7.1 render anchor resolved to missing model. "
                                + "facing=" + horizontalFacing
                                + ", anchorBlockstatePresent=" + anchorBlockstatePresent
                                + ", formedJsonPresent=" + formedJsonPresent
                                + ", formedTexturePresent=" + formedTexturePresent
                                + ". Check that the v7 CointCoreGTO resource pack is enabled."
                );
            }
        }

        super.renderMachine(quads, definition, machine, frontFacing, side, random, modelFacing, rotation);
    }
}
