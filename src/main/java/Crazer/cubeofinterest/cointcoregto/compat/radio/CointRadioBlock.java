package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class CointRadioBlock extends BaseEntityBlock {
    public CointRadioBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(2.0f, 6.0f)
                .sound(SoundType.METAL)
        );
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CointRadioBlockEntity(pos, state);
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

        if (!CointRadioConfig.isEnabled()) {
            player.displayClientMessage(
                    Component.literal("§c[CointMusic] Радио отключено в конфиге."),
                    true
            );
            return InteractionResult.SUCCESS;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);

        if (!(blockEntity instanceof CointRadioBlockEntity radio)) {
            player.displayClientMessage(
                    Component.literal("§c[CointMusic] Ошибка радиоблока."),
                    true
            );
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            String stationId = radio.nextStation();
            String url = radio.getStationUrl();

            CointRadioNetwork.sendSwitch(serverPlayer, url, stationId);

            return InteractionResult.SUCCESS;
        }

        String stationId = radio.getStationId();
        String url = radio.getStationUrl();

        CointRadioNetwork.sendToggle(serverPlayer, url, stationId);

        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return false;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return 0;
    }
}