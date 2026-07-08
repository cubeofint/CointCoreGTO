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

public class CointSpeakerBlockItem extends BlockItem {
    public CointSpeakerBlockItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.literal("Динамик");
    }

    @Override
    public String getDescriptionId(ItemStack stack) {
        return "Динамик";
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Level level,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        tooltip.add(Component.literal("Динамик для радио").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("Дублирует радиус связанного радио").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("ПКМ тюнером по динамику: связать с радио").withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.literal("Создание: Shift + ПКМ по воздуху с нотным блоком").withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.literal("Нужно: нотный блок + железный слиток + редстоун").withStyle(ChatFormatting.DARK_GRAY));
    }
}