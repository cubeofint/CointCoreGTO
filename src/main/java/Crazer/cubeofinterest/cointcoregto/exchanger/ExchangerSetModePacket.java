package Crazer.cubeofinterest.cointcoregto.exchanger;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class ExchangerSetModePacket {
    private final BlockPos pos;
    private final boolean editMode;

    public ExchangerSetModePacket(BlockPos pos, boolean editMode) {
        this.pos = pos;
        this.editMode = editMode;
    }

    public static void encode(ExchangerSetModePacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.pos);
        buffer.writeBoolean(packet.editMode);
    }

    public static ExchangerSetModePacket decode(FriendlyByteBuf buffer) {
        return new ExchangerSetModePacket(
                buffer.readBlockPos(),
                buffer.readBoolean()
        );
    }

    public static void handle(
            ExchangerSetModePacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }

            if (!(player.containerMenu instanceof ExchangerMenu menu)) {
                return;
            }

            if (!menu.canEdit() || !menu.getBlockPos().equals(packet.pos)) {
                return;
            }

            if (player.distanceToSqr(
                    packet.pos.getX() + 0.5D,
                    packet.pos.getY() + 0.5D,
                    packet.pos.getZ() + 0.5D
            ) > 64.0D) {
                return;
            }

            if (!menu.getExchanger().canEdit(player)) {
                return;
            }

            menu.setEditMode(packet.editMode);
        });
        context.setPacketHandled(true);
    }
}
