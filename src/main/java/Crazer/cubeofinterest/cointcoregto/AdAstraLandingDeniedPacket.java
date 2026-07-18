package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class AdAstraLandingDeniedPacket {

    public static void encode(
            AdAstraLandingDeniedPacket packet,
            FriendlyByteBuf buffer
    ) {
    }

    public static AdAstraLandingDeniedPacket decode(
            FriendlyByteBuf buffer
    ) {
        return new AdAstraLandingDeniedPacket();
    }

    public static void handle(
            AdAstraLandingDeniedPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();

        context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(
                        Dist.CLIENT,
                        () -> AdAstraLandingDeniedClientHandler::handle
                )
        );

        context.setPacketHandled(true);
    }
}