package Crazer.cubeofinterest.cointcoregto.mixin;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTOQuestShare;
import Crazer.cubeofinterest.cointcoregto.ItemShareChannel;
import dev.ftb.mods.ftblibrary.ui.BaseScreen;
import dev.ftb.mods.ftblibrary.ui.ContextMenuItem;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftbquests.client.gui.ContextMenuBuilder;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestButton;
import dev.ftb.mods.ftbquests.client.gui.quests.QuestScreen;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = QuestButton.class, remap = false)
public abstract class QuestButtonMixin {
    @Shadow
    @Final
    private Quest quest;

    @Inject(method = "onClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private void cointcoregto$openQuestShareMenu(MouseButton mouseButton, CallbackInfo callbackInfo) {
        if (mouseButton == null || !mouseButton.isRight() || quest == null) {
            return;
        }

        if (quest.getQuestFile().canEdit()) {
            return;
        }

        QuestButton self = (QuestButton) (Object) this;
        BaseScreen screen = self.getGui();
        if (screen == null) {
            return;
        }

        screen.openContextMenu(cointcoregto$createQuestShareItems(false));
        callbackInfo.cancel();
    }

    @ModifyArg(
            method = "onClicked",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ftb/mods/ftblibrary/ui/BaseScreen;openContextMenu(Ljava/util/List;)Ldev/ftb/mods/ftblibrary/ui/ContextMenu;",
                    ordinal = 0
            ),
            index = 0,
            remap = false
    )
    private List<ContextMenuItem> cointcoregto$addQuestShareItemsToSelectionMenu(List<ContextMenuItem> originalItems) {
        List<ContextMenuItem> result = cointcoregto$createQuestShareItems(true);
        if (originalItems != null && !originalItems.isEmpty()) {
            result.addAll(originalItems);
        }
        return result;
    }

    @Redirect(
            method = "onClicked",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ftb/mods/ftbquests/client/gui/ContextMenuBuilder;create(Ldev/ftb/mods/ftbquests/quest/QuestObjectBase;Ldev/ftb/mods/ftbquests/client/gui/quests/QuestScreen;)Ldev/ftb/mods/ftbquests/client/gui/ContextMenuBuilder;",
                    ordinal = 0
            ),
            remap = false
    )
    private ContextMenuBuilder cointcoregto$addQuestShareItemsToEditMenu(QuestObjectBase object, QuestScreen screen) {
        return ContextMenuBuilder.create(object, screen)
                .insertAtTop(cointcoregto$createQuestShareItems(true));
    }

    @Unique
    private List<ContextMenuItem> cointcoregto$createQuestShareItems(boolean addSeparator) {
        List<ContextMenuItem> items = new ArrayList<>(addSeparator ? 4 : 3);

        items.add(new ContextMenuItem(
                Component.translatable("cointcoregto.quest_share.local"),
                quest.getIcon(),
                ignored -> CointCoreGTOQuestShare.sendToServer(quest, ItemShareChannel.LOCAL)
        ));

        items.add(new ContextMenuItem(
                Component.translatable("cointcoregto.quest_share.global"),
                quest.getIcon(),
                ignored -> CointCoreGTOQuestShare.sendToServer(quest, ItemShareChannel.GLOBAL)
        ));

        items.add(new ContextMenuItem(
                Component.translatable("cointcoregto.quest_share.private"),
                quest.getIcon(),
                ignored -> CointCoreGTOQuestShare.sendToServer(quest, ItemShareChannel.PRIVATE)
        ));

        if (addSeparator) {
            items.add(ContextMenuItem.SEPARATOR);
        }

        return items;
    }
}
