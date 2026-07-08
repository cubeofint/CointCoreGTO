package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.client.Minecraft;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public final class CointRadioClientLiveStream {
    private static final byte[] STOP = new byte[0];
    private static final Map<UUID, ClientStream> STREAMS = new ConcurrentHashMap<>();

    private CointRadioClientLiveStream() {
    }

    public static void start(UUID transferId, String station, String radioId) {
        if (transferId == null) {
            return;
        }

        cancelRadio(radioId);

        try {
            PipedInputStream input = new PipedInputStream(1024 * 1024);
            PipedOutputStream output = new PipedOutputStream(input);

            ClientStream stream = new ClientStream(transferId, station, radioId, output);
            STREAMS.put(transferId, stream);

            Thread writer = new Thread(() -> runWriter(stream), "CointMusic-LiveClientWriter-" + transferId);
            writer.setDaemon(true);
            stream.writer = writer;
            writer.start();

            CointRadioPlayer.playLiveMp3Stream(
                    input,
                    message -> {
                        Minecraft minecraft = Minecraft.getInstance();

                        if (minecraft != null && minecraft.player != null) {
                            minecraft.player.displayClientMessage(message, true);
                        }
                    },
                    station
            );

            System.out.println("[CointMusic] Live transcode client started: " + transferId + ", station=" + station);
        } catch (Throwable throwable) {
            System.out.println("[CointMusic] Failed to start live transcode client: " + throwable.getMessage());
            throwable.printStackTrace();
        }
    }

    public static void chunk(UUID transferId, byte[] data) {
        ClientStream stream = STREAMS.get(transferId);

        if (stream == null || !stream.active) {
            return;
        }

        if (data == null || data.length <= 0) {
            return;
        }

        stream.queue.offer(data);
    }

    public static void stop(UUID transferId) {
        ClientStream stream = STREAMS.remove(transferId);

        if (stream != null) {
            stream.close();
        }
    }

    public static void fail(UUID transferId, String message) {
        stop(transferId);

        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("§c[CointMusic] Live-transcode failed: §f" + (message == null ? "" : message)),
                    true
            );
        }
    }

    public static void cancelRadio(String radioId) {
        if (radioId == null || radioId.isBlank()) {
            return;
        }

        for (ClientStream stream : STREAMS.values()) {
            if (radioId.equals(stream.radioId)) {
                STREAMS.remove(stream.transferId);
                stream.close();
            }
        }
    }

    private static void runWriter(ClientStream stream) {
        try {
            while (stream.active) {
                byte[] data = stream.queue.take();

                if (data == STOP) {
                    break;
                }

                stream.output.write(data);
                stream.output.flush();
            }
        } catch (Throwable throwable) {
            if (stream.active) {
                System.out.println("[CointMusic] Live client writer stopped: " + throwable.getMessage());
            }
        } finally {
            stream.closeOutputOnly();
        }
    }

    private static final class ClientStream {
        private final UUID transferId;
        private final String station;
        private final String radioId;
        private final PipedOutputStream output;
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private volatile boolean active = true;
        private Thread writer;

        private ClientStream(UUID transferId, String station, String radioId, PipedOutputStream output) {
            this.transferId = transferId;
            this.station = station == null ? "" : station;
            this.radioId = radioId == null ? "" : radioId;
            this.output = output;
        }

        private void close() {
            active = false;
            queue.offer(STOP);
            closeOutputOnly();
        }

        private void closeOutputOnly() {
            try {
                output.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
