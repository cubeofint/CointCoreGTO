package Crazer.cubeofinterest.cointcoregto.supply;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record SupplyBufferStatePacket(
        BlockPos pos,
        long[] itemAmounts,
        long[] itemTargets,
        long[] fluidAmounts,
        long[] fluidTargets
) {
    private static final int FILTER_COUNT = SupplyBufferBlockEntity.REQUEST_FILTER_COUNT;

    public static SupplyBufferStatePacket from(SupplyBufferBlockEntity buffer) {
        long[] itemAmounts = new long[FILTER_COUNT];
        long[] itemTargets = new long[FILTER_COUNT];
        long[] fluidAmounts = new long[FILTER_COUNT];
        long[] fluidTargets = new long[FILTER_COUNT];
        for (int index = 0; index < FILTER_COUNT; index++) {
            itemAmounts[index] = buffer.getConfiguredSupplyItemCount(index);
            itemTargets[index] = buffer.getItemTargetAmount(index);
            fluidAmounts[index] = buffer.getConfiguredFluidAmount(index);
            fluidTargets[index] = buffer.getFluidTargetAmount(index);
        }
        return new SupplyBufferStatePacket(
                buffer.getBlockPos(),
                itemAmounts,
                itemTargets,
                fluidAmounts,
                fluidTargets
        );
    }

    public static void encode(SupplyBufferStatePacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.pos());
        writeArray(buffer, packet.itemAmounts());
        writeArray(buffer, packet.itemTargets());
        writeArray(buffer, packet.fluidAmounts());
        writeArray(buffer, packet.fluidTargets());
    }

    public static SupplyBufferStatePacket decode(FriendlyByteBuf buffer) {
        return new SupplyBufferStatePacket(
                buffer.readBlockPos(),
                readArray(buffer),
                readArray(buffer),
                readArray(buffer),
                readArray(buffer)
        );
    }

    public static void handle(
            SupplyBufferStatePacket packet,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> SupplyBufferClient.handleState(packet)
        ));
        context.setPacketHandled(true);
    }

    private static void writeArray(FriendlyByteBuf buffer, long[] values) {
        for (int index = 0; index < FILTER_COUNT; index++) {
            long value = values != null && index < values.length ? values[index] : 0L;
            buffer.writeVarLong(Math.max(0L, Math.min(SupplyBufferBlockEntity.MAX_VIRTUAL_AMOUNT, value)));
        }
    }

    private static long[] readArray(FriendlyByteBuf buffer) {
        long[] values = new long[FILTER_COUNT];
        for (int index = 0; index < FILTER_COUNT; index++) {
            values[index] = Math.max(0L, Math.min(SupplyBufferBlockEntity.MAX_VIRTUAL_AMOUNT, buffer.readVarLong()));
        }
        return values;
    }
}
