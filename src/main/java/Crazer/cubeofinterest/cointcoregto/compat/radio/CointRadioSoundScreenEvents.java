package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.SoundOptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CointRadioSoundScreenEvents {

    private static Button minusButton;
    private static Button radioButton;
    private static Button plusButton;

    private CointRadioSoundScreenEvents() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof SoundOptionsScreen screen)) {
            return;
        }

        int y = getY(screen);
        int centerX = screen.width / 2;

        int smallWidth = 70;
        int mainWidth = 180;
        int height = 20;

        minusButton = Button.builder(
                        Component.literal("-10"),
                        button -> decreaseVolume()
                )
                .bounds(centerX - mainWidth / 2 - smallWidth - 6, y, smallWidth, height)
                .build();

        radioButton = Button.builder(
                        getRadioText(),
                        button -> cycleVolume()
                )
                .bounds(centerX - mainWidth / 2, y, mainWidth, height)
                .build();

        plusButton = Button.builder(
                        Component.literal("+10"),
                        button -> increaseVolume()
                )
                .bounds(centerX + mainWidth / 2 + 6, y, smallWidth, height)
                .build();

        event.addListener(minusButton);
        event.addListener(radioButton);
        event.addListener(plusButton);
    }

    @SubscribeEvent
    public static void onMouseClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof SoundOptionsScreen screen)) {
            return;
        }

        if (event.getButton() != 0) {
            return;
        }

        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();

        int y = getY(screen);
        int centerX = screen.width / 2;

        int smallWidth = 70;
        int mainWidth = 180;
        int height = 20;

        int minusX = centerX - mainWidth / 2 - smallWidth - 6;
        int mainX = centerX - mainWidth / 2;
        int plusX = centerX + mainWidth / 2 + 6;

        if (isInside(mouseX, mouseY, minusX, y, smallWidth, height)) {
            decreaseVolume();
            event.setCanceled(true);
            return;
        }

        if (isInside(mouseX, mouseY, mainX, y, mainWidth, height)) {
            cycleVolume();
            event.setCanceled(true);
            return;
        }

        if (isInside(mouseX, mouseY, plusX, y, smallWidth, height)) {
            increaseVolume();
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(ScreenEvent.MouseScrolled.Pre event) {
        if (!(event.getScreen() instanceof SoundOptionsScreen screen)) {
            return;
        }

        double mouseX = event.getMouseX();
        double mouseY = event.getMouseY();

        int y = getY(screen);
        int centerX = screen.width / 2;

        int mainWidth = 180;
        int height = 20;

        int mainX = centerX - mainWidth / 2;

        if (!isInside(mouseX, mouseY, mainX, y, mainWidth, height)) {
            return;
        }

        double scrollDelta = event.getScrollDelta();

        if (scrollDelta > 0) {
            increaseVolumeByOne();
        } else if (scrollDelta < 0) {
            decreaseVolumeByOne();
        }

        event.setCanceled(true);
    }

    private static int getY(SoundOptionsScreen screen) {
        return screen.height - 56;
    }

    private static boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x
                && mouseX <= x + width
                && mouseY >= y
                && mouseY <= y + height;
    }

    private static void decreaseVolume() {
        int volume = CointRadioPlayer.getVolumePercent();
        volume -= 10;

        if (volume < 0) {
            volume = 0;
        }

        CointRadioPlayer.setVolumePercent(volume);
        updateRadioButtonText();
    }

    private static void increaseVolume() {
        int volume = CointRadioPlayer.getVolumePercent();
        volume += 10;

        if (volume > 100) {
            volume = 100;
        }

        CointRadioPlayer.setVolumePercent(volume);
        updateRadioButtonText();
    }

    private static void decreaseVolumeByOne() {
        int volume = CointRadioPlayer.getVolumePercent();
        volume -= 1;

        if (volume < 0) {
            volume = 0;
        }

        CointRadioPlayer.setVolumePercent(volume);
        updateRadioButtonText();
    }

    private static void increaseVolumeByOne() {
        int volume = CointRadioPlayer.getVolumePercent();
        volume += 1;

        if (volume > 100) {
            volume = 100;
        }

        CointRadioPlayer.setVolumePercent(volume);
        updateRadioButtonText();
    }

    private static void cycleVolume() {
        int volume = CointRadioPlayer.getVolumePercent();
        volume += 10;

        if (volume > 100) {
            volume = 0;
        }

        CointRadioPlayer.setVolumePercent(volume);
        updateRadioButtonText();
    }

    private static void updateRadioButtonText() {
        if (radioButton != null) {
            radioButton.setMessage(getRadioText());
        }
    }

    private static Component getRadioText() {
        return Component.literal("Радио: " + CointRadioPlayer.getVolumePercent() + "%");
    }
}