package Crazer.cubeofinterest.cointcoregto.supply;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class SupplyBufferBlockItem extends BlockItem {
    public SupplyBufferBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.literal("Межсерверный буфер снабжения");
    }

    @Override
    public String getDescriptionId(ItemStack stack) {
        return "Межсерверный буфер снабжения";
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            @Nullable Level level,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.literal("Сверху: выдача запрошенной жидкости. Снизу: приём добытых предметов.")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Боковые стороны: выдача запрошенных предметов.")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Пустой картой создай Provider у главной ME, затем этой картой привяжи удалённые буферы.")
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
