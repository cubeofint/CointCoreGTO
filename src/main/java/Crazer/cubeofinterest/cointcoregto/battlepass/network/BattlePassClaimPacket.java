package Crazer.cubeofinterest.cointcoregto.battlepass.network;

import Crazer.cubeofinterest.cointcoregto.battlepass.BattlePassService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class BattlePassClaimPacket {
    public static void encode(BattlePassClaimPacket packet, FriendlyByteBuf buffer) {
    }

    public static BattlePassClaimPacket decode(FriendlyByteBuf buffer) {
        return new BattlePassClaimPacket();
    }

    public static void handle(BattlePassClaimPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                BattlePassService.claimCurrent(player);
            }
        });
        context.setPacketHandled(true);
    }
}
