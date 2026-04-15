package cn.li.forge1201.entity.effect;

import cn.li.forge1201.entity.ModEntities;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

public class IntensifyEffectEntity extends Entity {
    private static final int LIFE_TICKS = 15;
    private static final int ARC_LIFE_TICKS = 3;

    // Original-like tier sequence from 1.12 EntityIntensifyEffect#initEvents.
    private static final double[] TIER_HEIGHTS = {2.0D, 1.8D, 1.5D, 1.0D, 0.5D, 0.0D, -0.1D};
    private static final int[] TIER_DELAYS = {0, 1, 3, 4, 6, 7, 8};

    private UUID ownerUuid;
    private int age;
    private final List<ArcData> activeArcs = new ArrayList<>();

    public IntensifyEffectEntity(EntityType<? extends IntensifyEffectEntity> entityType, Level level) {
        super(entityType, level);
        this.noPhysics = true;
    }

    public static IntensifyEffectEntity create(Level level, Player owner) {
        IntensifyEffectEntity entity = new IntensifyEffectEntity(ModEntities.INTENSIFY_EFFECT.get(), level);
        entity.ownerUuid = owner.getUUID();
        entity.setPos(owner.getX(), owner.getY() + 1.0, owner.getZ());
        return entity;
    }

    @Override
    protected void defineSynchedData() {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.hasUUID("owner")) {
            ownerUuid = tag.getUUID("owner");
        }
        age = tag.getInt("age");
        activeArcs.clear();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (ownerUuid != null) {
            tag.putUUID("owner", ownerUuid);
        }
        tag.putInt("age", age);
    }

    @Override
    public void tick() {
        super.tick();

        Player owner = ownerUuid == null ? null : level().getPlayerByUUID(ownerUuid);
        if (owner != null) {
            setPos(owner.getX(), owner.getY() + 1.0, owner.getZ());
        }

        if (level().isClientSide() && level() instanceof ClientLevel clientLevel) {
            spawnTieredArcs();
            spawnArcParticles(clientLevel);
            tickArcLifetimes();
        }

        age++;
        if (age >= LIFE_TICKS) {
            discard();
        }
    }

    public int getAgeTicks() {
        return age;
    }

    public List<ArcData> getActiveArcs() {
        return Collections.unmodifiableList(activeArcs);
    }

    private void spawnTieredArcs() {
        for (int i = 0; i < TIER_DELAYS.length; i++) {
            if (age == TIER_DELAYS[i]) {
                spawnTierAtHeight(TIER_HEIGHTS[i]);
            }
        }
    }

    private void spawnTierAtHeight(double height) {
        int batch = 3 + random.nextInt(2);
        while (batch-- > 0) {
            double radius = 0.5D + random.nextDouble() * 0.1D;
            double theta = random.nextDouble() * Math.PI * 2.0D;
            double ox = radius * Math.sin(theta);
            double oz = radius * Math.cos(theta);
            activeArcs.add(createArcData(ox, height, oz));
        }
    }

    private ArcData createArcData(double ox, double oy, double oz) {
        float phase = random.nextFloat() * ((float) Math.PI * 2.0F);
        float flickerSeed = random.nextFloat() * 13.0F;

        // Main SubArc-like trunk: fast rise + rebound and damped side oscillation.
        int mainPoints = 7;
        float[][] main = new float[mainPoints][3];
        float baseTheta = (float) Math.atan2(ox, oz);
        float sideAmp = 0.06F + (random.nextFloat() * 0.05F);
        float rise = 0.26F + (random.nextFloat() * 0.1F);
        float rebound = 0.12F + (random.nextFloat() * 0.07F);

        for (int i = 0; i < mainPoints; i++) {
            float t = (float) i / (float) (mainPoints - 1);
            float damp = 1.0F - (0.65F * t);
            float wobble = (float) Math.sin((t * 6.8F) + phase) * sideAmp * damp;
            float reboundCurve = (float) Math.sin(t * Math.PI) * rebound;
            float y = (float) (oy + (rise * t) - (reboundCurve * t));

            // Use local radial tangent to make the arc bend around the player body.
            float radialX = (float) Math.sin(baseTheta);
            float radialZ = (float) Math.cos(baseTheta);
            float tangentX = radialZ;
            float tangentZ = -radialX;

            float pull = 1.0F - (0.22F * t);
            main[i][0] = (float) (ox * pull + tangentX * wobble);
            main[i][1] = y;
            main[i][2] = (float) (oz * pull + tangentZ * wobble);
        }

        int branchCount = 1 + random.nextInt(2);
        float[][][] strands = new float[1 + branchCount][][];
        strands[0] = main;

        for (int b = 0; b < branchCount; b++) {
            int attachIdx = 2 + random.nextInt(mainPoints - 3);
            float[] attach = main[attachIdx];
            int branchPoints = 3 + random.nextInt(2);
            float[][] branch = new float[branchPoints][3];

            float branchTheta = baseTheta + ((b == 0 ? 1.0F : -1.0F) * (0.55F + random.nextFloat() * 0.4F));
            float branchLen = 0.12F + random.nextFloat() * 0.09F;

            for (int i = 0; i < branchPoints; i++) {
                float t = (float) i / (float) (branchPoints - 1);
                float forkWobble = (float) Math.sin((phase * 0.7F) + (t * 5.2F)) * 0.025F;
                branch[i][0] = attach[0] + (float) Math.sin(branchTheta) * branchLen * t + forkWobble;
                branch[i][1] = attach[1] + (0.05F * t) - (0.03F * t * t);
                branch[i][2] = attach[2] + (float) Math.cos(branchTheta) * branchLen * t - forkWobble;
            }

            strands[1 + b] = branch;
        }

        return new ArcData(strands, ARC_LIFE_TICKS, phase, flickerSeed);
    }

    private void tickArcLifetimes() {
        Iterator<ArcData> it = activeArcs.iterator();
        while (it.hasNext()) {
            ArcData arc = it.next();
            arc.lifeTicks--;
            if (arc.lifeTicks <= 0) {
                it.remove();
            }
        }
    }

    private void spawnArcParticles(ClientLevel level) {
        for (ArcData arc : activeArcs) {
            float[] p0 = arc.strands[0][0];
            double px = getX() + p0[0];
            double py = getY() + p0[1];
            double pz = getZ() + p0[2];
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, px, py, pz, 0.0, 0.0, 0.0);

            // Brief sparks at branch tips improve SubArc fork readability.
            for (int i = 1; i < arc.strands.length; i++) {
                float[][] branch = arc.strands[i];
                float[] tip = branch[branch.length - 1];
                level.addParticle(ParticleTypes.ELECTRIC_SPARK,
                        getX() + tip[0], getY() + tip[1], getZ() + tip[2],
                        0.0, 0.0, 0.0);
            }
        }
    }

    public static final class ArcData {
        public final float[][][] strands;
        public int lifeTicks;
        public final float phase;
        public final float flickerSeed;

        private ArcData(float[][][] strands, int lifeTicks, float phase, float flickerSeed) {
            this.strands = strands;
            this.lifeTicks = lifeTicks;
            this.phase = phase;
            this.flickerSeed = flickerSeed;
        }
    }
}
