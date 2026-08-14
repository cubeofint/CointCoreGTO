package Crazer.cubeofinterest.cointcoregto.battlepass.network;

import Crazer.cubeofinterest.cointcoregto.battlepass.client.BattlePassClientEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Lightweight S2C packet used only to show/hide Battle Pass client UI. */
public record BattlePassAvailabilityPacket(boolean enabled) {

    public static void encode(BattlePassAvailabilityPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.enabled());
    }

    public static BattlePassAvailabilityPacket decode(FriendlyByteBuf buffer) {
        return new BattlePassAvailabilityPacket(buffer.readBoolean());
    }

    public static void handle(
            BattlePassAvailabilityPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();

        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> BattlePassClientEvents.setServerBattlePassEnabled(packet.enabled())
        ));

        context.setPacketHandled(true);
    }
}
