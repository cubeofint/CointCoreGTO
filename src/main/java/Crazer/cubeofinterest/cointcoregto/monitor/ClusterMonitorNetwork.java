package Crazer.cubeofinterest.cointcoregto.monitor;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ClusterMonitorNetwork {
    private static final String PROTOCOL = "3";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CointCoreGTO.MODID, "cluster_monitor"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static boolean registered;

    private ClusterMonitorNetwork() {
    }

    public static void register() {
        if (registered) {
            return;
        }

        CHANNEL.messageBuilder(ClusterMonitorRequestPacket.class, 0)
                .encoder(ClusterMonitorRequestPacket::encode)
                .decoder(ClusterMonitorRequestPacket::decode)
                .consumerMainThread(ClusterMonitorRequestPacket::handle)
                .add();

        CHANNEL.messageBuilder(ClusterMonitorSnapshotPacket.class, 1)
                .encoder(ClusterMonitorSnapshotPacket::encode)
                .decoder(ClusterMonitorSnapshotPacket::decode)
                .consumerMainThread(ClusterMonitorSnapshotPacket::handle)
                .add();

        registered = true;
    }
}
