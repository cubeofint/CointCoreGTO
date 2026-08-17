package Crazer.cubeofinterest.cointcoregto.pricecalc;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record PriceCalcRequestPacket(Action action) {
    public enum Action {
        CALC,
        QUERY_ACCESS
    }

    public static void encode(PriceCalcRequestPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.action());
    }

    public static PriceCalcRequestPacket decode(FriendlyByteBuf buffer) {
        return new PriceCalcRequestPacket(buffer.readEnum(Action.class));
    }

    public static void handle(
            PriceCalcRequestPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        context.enqueueWork(() -> {
            if (player == null) {
                return;
            }
            boolean allowed = PriceCalcCommands.hasAccess(player);
            PriceCalcNetwork.sendAccess(player, allowed);
            if (allowed && packet.action() == Action.CALC) {
                PriceCalcNetwork.sendTo(player, PriceCalcCommandPacket.Action.CALC);
            }
        });
        context.setPacketHandled(true);
    }
}
