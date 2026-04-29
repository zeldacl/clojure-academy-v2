package cn.li.forge1201.entity.effect.hooks;

import cn.li.forge1201.entity.ScriptedEffectEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side visual hook for EntityCoinThrowing.
 *
 * Implements parabolic arc physics matching upstream EntityCoinThrowing:
 *   - INIT_VEL = 0.92 blocks/tick upward
 *   - GRAVITY  = 0.06 blocks/tick²
 *   - Coin follows owner XZ every tick
 *   - Entity discards when coin falls below owner.getY() on the way down
 */
public final class CoinThrowingEffectHook implements ScriptedEffectHook {
    private static final double GRAVITY = 0.06D;
    private static final double INIT_VEL = 0.92D;

    private final Map<UUID, CoinState> states = new ConcurrentHashMap<>();

    @Override
    public void onClientTick(ScriptedEffectEntity entity, ClientLevel level) {
        if (!entity.isAlive()) {
            states.remove(entity.getUUID());
            return;
        }

        Player owner = entity.getOwnerPlayer();
        if (owner == null) {
            states.remove(entity.getUUID());
            entity.discard();
            return;
        }

        CoinState state = states.computeIfAbsent(entity.getUUID(),
                k -> new CoinState(entity.getY(), INIT_VEL));

        state.velY -= GRAVITY;
        state.currentY += state.velY;

        entity.setPos(owner.getX(), state.currentY, owner.getZ());

        // Discard when coin has peaked and returned below owner feet
        if (state.currentY < owner.getY() && state.velY < 0.0D) {
            states.remove(entity.getUUID());
            entity.discard();
        }
    }

    private static final class CoinState {
        double currentY;
        double velY;

        CoinState(double initY, double initVel) {
            this.currentY = initY;
            this.velY = initVel;
        }
    }
}
