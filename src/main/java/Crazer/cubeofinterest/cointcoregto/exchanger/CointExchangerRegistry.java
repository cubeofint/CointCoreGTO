package Crazer.cubeofinterest.cointcoregto.exchanger;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class CointExchangerRegistry {
    private CointExchangerRegistry() {
    }

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, CointCoreGTO.MODID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, CointCoreGTO.MODID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CointCoreGTO.MODID);

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, CointCoreGTO.MODID);

    public static final RegistryObject<Block> EXCHANGER = BLOCKS.register(
            "exchanger",
            () -> new ExchangerBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(4.0F, 6.0F)
                    .requiresCorrectToolForDrops())
    );

    public static final RegistryObject<Item> EXCHANGER_ITEM = ITEMS.register(
            "exchanger",
            () -> new ExchangerBlockItem(EXCHANGER.get(), new Item.Properties())
    );

    public static final RegistryObject<BlockEntityType<ExchangerBlockEntity>> EXCHANGER_BLOCK_ENTITY =
            BLOCK_ENTITIES.register(
                    "exchanger",
                    () -> BlockEntityType.Builder.of(
                            ExchangerBlockEntity::new,
                            EXCHANGER.get()
                    ).build(null)
            );

    public static final RegistryObject<MenuType<ExchangerMenu>> EXCHANGER_MENU =
            MENUS.register(
                    "exchanger",
                    () -> IForgeMenuType.create((windowId, inventory, data) ->
                            new ExchangerMenu(
                                    windowId,
                                    inventory,
                                    data.readBlockPos(),
                                    data.readBoolean()
                            )
                    )
            );

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
        ITEMS.register(eventBus);
        BLOCK_ENTITIES.register(eventBus);
        MENUS.register(eventBus);
    }
}