package Crazer.cubeofinterest.cointcoregto.supply;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record SupplyBufferFilterPacket(
        BlockPos pos,
        SupplyBufferDatabase.ResourceType resourceType,
        int filterIndex,
        String keyPayload
) {
    private static final int MAX_PAYLOAD_LENGTH = 16_384;

    public static void encode(SupplyBufferFilterPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.pos());
        buffer.writeEnum(packet.resourceType());
        buffer.writeVarInt(packet.filterIndex());
        buffer.writeUtf(packet.keyPayload() == null ? "" : packet.keyPayload(), MAX_PAYLOAD_LENGTH);
    }

    public static SupplyBufferFilterPacket decode(FriendlyByteBuf buffer) {
        return new SupplyBufferFilterPacket(
                buffer.readBlockPos(),
                buffer.readEnum(SupplyBufferDatabase.ResourceType.class),
                buffer.readVarInt(),
                buffer.readUtf(MAX_PAYLOAD_LENGTH)
        );
    }

    public static void handle(
            SupplyBufferFilterPacket packet,
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
                    || packet.filterIndex() >= SupplyBufferBlockEntity.REQUEST_FILTER_COUNT) {
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
                    && supplyBuffer.getRole() != SupplyBufferRole.UNLINKED) {
                supplyBuffer.setFilterPayload(
                        packet.resourceType(),
                        packet.filterIndex(),
                        packet.keyPayload(),
                        player
                );
            }
        });
        context.setPacketHandled(true);
    }
}
