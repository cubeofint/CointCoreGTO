package Crazer.cubeofinterest.cointcoregto.trade;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record TradeCancelPacket(UUID tradeId) {
    public static void encode(TradeCancelPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.tradeId);
    }

    public static TradeCancelPacket decode(FriendlyByteBuf buffer) {
        return new TradeCancelPacket(buffer.readUUID());
    }

    public static void handle(TradeCancelPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.containerMenu instanceof TradeMenu menu)
                    || !menu.tradeId().equals(packet.tradeId)) {
                return;
            }
            TradeService.OperationResult result = TradeService.cancel(player);
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    (result.success() ? "§e" : "§c") + result.message()
            ));
        });
        context.setPacketHandled(true);
    }
}
