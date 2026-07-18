package Crazer.cubeofinterest.cointcoregto;

import earth.terrarium.adastra.client.screens.PlanetsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class AdAstraLandingDenyOverlay {

    private AdAstraLandingDenyOverlay() {
    }

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof PlanetsScreen)) {
            return;
        }

        if (!AdAstraLandingDenyClient.isVisible()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        GuiGraphics graphics = event.getGuiGraphics();

        Component line1 = Component.literal(
                "Вы не можете приземлиться на данную планету."
        );

        Component line2 = Component.literal(
                "Вы не выполнили необходимый квест"
        );

        Component line3 = Component.literal(
                "на открытие варпа в ветке «Поехали»."
        );

        int x = 280;
        int y = 385;
        int color = 0xFFFF5555;

        graphics.drawCenteredString(
                minecraft.font,
                line1,
                x,
                y,
                color
        );

        graphics.drawCenteredString(
                minecraft.font,
                line2,
                x,
                y + 12,
                color
        );

        graphics.drawCenteredString(
                minecraft.font,
                line3,
                x,
                y + 24,
                color
        );
    }
}