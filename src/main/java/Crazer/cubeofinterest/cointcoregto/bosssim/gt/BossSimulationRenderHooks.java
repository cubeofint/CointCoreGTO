package Crazer.cubeofinterest.cointcoregto.bosssim.gt;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine;
import com.lowdragmc.lowdraglib.utils.TrackedDummyWorld;
import net.minecraft.client.Minecraft;

public final class BossSimulationRenderHooks {

    private static boolean hideLogPrinted;

    private BossSimulationRenderHooks() {}

    public static boolean shouldHideMachineRender(MetaMachine machine) {
        if (!(machine instanceof MultiblockPartMachine part)) return false;
        if (machine.getLevel() == null || machine.getLevel() instanceof TrackedDummyWorld) return false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || machine.getLevel() != minecraft.level) return false;

        for (IMultiController controller : part.getControllers()) {
            if (controller != null && controller.self() instanceof BossSimulationChamberMachine chamber && chamber.isFormed()) {
                if (!hideLogPrinted) {
                    hideLogPrinted = true;
                    System.out.println("[CointCoreGTO] Boss Simulation Chamber v5.12.34 hiding attached GTCEu parts");
                }
                return true;
            }
        }
        return false;
    }
}
