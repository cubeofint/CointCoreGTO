package Crazer.cubeofinterest.cointcoregto.monitor;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public record ClusterMonitorRequestPacket(BlockPos pos) {
    public static void encode(ClusterMonitorRequestPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.pos());
    }

    public static ClusterMonitorRequestPacket decode(FriendlyByteBuf buffer) {
        return new ClusterMonitorRequestPacket(buffer.readBlockPos());
    }

    public static void handle(
            ClusterMonitorRequestPacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.containerMenu instanceof ClusterMonitorMenu menu)) {
                return;
            }
            if (!menu.getBlockPos().equals(packet.pos())) {
                return;
            }
            if (player.distanceToSqr(
                    packet.pos().getX() + 0.5D,
                    packet.pos().getY() + 0.5D,
                    packet.pos().getZ() + 0.5D
            ) > 64.0D) {
                return;
            }
            if (!menu.tryBeginSnapshotRequest()) {
                return;
            }

            ClusterMonitorService.readSnapshot().whenComplete((snapshot, error) -> {
                player.server.execute(() -> {
                    if (!(player.containerMenu instanceof ClusterMonitorMenu currentMenu)
                            || !currentMenu.getBlockPos().equals(packet.pos())) {
                        return;
                    }

                    ClusterMonitorSnapshot response = snapshot;
                    if (error != null || response == null) {
                        String message = error == null ? "Unknown monitor error" : error.getMessage();
                        response = ClusterMonitorSnapshot.error("", message);
                    }

                    ClusterMonitorSnapshot finalResponse = response;
                    ClusterMonitorNetwork.CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new ClusterMonitorSnapshotPacket(finalResponse)
                    );
                });
            });
        });
        context.setPacketHandled(true);
    }
}
