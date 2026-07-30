package Crazer.cubeofinterest.cointcoregto.mixin;

import Crazer.cubeofinterest.cointcoregto.ClusterTransferGuard;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ClusterTransferPacketGuardMixin {
    private ServerPlayer cointcoregto$getPlayer() {
        return ((ServerGamePacketListenerImpl) (Object) this).player;
    }

    @Inject(
            method = "handleContainerClick",
            at = @At("HEAD"),
            cancellable = true
    )
    private void cointcoregto$blockContainerClick(
            ServerboundContainerClickPacket packet,
            CallbackInfo callbackInfo
    ) {
        ServerPlayer player = cointcoregto$getPlayer();

        if (!ClusterTransferGuard.isLocked(player)) {
            return;
        }

        player.containerMenu.sendAllDataToRemote();
        callbackInfo.cancel();
    }

    @Inject(
            method = "handleContainerButtonClick",
            at = @At("HEAD"),
            cancellable = true
    )
    private void cointcoregto$blockContainerButton(
            ServerboundContainerButtonClickPacket packet,
            CallbackInfo callbackInfo
    ) {
        ServerPlayer player = cointcoregto$getPlayer();

        if (ClusterTransferGuard.isLocked(player)) {
            callbackInfo.cancel();
        }
    }

    @Inject(
            method = "handleSetCreativeModeSlot",
            at = @At("HEAD"),
            cancellable = true
    )
    private void cointcoregto$blockCreativeSlot(
            ServerboundSetCreativeModeSlotPacket packet,
            CallbackInfo callbackInfo
    ) {
        ServerPlayer player = cointcoregto$getPlayer();

        if (!ClusterTransferGuard.isLocked(player)) {
            return;
        }

        player.inventoryMenu.sendAllDataToRemote();
        callbackInfo.cancel();
    }
}
