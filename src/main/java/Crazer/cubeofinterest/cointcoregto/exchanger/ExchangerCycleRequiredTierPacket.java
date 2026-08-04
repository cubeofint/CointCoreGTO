package Crazer.cubeofinterest.cointcoregto.exchanger;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class ExchangerCycleRequiredTierPacket {
    private final BlockPos pos;
    private final int direction;

    public ExchangerCycleRequiredTierPacket(BlockPos pos, int direction) {
        this.pos = pos;
        this.direction = direction < 0 ? -1 : 1;
    }

    public static void encode(ExchangerCycleRequiredTierPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.pos);
        buffer.writeByte(packet.direction);
    }

    public static ExchangerCycleRequiredTierPacket decode(FriendlyByteBuf buffer) {
        return new ExchangerCycleRequiredTierPacket(buffer.readBlockPos(), buffer.readByte());
    }

    public static void handle(
            ExchangerCycleRequiredTierPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.containerMenu instanceof ExchangerMenu menu)) {
                return;
            }
            if (!menu.isEditMode() || !menu.getBlockPos().equals(packet.pos)) {
                return;
            }
            if (player.distanceToSqr(
                    packet.pos.getX() + 0.5D,
                    packet.pos.getY() + 0.5D,
                    packet.pos.getZ() + 0.5D
            ) > 64.0D) {
                return;
            }
            BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);
            if (blockEntity instanceof ExchangerBlockEntity exchanger
                    && exchanger.canEditRequiredTier(player)) {
                exchanger.cycleRequiredTier(packet.direction);
            }
        });
        context.setPacketHandled(true);
    }
}
