package Crazer.cubeofinterest.cointcoregto.pricecalc;

import Crazer.cubeofinterest.cointcoregto.pricecalc.client.PriceCalcClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record PriceCalcAccessPacket(boolean allowed) {
    public static void encode(PriceCalcAccessPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.allowed());
    }

    public static PriceCalcAccessPacket decode(FriendlyByteBuf buffer) {
        return new PriceCalcAccessPacket(buffer.readBoolean());
    }

    public static void handle(
            PriceCalcAccessPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> PriceCalcClientPacketHandler.setAccessAllowed(packet.allowed())
        ));
        context.setPacketHandled(true);
    }
}
