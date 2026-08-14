package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;

public class CointRadioBlockItem extends BlockItem {
    public CointRadioBlockItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Level level,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        tooltip.add(Component.literal("Онлайн-радио GTO")
                .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("ПКМ по радио: открыть меню")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Shift + ПКМ по радио: следующая станция")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Включение и выключение — через меню")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Поддержка: OGG, MP3, M3U/PLS, online stream")
                .withStyle(ChatFormatting.DARK_AQUA));
    }
}
