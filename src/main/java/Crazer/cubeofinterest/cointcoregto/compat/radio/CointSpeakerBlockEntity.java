package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class CointSpeakerBlockEntity extends BlockEntity {
    private BlockPos radioPos;

    public CointSpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(CointRadioBlocks.COINT_SPEAKER_BLOCK_ENTITY.get(), pos, state);
    }

    public boolean hasRadio() {
        return radioPos != null;
    }

    public BlockPos getRadioPos() {
        return radioPos;
    }

    public String getRadioPosText() {
        if (radioPos == null) {
            return "-";
        }

        return radioPos.getX() + " " + radioPos.getY() + " " + radioPos.getZ();
    }

    public void setRadioPos(BlockPos radioPos) {
        this.radioPos = radioPos;
        setChanged();
        syncToClient();
    }

    public void clearRadioPos() {
        this.radioPos = null;
        setChanged();
        syncToClient();
    }

    public void unlinkFromRadio() {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        if (radioPos == null) {
            return;
        }

        BlockEntity blockEntity = serverLevel.getBlockEntity(radioPos);

        if (blockEntity instanceof CointRadioBlockEntity radio) {
            radio.removeSpeaker(worldPosition);
        }

        clearRadioPos();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);

        if (radioPos != null) {
            tag.putInt("RadioX", radioPos.getX());
            tag.putInt("RadioY", radioPos.getY());
            tag.putInt("RadioZ", radioPos.getZ());
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);

        if (tag.contains("RadioX") && tag.contains("RadioY") && tag.contains("RadioZ")) {
            radioPos = new BlockPos(
                    tag.getInt("RadioX"),
                    tag.getInt("RadioY"),
                    tag.getInt("RadioZ")
            );
        } else {
            radioPos = null;
        }
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();

        if (radioPos != null) {
            tag.putInt("RadioX", radioPos.getX());
            tag.putInt("RadioY", radioPos.getY());
            tag.putInt("RadioZ", radioPos.getZ());
        }

        return tag;
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket packet) {
        handleUpdateTag(packet.getTag());
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        super.handleUpdateTag(tag);

        if (tag.contains("RadioX") && tag.contains("RadioY") && tag.contains("RadioZ")) {
            radioPos = new BlockPos(
                    tag.getInt("RadioX"),
                    tag.getInt("RadioY"),
                    tag.getInt("RadioZ")
            );
        } else {
            radioPos = null;
        }
    }

    private void syncToClient() {
        if (level == null || level.isClientSide) {
            return;
        }

        BlockState state = level.getBlockState(worldPosition);

        level.sendBlockUpdated(
                worldPosition,
                state,
                state,
                3
        );
    }
}