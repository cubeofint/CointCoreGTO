package Crazer.cubeofinterest.cointcoregto.bosssim.gt;

import Crazer.cubeofinterest.cointcoregto.bosssim.BossSimulationMode;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BossSimulationMode.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BossSimulationClientModelEvents {
    private BossSimulationClientModelEvents() {}

    @SubscribeEvent
    public static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        event.register(new ResourceLocation(BossSimulationMode.MOD_ID, "item/boss_simulation_casing"));
    }
}
