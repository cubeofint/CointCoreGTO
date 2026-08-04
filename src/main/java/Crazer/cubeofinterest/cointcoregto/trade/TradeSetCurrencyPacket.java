package Crazer.cubeofinterest.cointcoregto.trade;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record TradeSetCurrencyPacket(UUID tradeId, long amount) {
    public static void encode(TradeSetCurrencyPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.tradeId);
        buffer.writeLong(packet.amount);
    }

    public static TradeSetCurrencyPacket decode(FriendlyByteBuf buffer) {
        return new TradeSetCurrencyPacket(buffer.readUUID(), buffer.readLong());
    }

    public static void handle(TradeSetCurrencyPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.containerMenu instanceof TradeMenu menu)
                    || !menu.tradeId().equals(packet.tradeId)) {
                return;
            }
            TradeService.OperationResult result = TradeService.setCurrency(player, packet.tradeId, packet.amount);
            if (!result.success()) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c" + result.message()));
            }
        });
        context.setPacketHandled(true);
    }
}
