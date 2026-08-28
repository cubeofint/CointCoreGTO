package Crazer.cubeofinterest.cointcoregto.supply;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record SupplyBufferPriorityPacket(BlockPos pos, int priority) {
    public static void encode(SupplyBufferPriorityPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.pos());
        buffer.writeVarInt(Math.max(0, Math.min(SupplyBufferBlockEntity.MAX_PRIORITY, packet.priority())));
    }

    public static SupplyBufferPriorityPacket decode(FriendlyByteBuf buffer) {
        return new SupplyBufferPriorityPacket(buffer.readBlockPos(), buffer.readVarInt());
    }

    public static void handle(
            SupplyBufferPriorityPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.containerMenu instanceof SupplyBufferMenu menu)) {
                return;
            }
            if (!menu.getBlockPos().equals(packet.pos()) || !menu.canEdit()) {
                return;
            }
            if (packet.priority() < 0 || packet.priority() > SupplyBufferBlockEntity.MAX_PRIORITY) {
                return;
            }
            if (player.distanceToSqr(
                    packet.pos().getX() + 0.5D,
                    packet.pos().getY() + 0.5D,
                    packet.pos().getZ() + 0.5D
            ) > 64.0D) {
                return;
            }

            BlockEntity blockEntity = player.level().getBlockEntity(packet.pos());
            if (blockEntity instanceof SupplyBufferBlockEntity supplyBuffer
                    && supplyBuffer.canEdit(player)
                    && supplyBuffer.getRole() == SupplyBufferRole.REMOTE) {
                supplyBuffer.setPriority(packet.priority(), player);
            }
        });
        context.setPacketHandled(true);
    }
}
