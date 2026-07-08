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
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class CointRadioBlock extends BaseEntityBlock {
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public CointRadioBlock() {
        super(BlockBehaviour.Properties.of()
                .strength(2.0f, 6.0f)
                .sound(SoundType.METAL)
        );

        this.registerDefaultState(this.stateDefinition.any()
                .setValue(ACTIVE, false)
        );
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        return List.of(new ItemStack(CointRadioBlocks.COINT_RADIO_ITEM.get()));
    }

    @Override
    public ItemStack getCloneItemStack(
            net.minecraft.world.level.BlockGetter level,
            BlockPos pos,
            BlockState state
    ) {
        return new ItemStack(CointRadioBlocks.COINT_RADIO_ITEM.get());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CointRadioBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type
    ) {
        if (level.isClientSide) {
            return null;
        }

        return createTickerHelper(
                type,
                CointRadioBlocks.COINT_RADIO_BLOCK_ENTITY.get(),
                CointRadioBlockEntity::serverTick
        );
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

        if (!CointRadioProtection.canUseRadio(serverPlayer, pos, hand)) {
            CointRadioProtection.denyWithMessage(serverPlayer);
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
                    Component.literal("§c[CointMusic] Ошибка радио."),
                    true
            );
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            CointRadioNetwork.sendOpenScreen(
                    serverPlayer,
                    pos,
                    CointRadioConfig.getStationScreenEntries(),
                    radio.getStationId(),
                    radio.isActive(),
                    radio.getRadius(),
                    radio.getCustomUrl()
            );

            return InteractionResult.SUCCESS;
        }

        if (!radio.isActive()) {
            radio.setActive(true);

            player.displayClientMessage(
                    Component.literal("§a[CointMusic] Радио включено. Радиус: §f" + CointRadioConfig.getRadius()),
                    true
            );
        } else {
            String nextStation = radio.nextStation();

            player.displayClientMessage(
                    Component.literal("§e[CointMusic] Следующая станция: §f" + CointRadioConfig.getStationName(nextStation)),
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

            if (blockEntity instanceof CointRadioBlockEntity radio) {
                radio.stopAllListeners();
            }
        }

        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public net.minecraft.network.chat.MutableComponent getName() {
        return net.minecraft.network.chat.Component.literal("Радио");
    }
}