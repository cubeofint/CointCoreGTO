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
        tooltip.add(Component.literal("Безопасная торговля между игроками")
                .withStyle(ChatFormatting.GOLD));

        if (!net.minecraft.client.gui.screens.Screen.hasShiftDown()) {
            tooltip.add(
                    Component.literal("Зажмите ")
                            .withStyle(ChatFormatting.DARK_GRAY)
                            .append(Component.literal("Shift")
                                    .withStyle(ChatFormatting.YELLOW))
                            .append(Component.literal(", чтобы узнать подробнее")
                                    .withStyle(ChatFormatting.DARK_GRAY))
            );
            return;
        }

        tooltip.add(Component.empty());

        tooltip.add(Component.literal("Настройка продавца:")
                .withStyle(ChatFormatting.AQUA));

        tooltip.add(
                Component.literal(" • ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal("Левый слот")
                                .withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(" — продаваемый товар")
                                .withStyle(ChatFormatting.GRAY))
        );

        tooltip.add(
                Component.literal(" • ")
                        .withStyle(ChatFormatting.DARK_GRAY)
                        .append(Component.literal("Правый слот")
                                .withStyle(ChatFormatting.YELLOW))
                        .append(Component.literal(" — цена за одну сделку")
                                .withStyle(ChatFormatting.GRAY))
        );

        tooltip.add(Component.empty());

        tooltip.add(Component.literal("Работа с ME-сетью:")
                .withStyle(ChatFormatting.AQUA));

        tooltip.add(Component.literal(" • Подключите к блоку ME-кабель")
                .withStyle(ChatFormatting.GRAY));

        tooltip.add(Component.literal(" • Товар берётся из сети продавца")
                .withStyle(ChatFormatting.GRAY));

        tooltip.add(Component.literal(" • Оплата возвращается в эту же сеть")
                .withStyle(ChatFormatting.GRAY));

        tooltip.add(Component.empty());

        tooltip.add(Component.literal("AE-режим покупателя:")
                .withStyle(ChatFormatting.LIGHT_PURPLE));

        tooltip.add(Component.literal(" • Оплата берётся из беспроводной ME-сети")
                .withStyle(ChatFormatting.GRAY));

        tooltip.add(Component.literal(" • Купленный товар отправляется туда же")
                .withStyle(ChatFormatting.GRAY));

        tooltip.add(Component.literal(" • Требуется доступный привязанный терминал")
                .withStyle(ChatFormatting.GRAY));

        tooltip.add(Component.empty());

        tooltip.add(Component.empty());

        tooltip.add(
                Component.literal("Крафт временно недоступен")
                        .withStyle(ChatFormatting.YELLOW)
        );

        tooltip.add(
                Component.literal("Будет открыт после баланса экономики")
                        .withStyle(ChatFormatting.DARK_GRAY)
        );

        tooltip.add(Component.literal("Настройки доступны только владельцу и OP")
                .withStyle(ChatFormatting.RED));
    }
}