package cn.li.mc1201.client.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active PositionalLoopSoundInstances by an arbitrary caller-chosen
 * key, so ability code (which never holds a Java reference) can start,
 * reposition, and stop a specific loop by key alone.
 */
public final class LoopingSoundRegistry {

    private static final Map<String, PositionalLoopSoundInstance> ACTIVE = new ConcurrentHashMap<>();

    private LoopingSoundRegistry() {}

    public static void start(String key, String soundId, float volume, float pitch, double x, double y, double z) {
        stop(key);
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        int sep = soundId.indexOf(':');
        ResourceLocation loc = sep < 0
            ? ResourceLocation.fromNamespaceAndPath("minecraft", soundId)
            : ResourceLocation.fromNamespaceAndPath(soundId.substring(0, sep), soundId.substring(sep + 1));
        SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(loc);
        if (event == null) {
            return;
        }
        PositionalLoopSoundInstance instance =
                new PositionalLoopSoundInstance(event, SoundSource.AMBIENT, volume, pitch, x, y, z);
        ACTIVE.put(key, instance);
        mc.getSoundManager().play(instance);
    }

    public static void updatePosition(String key, double x, double y, double z) {
        PositionalLoopSoundInstance instance = ACTIVE.get(key);
        if (instance != null) {
            instance.updatePosition(x, y, z);
        }
    }

    public static void stop(String key) {
        PositionalLoopSoundInstance instance = ACTIVE.remove(key);
        if (instance == null) {
            return;
        }
        instance.markStopped();
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            SoundManager soundManager = mc.getSoundManager();
            soundManager.stop(instance);
        }
    }

    public static void stopAll() {
        for (String key : ACTIVE.keySet().toArray(new String[0])) {
            stop(key);
        }
    }
}
