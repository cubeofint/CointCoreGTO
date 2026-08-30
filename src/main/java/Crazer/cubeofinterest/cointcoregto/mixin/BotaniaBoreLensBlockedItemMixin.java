package Crazer.cubeofinterest.cointcoregto.mixin;

import Crazer.cubeofinterest.cointcoregto.BlockedItemUseGuard;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "vazkii.botania.common.item.lens.BoreLens", remap = false)
public abstract class BotaniaBoreLensBlockedItemMixin {
    private static final ResourceLocation COINTCOREGTO$ITEM_ID =
            new ResourceLocation("botania", "lens_mine");

    @Inject(
            method = "collideBurst",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void cointcoregto$denyBlockedBoreLensEffect(
            CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        if (BlockedItemUseGuard.shouldDenyAutomatedEffect(
                COINTCOREGTO$ITEM_ID,
                "botania_bore_lens_burst"
        )) {
            // true tells Botania that the burst should terminate on this collision.
            callbackInfo.setReturnValue(true);
        }
    }
}
