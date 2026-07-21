package cn.li.mc1201.client.audio;

import net.minecraft.client.Minecraft;
import org.lwjgl.openal.AL10;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.libc.LibCStdlib;

import java.nio.IntBuffer;
import java.nio.ShortBuffer;

/**
 * Minimal external-.ogg playback for the terminal Media Player app's
 * "external tracks" feature (upstream: user-supplied files, no Minecraft
 * resource-pack registration involved). Reuses Minecraft's own already-current
 * OpenAL context on the client thread rather than managing a device/context.
 *
 * Fidelity: play/stop only — stopping and playing again always restarts from
 * the beginning (no true seek/resume-from-position, matching upstream's
 * SoundSystem behavior only partially — see media_player app docs).
 */
public final class ExternalOggPlayer {

    private static volatile int currentSource = 0;
    private static volatile int currentBuffer = 0;
    private static volatile float currentVolume = 1.0f;

    private ExternalOggPlayer() {}

    public static void play(String filePath, float volume) {
        currentVolume = volume;
        Thread decodeThread = new Thread(() -> decodeAndPlay(filePath, volume), "ac-media-decode");
        decodeThread.setDaemon(true);
        decodeThread.start();
    }

    private static void decodeAndPlay(String filePath, float volume) {
        try {
            final ShortBuffer pcm;
            final int channels;
            final int sampleRate;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer channelsBuf = stack.mallocInt(1);
                IntBuffer sampleRateBuf = stack.mallocInt(1);
                pcm = STBVorbis.stb_vorbis_decode_filename(filePath, channelsBuf, sampleRateBuf);
                if (pcm == null) {
                    return;
                }
                channels = channelsBuf.get(0);
                sampleRate = sampleRateBuf.get(0);
            }
            Minecraft.getInstance().execute(() -> {
                try {
                    stopInternal();
                    int format = channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
                    int buffer = AL10.alGenBuffers();
                    AL10.alBufferData(buffer, format, pcm, sampleRate);
                    int source = AL10.alGenSources();
                    AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
                    AL10.alSourcef(source, AL10.AL_GAIN, volume);
                    AL10.alSourcePlay(source);
                    currentSource = source;
                    currentBuffer = buffer;
                } finally {
                    LibCStdlib.free(pcm);
                }
            });
        } catch (Throwable ignored) {
            // Decode/playback failure (missing codec support, bad file, no AL
            // context yet, ...) — leave nothing playing rather than crash.
        }
    }

    public static void stop() {
        Minecraft.getInstance().execute(ExternalOggPlayer::stopInternal);
    }

    private static void stopInternal() {
        if (currentSource != 0) {
            AL10.alSourceStop(currentSource);
            AL10.alDeleteSources(currentSource);
            currentSource = 0;
        }
        if (currentBuffer != 0) {
            AL10.alDeleteBuffers(currentBuffer);
            currentBuffer = 0;
        }
    }

    public static void setVolume(float volume) {
        currentVolume = volume;
        Minecraft.getInstance().execute(() -> {
            if (currentSource != 0) {
                AL10.alSourcef(currentSource, AL10.AL_GAIN, volume);
            }
        });
    }

    public static float getVolume() {
        return currentVolume;
    }

    public static boolean isPlaying() {
        int source = currentSource;
        if (source == 0) {
            return false;
        }
        try {
            return AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
