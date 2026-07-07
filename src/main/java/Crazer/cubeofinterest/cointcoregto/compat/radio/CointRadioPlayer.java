package Crazer.cubeofinterest.cointcoregto.compat.radio;

import com.mojang.blaze3d.audio.OggAudioStream;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.openal.AL10;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class CointRadioPlayer {
    private static int sourceId = 0;
    private static int bufferId = 0;
    private static float volume = 1.0f;
    private static boolean playing = false;
    private static boolean loading = false;
    private static boolean volumeLoaded = false;
    private static ByteBuffer currentPcmBuffer;
    private static final int MAX_TRACK_SIZE_BYTES = 25 * 1024 * 1024;
    private static final int MAX_CACHE_SIZE_BYTES = 100 * 1024 * 1024;
    private static final int MAX_CACHED_TRACKS = 10;


    private static final Map<String, byte[]> OGG_CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static int currentCacheSizeBytes = 0;

    private CointRadioPlayer() {
    }

    public static boolean isPlaying() {
        return playing;
    }

    public static boolean isLoading() {
        return loading;
    }

    public static void play(String url, Consumer<Component> feedback) {
        loadSavedVolume();
        if (url == null || url.isBlank()) {
            send(feedback, "§c[CointMusic] Пустая ссылка.");
            return;
        }

        String cleanUrl = url.trim();
        String lowerUrl = cleanUrl.toLowerCase(Locale.ROOT);

        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            send(feedback, "§c[CointMusic] Нужна http/https ссылка.");
            return;
        }

        if (!lowerUrl.contains(".ogg")) {
            send(feedback, "§e[CointMusic] Сейчас поддерживаются только прямые .ogg ссылки.");
            return;
        }
        if (loading) {
            send(feedback, "§e[CointMusic] Трек уже загружается, подожди секунду.");
            return;
        }

        loading = true;

        if (isCached(cleanUrl)) {
            send(feedback, "§e[CointMusic] Запускаю музыку из кеша...");
        } else {
            send(feedback, "§e[CointMusic] Загружаю музыку...");
        }

        CompletableFuture.runAsync(() -> {
            try {
                byte[] oggBytes = getOrDownloadOgg(cleanUrl);

                try (InputStream raw = new BufferedInputStream(new ByteArrayInputStream(oggBytes));
                     OggAudioStream oggStream = new OggAudioStream(raw)) {

                    ByteBuffer pcmBuffer = oggStream.readAll();
                    AudioFormat format = oggStream.getFormat();

                    Minecraft.getInstance().execute(() -> {
                        try {
                            startDecodedAudio(pcmBuffer, format, feedback);
                        } catch (Throwable e) {
                            loading = false;
                            safeFree(pcmBuffer);
                            send(feedback, "§c[CointMusic] Не удалось запустить звук: §f" + e.getMessage());
                            e.printStackTrace();
                        }
                    });
                }
            } catch (Throwable e) {
                Minecraft.getInstance().execute(() -> {
                    loading = false;
                    send(feedback, "§c[CointMusic] Ошибка загрузки трека. Проверь прямую .ogg ссылку или не кликай слишком быстро.");
                    System.out.println("[CointMusic] Failed to load URL: " + cleanUrl);
                    System.out.println("[CointMusic] Load error: " + e.getMessage());
                    e.printStackTrace();
                });
            }
        });
    }

    private static boolean isCached(String cleanUrl) {
        synchronized (OGG_CACHE) {
            byte[] cached = OGG_CACHE.get(cleanUrl);
            return cached != null && cached.length > 0;
        }
    }

    private static byte[] getOrDownloadOgg(String cleanUrl) throws Exception {
        synchronized (OGG_CACHE) {
            byte[] cached = OGG_CACHE.get(cleanUrl);

            if (cached != null && cached.length > 0) {
                System.out.println("[CointMusic] Loaded from cache: " + cleanUrl);
                return cached;
            }
        }

        URLConnection connection = new URL(cleanUrl).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "CointCoreGTO-MusicPlayer/1.0");

        byte[] data;

        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int read;
            int total = 0;

            while ((read = input.read(buffer)) != -1) {
                total += read;

                if (total > MAX_TRACK_SIZE_BYTES) {
                    throw new IllegalStateException("Track is too large. Max size: " + MAX_TRACK_SIZE_BYTES + " bytes");
                }

                output.write(buffer, 0, read);
            }

            data = output.toByteArray();
        }

        if (data.length <= 0) {
            throw new IllegalStateException("Downloaded track is empty");
        }

        putInCache(cleanUrl, data);

        System.out.println("[CointMusic] Downloaded and cached: " + cleanUrl + " size=" + data.length);

        return data;
    }

    private static void putInCache(String cleanUrl, byte[] data) {
        synchronized (OGG_CACHE) {
            byte[] old = OGG_CACHE.remove(cleanUrl);

            if (old != null) {
                currentCacheSizeBytes -= old.length;
            }

            OGG_CACHE.put(cleanUrl, data);
            currentCacheSizeBytes += data.length;

            trimCacheIfNeeded();
        }
    }

    private static void trimCacheIfNeeded() {
        while (
                OGG_CACHE.size() > MAX_CACHED_TRACKS
                        || currentCacheSizeBytes > MAX_CACHE_SIZE_BYTES
        ) {
            Map.Entry<String, byte[]> eldest = OGG_CACHE.entrySet().iterator().next();

            String removedUrl = eldest.getKey();
            byte[] removedData = eldest.getValue();

            OGG_CACHE.remove(removedUrl);

            if (removedData != null) {
                currentCacheSizeBytes -= removedData.length;
            }

            System.out.println("[CointMusic] Removed old cached track: " + removedUrl);
        }

        if (currentCacheSizeBytes < 0) {
            currentCacheSizeBytes = 0;
        }
    }

    public static void clearCache() {
        synchronized (OGG_CACHE) {
            OGG_CACHE.clear();
            currentCacheSizeBytes = 0;
        }

        System.out.println("[CointMusic] Audio cache cleared");
    }

    public static String getCacheStats() {
        synchronized (OGG_CACHE) {
            return "tracks=" + OGG_CACHE.size()
                    + ", size=" + currentCacheSizeBytes / 1024 + " KB"
                    + ", maxTracks=" + MAX_CACHED_TRACKS
                    + ", maxSize=" + MAX_CACHE_SIZE_BYTES / 1024 / 1024 + " MB";
        }
    }

    public static void stop() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null) {
            return;
        }

        if (minecraft.isSameThread()) {
            stopNowOnClientThread();
        } else {
            minecraft.execute(CointRadioPlayer::stopNowOnClientThread);
        }
    }

    private static void stopNowOnClientThread() {
        try {
            playing = false;
            loading = false;

            if (sourceId != 0) {
                AL10.alSourceStop(sourceId);
                AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);
                AL10.alDeleteSources(sourceId);
                sourceId = 0;
            }

            if (bufferId != 0) {
                AL10.alDeleteBuffers(bufferId);
                bufferId = 0;
            }

            currentPcmBuffer = null;
        } catch (Throwable e) {
            System.out.println("[CointMusic] Failed to stop audio: " + e.getMessage());
        }
    }

    public static void setVolume(float newVolume) {
        volume = Math.max(0.0f, Math.min(1.0f, newVolume));

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }

        minecraft.execute(() -> {
            if (sourceId != 0) {
                AL10.alSourcef(sourceId, AL10.AL_GAIN, volume);
            }
        });
    }

    public static void setVolumePercent(int percent) {
        int safePercent = Math.max(0, Math.min(100, percent));
        setVolume(safePercent / 100.0f);
        saveVolumePercent(safePercent);

        System.out.println("[CointMusic] Volume set to " + safePercent + "%");
    }

    public static int getVolumePercent() {
        loadSavedVolume();
        return Math.round(volume * 100.0f);
    }

    public static void loadSavedVolume() {
        if (volumeLoaded) {
            return;
        }

        volumeLoaded = true;

        try {
            Path path = getVolumeConfigPath();

            if (path == null || !Files.exists(path)) {
                return;
            }

            String raw = Files.readString(path, StandardCharsets.UTF_8).trim();

            if (raw.isBlank()) {
                return;
            }

            int percent = Integer.parseInt(raw);
            int safePercent = Math.max(0, Math.min(100, percent));

            volume = safePercent / 100.0f;

            if (sourceId != 0) {
                AL10.alSourcef(sourceId, AL10.AL_GAIN, volume);
            }

            System.out.println("[CointMusic] Loaded saved volume: " + safePercent + "%");
        } catch (Throwable e) {
            System.out.println("[CointMusic] Failed to load saved volume: " + e.getMessage());
        }
    }

    private static void saveVolumePercent(int percent) {
        try {
            Path path = getVolumeConfigPath();

            if (path == null) {
                return;
            }

            Files.createDirectories(path.getParent());
            Files.writeString(path, String.valueOf(percent), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            System.out.println("[CointMusic] Failed to save volume: " + e.getMessage());
        }
    }

    private static Path getVolumeConfigPath() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null || minecraft.gameDirectory == null) {
            return null;
        }

        return minecraft.gameDirectory
                .toPath()
                .resolve("config")
                .resolve("cointcoregto-radio-client.txt");
    }

    private static void startDecodedAudio(ByteBuffer pcmBuffer, AudioFormat format, Consumer<Component> feedback) {
        clearOpenAlError();

        if (sourceId != 0 || bufferId != 0 || currentPcmBuffer != null) {
            stopNowOnClientThread();
        }

        int openAlFormat = getOpenAlFormat(format);
        int sampleRate = Math.round(format.getSampleRate());

        currentPcmBuffer = pcmBuffer;

        bufferId = AL10.alGenBuffers();
        checkOpenAl("alGenBuffers", feedback);

        AL10.alBufferData(bufferId, openAlFormat, currentPcmBuffer, sampleRate);
        checkOpenAl("alBufferData", feedback);

        sourceId = AL10.alGenSources();
        checkOpenAl("alGenSources", feedback);

        AL10.alSourcei(sourceId, AL10.AL_BUFFER, bufferId);
        AL10.alSourcef(sourceId, AL10.AL_GAIN, volume);
        AL10.alSourcef(sourceId, AL10.AL_PITCH, 1.0f);

        AL10.alSourcei(sourceId, AL10.AL_LOOPING, AL10.AL_TRUE);

        AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSource3f(sourceId, AL10.AL_POSITION, 0.0f, 0.0f, 0.0f);
        AL10.alSource3f(sourceId, AL10.AL_VELOCITY, 0.0f, 0.0f, 0.0f);

        AL10.alSourcePlay(sourceId);
        checkOpenAl("alSourcePlay", feedback);

        playing = true;
        loading = false;
        int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);
        send(feedback, "§a[CointMusic] Воспроизведение началось.");
        System.out.println("[CointMusic] Audio started. format=" + format + ", sampleRate=" + sampleRate + ", state=" + state);
    }

    private static int getOpenAlFormat(AudioFormat format) {
        int channels = format.getChannels();
        int bits = format.getSampleSizeInBits();

        if (channels == 1 && bits == 8) {
            return AL10.AL_FORMAT_MONO8;
        }

        if (channels == 1 && bits == 16) {
            return AL10.AL_FORMAT_MONO16;
        }

        if (channels == 2 && bits == 8) {
            return AL10.AL_FORMAT_STEREO8;
        }

        if (channels == 2 && bits == 16) {
            return AL10.AL_FORMAT_STEREO16;
        }

        throw new IllegalArgumentException(
                "Unsupported audio format: channels=" + channels + ", bits=" + bits
        );
    }

    private static void clearOpenAlError() {
        while (AL10.alGetError() != AL10.AL_NO_ERROR) {
            // clear
        }
    }

    private static void checkOpenAl(String step, Consumer<Component> feedback) {
        int error = AL10.alGetError();

        if (error != AL10.AL_NO_ERROR) {
            String message = "[CointMusic] OpenAL error after " + step + ": " + error;
            System.out.println(message);
            send(feedback, "§c" + message);
        }
    }

    private static void safeFree(ByteBuffer buffer) {
        try {
            if (buffer != null && buffer.isDirect()) {
                MemoryUtil.memFree(buffer);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void send(Consumer<Component> feedback, String text) {
        if (feedback != null) {
            feedback.accept(Component.literal(text));
        }
    }
}