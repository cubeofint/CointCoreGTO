package Crazer.cubeofinterest.cubechat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

public final class CubeChatItemShare {
    private static final String NETWORK_PROTOCOL_VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CubeChat.MODID, "item_share"),
            () -> NETWORK_PROTOCOL_VERSION,
            NETWORK_PROTOCOL_VERSION::equals,
            NETWORK_PROTOCOL_VERSION::equals
    );

    private static boolean registered = false;

    private CubeChatItemShare() {
    }

    public static void registerNetwork() {
        if (registered) {
            return;
        }

        CHANNEL.messageBuilder(ShareItemPacket.class, 0)
                .encoder(ShareItemPacket::encode)
                .decoder(ShareItemPacket::decode)
                .consumerMainThread(ShareItemPacket::handle)
                .add();

        CHANNEL.messageBuilder(ItemIconHintPacket.class, 1)
                .encoder(ItemIconHintPacket::encode)
                .decoder(ItemIconHintPacket::decode)
                .consumerMainThread(ItemIconHintPacket::handle)
                .add();

        registered = true;
    }

    public static void sendToServer(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        CHANNEL.sendToServer(new ShareItemPacket(stack.copy()));
    }

    public static void sendIconHintToPlayer(ServerPlayer player, ItemStack stack, String prefixText, String itemText) {
        if (player == null || stack == null || stack.isEmpty()) {
            return;
        }

        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new ItemIconHintPacket(stack.copy(), prefixText, itemText)
        );
    }

    private static final class ShareItemPacket {
        private final ItemStack stack;

        private ShareItemPacket(ItemStack stack) {
            this.stack = stack == null ? ItemStack.EMPTY : stack.copy();
        }

        private static void encode(ShareItemPacket packet, FriendlyByteBuf buffer) {
            buffer.writeItem(packet.stack);
        }

        private static ShareItemPacket decode(FriendlyByteBuf buffer) {
            return new ShareItemPacket(buffer.readItem());
        }

        private static void handle(ShareItemPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || packet.stack.isEmpty()) {
                    return;
                }

                CubeChat.shareItemInCurrentChat(player, packet.stack.copy());
            });
            context.setPacketHandled(true);
        }
    }

    private static final class ItemIconHintPacket {
        private final ItemStack stack;
        private final String prefixText;
        private final String itemText;

        private ItemIconHintPacket(ItemStack stack, String prefixText, String itemText) {
            this.stack = stack == null ? ItemStack.EMPTY : stack.copy();
            this.prefixText = prefixText == null ? "" : prefixText;
            this.itemText = itemText == null ? "" : itemText;
        }

        private static void encode(ItemIconHintPacket packet, FriendlyByteBuf buffer) {
            buffer.writeItem(packet.stack);
            buffer.writeUtf(packet.prefixText, 32767);
            buffer.writeUtf(packet.itemText, 1024);
        }

        private static ItemIconHintPacket decode(FriendlyByteBuf buffer) {
            return new ItemIconHintPacket(buffer.readItem(), buffer.readUtf(32767), buffer.readUtf(1024));
        }

        private static void handle(ItemIconHintPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                if (!packet.stack.isEmpty()) {
                    CubeChatItemIconOverlay.queueIcon(packet.stack.copy(), packet.prefixText, packet.itemText);
                }
            });
            context.setPacketHandled(true);
        }
    }
}
