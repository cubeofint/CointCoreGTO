package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        tooltip.add(Component.literal("Онлайн-радио GTO").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.literal("ПКМ по блоку: включить / следующая станция").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Shift + ПКМ по блоку: открыть меню").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Поддержка: OGG, MP3, M3U/PLS, online stream").withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.literal("Создание: Shift + ПКМ по воздуху с проигрывателем").withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.literal("Нужно: проигрыватель + нотный блок + редстоун").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(
            Level level,
            Player player,
            InteractionHand hand
    ) {
        ItemStack stack = player.getItemInHand(hand);

        if (!player.isShiftKeyDown()) {
            return InteractionResultHolder.pass(stack);
        }

        if (stack.getItem() != Items.JUKEBOX) {
            return InteractionResultHolder.pass(stack);
        }

        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.pass(stack);
        }

        if (!hasRequiredItems(player)) {
            serverPlayer.displayClientMessage(
                    Component.literal("§c[CointMusic] Для создания радио нужны: проигрыватель, нотный блок и редстоун."),
                    true
            );
            return InteractionResultHolder.fail(stack);
        }

        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
            removeOne(player, Items.NOTE_BLOCK);
            removeOne(player, Items.REDSTONE);
        }

        ItemStack radioStack = new ItemStack(CointRadioBlocks.COINT_RADIO_ITEM.get());

        if (!player.getInventory().add(radioStack)) {
            player.drop(radioStack, false);
        }

        serverPlayer.displayClientMessage(
                Component.literal("§a[CointMusic] Радиоблок создан."),
                true
        );

        return InteractionResultHolder.success(stack);
    }

    private static boolean hasRequiredItems(Player player) {
        if (player.getAbilities().instabuild) {
            return true;
        }

        return hasItem(player, Items.NOTE_BLOCK)
                && hasItem(player, Items.REDSTONE);
    }

    private static boolean hasItem(Player player, Item item) {
        Inventory inventory = player.getInventory();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);

            if (!stack.isEmpty() && stack.is(item)) {
                return true;
            }
        }

        return false;
    }

    private static void removeOne(Player player, Item item) {
        Inventory inventory = player.getInventory();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);

            if (!stack.isEmpty() && stack.is(item)) {
                stack.shrink(1);
                return;
            }
        }
    }
}