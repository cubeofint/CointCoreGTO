package Crazer.cubeofinterest.cointcoregto;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

public final class CointCoreGTOItemShare {
    private static final String NETWORK_PROTOCOL_VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CointCoreGTO.MODID, "item_share"),
            () -> NETWORK_PROTOCOL_VERSION,
            NETWORK_PROTOCOL_VERSION::equals,
            NETWORK_PROTOCOL_VERSION::equals
    );

    private static boolean registered = false;

    private CointCoreGTOItemShare() {
    }

    public static void registerNetwork() {
        if (registered) {
            return;
        }

        CHANNEL.messageBuilder(ShareItemPacket.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ShareItemPacket::encode)
                .decoder(ShareItemPacket::decode)
                .consumerMainThread(ShareItemPacket::handle)
                .add();

        registered = true;
    }

    public static void sendToServer(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        String displayName = stack.getHoverName().getString();
        if (displayName == null || displayName.isBlank()) {
            displayName = stack.getItem().getDescription().getString();
        }

        CHANNEL.sendToServer(new ShareItemPacket(stack.copy(), displayName));
    }

    public static void sendIconHintToPlayer(ServerPlayer player, ItemStack stack, String prefixText, String itemText) {
    }

    private record ShareItemPacket(ItemStack stack, String displayName) {
        private static void encode(ShareItemPacket packet, FriendlyByteBuf buffer) {
            buffer.writeItem(packet.stack);
            buffer.writeUtf(packet.displayName == null ? "" : packet.displayName, 256);
        }

        private static ShareItemPacket decode(FriendlyByteBuf buffer) {
            ItemStack stack = buffer.readItem();
            String displayName = buffer.readUtf(256);
            return new ShareItemPacket(stack, displayName);
        }

        private static void handle(ShareItemPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || packet.stack == null || packet.stack.isEmpty()) {
                    return;
                }

                CointCoreGTO.shareItemInCurrentChat(player, packet.stack.copy(), packet.displayName);
            });

            context.setPacketHandled(true);
        }
    }
}