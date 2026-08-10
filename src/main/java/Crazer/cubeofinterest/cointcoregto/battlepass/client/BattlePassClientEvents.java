package Crazer.cubeofinterest.cointcoregto.battlepass.client;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
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

public final class BattlePassClientEvents {
    public static final KeyMapping OPEN_BATTLE_PASS = new KeyMapping(
            "Открыть Battle Pass",
            KeyConflictContext.UNIVERSAL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            "CointCoreGTO"
    );

    private BattlePassClientEvents() {
    }

    public static void requestOpen() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && minecraft.getConnection() != null) {
            BattlePassNetwork.CHANNEL.sendToServer(new BattlePassOpenPacket());
        }
    }

    @Mod.EventBusSubscriber(
            modid = CointCoreGTO.MODID,
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
            modid = CointCoreGTO.MODID,
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
            while (OPEN_BATTLE_PASS.consumeClick()) {
                if (minecraft.screen == null || minecraft.screen instanceof InventoryScreen) {
                    requestOpen();
                }
            }
        }

        @SubscribeEvent
        public static void onScreenInit(ScreenEvent.Init.Post event) {
            if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)
                    || (!(screen instanceof InventoryScreen) && !(screen instanceof CreativeModeInventoryScreen))) {
                return;
            }
            int buttonWidth = 42;
            int buttonX = screen.getGuiLeft() + (screen.getXSize() - buttonWidth) / 2;
            int buttonY = Math.max(4, screen.getGuiTop() - 22);
            Button button = Button.builder(
                            Component.literal("БП"),
                            ignored -> requestOpen()
                    )
                    .bounds(buttonX, buttonY, buttonWidth, 20)
                    .build();
            event.addListener(button);
        }
    }
}
