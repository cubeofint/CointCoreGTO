package Crazer.cubeofinterest.cointcoregto.bosssim.gt;

import Crazer.cubeofinterest.cointcoregto.bosssim.BossSimulationMode;
import com.gregtechceu.gtceu.GTCEu;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.registry.registrate.GTRegistrate;
import com.gregtechceu.gtceu.common.data.GTBlocks;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
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
                .appearanceBlock(GTBlocks.CASING_STEEL_SOLID)
                .pattern(definition -> FactoryBlockPattern.start()
                        .aisle("CCC", "CCC", "CCC")
                        .aisle("CCC", "C C", "CCC")
                        .aisle("CCC", "CXC", "CCC")
                        .where('X', Predicates.controller(definition))
                        .where('C', Predicates.blocks(GTBlocks.CASING_STEEL_SOLID.get())
                                .or(Predicates.abilities(PartAbility.IMPORT_ITEMS)
                                        .setExactLimit(1)
                                        .setPreviewCount(1))
                                .or(Predicates.abilities(PartAbility.EXPORT_ITEMS)
                                        .setExactLimit(1)
                                        .setPreviewCount(1))
                                .or(Predicates.abilities(PartAbility.INPUT_ENERGY)
                                        .setMinGlobalLimited(1)
                                        .setMaxGlobalLimited(2)
                                        .setPreviewCount(1)))
                        .where(' ', Predicates.air())
                        .build())
                .workableCasingRenderer(
                        GTCEu.id("block/casings/solid/machine_casing_solid_steel"),
                        GTCEu.id("block/machines/alloy_smelter"),
                        false)
                .register();

        System.out.println("[CointCoreGTO] Boss Simulation Chamber registered during GTO GTOMachines.init");
    }

    public static MultiblockMachineDefinition getBossSimulationChamber() {
        return bossSimulationChamber;
    }
}
