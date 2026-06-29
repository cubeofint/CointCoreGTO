package Crazer.cubeofinterest.cubechat.mixin;

import Crazer.cubeofinterest.cubechat.CubeChatClient;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(ChatComponent.class)
public abstract class CubeChatChatComponentMixin {
    @ModifyConstant(
            method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            constant = @Constant(intValue = 100)
    )
    private int cubechat$increaseChatHistoryLimit(int original) {
        return CubeChatClient.getClientChatLineLimit();
    }
}
