package Crazer.cubeofinterest.cointcoregto.bosssim.gt;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

public final class BossSimulationCasingBlock extends Block {
    public static final String DISPLAY_NAME = "Корпус камеры симуляции";

    public BossSimulationCasingBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public String getDescriptionId() {
        return DISPLAY_NAME;
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        return new ItemStack(BossSimulationBlocks.BOSS_SIMULATION_CASING_ITEM.get());
    }
}
