package Crazer.cubeofinterest.cointcoregto.exchanger;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ExchangerBlockItem extends BlockItem {
    public ExchangerBlockItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.literal("Обменник");
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            @Nullable Level level,
            List<Component> tooltip,
            TooltipFlag flag
    ) {
        tooltip.add(Component.literal("Обменник для торговли между игроками").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("Игрок покупает один предмет за другой").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Товар: левый слот").withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.literal("Цена: правый слот").withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.literal("Только владелец и OP могут менять слоты").withStyle(ChatFormatting.RED));
    }
}