package Crazer.cubeofinterest.cointcoregto.supply;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record SupplyBufferSettingsPacket(BlockPos pos, int action) {
    public static void encode(SupplyBufferSettingsPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.pos());
        buffer.writeVarInt(packet.action());
    }

    public static SupplyBufferSettingsPacket decode(FriendlyByteBuf buffer) {
        return new SupplyBufferSettingsPacket(buffer.readBlockPos(), buffer.readVarInt());
    }

    public static void handle(
            SupplyBufferSettingsPacket packet,
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
            if (packet.action() < 0 || packet.action() > 3) {
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
                supplyBuffer.cycleSetting(packet.action());
            }
        });
        context.setPacketHandled(true);
    }
}
