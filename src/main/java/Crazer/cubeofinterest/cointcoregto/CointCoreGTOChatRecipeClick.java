package Crazer.cubeofinterest.cointcoregto;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.stack.EmiStack;
import dev.ftb.mods.ftbquests.client.ClientQuestFile;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.ClickEvent;
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
    private static final String QUEST_LINK_PREFIX = "/cointcoregto_open_quest ";

    private CointCoreGTOChatRecipeClick() {
    }

    @SubscribeEvent
    public static void onMouseClicked(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 0) {
            return;
        }

        if (!(event.getScreen() instanceof ChatScreen)) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui == null || minecraft.gui.getChat() == null) {
            return;
        }

        Style style = minecraft.gui.getChat().getClickedComponentStyleAt(
                event.getMouseX(),
                event.getMouseY()
        );
        if (style == null) {
            return;
        }
        ClickEvent clickEvent = style.getClickEvent();
        if (clickEvent != null && clickEvent.getAction() == ClickEvent.Action.RUN_COMMAND) {
            String value = clickEvent.getValue();
            if (value != null && value.startsWith(QUEST_LINK_PREFIX)) {
                String questCode = value.substring(QUEST_LINK_PREFIX.length()).trim();
                long questId = QuestObjectBase.parseCodeString(questCode);

                if (questId != 0L && ClientQuestFile.exists()) {
                    ClientQuestFile.openBookToQuestObject(questId);
                }
                event.setCanceled(true);
                return;
            }
        }

        HoverEvent hoverEvent = style.getHoverEvent();
        if (hoverEvent == null) {
            return;
        }

        HoverEvent.ItemStackInfo itemInfo = hoverEvent.getValue(HoverEvent.Action.SHOW_ITEM);
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

        EmiApi.displayRecipes(EmiStack.of(stack));
        event.setCanceled(true);
    }
}
