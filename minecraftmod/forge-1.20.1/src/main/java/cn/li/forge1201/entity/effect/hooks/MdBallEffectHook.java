package cn.li.forge1201.entity.effect.hooks;

import cn.li.forge1201.entity.ScriptedEffectEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MdBallEffectHook implements ScriptedEffectHook {
    private static final double RANGE_FROM = 0.8D;
    private static final double RANGE_TO = 1.3D;
    private static final double Y_FROM = -1.2D;
    private static final double Y_TO = 0.2D;
    private static final double WOBBLE_XZ = 0.03D;
    private static final double WOBBLE_Y = 0.04D;

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
                key -> BallState.create(owner, entity.getEffectRandom()));
        state.phase += 0.18D;

        double wobbleX = WOBBLE_XZ * Math.sin(state.phase);
        double wobbleZ = WOBBLE_XZ * Math.cos(state.phase);
        double wobbleY = WOBBLE_Y * Math.cos(state.phase * 1.4D + Math.PI / 3.5D);

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

        private static BallState create(Player owner, RandomSource random) {
            double baseYaw = Math.toRadians(-owner.getYRot());
            double theta = baseYaw + range(random, -Math.PI * 0.45D, Math.PI * 0.45D);
            double range = range(random, RANGE_FROM, RANGE_TO);
            double subX = Math.sin(theta) * range;
            double subZ = Math.cos(theta) * range;
            double subY = range(random, Y_FROM, Y_TO);
            double phase = range(random, 0.0D, Math.PI * 2.0D);
            return new BallState(subX, subY, subZ, phase);
        }

        private static double range(RandomSource random, double min, double max) {
            return min + (max - min) * random.nextDouble();
        }
    }
}
