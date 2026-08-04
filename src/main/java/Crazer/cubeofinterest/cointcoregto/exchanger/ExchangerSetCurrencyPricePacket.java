package Crazer.cubeofinterest.cointcoregto.exchanger;

import Crazer.cubeofinterest.cointcoregto.currency.CurrencyConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class ExchangerSetCurrencyPricePacket {
    private final BlockPos pos;
    private final long amount;

    public ExchangerSetCurrencyPricePacket(BlockPos pos, long amount) {
        this.pos = pos;
        this.amount = amount;
    }

    public static void encode(ExchangerSetCurrencyPricePacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.pos);
        buffer.writeVarLong(packet.amount);
    }

    public static ExchangerSetCurrencyPricePacket decode(FriendlyByteBuf buffer) {
        return new ExchangerSetCurrencyPricePacket(buffer.readBlockPos(), buffer.readVarLong());
    }

    public static void handle(
            ExchangerSetCurrencyPricePacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null
                    || packet.amount < 0L
                    || packet.amount > CurrencyConfig.descriptor().maximumBalance()) {
                return;
            }
            if (!(player.containerMenu instanceof ExchangerMenu menu)) {
                return;
            }
            if (!menu.isEditMode() || !menu.getBlockPos().equals(packet.pos)) {
                return;
            }
            if (player.distanceToSqr(
                    packet.pos.getX() + 0.5D,
                    packet.pos.getY() + 0.5D,
                    packet.pos.getZ() + 0.5D
            ) > 64.0D) {
                return;
            }
            BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);
            if (blockEntity instanceof ExchangerBlockEntity exchanger && exchanger.canEdit(player)) {
                exchanger.setCurrencyPricePerDeal(packet.amount);
            }
        });
        context.setPacketHandled(true);
    }
}
