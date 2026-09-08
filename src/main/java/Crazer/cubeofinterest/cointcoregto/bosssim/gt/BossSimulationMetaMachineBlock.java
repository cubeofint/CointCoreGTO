package Crazer.cubeofinterest.cointcoregto.bosssim.gt;

import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import net.minecraft.world.level.block.state.BlockBehaviour;

public final class BossSimulationMetaMachineBlock extends MetaMachineBlock {
    public static final String DISPLAY_NAME = "Камера симуляции боссов";

    public BossSimulationMetaMachineBlock(
            BlockBehaviour.Properties properties,
            MultiblockMachineDefinition definition
    ) {
        super(properties, definition);
    }

    @Override
    public String getDescriptionId() {
        return DISPLAY_NAME;
    }
}
