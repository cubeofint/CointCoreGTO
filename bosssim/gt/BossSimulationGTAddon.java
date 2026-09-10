package Crazer.cubeofinterest.cointcoregto.bosssim.gt;

import net.minecraftforge.eventbus.api.IEventBus;

public final class BossSimulationGTAddon {
    private BossSimulationGTAddon() {}

    public static void register(IEventBus modBus) {
        BossSimulationBlocks.register(modBus);
        BossSimulationGTRegistration.registerEventListeners(modBus);
    }
}
