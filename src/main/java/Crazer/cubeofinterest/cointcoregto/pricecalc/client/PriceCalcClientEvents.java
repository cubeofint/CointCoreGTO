package Crazer.cubeofinterest.cointcoregto.pricecalc.client;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import Crazer.cubeofinterest.cointcoregto.pricecalc.PriceCalcStorage;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
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
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.player == null || minecraft.screen != null) {
                return;
            }
            while (CALCULATE_PRICE.consumeClick()) {
                PriceCalcClient.calculateHoveredOrHeld();
            }
        }

        @SubscribeEvent
        public static void onScreenKey(ScreenEvent.KeyPressed.Pre event) {
            if (event.getScreen() instanceof ChatScreen || event.getScreen() instanceof PriceRecipePickerScreen) {
                return;
            }
            if (!CALCULATE_PRICE.matches(event.getKeyCode(), event.getScanCode())) {
                return;
            }
            PriceCalcClient.calculateHoveredOrHeld();
            event.setCanceled(true);
        }

        @SubscribeEvent
        public static void onTooltip(ItemTooltipEvent event) {
            if (event.getItemStack().isEmpty()) {
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
}
