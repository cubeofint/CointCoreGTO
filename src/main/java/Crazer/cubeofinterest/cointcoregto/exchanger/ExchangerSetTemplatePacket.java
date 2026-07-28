package Crazer.cubeofinterest.cointcoregto.exchanger;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class ExchangerSetTemplatePacket {
    private final BlockPos pos;
    private final int slot;
    private final ItemStack stack;

    public ExchangerSetTemplatePacket(BlockPos pos, int slot, ItemStack stack) {
        this.pos = pos;
        this.slot = slot;
        this.stack = stack == null ? ItemStack.EMPTY : stack.copy();
    }

    public static void encode(ExchangerSetTemplatePacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.pos);
        buffer.writeVarInt(packet.slot);
        buffer.writeItem(packet.stack);
    }

    public static ExchangerSetTemplatePacket decode(FriendlyByteBuf buffer) {
        return new ExchangerSetTemplatePacket(
                buffer.readBlockPos(),
                buffer.readVarInt(),
                buffer.readItem()
        );
    }

    public static void handle(
            ExchangerSetTemplatePacket packet,
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

            if (!menu.isEditMode() || !menu.getBlockPos().equals(packet.pos)) {
                return;
            }

            if (packet.slot != ExchangerBlockEntity.SLOT_PRODUCT
                    && packet.slot != ExchangerBlockEntity.SLOT_PRICE) {
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
            if (!(blockEntity instanceof ExchangerBlockEntity exchanger)) {
                return;
            }

            if (!exchanger.canEdit(player)) {
                return;
            }

            exchanger.getItems().setStackInSlot(packet.slot, sanitizeTemplate(packet.stack));
        });
        context.setPacketHandled(true);
    }

    private static ItemStack sanitizeTemplate(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        ItemStack result = stack.copy();
        int maximum = Math.max(1, Math.min(64, result.getMaxStackSize()));
        result.setCount(Math.max(1, Math.min(result.getCount(), maximum)));
        return result;
    }
}
