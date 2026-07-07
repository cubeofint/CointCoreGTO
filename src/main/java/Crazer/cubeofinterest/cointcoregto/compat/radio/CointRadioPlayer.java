package Crazer.cubeofinterest.cointcoregto.compat.radio;

import com.mojang.blaze3d.audio.OggAudioStream;
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final int MAX_CACHE_SIZE_BYTES = 300 * 1024 * 1024;
    private static final int MAX_CACHED_TRACKS = 10;

    private static final Map<String, byte[]> AUDIO_CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static int currentCacheSizeBytes = 0;
    private static volatile boolean streaming = false;
    private static CompletableFuture<?> streamingFuture;
    private static final List<Integer> STREAM_BUFFERS = new ArrayList<>();

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
                String resolvedUrl = resolvePlaylistIfNeeded(cleanUrl);

                if (shouldUseStreaming(resolvedUrl)) {
                    startMp3Stream(resolvedUrl, feedback);
                    return;
                }

                byte[] audioBytes = getOrDownloadAudio(resolvedUrl);
                DecodedAudio decodedAudio = decodeAudio(resolvedUrl, audioBytes);

                Minecraft.getInstance().execute(() -> {
                    try {
                        startDecodedAudio(decodedAudio.pcmBuffer(), decodedAudio.format(), feedback);
                    } catch (Throwable e) {
                        loading = false;
                        safeFree(decodedAudio.pcmBuffer());
                        send(feedback, "§c[CointMusic] Не удалось запустить звук: §f" + e.getMessage());
                        e.printStackTrace();
                    }
                });
            } catch (Throwable e) {
                Minecraft.getInstance().execute(() -> {
                    loading = false;
                    send(feedback, "§c[CointMusic] Ошибка загрузки трека. Проверь прямую .ogg/.mp3 ссылку или не кликай слишком быстро.");
                    System.out.println("[CointMusic] Failed to load URL: " + cleanUrl);
                    System.out.println("[CointMusic] Load error: " + e.getMessage());
                    e.printStackTrace();
                });
            }
        });
    }

    private static boolean isCached(String cleanUrl) {
        synchronized (AUDIO_CACHE) {
            byte[] cached = AUDIO_CACHE.get(cleanUrl);
            return cached != null && cached.length > 0;
        }
    }

    private record DecodedAudio(ByteBuffer pcmBuffer, AudioFormat format) {
    }

    private static DecodedAudio decodeAudio(String cleanUrl, byte[] audioBytes) throws Exception {
        if (isMp3Url(cleanUrl)) {
            return decodeMp3(audioBytes);
        }

        return decodeOgg(audioBytes);
    }

    private static String stripQuery(String url) {
        if (url == null) {
            return "";
        }

        int queryIndex = url.indexOf('?');
        if (queryIndex >= 0) {
            return url.substring(0, queryIndex);
        }

        return url;
    }

    private static boolean isSupportedAudioOrPlaylistUrl(String cleanPath) {
        if (cleanPath == null || cleanPath.isBlank()) {
            return false;
        }

        return cleanPath.endsWith(".ogg")
                || cleanPath.endsWith(".mp3")
                || cleanPath.endsWith(".m3u")
                || cleanPath.endsWith(".m3u8")
                || cleanPath.endsWith(".pls");
    }

    private static boolean isPlaylistUrl(String url) {
        String cleanPath = stripQuery(url.toLowerCase(Locale.ROOT));

        return cleanPath.endsWith(".m3u")
                || cleanPath.endsWith(".m3u8")
                || cleanPath.endsWith(".pls");
    }

    private static String resolvePlaylistIfNeeded(String cleanUrl) throws Exception {
        if (!isPlaylistUrl(cleanUrl)) {
            return cleanUrl;
        }

        byte[] playlistBytes = getOrDownloadAudio(cleanUrl);
        String playlistText = decodePlaylistText(playlistBytes);

        List<String> urls = extractUrlsFromPlaylist(playlistText);

        if (urls.isEmpty()) {
            throw new IllegalStateException("Playlist does not contain playable URLs");
        }

        return urls.get(0).trim();
    }

    private static String decodePlaylistText(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);

        if (utf8.contains("\uFFFD")) {
            return new String(bytes, Charset.forName("windows-1251"));
        }

        return utf8;
    }

    private static List<String> extractUrlsFromPlaylist(String playlistText) {
        List<String> result = new ArrayList<>();

        if (playlistText == null || playlistText.isBlank()) {
            return result;
        }

        String[] lines = playlistText.replace("\r", "").split("\n");

        for (String rawLine : lines) {
            String line = rawLine.trim();

            if (line.isBlank()) {
                continue;
            }

            if (line.startsWith("#")) {
                continue;
            }

            if (line.toLowerCase(Locale.ROOT).startsWith("file")) {
                int equals = line.indexOf('=');

                if (equals >= 0 && equals < line.length() - 1) {
                    line = line.substring(equals + 1).trim();
                }
            }

            if (line.startsWith("http://") || line.startsWith("https://")) {
                result.add(line);
            }
        }

        return result;
    }

    private static boolean shouldUseStreaming(String url) {
        String cleanPath = stripQuery(url.toLowerCase(Locale.ROOT));

        if (cleanPath.endsWith(".ogg")) {
            return false;
        }

        if (cleanPath.endsWith(".mp3")) {
            return false;
        }

        if (cleanPath.endsWith(".m3u")) {
            return false;
        }

        if (cleanPath.endsWith(".m3u8")) {
            return false;
        }

        if (cleanPath.endsWith(".pls")) {
            return false;
        }

        return true;
    }

    private static boolean isMp3Url(String url) {
        if (url == null) {
            return false;
        }

        String lowered = stripQuery(url.toLowerCase(Locale.ROOT));

        return lowered.endsWith(".mp3");
    }

    private static DecodedAudio decodeOgg(byte[] audioBytes) throws Exception {
        try (InputStream raw = new BufferedInputStream(new ByteArrayInputStream(audioBytes));
             OggAudioStream oggStream = new OggAudioStream(raw)) {

            ByteBuffer pcmBuffer = oggStream.readAll();
            AudioFormat format = oggStream.getFormat();

            return new DecodedAudio(pcmBuffer, format);
        }
    }

    private static DecodedAudio decodeMp3(byte[] audioBytes) throws Exception {
        try (ByteArrayInputStream input = new ByteArrayInputStream(audioBytes)) {
            Bitstream bitstream = new Bitstream(input);
            Decoder decoder = new Decoder();
            ByteArrayOutputStream pcmOutput = new ByteArrayOutputStream();

            int sampleRate = 44100;
            int channels = 2;

            Header header;

            while ((header = bitstream.readFrame()) != null) {
                SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);

                sampleRate = output.getSampleFrequency();
                channels = output.getChannelCount();

                short[] samples = output.getBuffer();
                int length = output.getBufferLength();

                for (int i = 0; i < length; i++) {
                    short sample = samples[i];

                    pcmOutput.write(sample & 0xFF);
                    pcmOutput.write((sample >> 8) & 0xFF);
                }

                bitstream.closeFrame();
            }

            byte[] pcmBytes = pcmOutput.toByteArray();

            if (pcmBytes.length <= 0) {
                throw new IllegalStateException("Decoded MP3 is empty");
            }

            ByteBuffer pcmBuffer = MemoryUtil.memAlloc(pcmBytes.length);
            pcmBuffer.put(pcmBytes);
            pcmBuffer.flip();

            AudioFormat format = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate,
                    16,
                    channels,
                    channels * 2,
                    sampleRate,
                    false
            );

            return new DecodedAudio(pcmBuffer, format);
        }
    }

    private static void startMp3Stream(String streamUrl, Consumer<Component> feedback) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null) {
            loading = false;
            return;
        }

        minecraft.execute(() -> {
            stopNowOnClientThread();

            streaming = true;

            clearOpenAlError();

            sourceId = AL10.alGenSources();
            checkOpenAl("stream alGenSources", feedback);

            AL10.alSourcef(sourceId, AL10.AL_GAIN, volume);
            AL10.alSourcef(sourceId, AL10.AL_PITCH, 1.0f);
            AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(sourceId, AL10.AL_POSITION, 0.0f, 0.0f, 0.0f);
            AL10.alSource3f(sourceId, AL10.AL_VELOCITY, 0.0f, 0.0f, 0.0f);

            playing = true;
            loading = false;

            send(feedback, "§a[CointMusic] Онлайн-радио запущено.");

            streamingFuture = CompletableFuture.runAsync(() -> runMp3StreamLoop(streamUrl, feedback));
        });
    }

    private static void runMp3StreamLoop(String streamUrl, Consumer<Component> feedback) {
        try {
            URLConnection connection = new URL(streamUrl).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("User-Agent", "CointCoreGTO-MusicPlayer/1.0");
            connection.setRequestProperty("Icy-MetaData", "0");

            try (InputStream rawInput = new BufferedInputStream(connection.getInputStream())) {
                Bitstream bitstream = new Bitstream(rawInput);
                Decoder decoder = new Decoder();

                int sampleRate = 44100;
                int channels = 2;

                while (streaming) {
                    ByteArrayOutputStream pcmOutput = new ByteArrayOutputStream();
                    int frames = 0;

                    while (streaming && frames < 24) {
                        Header header = bitstream.readFrame();

                        if (header == null) {
                            break;
                        }

                        SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);

                        sampleRate = output.getSampleFrequency();
                        channels = output.getChannelCount();

                        short[] samples = output.getBuffer();
                        int length = output.getBufferLength();

                        for (int i = 0; i < length; i++) {
                            short sample = samples[i];

                            pcmOutput.write(sample & 0xFF);
                            pcmOutput.write((sample >> 8) & 0xFF);
                        }

                        bitstream.closeFrame();
                        frames++;
                    }

                    byte[] pcmBytes = pcmOutput.toByteArray();

                    if (pcmBytes.length <= 0) {
                        break;
                    }

                    queueStreamChunk(pcmBytes, sampleRate, channels, feedback);
                    waitForStreamQueueRoom();
                }
            }
        } catch (Throwable e) {
            Minecraft minecraft = Minecraft.getInstance();

            if (minecraft != null) {
                minecraft.execute(() -> {
                    if (streaming) {
                        loading = false;
                        playing = false;
                        send(feedback, "§c[CointMusic] Онлайн-радио остановилось: §f" + e.getMessage());
                        System.out.println("[CointMusic] Stream error: " + e.getMessage());
                        e.printStackTrace();
                    }
                });
            }
        }
    }

    private static void queueStreamChunk(byte[] pcmBytes, int sampleRate, int channels, Consumer<Component> feedback) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null || pcmBytes.length <= 0) {
            return;
        }

        ByteBuffer pcmBuffer = MemoryUtil.memAlloc(pcmBytes.length);
        pcmBuffer.put(pcmBytes);
        pcmBuffer.flip();

        minecraft.execute(() -> {
            try {
                if (!streaming || sourceId == 0) {
                    safeFree(pcmBuffer);
                    return;
                }

                cleanupProcessedStreamBuffers();

                int openAlFormat = channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;

                int newBuffer = AL10.alGenBuffers();
                checkOpenAl("stream alGenBuffers", feedback);

                AL10.alBufferData(newBuffer, openAlFormat, pcmBuffer, sampleRate);
                checkOpenAl("stream alBufferData", feedback);

                AL10.alSourceQueueBuffers(sourceId, newBuffer);
                checkOpenAl("stream alSourceQueueBuffers", feedback);

                STREAM_BUFFERS.add(newBuffer);

                int state = AL10.alGetSourcei(sourceId, AL10.AL_SOURCE_STATE);

                if (state != AL10.AL_PLAYING) {
                    AL10.alSourcePlay(sourceId);
                    checkOpenAl("stream alSourcePlay", feedback);
                }

                safeFree(pcmBuffer);
            } catch (Throwable e) {
                safeFree(pcmBuffer);
                System.out.println("[CointMusic] Failed to queue stream chunk: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private static void cleanupProcessedStreamBuffers() {
        if (sourceId == 0) {
            return;
        }

        int processed = AL10.alGetSourcei(sourceId, AL10.AL_BUFFERS_PROCESSED);

        while (processed > 0 && !STREAM_BUFFERS.isEmpty()) {
            int buffer = AL10.alSourceUnqueueBuffers(sourceId);
            AL10.alDeleteBuffers(buffer);
            STREAM_BUFFERS.remove(Integer.valueOf(buffer));
            processed--;
        }
    }

    private static void waitForStreamQueueRoom() {
        AtomicInteger queued = new AtomicInteger(0);
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null) {
            return;
        }

        minecraft.execute(() -> queued.set(STREAM_BUFFERS.size()));

        while (streaming && queued.get() > 8) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }

            minecraft.execute(() -> {
                cleanupProcessedStreamBuffers();
                queued.set(STREAM_BUFFERS.size());
            });
        }
    }

    private static byte[] getOrDownloadAudio(String cleanUrl) throws Exception {
        synchronized (AUDIO_CACHE) {
            byte[] cached = AUDIO_CACHE.get(cleanUrl);

            if (cached != null && cached.length > 0) {
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

        return data;
    }

    private static void putInCache(String cleanUrl, byte[] data) {
        synchronized (AUDIO_CACHE) {
            byte[] old = AUDIO_CACHE.remove(cleanUrl);

            if (old != null) {
                currentCacheSizeBytes -= old.length;
            }

            AUDIO_CACHE.put(cleanUrl, data);
            currentCacheSizeBytes += data.length;

            trimCacheIfNeeded();
        }
    }

    private static void trimCacheIfNeeded() {
        while (
                AUDIO_CACHE.size() > MAX_CACHED_TRACKS
                        || currentCacheSizeBytes > MAX_CACHE_SIZE_BYTES
        ) {
            Map.Entry<String, byte[]> eldest = AUDIO_CACHE.entrySet().iterator().next();

            String removedUrl = eldest.getKey();
            byte[] removedData = eldest.getValue();

            AUDIO_CACHE.remove(removedUrl);

            if (removedData != null) {
                currentCacheSizeBytes -= removedData.length;
            }
        }

        if (currentCacheSizeBytes < 0) {
            currentCacheSizeBytes = 0;
        }
    }

    public static void clearCache() {
        synchronized (AUDIO_CACHE) {
            AUDIO_CACHE.clear();
            currentCacheSizeBytes = 0;
        }
    }

    public static String getCacheStats() {
        synchronized (AUDIO_CACHE) {
            return "tracks=" + AUDIO_CACHE.size()
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
            streaming = false;

            if (sourceId != 0) {
                AL10.alSourceStop(sourceId);

                cleanupProcessedStreamBuffers();

                AL10.alSourcei(sourceId, AL10.AL_BUFFER, 0);
                AL10.alDeleteSources(sourceId);
                sourceId = 0;
            }

            if (bufferId != 0) {
                AL10.alDeleteBuffers(bufferId);
                bufferId = 0;
            }

            for (Integer streamBuffer : new ArrayList<>(STREAM_BUFFERS)) {
                if (streamBuffer != null && streamBuffer != 0) {
                    AL10.alDeleteBuffers(streamBuffer);
                }
            }

            STREAM_BUFFERS.clear();

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
        send(feedback, "§a[CointMusic] Воспроизведение началось.");
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