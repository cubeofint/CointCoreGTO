package Crazer.cubeofinterest.cointcoregto.compat.radio;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class CointYouTubeResolver {
    private static final String[] YT_DLP_COMMANDS = {
            "yt-dlp",
            "yt-dlp.exe",
            "./yt-dlp",
            "./yt-dlp.exe"
    };

    private static final long CACHE_TTL_MS = Duration.ofMinutes(25).toMillis();

    private static final Map<String, CachedResolvedUrl> CACHE = new ConcurrentHashMap<>();

    private CointYouTubeResolver() {
    }

    public static boolean isYouTubeUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        String lowered = url.toLowerCase(Locale.ROOT);

        return lowered.contains("youtube.com/watch")
                || lowered.contains("youtube.com/shorts/")
                || lowered.contains("music.youtube.com/watch")
                || lowered.contains("youtu.be/");
    }

    public static CompletableFuture<String> resolveAsync(String youtubeUrl) {
        return CompletableFuture.supplyAsync(() -> resolve(youtubeUrl));
    }

    public static String resolve(String youtubeUrl) {
        String cleanUrl = youtubeUrl == null ? "" : youtubeUrl.trim();

        if (cleanUrl.isBlank()) {
            throw new IllegalArgumentException("YouTube URL is empty");
        }

        CachedResolvedUrl cached = CACHE.get(cleanUrl);

        if (cached != null && !cached.isExpired()) {
            return cached.url();
        }

        String resolved = runYtDlpWithFallback(cleanUrl);

        CACHE.put(cleanUrl, new CachedResolvedUrl(
                resolved,
                System.currentTimeMillis() + CACHE_TTL_MS
        ));

        return resolved;
    }

    private static String runYtDlpWithFallback(String youtubeUrl) {
        StringBuilder errors = new StringBuilder();

        for (String command : YT_DLP_COMMANDS) {
            try {
                String resolved = runYtDlp(command, youtubeUrl);

                System.out.println("[CointMusic] YouTube resolved with command: " + command);

                return resolved;
            } catch (Throwable throwable) {
                errors.append("Command: ")
                        .append(command)
                        .append(" failed: ")
                        .append(throwable.getMessage())
                        .append('\n');
            }
        }

        throw new IllegalStateException(
                "Could not resolve YouTube URL. Tried yt-dlp commands:\n" + errors
        );
    }

    private static String runYtDlp(String command, String youtubeUrl) {
        ProcessBuilder builder = new ProcessBuilder(
                command,
                "--no-playlist",
                "-f",
                "bestaudio/best",
                "-g",
                youtubeUrl
        );

        builder.redirectErrorStream(true);

        Process process = null;

        try {
            process = builder.start();

            String resolvedUrl = null;
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;

                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');

                    String trimmed = line.trim();

                    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                        resolvedUrl = trimmed;
                    }
                }
            }

            boolean finished = process.waitFor(25, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("yt-dlp timeout");
            }

            int exitCode = process.exitValue();

            if (exitCode != 0) {
                throw new IllegalStateException(
                        "yt-dlp failed, exitCode=" + exitCode + ", output=" + output
                );
            }

            if (resolvedUrl == null || resolvedUrl.isBlank()) {
                throw new IllegalStateException(
                        "yt-dlp did not return direct audio URL. Output=" + output
                );
            }

            return resolvedUrl;
        } catch (Throwable throwable) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }

            throw new IllegalStateException(
                    "Failed to run " + command + ": " + throwable.getMessage(),
                    throwable
            );
        }
    }

    private record CachedResolvedUrl(String url, long expiresAtMs) {
        private boolean isExpired() {
            return System.currentTimeMillis() >= expiresAtMs;
        }
    }
}