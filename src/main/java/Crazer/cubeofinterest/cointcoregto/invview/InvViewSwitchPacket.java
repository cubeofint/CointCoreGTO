package Crazer.cubeofinterest.cointcoregto.invview;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record InvViewSwitchPacket(UUID targetId, String targetName, InvViewMode mode, int page) {
    public static void encode(InvViewSwitchPacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.targetId);
        buffer.writeUtf(packet.targetName, 64);
        buffer.writeVarInt(packet.mode.ordinal());
        buffer.writeVarInt(Math.max(0, packet.page));
    }

    public static InvViewSwitchPacket decode(FriendlyByteBuf buffer) {
        return new InvViewSwitchPacket(
                buffer.readUUID(),
                buffer.readUtf(64),
                InvViewMode.byId(buffer.readVarInt()),
                buffer.readVarInt()
        );
    }

    public static void handle(InvViewSwitchPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) {
            context.enqueueWork(() -> InvViewService.open(sender, packet.targetId, packet.targetName, packet.mode, packet.page));
        }
        context.setPacketHandled(true);
    }
}
