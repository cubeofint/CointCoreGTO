package Crazer.cubeofinterest.cointcoregto.mixin;

import Crazer.cubeofinterest.cointcoregto.BlockedItemUseGuard;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "vazkii.botania.common.item.lens.ForceLens", remap = false)
public abstract class BotaniaForceLensBlockedItemMixin {
    private static final ResourceLocation COINTCOREGTO$ITEM_ID =
            new ResourceLocation("botania", "lens_piston");

    @Inject(
            method = "collideBurst",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void cointcoregto$denyBlockedForceLensEffect(
            CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        if (BlockedItemUseGuard.shouldDenyAutomatedEffect(
                COINTCOREGTO$ITEM_ID,
                "botania_force_lens_burst"
        )) {
            callbackInfo.setReturnValue(true);
        }
    }
}
