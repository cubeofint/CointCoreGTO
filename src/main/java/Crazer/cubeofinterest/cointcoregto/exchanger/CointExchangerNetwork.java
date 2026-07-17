package Crazer.cubeofinterest.cointcoregto.exchanger;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class CointExchangerNetwork {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CointCoreGTO.MODID, "exchanger"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static boolean registered = false;

    private CointExchangerNetwork() {
    }

    public static void register() {
        if (registered) {
            return;
        }

        int id = 0;

        CHANNEL.messageBuilder(ExchangerBuyPacket.class, id++)
                .encoder(ExchangerBuyPacket::encode)
                .decoder(ExchangerBuyPacket::decode)
                .consumerMainThread(ExchangerBuyPacket::handle)
                .add();

        registered = true;
    }
}