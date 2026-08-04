package Crazer.cubeofinterest.cointcoregto.mixin;

import Crazer.cubeofinterest.cointcoregto.BlockedBlockPlacementGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class BlockedBlockPlacementMixin {
    @Group(name = "cointcoregto_chunk_set_block", min = 1, max = 1)
    @Inject(
            method = "m_6978_(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void cointcoregto$denyForbiddenBlockStateSrg(
            BlockPos pos,
            BlockState requestedState,
            boolean moved,
            CallbackInfoReturnable<BlockState> callbackInfo
    ) {
        cointcoregto$denyForbiddenBlockState(pos, requestedState, callbackInfo);
    }

    @Group(name = "cointcoregto_chunk_set_block", min = 1, max = 1)
    @Inject(
            method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void cointcoregto$denyForbiddenBlockStateMojmap(
            BlockPos pos,
            BlockState requestedState,
            boolean moved,
            CallbackInfoReturnable<BlockState> callbackInfo
    ) {
        cointcoregto$denyForbiddenBlockState(pos, requestedState, callbackInfo);
    }

    private void cointcoregto$denyForbiddenBlockState(
            BlockPos pos,
            BlockState requestedState,
            CallbackInfoReturnable<BlockState> callbackInfo
    ) {
        LevelChunk chunk = (LevelChunk) (Object) this;

        if (!(chunk.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockState currentState = chunk.getBlockState(pos);

        if (BlockedBlockPlacementGuard.shouldDenyPlacement(
                serverLevel,
                pos,
                currentState,
                requestedState
        )) {
            callbackInfo.setReturnValue(null);
        }
    }
}
