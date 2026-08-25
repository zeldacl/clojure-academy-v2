package cn.li.mcmod.runtime.vfx;

/** Allocation-free particle update kernels used by the neutral VFX runtime. */
public final class ParticleKernel {
    private ParticleKernel() {}

    /** Integrate a reserved range and compact expired particles in-place. */
    public static int integrate(ParticleBuffer particles, int start, int end, float deltaSeconds) {
        if (particles == null) throw new NullPointerException("particles");
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0f) throw new IllegalArgumentException("deltaSeconds");
        int limit = Math.min(Math.max(end, 0), particles.size());
        int index = Math.max(0, start);
        float[] px = particles.positionX(), py = particles.positionY(), pz = particles.positionZ();
        float[] vx = particles.velocityX(), vy = particles.velocityY(), vz = particles.velocityZ();
        float[] age = particles.age(), lifetime = particles.lifetime();
        while (index < limit) {
            float nextAge = age[index] + deltaSeconds;
            age[index] = nextAge;
            if (lifetime[index] > 0f && nextAge >= lifetime[index]) {
                particles.swapRemove(index);
                limit--;
                continue;
            }
            px[index] += vx[index] * deltaSeconds;
            py[index] += vy[index] * deltaSeconds;
            pz[index] += vz[index] * deltaSeconds;
            index++;
        }
        return limit - Math.max(0, start);
    }
}