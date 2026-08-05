package cn.li.mc262.client.audio;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class LoopingSoundRegistry {
    private LoopingSoundRegistry() {}
    public static void start(String key, String soundId, float volume, float pitch, double x, double y, double z) {}
    public static void startFollowingPlayer(String key, String soundId, float volume, float pitch) {}
    public static void startFollowingPlayer(String key, String soundId, float volume, float pitch, Object player) {}
    public static void updatePosition(String key, double x, double y, double z) {}
    public static void stop(String key) {}
    public static void stopAll() {}
}
