package Crazer.cubeofinterest.cointcoregto.mixin;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTOClient;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public abstract class CointCoreGTOChatComponentMixin {
    @ModifyConstant(
            method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            constant = @Constant(intValue = 100),
            require = 0
    )
    private int cointcoregto$increaseChatHistoryLimitNamed(int original) {
        return CointCoreGTOClient.getClientChatLineLimit();
    }

    @ModifyConstant(
            method = "m_240964_(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            constant = @Constant(intValue = 100),
            remap = false,
            require = 0
    )
    private int cointcoregto$increaseChatHistoryLimitSrg(int original) {
        return CointCoreGTOClient.getClientChatLineLimit();
    }

    @Inject(
            method = "clearMessages(Z)V",
            at = @At("HEAD"),
            require = 0
    )
    private void cointcoregto$captureChatBeforeClear(
            boolean clearRecentChat,
            CallbackInfo callbackInfo
    ) {
        CointCoreGTOClient.beforeChatMessagesCleared((ChatComponent) (Object) this, clearRecentChat);
    }

    @Inject(
            method = "clearMessages(Z)V",
            at = @At("TAIL"),
            require = 0
    )
    private void cointcoregto$preserveChatBetweenServers(
            boolean clearRecentChat,
            CallbackInfo callbackInfo
    ) {
        CointCoreGTOClient.onChatMessagesCleared(clearRecentChat);
    }
}