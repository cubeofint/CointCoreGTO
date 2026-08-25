package Crazer.cubeofinterest.cointcoregto.supply;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record SupplyBufferTargetPacket(
        BlockPos pos,
        SupplyBufferDatabase.ResourceType resourceType,
        int filterIndex,
        long targetAmount
) {
    public static void encode(SupplyBufferTargetPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.pos());
        buffer.writeEnum(packet.resourceType());
        buffer.writeVarInt(packet.filterIndex());
        buffer.writeVarLong(Math.max(1L, Math.min(SupplyBufferBlockEntity.MAX_VIRTUAL_AMOUNT, packet.targetAmount())));
    }

    public static SupplyBufferTargetPacket decode(FriendlyByteBuf buffer) {
        return new SupplyBufferTargetPacket(
                buffer.readBlockPos(),
                buffer.readEnum(SupplyBufferDatabase.ResourceType.class),
                buffer.readVarInt(),
                buffer.readVarLong()
        );
    }

    public static void handle(
            SupplyBufferTargetPacket packet,
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
            if (packet.filterIndex() < 0
                    || packet.filterIndex() >= SupplyBufferBlockEntity.REQUEST_FILTER_COUNT
                    || packet.targetAmount() <= 0L
                    || packet.targetAmount() > SupplyBufferBlockEntity.MAX_VIRTUAL_AMOUNT) {
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
                supplyBuffer.setTargetAmount(
                        packet.resourceType(),
                        packet.filterIndex(),
                        packet.targetAmount(),
                        player
                );
            }
        });
        context.setPacketHandled(true);
    }
}
