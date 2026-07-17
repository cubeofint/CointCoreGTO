package Crazer.cubeofinterest.cointcoregto.mixin;

import net.minecraftforge.common.ForgeConfigSpec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(
        targets = "vazkii.botania.forge.ForgeBotaniaConfig$Client",
        remap = false
)
public abstract class BotaniaClientConfigMixin {
    @Shadow
    @Final
    private ForgeConfigSpec.BooleanValue splashesEnabled;

    @Inject(
            method = "splashesEnabled",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void cointcoregto$safeSplashesEnabled(CallbackInfoReturnable<Boolean> cir) {
        try {
            cir.setReturnValue(this.splashesEnabled.get());
        } catch (IllegalStateException ignored) {
            cir.setReturnValue(false);
        }
    }
}