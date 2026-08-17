package Crazer.cubeofinterest.cointcoregto.pricecalc.client;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import Crazer.cubeofinterest.cointcoregto.pricecalc.PriceCalcNetwork;
import Crazer.cubeofinterest.cointcoregto.pricecalc.PriceCalcStorage;
import com.mojang.blaze3d.platform.InputConstants;
import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.stack.EmiStackInteraction;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

public final class PriceCalcClientEvents {
    public static final KeyMapping CALCULATE_PRICE = new KeyMapping(
            "Рассчитать стоимость",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P,
            "key.categories.cointcoregto"
    );

    private static final int ACCESS_REFRESH_TICKS = 100;
    private static boolean accessAllowed;
    private static boolean systemEnabled;
    private static boolean tooltipEnabled;
    private static int accessRefreshTicks;

    private PriceCalcClientEvents() {
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
            PriceCalcStorage.ensureLoaded();
            event.register(CALCULATE_PRICE);
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
        public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
            accessAllowed = false;
            systemEnabled = false;
            tooltipEnabled = false;
            accessRefreshTicks = 20;
            drainCalculateClicks();
        }

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            accessAllowed = false;
            systemEnabled = false;
            tooltipEnabled = false;
            accessRefreshTicks = 0;
            drainCalculateClicks();
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
                drainCalculateClicks();
                return;
            }

            if (accessRefreshTicks > 0) {
                accessRefreshTicks--;
            } else {
                PriceCalcNetwork.requestAccessState();
                accessRefreshTicks = ACCESS_REFRESH_TICKS;
            }

            if (!accessAllowed || !systemEnabled || minecraft.screen != null) {
                drainCalculateClicks();
                return;
            }

            while (CALCULATE_PRICE.consumeClick()) {
                PriceCalcNetwork.requestCalculation();
            }
        }

        @SubscribeEvent
        public static void onScreenKey(ScreenEvent.KeyPressed.Pre event) {
            if (!accessAllowed || !systemEnabled) {
                return;
            }
            if (event.getScreen() instanceof ChatScreen || event.getScreen() instanceof PriceRecipePickerScreen) {
                return;
            }
            if (event.getScreen().getFocused() instanceof EditBox) {
                return;
            }
            if (!CALCULATE_PRICE.matches(event.getKeyCode(), event.getScanCode())) {
                return;
            }
            if (!hasHoveredEmiStack()) {
                return;
            }
            PriceCalcNetwork.requestCalculation();
            event.setCanceled(true);
        }

        @SubscribeEvent
        public static void onTooltip(ItemTooltipEvent event) {
            if (!accessAllowed || !systemEnabled || !tooltipEnabled || event.getItemStack().isEmpty()) {
                return;
            }
            PriceCalcStorage.ensureLoaded();
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(event.getItemStack().getItem());
            if (itemId == null) {
                return;
            }
            PriceCalcStorage.ComputedPrice computed = PriceCalcStorage.getComputedPrice(itemId.toString());
            if (computed != null && Double.isFinite(computed.price) && computed.price >= 0.0D) {
                event.getToolTip().add(Component.literal("§6Расчётная стоимость: §f" + PriceCalcClient.formatPrice(computed.price)));
                return;
            }
            Double basePrice = PriceCalcStorage.getItemUnitPrice(itemId);
            if (basePrice != null) {
                event.getToolTip().add(Component.literal("§6Базовая стоимость: §f" + PriceCalcClient.formatPrice(basePrice)));
                return;
            }
            event.getToolTip().add(Component.literal("§8[P] — рассчитать стоимость"));
        }
    }

    static void setAccessAllowed(boolean allowed) {
        accessAllowed = allowed;
        if (!allowed) {
            systemEnabled = false;
            tooltipEnabled = false;
            PriceCalcClient.cancelPendingChoice();
            drainCalculateClicks();
        }
    }

    static boolean isSystemEnabled() {
        return accessAllowed && systemEnabled;
    }

    static boolean setSystemEnabled(boolean enabled) {
        if (!accessAllowed) {
            systemEnabled = false;
            tooltipEnabled = false;
            drainCalculateClicks();
            return false;
        }
        systemEnabled = enabled;
        if (!enabled) {
            tooltipEnabled = false;
            PriceCalcClient.cancelPendingChoice();
        }
        drainCalculateClicks();
        return systemEnabled;
    }

    static boolean toggleSystem() {
        return setSystemEnabled(!systemEnabled);
    }

    static boolean toggleTooltip() {
        if (!isSystemEnabled()) {
            tooltipEnabled = false;
            return false;
        }
        tooltipEnabled = !tooltipEnabled;
        return tooltipEnabled;
    }

    private static boolean hasHoveredEmiStack() {
        try {
            EmiStackInteraction interaction = EmiApi.getHoveredStack(false);
            return interaction != null && !interaction.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void drainCalculateClicks() {
        while (CALCULATE_PRICE.consumeClick()) {
        }
    }
}
