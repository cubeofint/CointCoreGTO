package Crazer.cubeofinterest.cointcoregto.bosssim.gt;

import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import net.minecraft.world.item.Item;

public final class BossSimulationMetaMachineItem extends MetaMachineItem {

    public BossSimulationMetaMachineItem(MetaMachineBlock block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public String getDescriptionId() {
        return BossSimulationMetaMachineBlock.DISPLAY_NAME;
    }
}
