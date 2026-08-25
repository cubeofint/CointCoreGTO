package Crazer.cubeofinterest.cointcoregto.monitor;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ClusterMonitorRegistry {
    private ClusterMonitorRegistry() {
    }

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, CointCoreGTO.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, CointCoreGTO.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CointCoreGTO.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, CointCoreGTO.MODID);

    public static final RegistryObject<Block> CLUSTER_MONITOR = BLOCKS.register(
            "cluster_monitor",
            () -> new ClusterMonitorBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(5.0F, 1200.0F)
                    .sound(SoundType.METAL))
    );

    public static final RegistryObject<Item> CLUSTER_MONITOR_ITEM = ITEMS.register(
            "cluster_monitor",
            () -> new ClusterMonitorBlockItem(CLUSTER_MONITOR.get(), new Item.Properties())
    );

    public static final RegistryObject<BlockEntityType<ClusterMonitorBlockEntity>> CLUSTER_MONITOR_BLOCK_ENTITY =
            BLOCK_ENTITIES.register(
                    "cluster_monitor",
                    () -> BlockEntityType.Builder.of(
                            ClusterMonitorBlockEntity::new,
                            CLUSTER_MONITOR.get()
                    ).build(null)
            );

    public static final RegistryObject<MenuType<ClusterMonitorMenu>> CLUSTER_MONITOR_MENU = MENUS.register(
            "cluster_monitor",
            () -> IForgeMenuType.create((windowId, inventory, data) ->
                    new ClusterMonitorMenu(windowId, inventory, data.readBlockPos()))
    );

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
        ITEMS.register(eventBus);
        BLOCK_ENTITIES.register(eventBus);
        MENUS.register(eventBus);
        eventBus.addListener(ClusterMonitorRegistry::addCreativeTabEntries);
    }

    private static void addCreativeTabEntries(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(CLUSTER_MONITOR_ITEM.get());
        }
    }
}
