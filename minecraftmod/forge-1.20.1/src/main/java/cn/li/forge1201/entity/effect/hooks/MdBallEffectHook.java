package cn.li.forge1201.entity.effect.hooks;

import cn.li.forge1201.entity.ScriptedEffectEntity;
import cn.li.mc1201.entity.spec.ScriptedEffectSpec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MdBallEffectHook implements ScriptedEffectHook {
    private static final double DEFAULT_RANGE_FROM = 0.8D;
    private static final double DEFAULT_RANGE_TO = 1.3D;
    private static final double DEFAULT_Y_FROM = -1.2D;
    private static final double DEFAULT_Y_TO = 0.2D;
    private static final double DEFAULT_WOBBLE_XZ = 0.03D;
    private static final double DEFAULT_WOBBLE_Y = 0.04D;
    private static final double DEFAULT_PHASE_STEP = 0.18D;
    private static final double DEFAULT_WOBBLE_Y_FREQ = 1.4D;
    private static final double DEFAULT_WOBBLE_Y_PHASE_SHIFT = Math.PI / 3.5D;
    private static final double DEFAULT_THETA_SPREAD_FACTOR = 0.45D;

    private final Map<UUID, BallState> states = new ConcurrentHashMap<>();

    @Override
    public void onClientTick(ScriptedEffectEntity entity, ClientLevel level) {
        if (!entity.isAlive()) {
            states.remove(entity.getUUID());
            return;
        }

        Player owner = entity.getOwnerPlayer();
        if (owner == null) {
            return;
        }

        BallState state = states.computeIfAbsent(entity.getUUID(),
        key -> BallState.create(entity, owner, entity.getEffectRandom()));
        ScriptedEffectSpec effectSpec = entity.getEffectSpec();
        double phaseStep = effectSpec == null
            ? DEFAULT_PHASE_STEP
            : effectSpec.getDoubleParam("phase-step", DEFAULT_PHASE_STEP);
        double wobbleYFreq = effectSpec == null
            ? DEFAULT_WOBBLE_Y_FREQ
            : effectSpec.getDoubleParam("wobble-y-freq", DEFAULT_WOBBLE_Y_FREQ);
        double wobbleYPhaseShift = effectSpec == null
            ? DEFAULT_WOBBLE_Y_PHASE_SHIFT
            : effectSpec.getDoubleParam("wobble-y-phase-shift", DEFAULT_WOBBLE_Y_PHASE_SHIFT);
        state.phase += phaseStep;

        double wobbleXz = effectSpec == null
            ? DEFAULT_WOBBLE_XZ
            : effectSpec.getDoubleParam("wobble-xz", DEFAULT_WOBBLE_XZ);
        double wobbleYScale = effectSpec == null
            ? DEFAULT_WOBBLE_Y
            : effectSpec.getDoubleParam("wobble-y", DEFAULT_WOBBLE_Y);

        double wobbleX = wobbleXz * Math.sin(state.phase);
        double wobbleZ = wobbleXz * Math.cos(state.phase);
        double wobbleY = wobbleYScale * Math.cos(state.phase * wobbleYFreq + wobbleYPhaseShift);

        entity.setPos(
                owner.getX() + state.subX + wobbleX,
                owner.getY() + state.subY + wobbleY,
                owner.getZ() + state.subZ + wobbleZ
        );
        entity.setYRot(owner.getYRot());
        entity.setXRot(owner.getXRot());
    }

    private static final class BallState {
        private final double subX;
        private final double subY;
        private final double subZ;
        private double phase;

        private BallState(double subX, double subY, double subZ, double phase) {
            this.subX = subX;
            this.subY = subY;
            this.subZ = subZ;
            this.phase = phase;
        }

        private static BallState create(ScriptedEffectEntity entity, Player owner, RandomSource random) {
            ScriptedEffectSpec effectSpec = entity.getEffectSpec();
            double baseYaw = Math.toRadians(-owner.getYRot());
            double thetaSpreadFactor = effectSpec == null
                ? DEFAULT_THETA_SPREAD_FACTOR
                : effectSpec.getDoubleParam("theta-spread-factor", DEFAULT_THETA_SPREAD_FACTOR);
            double thetaSpread = Math.PI * thetaSpreadFactor;
            double theta = baseYaw + range(random, -thetaSpread, thetaSpread);
            double rangeFrom = effectSpec == null ? DEFAULT_RANGE_FROM : effectSpec.getDoubleParam("range-from", DEFAULT_RANGE_FROM);
            double rangeTo = effectSpec == null ? DEFAULT_RANGE_TO : effectSpec.getDoubleParam("range-to", DEFAULT_RANGE_TO);
            double yFrom = effectSpec == null ? DEFAULT_Y_FROM : effectSpec.getDoubleParam("y-from", DEFAULT_Y_FROM);
            double yTo = effectSpec == null ? DEFAULT_Y_TO : effectSpec.getDoubleParam("y-to", DEFAULT_Y_TO);
            double radialRange = range(random, rangeFrom, rangeTo);
            double subX = Math.sin(theta) * radialRange;
            double subZ = Math.cos(theta) * radialRange;
            double subY = range(random, yFrom, yTo);
            double phase = range(random, 0.0D, Math.PI * 2.0D);
            return new BallState(subX, subY, subZ, phase);
        }

        private static double range(RandomSource random, double min, double max) {
            return min + (max - min) * random.nextDouble();
        }
    }
}
