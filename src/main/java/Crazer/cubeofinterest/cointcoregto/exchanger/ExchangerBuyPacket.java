package Crazer.cubeofinterest.cointcoregto.exchanger;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class ExchangerBuyPacket {
    private final BlockPos pos;
    private final int amount;

    public ExchangerBuyPacket(BlockPos pos, int amount) {
        this.pos = pos;
        this.amount = amount;
    }

    public static void encode(ExchangerBuyPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.pos);
        buffer.writeVarInt(packet.amount);
    }

    public static ExchangerBuyPacket decode(FriendlyByteBuf buffer) {
        return new ExchangerBuyPacket(
                buffer.readBlockPos(),
                buffer.readVarInt()
        );
    }

    public static void handle(ExchangerBuyPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();

        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();

            if (player == null) {
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

            if (blockEntity instanceof ExchangerBlockEntity exchanger) {
                exchanger.buy(player, packet.amount);
            }
        });

        context.setPacketHandled(true);
    }
}