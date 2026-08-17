package Crazer.cubeofinterest.cointcoregto.pricecalc;

import Crazer.cubeofinterest.cointcoregto.pricecalc.client.PriceCalcClientPacketHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record PriceCalcCommandPacket(Action action) {
    public enum Action {
        CALC,
        RELOAD,
        CLEAR,
        TOGGLE_TOOLTIP,
        TOGGLE_SYSTEM,
        ENABLE_SYSTEM,
        DISABLE_SYSTEM,
        OPEN_BLACKLIST,
        SHOW_STATUS
    }

    public static void encode(PriceCalcCommandPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.action());
    }

    public static PriceCalcCommandPacket decode(FriendlyByteBuf buffer) {
        return new PriceCalcCommandPacket(buffer.readEnum(Action.class));
    }

    public static void handle(
            PriceCalcCommandPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> PriceCalcClientPacketHandler.handle(packet.action())
        ));
        context.setPacketHandled(true);
    }
}
