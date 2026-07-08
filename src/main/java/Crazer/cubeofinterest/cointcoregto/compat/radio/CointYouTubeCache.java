package Crazer.cubeofinterest.cointcoregto.compat.radio;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class CointYouTubeCache {
    private static final Path CACHE_DIR = FMLPaths.GAMEDIR.get()
            .resolve("cointmusic-cache")
            .resolve("youtube");

    private static final String[] YT_DLP_COMMANDS = {
            "yt-dlp",
            "yt-dlp.exe",
            "./yt-dlp",
            "./yt-dlp.exe"
    };

    private static final String[] FFMPEG_COMMANDS = {
            "ffmpeg",
            "ffmpeg.exe",
            "./ffmpeg",
            "./ffmpeg.exe"
    };

    private static final Map<String, Object> CACHE_LOCKS = new ConcurrentHashMap<>();

    private CointYouTubeCache() {
    }

    public static Path getOrCreateCachedMp3(String youtubeUrl) {
        String key = sha256(youtubeUrl);
        Path mp3Path = CACHE_DIR.resolve(key + ".mp3");
        Path tempPath = CACHE_DIR.resolve(key + ".tmp");

        if (isValidCachedMp3(mp3Path)) {
            System.out.println("[CointMusic] YouTube packet cache hit: " + mp3Path.getFileName());
            return mp3Path;
        }

        Object lock = CACHE_LOCKS.computeIfAbsent(key, ignored -> new Object());

        synchronized (lock) {
            if (isValidCachedMp3(mp3Path)) {
                System.out.println("[CointMusic] YouTube packet cache hit after wait: " + mp3Path.getFileName());
                return mp3Path;
            }

            try {
                Files.createDirectories(CACHE_DIR);
                Files.deleteIfExists(tempPath);

                System.out.println("[CointMusic] YouTube packet cache miss, downloading: " + mp3Path.getFileName());

                downloadYoutubeToMp3(youtubeUrl, tempPath);

                if (!isValidCachedMp3(tempPath)) {
                    throw new IllegalStateException("Downloaded MP3 is invalid or empty");
                }

                Files.move(
                        tempPath,
                        mp3Path,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE
                );

                System.out.println("[CointMusic] YouTube packet cached: " + mp3Path.getFileName());

                return mp3Path;
            } catch (Throwable throwable) {
                try {
                    Files.deleteIfExists(tempPath);
                } catch (Throwable ignored) {
                }

                throw new IllegalStateException("Failed to create YouTube cache: " + throwable.getMessage(), throwable);
            } finally {
                CACHE_LOCKS.remove(key);
            }
        }
    }

    private static boolean isValidCachedMp3(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) > 64 * 1024;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void downloadYoutubeToMp3(String youtubeUrl, Path outputPath) {
        StringBuilder errors = new StringBuilder();

        for (String ytDlpCommand : YT_DLP_COMMANDS) {
            for (String ffmpegCommand : FFMPEG_COMMANDS) {
                Process ytDlp = null;
                Process ffmpeg = null;

                try {
                    ytDlp = startYtDlp(ytDlpCommand, youtubeUrl);
                    ffmpeg = startFfmpegToFile(ffmpegCommand, outputPath);

                    drainErrors("yt-dlp", ytDlp);
                    drainErrors("ffmpeg", ffmpeg);

                    pipeYtDlpToFfmpeg(ytDlp, ffmpeg);

                    int ffmpegExit = ffmpeg.waitFor();
                    int ytDlpExit = ytDlp.waitFor();

                    if (ytDlpExit != 0) {
                        throw new IllegalStateException("yt-dlp exit code: " + ytDlpExit);
                    }

                    if (ffmpegExit != 0) {
                        throw new IllegalStateException("ffmpeg exit code: " + ffmpegExit);
                    }

                    System.out.println("[CointMusic] YouTube yt-dlp command: " + ytDlpCommand);
                    System.out.println("[CointMusic] YouTube ffmpeg command: " + ffmpegCommand);

                    return;
                } catch (Throwable throwable) {
                    if (ytDlp != null && ytDlp.isAlive()) {
                        ytDlp.destroyForcibly();
                    }

                    if (ffmpeg != null && ffmpeg.isAlive()) {
                        ffmpeg.destroyForcibly();
                    }

                    errors.append("yt-dlp=")
                            .append(ytDlpCommand)
                            .append(", ffmpeg=")
                            .append(ffmpegCommand)
                            .append(" failed: ")
                            .append(throwable.getMessage())
                            .append('\n');

                    try {
                        Files.deleteIfExists(outputPath);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        throw new IllegalStateException("Could not create YouTube MP3 cache:\n" + errors);
    }

    private static Process startYtDlp(String command, String youtubeUrl) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                command,
                "--no-playlist",
                "--no-warnings",
                "-f",
                "bestaudio/best",
                "-o",
                "-",
                youtubeUrl
        );

        return builder.start();
    }

    private static Process startFfmpegToFile(String command, Path outputPath) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                command,
                "-y",
                "-hide_banner",
                "-loglevel",
                "warning",
                "-i",
                "pipe:0",
                "-vn",
                "-f",
                "mp3",
                "-codec:a",
                "libmp3lame",
                "-b:a",
                "128k",
                outputPath.toAbsolutePath().toString()
        );

        return builder.start();
    }

    private static void pipeYtDlpToFfmpeg(Process ytDlp, Process ffmpeg) {
        Thread thread = new Thread(() -> {
            try (
                    InputStream ytOutput = ytDlp.getInputStream();
                    OutputStream ffmpegInput = ffmpeg.getOutputStream()
            ) {
                ytOutput.transferTo(ffmpegInput);
                ffmpegInput.flush();
            } catch (Throwable throwable) {
                System.out.println("[CointMusic] yt-dlp -> ffmpeg pipe closed: " + throwable.getMessage());
            }
        }, "CointMusic-YtDlp-To-FFmpeg");

        thread.setDaemon(true);
        thread.start();
    }

    private static void drainErrors(String name, Process process) {
        Thread thread = new Thread(() -> {
            try (InputStream error = process.getErrorStream()) {
                byte[] buffer = new byte[4096];
                StringBuilder pending = new StringBuilder();

                while (true) {
                    int read = error.read(buffer);

                    if (read < 0) {
                        break;
                    }

                    if (read > 0) {
                        pending.append(new String(buffer, 0, read, StandardCharsets.UTF_8));

                        int newline;

                        while ((newline = pending.indexOf("\n")) >= 0) {
                            String line = pending.substring(0, newline).trim();
                            pending.delete(0, newline + 1);

                            if (!line.isBlank()) {
                                System.out.println("[CointMusic] " + name + ": " + line);
                            }
                        }
                    }
                }

                String tail = pending.toString().trim();

                if (!tail.isBlank()) {
                    System.out.println("[CointMusic] " + name + ": " + tail);
                }
            } catch (Throwable throwable) {
                System.out.println("[CointMusic] " + name + " stderr read failed: " + throwable.getMessage());
            }
        }, "CointMusic-" + name + "-Error-Drain");

        thread.setDaemon(true);
        thread.start();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Throwable throwable) {
            throw new IllegalStateException("Failed to hash YouTube URL", throwable);
        }
    }
}
