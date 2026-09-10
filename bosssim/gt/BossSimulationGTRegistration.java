package Crazer.cubeofinterest.cointcoregto.bosssim.gt;

import Crazer.cubeofinterest.cointcoregto.bosssim.BossSimulationMode;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.MultiblockShapeInfo;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate;
import com.gregtechceu.gtceu.common.data.GTMachines;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import net.minecraft.core.Direction;
import net.minecraftforge.eventbus.api.IEventBus;

public final class BossSimulationGTRegistration {

    public static final GTRegistrate REGISTRATE = GTRegistrate.create(BossSimulationMode.MOD_ID);

    private static volatile MultiblockMachineDefinition bossSimulationChamber;
    private static boolean listenersRegistered;

    private BossSimulationGTRegistration() {}

    public static synchronized void registerEventListeners(IEventBus modBus) {
        if (listenersRegistered) return;
        REGISTRATE.registerEventListeners(modBus);
        listenersRegistered = true;
    }

    public static synchronized void registerMachineDuringGtoInit() {
        if (bossSimulationChamber != null) return;

        bossSimulationChamber = REGISTRATE
                .multiblock(
                        "boss_simulation_chamber",
                        BossSimulationChamberMachine::new,
                        BossSimulationMetaMachineBlock::new,
                        BossSimulationMetaMachineItem::new,
                        MetaMachineBlockEntity::createBlockEntity
                )
                .langValue("Камера симуляции боссов")
                .rotationState(RotationState.NON_Y_AXIS)
                .recipeType(GTRecipeTypes.DUMMY_RECIPES)
                .appearanceBlock(BossSimulationBlocks.BOSS_SIMULATION_CASING)
                .pattern(definition -> FactoryBlockPattern.start()
                        .aisle("CCC", "CCC", "CCC")
                        .aisle("CCC", "C C", "CCC")
                        .aisle("CCC", "CXC", "CCC")
                        .where('X', Predicates.controller(definition))
                        .where('C', Predicates.blocks(BossSimulationBlocks.BOSS_SIMULATION_CASING.get())
                                .disableRenderFormed()
                                .or(Predicates.abilities(PartAbility.IMPORT_ITEMS)
                                        .setExactLimit(1)
                                        .setPreviewCount(1)
                                        .disableRenderFormed())
                                .or(Predicates.abilities(PartAbility.EXPORT_ITEMS)
                                        .setExactLimit(1)
                                        .setPreviewCount(1)
                                        .disableRenderFormed())
                                .or(Predicates.abilities(PartAbility.INPUT_ENERGY)
                                        .setMinGlobalLimited(1)
                                        .setMaxGlobalLimited(2)
                                        .setPreviewCount(1)
                                        .disableRenderFormed()))
                        .where(' ', Predicates.air())
                        .build())
                .shapeInfo(definition -> MultiblockShapeInfo.builder()
                        .aisle("CCC", "CCC", "CCC")
                        .aisle("CCC", "C C", "CCC")
                        .aisle("CIC", "OXC", "CEC")
                        .where('C', BossSimulationBlocks.BOSS_SIMULATION_CASING)
                        .where('I', GTMachines.ITEM_IMPORT_BUS[GTValues.LV], Direction.SOUTH)
                        .where('O', GTMachines.ITEM_EXPORT_BUS[GTValues.LV], Direction.SOUTH)
                        .where('E', GTMachines.ENERGY_INPUT_HATCH[GTValues.LV], Direction.SOUTH)
                        .where('X', definition, Direction.SOUTH)
                        .build(definition))
                .renderer(BossSimulationChamberRenderer::new)
                .register();

        System.out.println("[CointCoreGTO] Boss Simulation Chamber v5.12.7.1 registered during GTO GTOMachines.init");
    }

    public static MultiblockMachineDefinition getBossSimulationChamber() {
        return bossSimulationChamber;
    }
}
