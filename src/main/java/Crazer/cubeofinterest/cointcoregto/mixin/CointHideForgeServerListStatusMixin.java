package Crazer.cubeofinterest.cointcoregto.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.client.ForgeHooksClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ForgeHooksClient.class, remap = false)
public abstract class CointHideForgeServerListStatusMixin {
    @Inject(
            method = "drawForgePingInfo",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void cointcoregto$hideForgePingInfo(
            JoinMultiplayerScreen screen,
            ServerData serverData,
            GuiGraphics guiGraphics,
            int x,
            int y,
            int width,
            int relativeMouseX,
            int relativeMouseY,
            CallbackInfo ci
    ) {
        ci.cancel();
    }
}