package Crazer.cubeofinterest.cointcoregto.compat.radio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class CointRadioExternalTools {
    private static Boolean ffmpegAvailable;
    private static Boolean ytdlpAvailable;

    private CointRadioExternalTools() {
    }

    public static boolean isFfmpegAvailable() {
        if (ffmpegAvailable == null) {
            ffmpegAvailable = checkTool("ffmpeg");
        }

        return ffmpegAvailable;
    }

    public static boolean isYtdlpAvailable() {
        if (ytdlpAvailable == null) {
            ytdlpAvailable = checkTool("yt-dlp");
        }

        return ytdlpAvailable;
    }

    public static boolean isYouTubeAvailable() {
        return isFfmpegAvailable() && isYtdlpAvailable();
    }

    public static String getFfmpegDisabledMessage() {
        return "§c[CointMusic] Эта ссылка не поддерживается серверным радио.";
    }

    public static String getYouTubeDisabledMessage() {
        return "§c[CointMusic] YouTube-ссылки не поддерживаются серверным радио.";
    }

    public static String getAdminStatusLine() {
        return "[CointMusic] Optional tools: ffmpeg="
                + (isFfmpegAvailable() ? "available" : "missing")
                + ", yt-dlp="
                + (isYtdlpAvailable() ? "available" : "missing")
                + ". Basic radio works without them.";
    }

    private static boolean checkTool(String toolName) {
        String[] candidates = getCandidates(toolName);

        for (String candidate : candidates) {
            if (canRun(candidate)) {
                return true;
            }
        }

        return false;
    }

    private static String[] getCandidates(String toolName) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

        if ("ffmpeg".equals(toolName)) {
            if (windows) {
                return new String[] {
                        "ffmpeg.exe",
                        "ffmpeg",
                        ".\\ffmpeg.exe",
                        "./ffmpeg.exe"
                };
            }

            return new String[] {
                    "./ffmpeg",
                    "ffmpeg"
            };
        }

        if ("yt-dlp".equals(toolName)) {
            if (windows) {
                return new String[] {
                        "yt-dlp.exe",
                        "yt-dlp",
                        ".\\yt-dlp.exe",
                        "./yt-dlp.exe"
                };
            }

            return new String[] {
                    "./yt-dlp",
                    "yt-dlp"
            };
        }

        return new String[] { toolName };
    }

    private static boolean canRun(String executable) {
        try {
            Path path = Path.of(executable);

            if (executable.contains("/") || executable.contains("\\") || executable.startsWith(".")) {
                if (!Files.exists(path)) {
                    return false;
                }
            }

            ProcessBuilder builder = new ProcessBuilder(executable, "-version");
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);

            Process process = builder.start();
            boolean exited = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);

            if (!exited) {
                process.destroyForcibly();
                return false;
            }

            return process.exitValue() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
