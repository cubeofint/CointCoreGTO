package Crazer.cubeofinterest.cubechat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(
        modid = CubeChat.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD
)
public final class CubeChatKeyMappings {
    public static final String CATEGORY_CUBECHAT = "key.categories.cubechat";

    public static final KeyMapping SHARE_ITEM_TO_CHAT = new KeyMapping(
            "key.cubechat.share_item_to_chat",
            KeyConflictContext.GUI,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_T,
            CATEGORY_CUBECHAT
    );

    private CubeChatKeyMappings() {
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(SHARE_ITEM_TO_CHAT);
    }

    public static boolean isShareItemKey(int keyCode, int scanCode) {
        try {
            return SHARE_ITEM_TO_CHAT.matches(keyCode, scanCode);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
