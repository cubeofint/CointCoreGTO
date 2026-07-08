package Crazer.cubeofinterest.cointcoregto.compat.radio;

import Crazer.cubeofinterest.cointcoregto.CointCoreGTO;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
        CHANNEL.registerMessage(nextId(), PlayRadioPacket.class, PlayRadioPacket::encode, PlayRadioPacket::decode, PlayRadioPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), StopRadioPacket.class, StopRadioPacket::encode, StopRadioPacket::decode, StopRadioPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), ToggleRadioPacket.class, ToggleRadioPacket::encode, ToggleRadioPacket::decode, ToggleRadioPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), SwitchRadioPacket.class, SwitchRadioPacket::encode, SwitchRadioPacket::decode, SwitchRadioPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), OpenRadioScreenPacket.class, OpenRadioScreenPacket::encode, OpenRadioScreenPacket::decode, OpenRadioScreenPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), SelectStationPacket.class, SelectStationPacket::encode, SelectStationPacket::decode, SelectStationPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), ToggleActivePacket.class, ToggleActivePacket::encode, ToggleActivePacket::decode, ToggleActivePacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), SetCustomUrlPacket.class, SetCustomUrlPacket::encode, SetCustomUrlPacket::decode, SetCustomUrlPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), ClearCustomUrlPacket.class, ClearCustomUrlPacket::encode, ClearCustomUrlPacket::decode, ClearCustomUrlPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), NextStationPacket.class, NextStationPacket::encode, NextStationPacket::decode, NextStationPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), RandomStationPacket.class, RandomStationPacket::encode, RandomStationPacket::decode, RandomStationPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), SetRadiusPacket.class, SetRadiusPacket::encode, SetRadiusPacket::decode, SetRadiusPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(nextId(), YouTubeTransferStartPacket.class, YouTubeTransferStartPacket::encode, YouTubeTransferStartPacket::decode, YouTubeTransferStartPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), YouTubeTransferChunkPacket.class, YouTubeTransferChunkPacket::encode, YouTubeTransferChunkPacket::decode, YouTubeTransferChunkPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), YouTubeTransferFinishPacket.class, YouTubeTransferFinishPacket::encode, YouTubeTransferFinishPacket::decode, YouTubeTransferFinishPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(nextId(), YouTubeTransferFailPacket.class, YouTubeTransferFailPacket::encode, YouTubeTransferFailPacket::decode, YouTubeTransferFailPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
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

    public static void sendOpenScreen(
            ServerPlayer player,
            BlockPos pos,
            List<String> stations,
            String currentStation,
            boolean active,
            int radius,
            String customUrl
    ) {
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new OpenRadioScreenPacket(pos, stations, currentStation, active, radius, customUrl)
        );
    }

    public static void sendSetCustomUrlToServer(BlockPos pos, String customUrl) {
        CHANNEL.sendToServer(new SetCustomUrlPacket(pos, customUrl == null ? "" : customUrl));
    }

    public static void sendClearCustomUrlToServer(BlockPos pos) {
        CHANNEL.sendToServer(new ClearCustomUrlPacket(pos));
    }

    public static void sendSelectStationToServer(BlockPos pos, String stationId) {
        CHANNEL.sendToServer(new SelectStationPacket(pos, stationId));
    }

    public static void sendToggleActiveToServer(BlockPos pos) {
        CHANNEL.sendToServer(new ToggleActivePacket(pos));
    }

    public static void sendNextStationToServer(BlockPos pos) {
        CHANNEL.sendToServer(new NextStationPacket(pos));
    }

    public static void sendRandomStationToServer(BlockPos pos) {
        CHANNEL.sendToServer(new RandomStationPacket(pos));
    }

    public static void sendSetRadiusToServer(BlockPos pos, int radius) {
        CHANNEL.sendToServer(new SetRadiusPacket(pos, radius));
    }

    public static void sendYouTubeCachedMp3(
            ServerPlayer player,
            String youtubeUrl,
            String station,
            String radioId,
            long startOffsetMs
    ) {
        CompletableFuture.runAsync(() -> {
            UUID transferId = UUID.randomUUID();

            try {
                Path mp3 = CointYouTubeCache.getOrCreateCachedMp3(youtubeUrl);
                byte[] bytes = Files.readAllBytes(mp3);

                if (bytes.length <= 64 * 1024) {
                    throw new IllegalStateException("Cached MP3 is too small: " + bytes.length);
                }

                if (player.getServer() == null) {
                    throw new IllegalStateException("Player server is null");
                }

                System.out.println("[CointMusic] Sending YouTube MP3 to player: "
                        + player.getGameProfile().getName()
                        + ", bytes="
                        + bytes.length
                        + ", transfer="
                        + transferId
                );

                player.getServer().execute(() -> {
                    CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new YouTubeTransferStartPacket(
                                    transferId,
                                    station,
                                    radioId,
                                    bytes.length,
                                    Math.max(0L, startOffsetMs)
                            )
                    );

                    int chunkSize = 24 * 1024;

                    for (int offset = 0; offset < bytes.length; offset += chunkSize) {
                        int end = Math.min(bytes.length, offset + chunkSize);
                        byte[] chunk = Arrays.copyOfRange(bytes, offset, end);

                        CHANNEL.send(
                                PacketDistributor.PLAYER.with(() -> player),
                                new YouTubeTransferChunkPacket(transferId, chunk)
                        );
                    }

                    CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new YouTubeTransferFinishPacket(transferId)
                    );
                });
            } catch (Throwable throwable) {
                System.out.println("[CointMusic] Failed to send YouTube MP3: " + throwable.getMessage());
                throwable.printStackTrace();

                if (player.getServer() != null) {
                    player.getServer().execute(() -> CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new YouTubeTransferFailPacket(transferId, throwable.getMessage())
                    ));
                }
            }
        });
    }

    private static int nextId() {
        return packetId++;
    }

    private static boolean isAllowedCustomUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        String lowered = url.toLowerCase(java.util.Locale.ROOT);

        return url.length() <= 2048
                && (lowered.startsWith("http://") || lowered.startsWith("https://"));
    }

    private static boolean isValidRadioAccess(ServerPlayer player, BlockPos pos) {
        if (player == null) {
            return false;
        }

        if (!player.level().isLoaded(pos)) {
            return false;
        }

        if (player.distanceToSqr(
                pos.getX() + 0.5,
                pos.getY() + 0.5,
                pos.getZ() + 0.5
        ) > 64.0D) {
            return false;
        }

        if (!CointRadioProtection.canUseRadio(player, pos, InteractionHand.MAIN_HAND)) {
            CointRadioProtection.denyWithMessage(player);
            return false;
        }

        return true;
    }

    public record YouTubeTransferStartPacket(
            UUID transferId,
            String station,
            String radioId,
            int totalBytes,
            long startOffsetMs
    ) {
        public static void encode(YouTubeTransferStartPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUUID(packet.transferId);
            buffer.writeUtf(packet.station, 256);
            buffer.writeUtf(packet.radioId, 256);
            buffer.writeInt(packet.totalBytes);
            buffer.writeLong(packet.startOffsetMs);
        }

        public static YouTubeTransferStartPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new YouTubeTransferStartPacket(
                    buffer.readUUID(),
                    buffer.readUtf(256),
                    buffer.readUtf(256),
                    buffer.readInt(),
                    buffer.readLong()
            );
        }

        public static void handle(YouTubeTransferStartPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> CointYouTubeClientTransfer.start(
                            packet.transferId,
                            packet.station,
                            packet.radioId,
                            packet.totalBytes,
                            packet.startOffsetMs
                    )
            ));

            context.setPacketHandled(true);
        }
    }

    public record YouTubeTransferChunkPacket(UUID transferId, byte[] data) {
        public static void encode(YouTubeTransferChunkPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUUID(packet.transferId);
            buffer.writeByteArray(packet.data);
        }

        public static YouTubeTransferChunkPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new YouTubeTransferChunkPacket(
                    buffer.readUUID(),
                    buffer.readByteArray(32768)
            );
        }

        public static void handle(YouTubeTransferChunkPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> CointYouTubeClientTransfer.chunk(packet.transferId, packet.data)
            ));

            context.setPacketHandled(true);
        }
    }

    public record YouTubeTransferFinishPacket(UUID transferId) {
        public static void encode(YouTubeTransferFinishPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUUID(packet.transferId);
        }

        public static YouTubeTransferFinishPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new YouTubeTransferFinishPacket(buffer.readUUID());
        }

        public static void handle(YouTubeTransferFinishPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> CointYouTubeClientTransfer.finish(packet.transferId)
            ));

            context.setPacketHandled(true);
        }
    }

    public record YouTubeTransferFailPacket(UUID transferId, String message) {
        public static void encode(YouTubeTransferFailPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUUID(packet.transferId);
            buffer.writeUtf(packet.message == null ? "" : packet.message, 32767);
        }

        public static YouTubeTransferFailPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new YouTubeTransferFailPacket(
                    buffer.readUUID(),
                    buffer.readUtf(32767)
            );
        }

        public static void handle(YouTubeTransferFailPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> CointYouTubeClientTransfer.fail(packet.transferId, packet.message)
            ));

            context.setPacketHandled(true);
        }
    }

    public record PlayRadioPacket(String url, String stationId, String radioId) {
        public static void encode(PlayRadioPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeUtf(packet.url, 32767);
            buffer.writeUtf(packet.stationId, 256);
            buffer.writeUtf(packet.radioId, 256);
        }

        public static PlayRadioPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new PlayRadioPacket(buffer.readUtf(32767), buffer.readUtf(256), buffer.readUtf(256));
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
                    () -> () -> {
                        CointYouTubeClientTransfer.cancelRadio(packet.radioId);
                        CointRadioClientPacketHandler.stop(packet.radioId);
                    }
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
            return new ToggleRadioPacket(buffer.readUtf(32767), buffer.readUtf(256), buffer.readUtf(256));
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
            return new SwitchRadioPacket(buffer.readUtf(32767), buffer.readUtf(256), buffer.readUtf(256));
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

    public record OpenRadioScreenPacket(
            BlockPos pos,
            List<String> stations,
            String currentStation,
            boolean active,
            int radius,
            String customUrl
    ) {
        public static void encode(OpenRadioScreenPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.pos);
            buffer.writeVarInt(packet.stations.size());

            for (String station : packet.stations) {
                buffer.writeUtf(station, 256);
            }

            buffer.writeUtf(packet.currentStation, 256);
            buffer.writeBoolean(packet.active);
            buffer.writeVarInt(packet.radius);
            buffer.writeUtf(packet.customUrl == null ? "" : packet.customUrl, 2048);
        }

        public static OpenRadioScreenPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            BlockPos pos = buffer.readBlockPos();

            int size = Math.min(buffer.readVarInt(), 128);
            List<String> stations = new ArrayList<>();

            for (int i = 0; i < size; i++) {
                stations.add(buffer.readUtf(256));
            }

            String currentStation = buffer.readUtf(256);
            boolean active = buffer.readBoolean();
            int radius = buffer.readVarInt();
            String customUrl = buffer.readUtf(2048);

            return new OpenRadioScreenPacket(pos, stations, currentStation, active, radius, customUrl);
        }

        public static void handle(OpenRadioScreenPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                    Dist.CLIENT,
                    () -> () -> CointRadioClientPacketHandler.openScreen(
                            packet.pos,
                            packet.stations,
                            packet.currentStation,
                            packet.active,
                            packet.radius,
                            packet.customUrl
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
            return new SelectStationPacket(buffer.readBlockPos(), buffer.readUtf(256));
        }

        public static void handle(SelectStationPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();

                if (!isValidRadioAccess(player, packet.pos)) {
                    return;
                }

                BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);

                if (!(blockEntity instanceof CointRadioBlockEntity radio)) {
                    return;
                }

                radio.setStationId(packet.stationId);

                player.displayClientMessage(
                        Component.literal("§e[CointMusic] Станция выбрана: §f" + CointRadioConfig.getStationName(radio.getStationId())),
                        true
                );
            });

            context.setPacketHandled(true);
        }
    }

    public record ToggleActivePacket(BlockPos pos) {
        public static void encode(ToggleActivePacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.pos);
        }

        public static ToggleActivePacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new ToggleActivePacket(buffer.readBlockPos());
        }

        public static void handle(ToggleActivePacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();

                if (!isValidRadioAccess(player, packet.pos)) {
                    return;
                }

                BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);

                if (!(blockEntity instanceof CointRadioBlockEntity radio)) {
                    return;
                }

                radio.toggleActive();

                if (radio.isActive()) {
                    player.displayClientMessage(
                            Component.literal("§a[CointMusic] Радио включено. Радиус: §f" + radio.getRadius()),
                            true
                    );
                } else {
                    player.displayClientMessage(
                            Component.literal("§c[CointMusic] Радио выключено."),
                            true
                    );
                }
            });

            context.setPacketHandled(true);
        }
    }

    public record SetCustomUrlPacket(BlockPos pos, String customUrl) {
        public static void encode(SetCustomUrlPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.pos);
            buffer.writeUtf(packet.customUrl == null ? "" : packet.customUrl, 2048);
        }

        public static SetCustomUrlPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new SetCustomUrlPacket(
                    buffer.readBlockPos(),
                    buffer.readUtf(2048)
            );
        }

        public static void handle(SetCustomUrlPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();

                if (!isValidRadioAccess(player, packet.pos)) {
                    return;
                }

                BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);

                if (!(blockEntity instanceof CointRadioBlockEntity radio)) {
                    return;
                }

                String cleanUrl = packet.customUrl == null ? "" : packet.customUrl.trim();

                if (!isAllowedCustomUrl(cleanUrl)) {
                    player.displayClientMessage(
                            Component.literal("§c[CointMusic] Нужна http/https ссылка на .ogg/.mp3, плейлист или онлайн-радио."),
                            true
                    );
                    return;
                }

                radio.setCustomUrl(cleanUrl);

                player.displayClientMessage(
                        Component.literal("§a[CointMusic] URL радио применён."),
                        true
                );
            });

            context.setPacketHandled(true);
        }
    }

    public record ClearCustomUrlPacket(BlockPos pos) {
        public static void encode(ClearCustomUrlPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.pos);
        }

        public static ClearCustomUrlPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new ClearCustomUrlPacket(buffer.readBlockPos());
        }

        public static void handle(ClearCustomUrlPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();

                if (!isValidRadioAccess(player, packet.pos)) {
                    return;
                }

                BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);

                if (!(blockEntity instanceof CointRadioBlockEntity radio)) {
                    return;
                }

                radio.clearCustomUrl();

                player.displayClientMessage(
                        Component.literal("§e[CointMusic] URL радио очищен. Используется станция: §f" + CointRadioConfig.getStationName(radio.getStationId())),
                        true
                );
            });

            context.setPacketHandled(true);
        }
    }

    public record NextStationPacket(BlockPos pos) {
        public static void encode(NextStationPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.pos);
        }

        public static NextStationPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new NextStationPacket(buffer.readBlockPos());
        }

        public static void handle(NextStationPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();

                if (!isValidRadioAccess(player, packet.pos)) {
                    return;
                }

                BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);

                if (!(blockEntity instanceof CointRadioBlockEntity radio)) {
                    return;
                }

                String nextStation = radio.nextStation();

                player.displayClientMessage(
                        Component.literal("§e[CointMusic] Следующая станция: §f" + CointRadioConfig.getStationName(nextStation)),
                        true
                );
            });

            context.setPacketHandled(true);
        }
    }

    public record RandomStationPacket(BlockPos pos) {
        public static void encode(RandomStationPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.pos);
        }

        public static RandomStationPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new RandomStationPacket(buffer.readBlockPos());
        }

        public static void handle(RandomStationPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();

                if (!isValidRadioAccess(player, packet.pos)) {
                    return;
                }

                BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);

                if (!(blockEntity instanceof CointRadioBlockEntity radio)) {
                    return;
                }

                String randomStation = radio.randomStation();

                player.displayClientMessage(
                        Component.literal("§d[CointMusic] Случайная станция: §f" + CointRadioConfig.getStationName(randomStation)),
                        true
                );
            });

            context.setPacketHandled(true);
        }
    }

    public record SetRadiusPacket(BlockPos pos, int radius) {
        public static void encode(SetRadiusPacket packet, net.minecraft.network.FriendlyByteBuf buffer) {
            buffer.writeBlockPos(packet.pos);
            buffer.writeVarInt(packet.radius);
        }

        public static SetRadiusPacket decode(net.minecraft.network.FriendlyByteBuf buffer) {
            return new SetRadiusPacket(buffer.readBlockPos(), buffer.readVarInt());
        }

        public static void handle(SetRadiusPacket packet, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
            net.minecraftforge.network.NetworkEvent.Context context = contextSupplier.get();

            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();

                if (!isValidRadioAccess(player, packet.pos)) {
                    return;
                }

                BlockEntity blockEntity = player.level().getBlockEntity(packet.pos);

                if (!(blockEntity instanceof CointRadioBlockEntity radio)) {
                    return;
                }

                radio.setRadius(packet.radius);

                player.displayClientMessage(
                        Component.literal("§e[CointMusic] Радиус радио: §f" + radio.getRadius()),
                        true
                );
            });

            context.setPacketHandled(true);
        }
    }
}