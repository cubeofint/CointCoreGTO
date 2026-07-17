package Crazer.cubeofinterest.cointcoregto.exchanger;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraftforge.registries.ForgeRegistries;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class ExchangerCraftEvents {
    // true = craft on false = cannot craft
    private static final boolean CRAFTING_ENABLED = false;

    private static final ResourceLocation ME_INTERFACE_ID =
            new ResourceLocation("ae2", "interface");

    private static final ResourceLocation LV_MACHINE_HULL_ID =
            new ResourceLocation("gtceu", "lv_machine_hull");

    private static final ResourceLocation LV_ROBOT_ARM_ID =
            new ResourceLocation("gtceu", "lv_robot_arm");

    private static final ResourceLocation LV_CONVEYOR_ID =
            new ResourceLocation("gtceu", "lv_conveyor_module");

    private static final ResourceLocation LV_CIRCUIT_TAG_ID =
            new ResourceLocation("gtceu", "circuits/lv");

    private ExchangerCraftEvents() {
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Player player = event.getEntity();
        InteractionHand hand = event.getHand();
        ItemStack held = player.getItemInHand(hand);

        if (!player.isShiftKeyDown()) {
            return;
        }

        Item meInterface = getItem(ME_INTERFACE_ID);

        if (meInterface == Items.AIR || !held.is(meInterface)) {
            return;
        }

        event.setCanceled(true);

        if (player.level().isClientSide) {
            return;
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        craftExchanger(serverPlayer, held);
    }

    private static void craftExchanger(ServerPlayer player, ItemStack held) {
        if (!CRAFTING_ENABLED) {
            player.displayClientMessage(
                    Component.literal("§e[Обменник] В данный момент предмет нельзя создать. Крафт будет доступен после баланса экономики."),
                    true
            );
            return;
        }

        Item machineHull = getItem(LV_MACHINE_HULL_ID);
        Item robotArm = getItem(LV_ROBOT_ARM_ID);
        Item conveyor = getItem(LV_CONVEYOR_ID);

        if (machineHull == Items.AIR || robotArm == Items.AIR || conveyor == Items.AIR) {
            player.displayClientMessage(
                    Component.literal(
                            "§c[Обменник] Не найдены необходимые компоненты GTCEu. " +
                                    "Проверь ID LV-корпуса, манипулятора и конвейера."
                    ),
                    true
            );
            return;
        }

        if (!player.getAbilities().instabuild) {
            if (countItem(player, machineHull) < 1
                    || countItem(player, robotArm) < 2
                    || countItem(player, conveyor) < 2
                    || countItem(player, Items.COMPARATOR) < 1
                    || countTag(player, LV_CIRCUIT_TAG_ID) < 2) {

                player.displayClientMessage(
                        Component.literal(
                                "§c[Обменник] Для сборки нужны: " +
                                        "ME-интерфейс в руке, 1 LV корпус машины, " +
                                        "2 LV манипулятора, 2 LV конвейера, " +
                                        "2 LV схемы и 1 компаратор."
                        ),
                        true
                );
                return;
            }

            held.shrink(1);
            removeItems(player, machineHull, 1);
            removeItems(player, robotArm, 2);
            removeItems(player, conveyor, 2);
            removeItems(player, Items.COMPARATOR, 1);
            removeTagItems(player, LV_CIRCUIT_TAG_ID, 2);
        }

        give(player, new ItemStack(CointExchangerRegistry.EXCHANGER_ITEM.get()));

        player.displayClientMessage(
                Component.literal("§a[Обменник] Торговый обменник собран."),
                true
        );
    }

    private static Item getItem(ResourceLocation id) {
        Item item = ForgeRegistries.ITEMS.getValue(id);
        return item == null ? Items.AIR : item;
    }

    private static int countItem(Player player, Item item) {
        int count = 0;
        Inventory inventory = player.getInventory();

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);

            if (!stack.isEmpty() && stack.is(item)) {
                count += stack.getCount();
            }
        }

        return count;
    }

    private static int countTag(Player player, ResourceLocation tagId) {
        int count = 0;
        Inventory inventory = player.getInventory();

        var tag = net.minecraft.tags.TagKey.create(
                net.minecraft.core.registries.Registries.ITEM,
                tagId
        );

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);

            if (!stack.isEmpty() && stack.is(tag)) {
                count += stack.getCount();
            }
        }

        return count;
    }

    private static void removeItems(Player player, Item item, int amount) {
        Inventory inventory = player.getInventory();
        int remaining = amount;

        for (int slot = 0;
             slot < inventory.getContainerSize() && remaining > 0;
             slot++) {

            ItemStack stack = inventory.getItem(slot);

            if (stack.isEmpty() || !stack.is(item)) {
                continue;
            }

            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            remaining -= removed;
        }
    }

    private static void removeTagItems(
            Player player,
            ResourceLocation tagId,
            int amount
    ) {
        Inventory inventory = player.getInventory();
        int remaining = amount;

        var tag = net.minecraft.tags.TagKey.create(
                net.minecraft.core.registries.Registries.ITEM,
                tagId
        );

        for (int slot = 0;
             slot < inventory.getContainerSize() && remaining > 0;
             slot++) {

            ItemStack stack = inventory.getItem(slot);

            if (stack.isEmpty() || !stack.is(tag)) {
                continue;
            }

            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            remaining -= removed;
        }
    }

    private static void give(Player player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }
}