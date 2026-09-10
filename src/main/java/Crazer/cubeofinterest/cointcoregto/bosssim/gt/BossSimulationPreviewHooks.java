package Crazer.cubeofinterest.cointcoregto.bosssim.gt;

import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

public final class BossSimulationPreviewHooks {

    private static boolean previewLogPrinted;

    private BossSimulationPreviewHooks() {}

    public static BlockState resolveRenderState(BlockState state, Map<BlockPos, BlockInfo> blocks) {
        if (state == null || blocks == null || blocks.isEmpty()) return state;

        MultiblockMachineDefinition definition = BossSimulationGTRegistration.getBossSimulationChamber();
        if (definition == null) return state;

        Block controller = definition.get();
        boolean bossPreview = false;
        for (BlockInfo info : blocks.values()) {
            if (info != null && info.getBlockState().getBlock() == controller) {
                bossPreview = true;
                break;
            }
        }
        if (!bossPreview) return state;

        Block block = state.getBlock();
        BlockState replacement = null;

        if (block == controller) {
            replacement = BossSimulationBlocks.BOSS_SIMULATION_PREVIEW_CONTROLLER.get().defaultBlockState();
        } else if (block instanceof MetaMachineBlock machineBlock) {
            String path = machineBlock.getDefinition().getId().getPath();
            if (path.endsWith("input_bus")) {
                replacement = BossSimulationBlocks.BOSS_SIMULATION_PREVIEW_INPUT_BUS.get().defaultBlockState();
            } else if (path.endsWith("output_bus")) {
                replacement = BossSimulationBlocks.BOSS_SIMULATION_PREVIEW_OUTPUT_BUS.get().defaultBlockState();
            } else if (path.contains("energy_input_hatch") ||
                    path.contains("substation_input_hatch") ||
                    path.contains("laser_input_hatch")) {
                replacement = BossSimulationBlocks.BOSS_SIMULATION_PREVIEW_ENERGY_HATCH.get().defaultBlockState();
            }
        }

        if (replacement != null && !previewLogPrinted) {
            previewLogPrinted = true;
            System.out.println("[CointCoreGTO] Boss Simulation Chamber v5.12.27 using tier-independent in-world preview models");
        }

        return replacement == null ? state : replacement;
    }
}
