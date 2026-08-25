package Crazer.cubeofinterest.cointcoregto.monitor;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ClusterMonitorBlockItem extends BlockItem {
    public ClusterMonitorBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.literal("Кластерный монитор");
    }

    @Override
    public String getDescriptionId(ItemStack stack) {
        return "Кластерный монитор";
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            @Nullable Level level,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.literal("Показывает ноды и Supply Buffer всего межсерверного кластера.")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Видит Provider/Remote, состояние связи и незавершённые операции.")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
