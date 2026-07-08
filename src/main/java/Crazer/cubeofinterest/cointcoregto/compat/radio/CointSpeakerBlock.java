package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class CointSpeakerBlock extends BaseEntityBlock {
    public CointSpeakerBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(1.5f, 4.0f)
                .sound(SoundType.WOOD)
        );
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        return List.of(new ItemStack(CointRadioBlocks.COINT_SPEAKER_ITEM.get()));
    }

    @Override
    public ItemStack getCloneItemStack(
            net.minecraft.world.level.BlockGetter level,
            BlockPos pos,
            BlockState state
    ) {
        return new ItemStack(CointRadioBlocks.COINT_SPEAKER_ITEM.get());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CointSpeakerBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type
    ) {
        return null;
    }

    @Override
    public InteractionResult use(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hit
    ) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }

        ItemStack held = player.getItemInHand(hand);

        if (held.getItem() instanceof CointTunerItem) {
            return InteractionResult.PASS;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);

        if (!(blockEntity instanceof CointSpeakerBlockEntity speaker)) {
            return InteractionResult.SUCCESS;
        }

        if (speaker.hasRadio()) {
            serverPlayer.displayClientMessage(
                    Component.literal("§e[CointMusic] Динамик связан с радио: §f" + speaker.getRadioPosText()),
                    true
            );
        } else {
            serverPlayer.displayClientMessage(
                    Component.literal("§7[CointMusic] Динамик не связан. Используй тюнер."),
                    true
            );
        }

        return InteractionResult.SUCCESS;
    }

    @Override
    public void onRemove(
            BlockState state,
            Level level,
            BlockPos pos,
            BlockState newState,
            boolean isMoving
    ) {
        if (!state.is(newState.getBlock())) {
            BlockEntity blockEntity = level.getBlockEntity(pos);

            if (blockEntity instanceof CointSpeakerBlockEntity speaker) {
                speaker.unlinkFromRadio();
            }
        }

        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public net.minecraft.network.chat.MutableComponent getName() {
        return Component.literal("Динамик");
    }
}