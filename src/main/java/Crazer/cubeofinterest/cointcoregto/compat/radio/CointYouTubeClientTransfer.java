package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CointYouTubeClientTransfer {
    private static final Map<UUID, ReceivingFile> RECEIVING = new ConcurrentHashMap<>();

    private CointYouTubeClientTransfer() {
    }

    public static void start(UUID transferId, String station, String radioId, int totalBytes, long startOffsetMs) {
        cancelRadio(radioId);

        RECEIVING.put(transferId, new ReceivingFile(
                station,
                radioId,
                totalBytes,
                Math.max(0L, startOffsetMs),
                new ByteArrayOutputStream(Math.max(1024, Math.min(totalBytes, 8 * 1024 * 1024)))
        ));

        System.out.println("[CointMusic] YouTube client transfer started: " + transferId + ", bytes=" + totalBytes);
    }

    public static void chunk(UUID transferId, byte[] data) {
        ReceivingFile receiving = RECEIVING.get(transferId);

        if (receiving == null) {
            return;
        }

        try {
            receiving.output().write(data);
        } catch (Throwable throwable) {
            RECEIVING.remove(transferId);
            System.out.println("[CointMusic] YouTube client transfer chunk failed: " + throwable.getMessage());
        }
    }

    public static void finish(UUID transferId) {
        ReceivingFile receiving = RECEIVING.remove(transferId);

        if (receiving == null) {
            return;
        }

        try {
            byte[] bytes = receiving.output().toByteArray();

            if (bytes.length <= 64 * 1024) {
                throw new IllegalStateException("Received MP3 is too small: " + bytes.length);
            }

            Path dir = Minecraft.getInstance().gameDirectory.toPath()
                    .resolve("cointmusic-cache")
                    .resolve("client-youtube");

            Files.createDirectories(dir);

            Path file = dir.resolve(transferId + ".mp3");

            Files.write(file, bytes);

            String fileUrl = file.toUri().toString();

            System.out.println("[CointMusic] YouTube client transfer finished: " + file);
            System.out.println("[CointMusic] YouTube local file URL: " + fileUrl);

            CointRadioPlayer.play(
                    fileUrl,
                    message -> {
                        if (Minecraft.getInstance().player != null) {
                            Minecraft.getInstance().player.displayClientMessage(message, true);
                        }
                    }
            );
        } catch (Throwable throwable) {
            System.out.println("[CointMusic] YouTube client transfer finish failed: " + throwable.getMessage());
            throwable.printStackTrace();
        }
    }

    public static void fail(UUID transferId, String message) {
        RECEIVING.remove(transferId);
        System.out.println("[CointMusic] YouTube transfer failed: " + message);
    }

    public static void cancelRadio(String radioId) {
        if (radioId == null || radioId.isBlank()) {
            return;
        }

        RECEIVING.entrySet().removeIf(entry -> radioId.equals(entry.getValue().radioId()));
    }

    private record ReceivingFile(
            String station,
            String radioId,
            int totalBytes,
            long startOffsetMs,
            ByteArrayOutputStream output
    ) {
    }
}
