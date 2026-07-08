package Crazer.cubeofinterest.cointcoregto.compat.radio;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class CointRadioCraftEvents {
    private CointRadioCraftEvents() {
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        InteractionHand hand = event.getHand();
        ItemStack held = player.getItemInHand(hand);

        if (!player.isShiftKeyDown()) {
            return;
        }

        if (held.is(Items.JUKEBOX)) {
            craftRadio(event, player, held);
            return;
        }

        if (held.is(Items.NOTE_BLOCK)) {
            craftSpeaker(event, player, held);
            return;
        }

        if (held.is(Items.COMPASS)) {
            craftTuner(event, player, held);
        }
    }

    private static void craftRadio(
            PlayerInteractEvent.RightClickItem event,
            Player player,
            ItemStack held
    ) {
        if (player.level().isClientSide) {
            event.setCanceled(true);
            return;
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        if (!hasItems(player, Items.NOTE_BLOCK, Items.REDSTONE)) {
            serverPlayer.displayClientMessage(
                    Component.literal("§c[CointMusic] Для создания радио нужны: проигрыватель, нотный блок и редстоун."),
                    true
            );
            event.setCanceled(true);
            return;
        }

        if (!player.getAbilities().instabuild) {
            held.shrink(1);
            removeOne(player, Items.NOTE_BLOCK);
            removeOne(player, Items.REDSTONE);
        }

        give(player, new ItemStack(CointRadioBlocks.COINT_RADIO_ITEM.get()));

        serverPlayer.displayClientMessage(
                Component.literal("§a[CointMusic] Радио создано."),
                true
        );

        event.setCanceled(true);
    }

    private static void craftSpeaker(
            PlayerInteractEvent.RightClickItem event,
            Player player,
            ItemStack held
    ) {
        if (player.level().isClientSide) {
            event.setCanceled(true);
            return;
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        if (!hasItems(player, Items.IRON_INGOT, Items.REDSTONE)) {
            serverPlayer.displayClientMessage(
                    Component.literal("§c[CointMusic] Для создания динамика нужны: нотный блок, железный слиток и редстоун."),
                    true
            );
            event.setCanceled(true);
            return;
        }

        if (!player.getAbilities().instabuild) {
            held.shrink(1);
            removeOne(player, Items.IRON_INGOT);
            removeOne(player, Items.REDSTONE);
        }

        give(player, new ItemStack(CointRadioBlocks.COINT_SPEAKER_ITEM.get()));

        serverPlayer.displayClientMessage(
                Component.literal("§a[CointMusic] Динамик создан."),
                true
        );

        event.setCanceled(true);
    }

    private static void craftTuner(
            PlayerInteractEvent.RightClickItem event,
            Player player,
            ItemStack held
    ) {
        if (player.level().isClientSide) {
            event.setCanceled(true);
            return;
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        if (!hasItems(player, Items.IRON_INGOT, Items.REDSTONE)) {
            serverPlayer.displayClientMessage(
                    Component.literal("§c[CointMusic] Для создания тюнера нужны: компас, железный слиток и редстоун."),
                    true
            );
            event.setCanceled(true);
            return;
        }

        if (!player.getAbilities().instabuild) {
            held.shrink(1);
            removeOne(player, Items.IRON_INGOT);
            removeOne(player, Items.REDSTONE);
        }

        give(player, new ItemStack(CointRadioBlocks.COINT_TUNER_ITEM.get()));

        serverPlayer.displayClientMessage(
                Component.literal("§a[CointMusic] Тюнер создан."),
                true
        );

        event.setCanceled(true);
    }

    private static boolean hasItems(Player player, Item... items) {
        if (player.getAbilities().instabuild) {
            return true;
        }

        for (Item item : items) {
            if (!hasItem(player, item)) {
                return false;
            }
        }

        return true;
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

    private static void give(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }
}