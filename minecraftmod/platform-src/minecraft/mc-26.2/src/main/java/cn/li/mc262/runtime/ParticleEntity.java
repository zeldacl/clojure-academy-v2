package cn.li.mc262.runtime;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.level.Level;

public final class ParticleEntity {
    private ParticleEntity() {}
    public static void spawn(Level level, ParticleOptions options, double x, double y, double z) {
        if (level.isClientSide()) {
            level.addParticle(options, x, y, z, 0, 0, 0);
        }
    }
}
