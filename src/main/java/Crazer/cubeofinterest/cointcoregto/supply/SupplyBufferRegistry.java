package Crazer.cubeofinterest.cointcoregto.supply;

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

public final class SupplyBufferRegistry {
    private SupplyBufferRegistry() {
    }

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, CointCoreGTO.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, CointCoreGTO.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CointCoreGTO.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, CointCoreGTO.MODID);

    public static final RegistryObject<Block> SUPPLY_BUFFER = BLOCKS.register(
            "supply_buffer",
            () -> new SupplyBufferBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(5.0F, 1200.0F)
                    .sound(SoundType.METAL))
    );

    public static final RegistryObject<Item> SUPPLY_BUFFER_ITEM = ITEMS.register(
            "supply_buffer",
            () -> new SupplyBufferBlockItem(SUPPLY_BUFFER.get(), new Item.Properties())
    );

    public static final RegistryObject<Item> SUPPLY_LINK_CARD = ITEMS.register(
            "supply_link_card",
            () -> new SupplyLinkCardItem(new Item.Properties())
    );

    public static final RegistryObject<BlockEntityType<SupplyBufferBlockEntity>> SUPPLY_BUFFER_BLOCK_ENTITY =
            BLOCK_ENTITIES.register(
                    "supply_buffer",
                    () -> BlockEntityType.Builder.of(
                            SupplyBufferBlockEntity::new,
                            SUPPLY_BUFFER.get()
                    ).build(null)
            );

    public static final RegistryObject<MenuType<SupplyBufferMenu>> SUPPLY_BUFFER_MENU = MENUS.register(
            "supply_buffer",
            () -> IForgeMenuType.create((windowId, inventory, data) -> {
                var pos = data.readBlockPos();
                boolean canEdit = data.readBoolean();
                String linkId = data.readUtf(64);
                String providerNode = data.readUtf(64);
                return new SupplyBufferMenu(
                        windowId,
                        inventory,
                        pos,
                        canEdit,
                        linkId,
                        providerNode
                );
            })
    );

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
        ITEMS.register(eventBus);
        BLOCK_ENTITIES.register(eventBus);
        MENUS.register(eventBus);
        eventBus.addListener(SupplyBufferRegistry::addCreativeTabEntries);
    }

    private static void addCreativeTabEntries(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(SUPPLY_BUFFER_ITEM.get());
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(SUPPLY_LINK_CARD.get());
        }
    }
}
