package Crazer.cubeofinterest.cointcoregto.bosssim.gt;

import Crazer.cubeofinterest.cointcoregto.bosssim.BossSimulationMode;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class BossSimulationBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, BossSimulationMode.MOD_ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, BossSimulationMode.MOD_ID);

    public static final RegistryObject<Block> BOSS_SIMULATION_CASING = BLOCKS.register(
            "boss_simulation_casing",
            () -> new BossSimulationCasingBlock(
                    BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK)
                            .strength(5.0F, 6.0F)
                            .requiresCorrectToolForDrops()
                            .lightLevel(state -> state.getValue(BossSimulationCasingBlock.FORMED) ? 12 : 0)
            )
    );

    public static final RegistryObject<Block> BOSS_SIMULATION_RENDER_ANCHOR = BLOCKS.register(
            "boss_simulation_render_anchor",
            () -> new BossSimulationRenderAnchorBlock(
                    BlockBehaviour.Properties.copy(Blocks.BARRIER)
                            .noCollission()
                            .noOcclusion()
            )
    );

    public static final RegistryObject<Block> BOSS_SIMULATION_PREVIEW_CONTROLLER = BLOCKS.register(
            "boss_simulation_preview_controller",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK).noCollission().noOcclusion())
    );

    public static final RegistryObject<Block> BOSS_SIMULATION_PREVIEW_INPUT_BUS = BLOCKS.register(
            "boss_simulation_preview_input_bus",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK).noCollission().noOcclusion())
    );

    public static final RegistryObject<Block> BOSS_SIMULATION_PREVIEW_OUTPUT_BUS = BLOCKS.register(
            "boss_simulation_preview_output_bus",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK).noCollission().noOcclusion())
    );

    public static final RegistryObject<Block> BOSS_SIMULATION_PREVIEW_ENERGY_HATCH = BLOCKS.register(
            "boss_simulation_preview_energy_hatch",
            () -> new Block(BlockBehaviour.Properties.copy(Blocks.IRON_BLOCK).noCollission().noOcclusion())
    );

    public static final RegistryObject<Item> BOSS_SIMULATION_CASING_ITEM = ITEMS.register(
            "boss_simulation_casing",
            () -> new BossSimulationCasingItem(
                    BOSS_SIMULATION_CASING.get(),
                    new Item.Properties()
            )
    );

    private static boolean registered;

    private BossSimulationBlocks() {}

    public static synchronized void register(IEventBus modBus) {
        if (registered) return;
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        registered = true;
    }
}
