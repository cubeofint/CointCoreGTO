package Crazer.cubeofinterest.cointcoregto.trade;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record TradeReadyPacket(UUID tradeId, boolean ready) {
    public static void encode(TradeReadyPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.tradeId);
        buffer.writeBoolean(packet.ready);
    }

    public static TradeReadyPacket decode(FriendlyByteBuf buffer) {
        return new TradeReadyPacket(buffer.readUUID(), buffer.readBoolean());
    }

    public static void handle(TradeReadyPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.containerMenu instanceof TradeMenu menu)
                    || !menu.tradeId().equals(packet.tradeId)) {
                return;
            }
            TradeService.OperationResult result = TradeService.setReady(player, packet.tradeId, packet.ready);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    (result.success() ? "§a" : "§c") + result.message()
            ));
        });
        context.setPacketHandled(true);
    }
}
