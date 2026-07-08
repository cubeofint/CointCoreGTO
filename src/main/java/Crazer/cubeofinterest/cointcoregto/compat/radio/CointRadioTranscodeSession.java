package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraft.server.level.ServerPlayer;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class CointRadioTranscodeSession {
    private static final int CHUNK_SIZE = 12 * 1024;
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private CointRadioTranscodeSession() {
    }

    public static boolean shouldTranscode(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        String lowered = stripQuery(url.trim().toLowerCase(Locale.ROOT));

        return lowered.endsWith(".aac")
                || lowered.endsWith(".aacp")
                || lowered.endsWith(".m3u8")
                || lowered.contains("radiorecord.hostingradio.ru")
                || lowered.contains("montecarlo.hostingradio.ru");
    }

    public static void start(ServerPlayer player, String sourceUrl, String station, String radioId) {
        if (player == null || sourceUrl == null || sourceUrl.isBlank()) {
            return;
        }

        if (!CointRadioExternalTools.isFfmpegAvailable()) {
            if (player.getServer() != null) {
                player.getServer().execute(() -> player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal(CointRadioExternalTools.getFfmpegDisabledMessage()),
                        true
                ));
            }

            return;
        }

        stop(player.getUUID(), radioId);

        UUID transferId = UUID.randomUUID();
        String key = key(player.getUUID(), radioId);
        Session session = new Session(player.getUUID(), radioId, transferId);
        SESSIONS.put(key, session);

        if (player.getServer() != null) {
            player.getServer().execute(() -> CointRadioNetwork.sendLiveTranscodeStart(player, transferId, station, radioId));
        }

        CompletableFuture.runAsync(() -> runSession(player, sourceUrl, radioId, key, session));
    }

    public static void stop(UUID playerId, String radioId) {
        if (playerId == null || radioId == null) {
            return;
        }

        Session session = SESSIONS.remove(key(playerId, radioId));

        if (session != null) {
            session.stop();
        }
    }

    private static void runSession(ServerPlayer player, String sourceUrl, String radioId, String key, Session session) {
        Process process = null;

        try {
            ProcessBuilder builder = new ProcessBuilder(
                    findFfmpegExecutable(),
                    "-hide_banner",
                    "-loglevel", "warning",
                    "-reconnect", "1",
                    "-reconnect_streamed", "1",
                    "-reconnect_delay_max", "5",
                    "-user_agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "-i", sourceUrl,
                    "-vn",
                    "-f", "mp3",
                    "-codec:a", "libmp3lame",
                    "-b:a", "128k",
                    "-"
            );

            builder.redirectErrorStream(false);

            process = builder.start();
            session.process = process;

            Process finalProcess = process;
            Thread stderrThread = new Thread(() -> logFfmpegErrors(finalProcess), "CointMusic-ffmpeg-live-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();

            byte[] buffer = new byte[CHUNK_SIZE];

            try (InputStream input = new BufferedInputStream(process.getInputStream())) {
                while (session.active) {
                    int read = input.read(buffer);

                    if (read < 0) {
                        break;
                    }

                    if (read == 0) {
                        continue;
                    }

                    byte[] chunk = new byte[read];
                    System.arraycopy(buffer, 0, chunk, 0, read);

                    if (player.getServer() == null) {
                        break;
                    }

                    player.getServer().execute(() -> {
                        if (session.active && player.connection != null) {
                            CointRadioNetwork.sendLiveTranscodeChunk(player, session.transferId, chunk);
                        }
                    });
                }
            }

            int exitCode = process.waitFor();

            if (session.active && exitCode != 0 && player.getServer() != null) {
                player.getServer().execute(() -> CointRadioNetwork.sendLiveTranscodeFail(
                        player,
                        session.transferId,
                        "ffmpeg exit code " + exitCode
                ));
            }
        } catch (Throwable throwable) {
            System.out.println("[CointMusic] Live transcode failed: " + shortError(throwable));

            if (session.active && player.getServer() != null) {
                player.getServer().execute(() -> CointRadioNetwork.sendLiveTranscodeFail(
                        player,
                        session.transferId,
                        throwable.getMessage()
                ));
            }
        } finally {
            session.stop();

            if (process != null) {
                process.destroyForcibly();
            }

            SESSIONS.remove(key, session);
        }
    }

    private static void logFfmpegErrors(Process process) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;

            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    System.out.println("[CointMusic] ffmpeg-live: " + line);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String shortError(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }

        String message = throwable.getMessage();

        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }

        int lineBreak = message.indexOf('\n');

        if (lineBreak >= 0) {
            message = message.substring(0, lineBreak);
        }

        if (message.length() > 240) {
            message = message.substring(0, 240) + "...";
        }

        return message;
    }

    private static String findFfmpegExecutable() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.contains("win")) {
            Path localExe = Path.of("ffmpeg.exe");

            if (Files.exists(localExe)) {
                return localExe.toAbsolutePath().toString();
            }

            return "ffmpeg.exe";
        }

        Path local = Path.of("ffmpeg");

        if (Files.exists(local)) {
            return local.toAbsolutePath().toString();
        }

        return "ffmpeg";
    }

    private static String stripQuery(String url) {
        int index = url.indexOf('?');

        if (index >= 0) {
            return url.substring(0, index);
        }

        return url;
    }

    private static String key(UUID playerId, String radioId) {
        return playerId + "|" + (radioId == null ? "" : radioId);
    }

    private static final class Session {
        private final UUID playerId;
        private final String radioId;
        private final UUID transferId;
        private volatile boolean active = true;
        private volatile Process process;

        private Session(UUID playerId, String radioId, UUID transferId) {
            this.playerId = playerId;
            this.radioId = radioId == null ? "" : radioId;
            this.transferId = transferId;
        }

        private void stop() {
            active = false;

            Process current = process;

            if (current != null) {
                current.destroyForcibly();
            }
        }
    }
}
