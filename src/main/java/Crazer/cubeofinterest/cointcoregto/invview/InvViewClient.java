package Crazer.cubeofinterest.cointcoregto.invview;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = CointCoreGTO.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class InvViewClient {
    private static boolean registered;

    private InvViewClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(InvViewClient::registerScreens);
    }

    public static void registerScreens() {
        if (registered) {
            return;
        }
        MenuScreens.register(InvViewRegistry.MENU.get(), InvViewScreen::new);
        registered = true;
    }
}
