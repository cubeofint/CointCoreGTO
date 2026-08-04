package Crazer.cubeofinterest.cointcoregto.mixin;

import Crazer.cubeofinterest.cointcoregto.BlockedBlockPlacementGuard;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "appeng.spatial.SpatialStorageHelper", remap = false)
public abstract class Ae2SpatialStorageBlockGuardMixin {
    @Inject(
            method = "swapRegions",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void cointcoregto$denyForbiddenSpatialTransfer(
            ServerLevel sourceLevel,
            int sourceX,
            int sourceY,
            int sourceZ,
            ServerLevel targetLevel,
            int targetX,
            int targetY,
            int targetZ,
            int sizeX,
            int sizeY,
            int sizeZ,
            CallbackInfo callbackInfo
    ) {
        int width = sizeX + 1;
        int height = sizeY + 1;
        int depth = sizeZ + 1;

        BlockedBlockPlacementGuard.ForbiddenBlock sourceForbidden =
                BlockedBlockPlacementGuard.findForbiddenBlock(
                        sourceLevel,
                        sourceX,
                        sourceY,
                        sourceZ,
                        width,
                        height,
                        depth
                );

        if (sourceForbidden != null) {
            BlockedBlockPlacementGuard.logDeniedSpatialTransfer(sourceLevel, sourceForbidden);
            callbackInfo.cancel();
            return;
        }

        BlockedBlockPlacementGuard.ForbiddenBlock targetForbidden =
                BlockedBlockPlacementGuard.findForbiddenBlock(
                        targetLevel,
                        targetX,
                        targetY,
                        targetZ,
                        width,
                        height,
                        depth
                );

        if (targetForbidden != null) {
            BlockedBlockPlacementGuard.logDeniedSpatialTransfer(targetLevel, targetForbidden);
            callbackInfo.cancel();
        }
    }
}
