package Crazer.cubeofinterest.cointcoregto.bosssim.gt;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

public final class BossSimulationCasingItem extends BlockItem {
    public BossSimulationCasingItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public String getDescriptionId() {
        return BossSimulationCasingBlock.DISPLAY_NAME;
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.literal(BossSimulationCasingBlock.DISPLAY_NAME);
    }
}
