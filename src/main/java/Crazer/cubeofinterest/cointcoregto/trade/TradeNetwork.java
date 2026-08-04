package Crazer.cubeofinterest.cointcoregto.trade;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class TradeNetwork {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CointCoreGTO.MODID, "player_trade"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );
    private static boolean registered;

    private TradeNetwork() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        int id = 0;
        CHANNEL.messageBuilder(TradeSetCurrencyPacket.class, id++)
                .encoder(TradeSetCurrencyPacket::encode)
                .decoder(TradeSetCurrencyPacket::decode)
                .consumerMainThread(TradeSetCurrencyPacket::handle)
                .add();
        CHANNEL.messageBuilder(TradeReadyPacket.class, id++)
                .encoder(TradeReadyPacket::encode)
                .decoder(TradeReadyPacket::decode)
                .consumerMainThread(TradeReadyPacket::handle)
                .add();
        CHANNEL.messageBuilder(TradeCancelPacket.class, id)
                .encoder(TradeCancelPacket::encode)
                .decoder(TradeCancelPacket::decode)
                .consumerMainThread(TradeCancelPacket::handle)
                .add();
        registered = true;
    }
}
