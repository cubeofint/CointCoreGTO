package Crazer.cubeofinterest.cointcoregto.compat.radio;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
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
                PlayRadioPacket.class,
                PlayRadioPacket::encode,
                PlayRadioPacket::decode,
                PlayRadioPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

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

        CHANNEL.registerMessage(
                nextId(),
                OpenRadioScreenPacket.class,
                OpenRadioScreenPacket::encode,
                OpenRadioScreenPacket::decode,
                OpenRadioScreenPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );

        CHANNEL.registerMessage(
                nextId(),
                SelectStationPacket.class,
                SelectStationPacket::encode,
                SelectStationPacket::decode,
                SelectStationPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
    }

    public static void sendPlay(ServerPlayer player, String url, String stationId, String radioId) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PlayRadioPacket(url, stationId, radioId));
    }

    public static void sendStop(ServerPlayer player, String radioId) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new StopRadioPacket(radioId));
    }

    public static void sendToggle(ServerPlayer player, String url, String stationId, String radioId) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ToggleRadioPacket(url, stationId, radioId));
    }

    public static void sendSwitch(ServerPlayer player, String url, String stationId, String radioId) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SwitchRadioPacket(url, stationId, radioId));
    }

    public static void sendOpenScreen(ServerPlayer player, BlockPos pos, List<String> stations, String currentStation) {
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new OpenRadioScreenPacket(pos, stations, currentStation)
        );
    }

    public static void sendSelectStationToServer(BlockPos pos, String stationId) {
        CHANNEL.sendToServer(new SelectStationPacket(pos, stationId));
    }

    private static int nextId() {
        return packetId++;
    }

    public record PlayRadioPacket(String url, String stationId, String radioId) {
        public static void encode(PlayRadioPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUtf(packet.url, 32767);
            buffer.writeUtf(packet.stationId, 256);
            buffer.writeUtf(packet.radioId, 256);
        }

        public static PlayRadioPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new PlayRadioPacket(
                    buffer.readUtf(32767),
                    buffer.readUtf(256),
                    buffer.readUtf(256)
            );
        }

        public static void handle(PlayRadioPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> CointRadioClientPacketHandler.play(packet.url, packet.stationId, packet.radioId)
            ));

            context.setPacketHandled(true);
        }
    }

    public record StopRadioPacket(String radioId) {
        public static void encode(StopRadioPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUtf(packet.radioId, 256);
        }

        public static StopRadioPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new StopRadioPacket(buffer.readUtf(256));
        }

        public static void handle(StopRadioPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> CointRadioClientPacketHandler.stop(packet.radioId)
            ));

            context.setPacketHandled(true);
        }
    }

    public record ToggleRadioPacket(String url, String stationId, String radioId) {
        public static void encode(ToggleRadioPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUtf(packet.url, 32767);
            buffer.writeUtf(packet.stationId, 256);
            buffer.writeUtf(packet.radioId, 256);
        }

        public static ToggleRadioPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new ToggleRadioPacket(
                    buffer.readUtf(32767),
                    buffer.readUtf(256),
                    buffer.readUtf(256)
            );
        }

        public static void handle(ToggleRadioPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> CointRadioClientPacketHandler.toggle(packet.url, packet.stationId, packet.radioId)
            ));

            context.setPacketHandled(true);
        }
    }

    public record SwitchRadioPacket(String url, String stationId, String radioId) {
        public static void encode(SwitchRadioPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUtf(packet.url, 32767);
            buffer.writeUtf(packet.stationId, 256);
            buffer.writeUtf(packet.radioId, 256);
        }

        public static SwitchRadioPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new SwitchRadioPacket(
                    buffer.readUtf(32767),
                    buffer.readUtf(256),
                    buffer.readUtf(256)
            );
        }

        public static void handle(SwitchRadioPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> CointRadioClientPacketHandler.switchStation(packet.url, packet.stationId, packet.radioId)
            ));

            context.setPacketHandled(true);
        }
    }

    public record OpenRadioScreenPacket(BlockPos pos, List<String> stations, String currentStation) {
        public static void encode(OpenRadioScreenPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.pos);
            buffer.writeVarInt(packet.stations.size());

            for (String station : packet.stations) {
                buffer.writeUtf(station, 256);
            }

            buffer.writeUtf(packet.currentStation, 256);
        }

        public static OpenRadioScreenPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            BlockPos pos = buffer.readBlockPos();

            int size = Math.min(buffer.readVarInt(), 128);
            List<String> stations = new ArrayList<>();

            for (int i = 0; i < size; i++) {
                stations.add(buffer.readUtf(256));
            }

            String currentStation = buffer.readUtf(256);

            return new OpenRadioScreenPacket(pos, stations, currentStation);
        }

        public static void handle(OpenRadioScreenPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> CointRadioClientPacketHandler.openScreen(
                            packet.pos,
                            packet.stations,
                            packet.currentStation
                    )
            ));

            context.setPacketHandled(true);
        }
    }

    public record SelectStationPacket(BlockPos pos, String stationId) {
        public static void encode(SelectStationPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.pos);
            buffer.writeUtf(packet.stationId, 256);
        }

        public static SelectStationPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new SelectStationPacket(
                    buffer.readBlockPos(),
                    buffer.readUtf(256)
            );
        }

        public static void handle(SelectStationPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();

                if (player == null) {
                    return;
                }

                if (!player.level().isLoaded(packet.pos)) {
                    return;
                }

                if (player.distanceToSqr(
                        packet.pos.getX() + 0.5,
                        packet.pos.getY() + 0.5,
                        packet.pos.getZ() + 0.5
                ) > 64.0D) {
                    return;
                }

                BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);

                if (!(blockEntity instanceof CointRadioBlockEntity radio)) {
                    return;
                }

                radio.setStationId(packet.stationId);

                player.displayClientMessage(
                        Component.literal("§e[CointMusic] Станция выбрана: §f" + radio.getStationId()),
                        true
                );
            });

            context.setPacketHandled(true);
        }
    }
}