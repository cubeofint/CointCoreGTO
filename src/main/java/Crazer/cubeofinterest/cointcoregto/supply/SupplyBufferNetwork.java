package Crazer.cubeofinterest.cointcoregto.supply;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class SupplyBufferNetwork {
    private static final String PROTOCOL = "4";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CointCoreGTO.MODID, "supply_buffer"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static boolean registered;

    private SupplyBufferNetwork() {
    }

    public static void register() {
        if (registered) {
            return;
        }

        CHANNEL.messageBuilder(SupplyBufferSettingsPacket.class, 0)
                .encoder(SupplyBufferSettingsPacket::encode)
                .decoder(SupplyBufferSettingsPacket::decode)
                .consumerMainThread(SupplyBufferSettingsPacket::handle)
                .add();

        CHANNEL.messageBuilder(SupplyBufferFilterPacket.class, 1)
                .encoder(SupplyBufferFilterPacket::encode)
                .decoder(SupplyBufferFilterPacket::decode)
                .consumerMainThread(SupplyBufferFilterPacket::handle)
                .add();

        CHANNEL.messageBuilder(SupplyBufferTargetPacket.class, 2)
                .encoder(SupplyBufferTargetPacket::encode)
                .decoder(SupplyBufferTargetPacket::decode)
                .consumerMainThread(SupplyBufferTargetPacket::handle)
                .add();

        CHANNEL.messageBuilder(SupplyBufferStatePacket.class, 3)
                .encoder(SupplyBufferStatePacket::encode)
                .decoder(SupplyBufferStatePacket::decode)
                .consumerMainThread(SupplyBufferStatePacket::handle)
                .add();

        CHANNEL.messageBuilder(SupplyBufferPriorityPacket.class, 4)
                .encoder(SupplyBufferPriorityPacket::encode)
                .decoder(SupplyBufferPriorityPacket::decode)
                .consumerMainThread(SupplyBufferPriorityPacket::handle)
                .add();

        registered = true;
    }
}
