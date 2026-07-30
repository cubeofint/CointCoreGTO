package Crazer.cubeofinterest.cointcoregto.mixin;

import Crazer.cubeofinterest.cointcoregto.ClusterTestModule;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(ServerLevel.class)
public abstract class ClusterDimensionTickGuardMixin {
    @Inject(
            method = "m_8793_(Ljava/util/function/BooleanSupplier;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void cointcoregto$skipNonOwnedDimensionTickSrg(
            BooleanSupplier hasTimeLeft,
            CallbackInfo callbackInfo
    ) {
        cointcoregto$applyDimensionTickGuard(callbackInfo);
    }
    @Inject(
            method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void cointcoregto$skipNonOwnedDimensionTickMojmap(
            BooleanSupplier hasTimeLeft,
            CallbackInfo callbackInfo
    ) {
        cointcoregto$applyDimensionTickGuard(callbackInfo);
    }

    private void cointcoregto$applyDimensionTickGuard(
            CallbackInfo callbackInfo
    ) {
        ServerLevel level = (ServerLevel) (Object) this;
        ClusterTestModule.markDimensionTickGuardActive();

        if (ClusterTestModule.shouldSkipDimensionTick(level)) {
            callbackInfo.cancel();
        }
    }
}
