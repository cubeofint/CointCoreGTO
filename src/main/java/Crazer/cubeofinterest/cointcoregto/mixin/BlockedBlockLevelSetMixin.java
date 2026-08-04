package Crazer.cubeofinterest.cointcoregto.mixin;

import Crazer.cubeofinterest.cointcoregto.BlockedBlockPlacementGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class BlockedBlockLevelSetMixin {
    @Group(name = "cointcoregto_level_set_block_3", min = 1, max = 1)
    @Inject(
            method = "m_7731_(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void cointcoregto$denyForbiddenSetBlock3Srg(
            BlockPos pos,
            BlockState requestedState,
            int flags,
            CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        cointcoregto$denyForbiddenSetBlock(pos, requestedState, callbackInfo);
    }

    @Group(name = "cointcoregto_level_set_block_3", min = 1, max = 1)
    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void cointcoregto$denyForbiddenSetBlock3Mojmap(
            BlockPos pos,
            BlockState requestedState,
            int flags,
            CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        cointcoregto$denyForbiddenSetBlock(pos, requestedState, callbackInfo);
    }

    @Group(name = "cointcoregto_level_set_block_4", min = 1, max = 1)
    @Inject(
            method = "m_6933_(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void cointcoregto$denyForbiddenSetBlock4Srg(
            BlockPos pos,
            BlockState requestedState,
            int flags,
            int recursionLeft,
            CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        cointcoregto$denyForbiddenSetBlock(pos, requestedState, callbackInfo);
    }

    @Group(name = "cointcoregto_level_set_block_4", min = 1, max = 1)
    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void cointcoregto$denyForbiddenSetBlock4Mojmap(
            BlockPos pos,
            BlockState requestedState,
            int flags,
            int recursionLeft,
            CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        cointcoregto$denyForbiddenSetBlock(pos, requestedState, callbackInfo);
    }

    private void cointcoregto$denyForbiddenSetBlock(
            BlockPos pos,
            BlockState requestedState,
            CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        Level level = (Level) (Object) this;

        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockState currentState = serverLevel.getBlockState(pos);

        if (BlockedBlockPlacementGuard.shouldDenyPlacement(
                serverLevel,
                pos,
                currentState,
                requestedState
        )) {
            callbackInfo.setReturnValue(false);
        }
    }
}
