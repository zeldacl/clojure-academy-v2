package cn.li.forge1201.entity.effect.hooks;

import cn.li.forge1201.entity.ScriptedEffectEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;

import java.util.Iterator;
import java.util.List;

public final class IntensifyArcsEffectHook implements ScriptedEffectHook {
    private static final int ARC_LIFE_TICKS = 3;

    // Original-like tier sequence from 1.12 EntityIntensifyEffect#initEvents.
    private static final double[] TIER_HEIGHTS = {2.0D, 1.8D, 1.5D, 1.0D, 0.5D, 0.0D, -0.1D};
    private static final int[] TIER_DELAYS = {0, 1, 3, 4, 6, 7, 8};

    @Override
    public void onClientTick(ScriptedEffectEntity entity, ClientLevel level) {
        spawnTieredArcs(entity);
        spawnArcParticles(entity, level);
        tickArcLifetimes(entity);
    }

    private void spawnTieredArcs(ScriptedEffectEntity entity) {
        int age = entity.getAgeTicks();
        for (int i = 0; i < TIER_DELAYS.length; i++) {
            if (age == TIER_DELAYS[i]) {
                spawnTierAtHeight(entity, TIER_HEIGHTS[i]);
            }
        }
    }

    private void spawnTierAtHeight(ScriptedEffectEntity entity, double height) {
        RandomSource random = entity.getEffectRandom();
        List<ScriptedEffectEntity.ArcData> arcs = entity.mutableActiveArcs();
        int batch = 3 + random.nextInt(2);
        while (batch-- > 0) {
            double radius = 0.5D + random.nextDouble() * 0.1D;
            double theta = random.nextDouble() * Math.PI * 2.0D;
            double ox = radius * Math.sin(theta);
            double oz = radius * Math.cos(theta);
            arcs.add(createArcData(entity, ox, height, oz));
        }
    }

    private ScriptedEffectEntity.ArcData createArcData(ScriptedEffectEntity entity, double ox, double oy, double oz) {
        RandomSource random = entity.getEffectRandom();
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

        return new ScriptedEffectEntity.ArcData(strands, ARC_LIFE_TICKS, phase, flickerSeed);
    }

    private void tickArcLifetimes(ScriptedEffectEntity entity) {
        Iterator<ScriptedEffectEntity.ArcData> it = entity.mutableActiveArcs().iterator();
        while (it.hasNext()) {
            ScriptedEffectEntity.ArcData arc = it.next();
            arc.lifeTicks--;
            if (arc.lifeTicks <= 0) {
                it.remove();
            }
        }
    }

    private void spawnArcParticles(ScriptedEffectEntity entity, ClientLevel level) {
        for (ScriptedEffectEntity.ArcData arc : entity.mutableActiveArcs()) {
            float[] p0 = arc.strands[0][0];
            double px = entity.getX() + p0[0];
            double py = entity.getY() + p0[1];
            double pz = entity.getZ() + p0[2];
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, px, py, pz, 0.0, 0.0, 0.0);

            // Brief sparks at branch tips improve SubArc fork readability.
            for (int i = 1; i < arc.strands.length; i++) {
                float[][] branch = arc.strands[i];
                float[] tip = branch[branch.length - 1];
                level.addParticle(ParticleTypes.ELECTRIC_SPARK,
                        entity.getX() + tip[0], entity.getY() + tip[1], entity.getZ() + tip[2],
                        0.0, 0.0, 0.0);
            }
        }
    }
}
