package Crazer.cubeofinterest.cointcoregto;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
        modid = CointCoreGTO.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE
)
public final class CointCoreGTOChatRecipeClick {

    private CointCoreGTOChatRecipeClick() {
    }

    @SubscribeEvent
    public static void onMouseClicked(
            ScreenEvent.MouseButtonPressed.Pre event
    ) {
        if (event.getButton() != 0) {
            return;
        }

        if (!(event.getScreen() instanceof ChatScreen)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.gui == null
                || minecraft.gui.getChat() == null) {
            return;
        }
        Style style = minecraft.gui
                .getChat()
                .getClickedComponentStyleAt(
                        event.getMouseX(),
                        event.getMouseY()
                );

        if (style == null) {
            return;
        }

        HoverEvent hoverEvent = style.getHoverEvent();

        if (hoverEvent == null) {
            return;
        }

        HoverEvent.ItemStackInfo itemInfo =
                hoverEvent.getValue(HoverEvent.Action.SHOW_ITEM);

        if (itemInfo == null) {
            return;
        }

        ItemStack stack;

        try {
            stack = itemInfo.getItemStack();
        } catch (Throwable ignored) {
            return;
        }

        if (stack == null || stack.isEmpty()) {
            return;
        }
        EmiApi.displayRecipes(
                EmiStack.of(stack)
        );
        event.setCanceled(true);
    }
}