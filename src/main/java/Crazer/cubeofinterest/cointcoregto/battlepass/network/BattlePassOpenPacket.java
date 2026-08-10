package Crazer.cubeofinterest.cointcoregto.battlepass.network;

import Crazer.cubeofinterest.cointcoregto.battlepass.BattlePassService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class BattlePassOpenPacket {
    public static void encode(BattlePassOpenPacket packet, FriendlyByteBuf buffer) {
    }

    public static BattlePassOpenPacket decode(FriendlyByteBuf buffer) {
        return new BattlePassOpenPacket();
    }

    public static void handle(BattlePassOpenPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null) {
                BattlePassService.sendState(player, "");
            }
        });
        context.setPacketHandled(true);
    }
}
