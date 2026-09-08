package Crazer.cubeofinterest.cointcoregto.bosssim;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class NamedSelectorItem extends Item {
    private final Component displayName;

    public NamedSelectorItem(Properties properties, String displayName) {
        super(properties);
        this.displayName = Component.literal(displayName);
    }

    @Override
    public Component getName(ItemStack stack) {
        return displayName;
    }
}
