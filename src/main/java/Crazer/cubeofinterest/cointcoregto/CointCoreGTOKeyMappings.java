package Crazer.cubeofinterest.cointcoregto;

import Crazer.cubeofinterest.cointcoregto.compat.emi.CointManaOverlayDragMode;
import Crazer.cubeofinterest.cointcoregto.compat.emi.CointManaOverlayEditScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD
)
public final class CointCoreGTOKeyMappings {

    public static final String CATEGORY_CUBECHAT = "key.categories.cointcoregto";

    public static final KeyMapping SHARE_ITEM_TO_CHAT = new KeyMapping(
            "key.cointcoregto.share_item_to_chat",
            KeyConflictContext.GUI,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_T,
            CATEGORY_CUBECHAT
    );
    public static final KeyMapping OPEN_MANA_OVERLAY_SETTINGS = new KeyMapping(
            "Настроить GUI маны",
            KeyConflictContext.UNIVERSAL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,
            CATEGORY_CUBECHAT
    );

    private CointCoreGTOKeyMappings() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(SHARE_ITEM_TO_CHAT);
        event.register(OPEN_MANA_OVERLAY_SETTINGS);
    }

    public static boolean isShareItemKey(int keyCode, int scanCode) {
        try {
            return SHARE_ITEM_TO_CHAT.matches(keyCode, scanCode);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isManaOverlaySettingsKey(int keyCode, int scanCode) {
        try {
            return OPEN_MANA_OVERLAY_SETTINGS.matches(keyCode, scanCode);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isKeybindSettingsScreen(Screen screen) {
        if (screen == null) {
            return false;
        }

        String className = screen.getClass().getName().toLowerCase(Locale.ROOT);

        return className.contains("control")
                || className.contains("keybind")
                || className.contains("keymapping")
                || className.contains("options");
    }

    private static void openOrToggleManaOverlayEditor() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null || minecraft.player == null) {
            return;
        }

        if (CointManaOverlayDragMode.tryToggleForCurrentScreen()) {
            return;
        }

        minecraft.setScreen(new CointManaOverlayEditScreen());
    }

    @Mod.EventBusSubscriber(
            modid = CointCoreGTO.MODID,
            value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.FORGE
    )
    public static final class ForgeEvents {

        private ForgeEvents() {
        }

        @SubscribeEvent
        public static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
            Screen screen = event.getScreen();

            if (screen == null) {
                return;
            }
            if (isKeybindSettingsScreen(screen)) {
                return;
            }

            if (!isManaOverlaySettingsKey(event.getKeyCode(), event.getScanCode())) {
                return;
            }

            openOrToggleManaOverlayEditor();
            event.setCanceled(true);
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }

            Minecraft minecraft = Minecraft.getInstance();

            if (minecraft == null || minecraft.player == null) {
                return;
            }

            while (OPEN_MANA_OVERLAY_SETTINGS.consumeClick()) {
                openOrToggleManaOverlayEditor();
            }
        }
    }
}
