package Crazer.cubeofinterest.cointcoregto.supply;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class SupplyBufferBlock extends BaseEntityBlock {
    public SupplyBufferBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SupplyBufferBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> type
    ) {
        if (level.isClientSide || type != SupplyBufferRegistry.SUPPLY_BUFFER_BLOCK_ENTITY.get()) {
            return null;
        }

        return (tickLevel, pos, tickState, blockEntity) -> {
            if (blockEntity instanceof SupplyBufferBlockEntity supplyBuffer
                    && tickLevel instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                SupplyBufferBlockEntity.serverTick(serverLevel, pos, tickState, supplyBuffer);
            }
        };
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public List<ItemStack> getDrops(BlockState state, LootParams.Builder builder) {
        return List.of(new ItemStack(SupplyBufferRegistry.SUPPLY_BUFFER_ITEM.get()));
    }

    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        return new ItemStack(SupplyBufferRegistry.SUPPLY_BUFFER_ITEM.get());
    }

    @Override
    public void setPlacedBy(
            Level level,
            BlockPos pos,
            BlockState state,
            @Nullable LivingEntity placer,
            ItemStack stack
    ) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof SupplyBufferBlockEntity supplyBuffer) {
                supplyBuffer.setOwner(player);
            }
        }
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
        ItemStack held = player.getItemInHand(hand);
        BlockEntity blockEntity = level.getBlockEntity(pos);

        if (held.getItem() instanceof SupplyLinkCardItem) {
            if (!level.isClientSide
                    && player instanceof ServerPlayer serverPlayer
                    && blockEntity instanceof SupplyBufferBlockEntity supplyBuffer) {
                supplyBuffer.handleLinkCard(serverPlayer, held, player.isShiftKeyDown());
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        if (blockEntity instanceof SupplyBufferBlockEntity supplyBuffer
                && blockEntity instanceof MenuProvider menuProvider
                && player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(
                    serverPlayer,
                    menuProvider,
                    buffer -> {
                        buffer.writeBlockPos(pos);
                        buffer.writeBoolean(supplyBuffer.canEdit(serverPlayer));
                        buffer.writeUtf(supplyBuffer.getLinkId(), 64);
                        buffer.writeUtf(supplyBuffer.getProviderNode(), 64);
                    }
            );
            return InteractionResult.CONSUME;
        }

        return InteractionResult.PASS;
    }

    @Override
    public boolean onDestroyedByPlayer(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            boolean willHarvest,
            FluidState fluid
    ) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!level.isClientSide
                && blockEntity instanceof SupplyBufferBlockEntity supplyBuffer) {
            if (supplyBuffer.hasPendingTransfers()) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§cSupply Buffer нельзя ломать, пока есть незавершённые межсерверные операции."
                        ),
                        true
                );
                return false;
            }
            if (supplyBuffer.hasStoredSupplyResources()) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(
                                "§cСначала забери предметы и жидкости из виртуального запаса Supply Buffer."
                        ),
                        true
                );
                return false;
            }
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    @Override
    public void onRemove(
            BlockState oldState,
            Level level,
            BlockPos pos,
            BlockState newState,
            boolean moving
    ) {
        if (!oldState.is(newState.getBlock())) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof SupplyBufferBlockEntity supplyBuffer) {
                supplyBuffer.dropRealContents();
            }
        }
        super.onRemove(oldState, level, pos, newState, moving);
    }
    @Override
    public net.minecraft.network.chat.MutableComponent getName() {
        return net.minecraft.network.chat.Component.literal("Межсерверный буфер снабжения");
    }

}
