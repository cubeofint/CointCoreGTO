package Crazer.cubeofinterest.cointcoregto.compat.radio;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

public final class CointRadioNetwork {
    private static final String PROTOCOL = "1";

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CointCoreGTO.MODID, "radio"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private static int packetId = 0;

    private CointRadioNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(
                nextId(),
                StopRadioPacket.class,
                StopRadioPacket::encode,
                StopRadioPacket::decode,
                StopRadioPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                nextId(),
                ToggleRadioPacket.class,
                ToggleRadioPacket::encode,
                ToggleRadioPacket::decode,
                ToggleRadioPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                nextId(),
                SwitchRadioPacket.class,
                SwitchRadioPacket::encode,
                SwitchRadioPacket::decode,
                SwitchRadioPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
    }

    public static void sendSwitch(ServerPlayer player, String url, String stationId) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SwitchRadioPacket(url, stationId));
    }

    public static void sendToggle(ServerPlayer player, String url, String stationId) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ToggleRadioPacket(url, stationId));
    }
    public static void sendStop(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new StopRadioPacket());
    }

    private static int nextId() {
        return packetId++;
    }

    public record ToggleRadioPacket(String url, String stationId) {
        public static void encode(ToggleRadioPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUtf(packet.url, 32767);
            buffer.writeUtf(packet.stationId, 256);
        }

        public static ToggleRadioPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new ToggleRadioPacket(
                    buffer.readUtf(32767),
                    buffer.readUtf(256)
            );
        }

        public static void handle(ToggleRadioPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> CointRadioClientPacketHandler.toggle(packet.url, packet.stationId)
            ));

            context.setPacketHandled(true);
        }
    }

    public record SwitchRadioPacket(String url, String stationId) {
        public static void encode(SwitchRadioPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUtf(packet.url, 32767);
            buffer.writeUtf(packet.stationId, 256);
        }

        public static SwitchRadioPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new SwitchRadioPacket(
                    buffer.readUtf(32767),
                    buffer.readUtf(256)
            );
        }

        public static void handle(SwitchRadioPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> CointRadioClientPacketHandler.switchStation(packet.url, packet.stationId)
            ));

            context.setPacketHandled(true);
        }
    }

    public record StopRadioPacket() {
        public static void encode(StopRadioPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
        }

        public static StopRadioPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new StopRadioPacket();
        }

        public static void handle(StopRadioPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> CointRadioClientPacketHandler::stop
            ));

            context.setPacketHandled(true);
        }
    }
}