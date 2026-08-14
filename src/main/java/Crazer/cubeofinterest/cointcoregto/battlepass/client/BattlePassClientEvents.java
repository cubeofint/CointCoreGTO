package Crazer.cubeofinterest.cointcoregto.battlepass.client;

import Crazer.cubeofinterest.cointcoregto.battlepass.network.BattlePassNetwork;
import Crazer.cubeofinterest.cointcoregto.battlepass.network.BattlePassOpenPacket;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Client-side entry points for Battle Pass.
 *
 * The server is authoritative for whether Battle Pass is enabled. The client
 * receives that value via BattlePassAvailabilityPacket and does not create the
 * inventory button or send open requests while disabled.
 */
public final class BattlePassClientEvents {

    public static final KeyMapping OPEN_BATTLE_PASS = new KeyMapping(
            "Открыть Battle Pass",
            KeyConflictContext.UNIVERSAL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            "CointCoreGTO"
    );

    private static volatile boolean serverBattlePassEnabled = false;
    private static Button currentInventoryButton;

    private BattlePassClientEvents() {
    }

    public static boolean isServerBattlePassEnabled() {
        return serverBattlePassEnabled;
    }

    /** Called from the S2C availability packet. */
    public static void setServerBattlePassEnabled(boolean enabled) {
        serverBattlePassEnabled = enabled;

        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            Button button = currentInventoryButton;
            if (button != null) {
                button.visible = enabled;
                button.active = enabled;
            }

            // If the server disables BP while its screen is already open,
            // close it immediately instead of leaving a stale usable screen.
            if (!enabled && minecraft.screen instanceof BattlePassScreen) {
                minecraft.setScreen(null);
            }
        });
    }

    public static void requestOpen() {
        if (!serverBattlePassEnabled) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            return;
        }

        BattlePassNetwork.CHANNEL.sendToServer(new BattlePassOpenPacket());
    }

    @Mod.EventBusSubscriber(
            modid = "cointcoregto",
            value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.MOD
    )
    public static final class ModEvents {
        private ModEvents() {
        }

        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_BATTLE_PASS);
        }
    }

    @Mod.EventBusSubscriber(
            modid = "cointcoregto",
            value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.FORGE
    )
    public static final class ForgeEvents {
        private ForgeEvents() {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }

            Minecraft minecraft = Minecraft.getInstance();

            // No connection means the value from the previous server/session
            // must not leak into the next one.
            if (minecraft.player == null || minecraft.getConnection() == null) {
                if (serverBattlePassEnabled) {
                    serverBattlePassEnabled = false;
                }
                currentInventoryButton = null;
                return;
            }

            while (OPEN_BATTLE_PASS.consumeClick()) {
                if (!serverBattlePassEnabled) {
                    continue;
                }

                if (minecraft.screen == null || minecraft.screen instanceof InventoryScreen) {
                    requestOpen();
                }
            }
        }

        @SubscribeEvent
        public static void onScreenInit(ScreenEvent.Init.Post event) {
            if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
                return;
            }

            if (!(screen instanceof InventoryScreen)
                    && !(screen instanceof CreativeModeInventoryScreen)) {
                return;
            }

            currentInventoryButton = null;

            // This is the important part: disabled on the SERVER means no BP
            // button is created on the client at all.
            if (!serverBattlePassEnabled) {
                return;
            }

            int width = 42;
            int x = screen.getGuiLeft() + (screen.getXSize() - width) / 2;
            int y = Math.max(4, screen.getGuiTop() - 22);

            Button button = Button.builder(
                            Component.literal("БП"),
                            ignored -> requestOpen()
                    )
                    .bounds(x, y, width, 20)
                    .build();

            currentInventoryButton = button;
            event.addListener(button);
        }
    }
}
