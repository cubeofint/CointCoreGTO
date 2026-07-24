package Crazer.cubeofinterest.cointcoregto.mixin;

import Crazer.cubeofinterest.cointcoregto.ClusterTestModule;
import dev.ftb.mods.ftbessentials.util.TeleportPos;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(
        value = TeleportPos.class,
        remap = false
)
public abstract class FtbEssentialsTeleportPosMixin {
    @Inject(
            method = "teleport(Lnet/minecraft/server/level/ServerPlayer;)Ldev/ftb/mods/ftbessentials/util/TeleportPos$TeleportResult;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void cointcoregto$routeClusterTeleport(
            ServerPlayer player,
            CallbackInfoReturnable<TeleportPos.TeleportResult> cir
    ) {
        TeleportPos teleportPos =
                (TeleportPos) (Object) this;

        TeleportPos.TeleportResult blacklistResult =
                teleportPos.checkDimensionBlacklist(
                        player
                );

        if (!blacklistResult.isSuccess()) {
            cir.setReturnValue(
                    blacklistResult
            );

            return;
        }

        if (!ClusterTestModule.routeFtbEssentialsTeleport(
                player,
                teleportPos.getDimension(),
                teleportPos.getPos(),
                teleportPos.yRot,
                teleportPos.xRot
        )) {
            return;
        }

        cir.setReturnValue(
                TeleportPos.TeleportResult.SUCCESS
        );
    }
}
