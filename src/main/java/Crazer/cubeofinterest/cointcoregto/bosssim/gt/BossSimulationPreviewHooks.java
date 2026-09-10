package Crazer.cubeofinterest.cointcoregto.bosssim.gt;

import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.pattern.MultiblockShapeInfo;
import com.gregtechceu.gtceu.client.renderer.MultiblockInWorldPreviewRenderer;
import com.gregtechceu.gtceu.common.data.GTMachines;
import com.lowdragmc.lowdraglib.utils.BlockInfo;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public final class BossSimulationPreviewHooks {

    private static boolean terminalPreviewLogPrinted;
    private static boolean fullscreenPreviewLogPrinted;

    private BossSimulationPreviewHooks() {}

    /**
     * GTCEu TerminalBehavior path. Keep this because it is also used by the
     * in-world terminal preview, which is separate from GTO's fullscreen
     * PatternPreview screen.
     */
    public static void showPreview(
            BlockPos pos,
            Direction frontFacing,
            Direction upwardsFacing,
            MultiblockShapeInfo shapeInfo,
            int duration) {
        if (shapeInfo == null) return;

        BlockInfo[][][] source = shapeInfo.getBlocks();
        BlockInfo[][][] patched = patchShapeBlocks(source);
        MultiblockShapeInfo target = patched == source
                ? shapeInfo
                : new MultiblockShapeInfo(shapeInfo.pattern, patched);

        MultiblockInWorldPreviewRenderer.showPreview(pos, frontFacing, upwardsFacing, target, duration);
    }

    /**
     * GTO 0.5.6 fullscreen PatternPreview path.
     *
     * PatternPreview flattens GTOLib's pattern result into a
     * Long2ReferenceOpenHashMap<BlockInfo> and then builds its dummy world
     * directly from that map. GTOLib chooses the casing fallback for our
     * ability predicates, so patching shapeInfo/TerminalBehavior cannot affect
     * this screen. This hook runs immediately after GTO creates that flat map
     * and before it creates any block entities / fills the dummy world.
     */
    public static void patchGtoFullscreenPreview(
            BlockPos controllerPos,
            Long2ReferenceOpenHashMap<BlockInfo> blockMap) {
        if (controllerPos == null || blockMap == null || blockMap.isEmpty()) return;

        MultiblockMachineDefinition definition = BossSimulationGTRegistration.getBossSimulationChamber();
        if (definition == null) return;

        BlockInfo controllerInfo = blockMap.get(controllerPos.asLong());
        if (controllerInfo == null || controllerInfo.getBlockState() == null) return;
        if (controllerInfo.getBlockState().getBlock() != definition.get()) return;

        /*
         * The controller is centered on one 3x3 wall. Exactly one horizontal
         * direction from it contains both +1 and +2 structure positions. That
         * is the depth direction, and deriving it from geometry makes this
         * independent of whatever canonical facing GTOLib picked for preview.
         */
        Direction depth = findDepthDirection(controllerPos, blockMap);
        if (depth == null) {
            System.err.println(
                    "[CointCoreGTO] Boss Simulation Chamber v5.12.32 GTO fullscreen preview: "
                            + "could not resolve structure depth from controller at " + controllerPos);
            return;
        }

        Direction controllerOutward = depth.getOpposite();
        Direction inputOutward = controllerOutward.getClockWise();
        Direction energyOutward = controllerOutward.getCounterClockWise();

        BlockPos middle = controllerPos.relative(depth);
        BlockPos outputPos = controllerPos.relative(depth, 2);
        BlockPos inputPos = middle.relative(inputOutward);
        BlockPos energyPos = middle.relative(energyOutward);

        if (!blockMap.containsKey(inputPos.asLong())
                || !blockMap.containsKey(outputPos.asLong())
                || !blockMap.containsKey(energyPos.asLong())) {
            System.err.println(
                    "[CointCoreGTO] Boss Simulation Chamber v5.12.32 GTO fullscreen preview: "
                            + "resolved hatch positions are outside block map; controller=" + controllerPos
                            + ", depth=" + depth
                            + ", input=" + inputPos
                            + ", output=" + outputPos
                            + ", energy=" + energyPos);
            return;
        }

        blockMap.put(
                inputPos.asLong(),
                machineInfo(GTMachines.ITEM_IMPORT_BUS[GTValues.LV], inputOutward));
        blockMap.put(
                outputPos.asLong(),
                machineInfo(GTMachines.ITEM_EXPORT_BUS[GTValues.LV], depth));
        blockMap.put(
                energyPos.asLong(),
                machineInfo(GTMachines.ENERGY_INPUT_HATCH[GTValues.LV], energyOutward));

        if (!fullscreenPreviewLogPrinted) {
            fullscreenPreviewLogPrinted = true;
            System.err.println(
                    "[CointCoreGTO] Boss Simulation Chamber v5.12.32 GTO fullscreen PatternPreview patched; "
                            + "input=" + inputPos
                            + ", output=" + outputPos
                            + ", energy=" + energyPos
                            + ", depth=" + depth);
        }
    }

    private static Direction findDepthDirection(
            BlockPos controllerPos,
            Long2ReferenceOpenHashMap<BlockInfo> blockMap) {
        Direction[] horizontal = {
                Direction.NORTH,
                Direction.SOUTH,
                Direction.WEST,
                Direction.EAST
        };
        for (Direction direction : horizontal) {
            if (blockMap.containsKey(controllerPos.relative(direction).asLong())
                    && blockMap.containsKey(controllerPos.relative(direction, 2).asLong())) {
                return direction;
            }
        }
        return null;
    }

    private static BlockInfo machineInfo(MachineDefinition definition, Direction facing) {
        MetaMachineBlock block = definition.get();
        BlockState state = block.defaultBlockState();
        RotationState rotationState = block.getRotationState();
        if (rotationState != RotationState.NONE
                && rotationState.test(facing)
                && state.hasProperty(rotationState.property)) {
            state = state.setValue(rotationState.property, facing);
        }
        return BlockInfo.fromBlockState(state);
    }

    public static BlockInfo[][][] patchShapeBlocks(BlockInfo[][][] source) {
        if (source == null) return null;

        MultiblockMachineDefinition definition = BossSimulationGTRegistration.getBossSimulationChamber();
        if (definition == null) return source;

        Block controller = definition.get();
        int controllerX = -1;
        int controllerY = -1;
        int controllerZ = -1;

        for (int x = 0; x < source.length; x++) {
            BlockInfo[][] aisle = source[x];
            if (aisle == null) continue;
            for (int y = 0; y < aisle.length; y++) {
                BlockInfo[] row = aisle[y];
                if (row == null) continue;
                for (int z = 0; z < row.length; z++) {
                    BlockInfo info = row[z];
                    if (info != null && info.getBlockState() != null && info.getBlockState().getBlock() == controller) {
                        controllerX = x;
                        controllerY = y;
                        controllerZ = z;
                        break;
                    }
                }
                if (controllerX >= 0) break;
            }
            if (controllerX >= 0) break;
        }

        if (controllerX < 0) return source;

        int inputX = controllerX - 1;
        int inputY = controllerY;
        int inputZ = controllerZ - 1;
        int outputX = controllerX - 2;
        int outputY = controllerY;
        int outputZ = controllerZ;
        int energyX = controllerX - 1;
        int energyY = controllerY;
        int energyZ = controllerZ + 1;

        if (!inside(source, inputX, inputY, inputZ)
                || !inside(source, outputX, outputY, outputZ)
                || !inside(source, energyX, energyY, energyZ)) {
            return source;
        }

        BlockInfo[][][] result = copy(source);
        result[inputX][inputY][inputZ] = BlockInfo.fromBlockState(
                BossSimulationBlocks.BOSS_SIMULATION_PREVIEW_INPUT_BUS.get().defaultBlockState());
        result[outputX][outputY][outputZ] = BlockInfo.fromBlockState(
                BossSimulationBlocks.BOSS_SIMULATION_PREVIEW_OUTPUT_BUS.get().defaultBlockState());
        result[energyX][energyY][energyZ] = BlockInfo.fromBlockState(
                BossSimulationBlocks.BOSS_SIMULATION_PREVIEW_ENERGY_HATCH.get().defaultBlockState());

        if (!terminalPreviewLogPrinted) {
            terminalPreviewLogPrinted = true;
            System.err.println("[CointCoreGTO] Boss Simulation Chamber v5.12.32 terminal preview intercepted; proxies=3");
        }

        return result;
    }

    private static boolean inside(BlockInfo[][][] source, int x, int y, int z) {
        return x >= 0
                && x < source.length
                && source[x] != null
                && y >= 0
                && y < source[x].length
                && source[x][y] != null
                && z >= 0
                && z < source[x][y].length;
    }

    private static BlockInfo[][][] copy(BlockInfo[][][] source) {
        BlockInfo[][][] result = new BlockInfo[source.length][][];
        for (int x = 0; x < source.length; x++) {
            if (source[x] == null) continue;
            result[x] = new BlockInfo[source[x].length][];
            for (int y = 0; y < source[x].length; y++) {
                if (source[x][y] != null) result[x][y] = source[x][y].clone();
            }
        }
        return result;
    }
}
