package Crazer.cubeofinterest.cointcoregto.invview;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class InvViewNetwork {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CointCoreGTO.MODID, "inv_view"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );
    private static boolean registered;

    private InvViewNetwork() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        CHANNEL.messageBuilder(InvViewSwitchPacket.class, 0)
                .encoder(InvViewSwitchPacket::encode)
                .decoder(InvViewSwitchPacket::decode)
                .consumerMainThread(InvViewSwitchPacket::handle)
                .add();
        registered = true;
    }
}
