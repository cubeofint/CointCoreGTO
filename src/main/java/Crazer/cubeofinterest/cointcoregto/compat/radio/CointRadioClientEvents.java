package Crazer.cubeofinterest.cointcoregto.compat.radio;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class CointRadioClientEvents {
    private CointRadioClientEvents() {
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        CointRadioPlayer.stop();
        CointRadioClientPacketHandler.resetCurrentRadio();

        System.out.println("[CointMusic] Stopped radio because client left the world/server");
    }
}