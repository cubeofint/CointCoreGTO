package Crazer.cubeofinterest.cointcoregto.compat.radio;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class CointRadioBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, CointCoreGTO.MODID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, CointCoreGTO.MODID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, CointCoreGTO.MODID);

    public static final RegistryObject<Block> COINT_RADIO =
            BLOCKS.register("coint_radio", CointRadioBlock::new);

    public static final RegistryObject<Block> COINT_SPEAKER =
            BLOCKS.register("coint_speaker", CointSpeakerBlock::new);

    public static final RegistryObject<Item> COINT_RADIO_ITEM =
            ITEMS.register(
                    "coint_radio",
                    () -> new CointRadioBlockItem(
                            COINT_RADIO.get(),
                            new Item.Properties()
                    )
            );

    public static final RegistryObject<Item> COINT_SPEAKER_ITEM =
            ITEMS.register(
                    "coint_speaker",
                    () -> new CointSpeakerBlockItem(
                            COINT_SPEAKER.get(),
                            new Item.Properties()
                    )
            );

    public static final RegistryObject<Item> COINT_TUNER_ITEM =
            ITEMS.register(
                    "coint_tuner",
                    () -> new CointTunerItem(new Item.Properties().stacksTo(1))
            );

    public static final RegistryObject<BlockEntityType<CointRadioBlockEntity>> COINT_RADIO_BLOCK_ENTITY =
            BLOCK_ENTITIES.register(
                    "coint_radio",
                    () -> BlockEntityType.Builder
                            .of(CointRadioBlockEntity::new, COINT_RADIO.get())
                            .build(null)
            );

    public static final RegistryObject<BlockEntityType<CointSpeakerBlockEntity>> COINT_SPEAKER_BLOCK_ENTITY =
            BLOCK_ENTITIES.register(
                    "coint_speaker",
                    () -> BlockEntityType.Builder
                            .of(CointSpeakerBlockEntity::new, COINT_SPEAKER.get())
                            .build(null)
            );

    private CointRadioBlocks() {
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
    }
}