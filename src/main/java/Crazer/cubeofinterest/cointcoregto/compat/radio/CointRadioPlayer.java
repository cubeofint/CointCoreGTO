package Crazer.cubeofinterest.cointcoregto.compat.radio;

import com.mojang.blaze3d.audio.OggAudioStream;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.openal.AL10;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class CointRadioPlayer {
    private static int sourceId = 0;
    private static int bufferId = 0;
    private static float volume = 1.0f;
    private static ByteBuffer currentPcmBuffer;

    private CointRadioPlayer() {
    }

    public static void play(String url, Consumer<Component> feedback) {
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

        stop();

        send(feedback, "§a[CointMusic] Загружаю музыку...");

        CompletableFuture.runAsync(() -> {
            try {
                URLConnection connection = new URL(cleanUrl).openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(30_000);
                connection.setRequestProperty("User-Agent", "CointCoreGTO-MusicPlayer/1.0");

                try (InputStream raw = new BufferedInputStream(connection.getInputStream());
                     OggAudioStream oggStream = new OggAudioStream(raw)) {

                    ByteBuffer pcmBuffer = oggStream.readAll();
                    AudioFormat format = oggStream.getFormat();

                    Minecraft.getInstance().execute(() -> {
                        try {
                            startDecodedAudio(pcmBuffer, format, feedback);
                        } catch (Throwable e) {
                            safeFree(pcmBuffer);
                            send(feedback, "§c[CointMusic] Не удалось запустить звук: §f" + e.getMessage());
                            e.printStackTrace();
                        }
                    });
                }
            } catch (Throwable e) {
                Minecraft.getInstance().execute(() -> {
                    send(feedback, "§c[CointMusic] Ошибка загрузки: §f" + e.getMessage());
                    e.printStackTrace();
                });
            }
        });
    }

    public static void stop() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null) {
            return;
        }

        minecraft.execute(() -> {
            try {
                if (sourceId != 0) {
                    AL10.alSourceStop(sourceId);
                    AL10.alDeleteSources(sourceId);
                    sourceId = 0;
                }

                if (bufferId != 0) {
                    AL10.alDeleteBuffers(bufferId);
                    bufferId = 0;
                }

                if (currentPcmBuffer != null) {
                    safeFree(currentPcmBuffer);
                    currentPcmBuffer = null;
                }
            } catch (Throwable e) {
                System.out.println("[CointMusic] Failed to stop audio: " + e.getMessage());
            }
        });
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

    private static void startDecodedAudio(ByteBuffer pcmBuffer, AudioFormat format, Consumer<Component> feedback) {
        clearOpenAlError();

        if (sourceId != 0 || bufferId != 0 || currentPcmBuffer != null) {
            stop();
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

        AL10.alSourcei(sourceId, AL10.AL_LOOPING, AL10.AL_FALSE);

        AL10.alSourcei(sourceId, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSource3f(sourceId, AL10.AL_POSITION, 0.0f, 0.0f, 0.0f);
        AL10.alSource3f(sourceId, AL10.AL_VELOCITY, 0.0f, 0.0f, 0.0f);

        checkOpenAl("source setup", feedback);

        AL10.alSourcePlay(sourceId);
        checkOpenAl("alSourcePlay", feedback);

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