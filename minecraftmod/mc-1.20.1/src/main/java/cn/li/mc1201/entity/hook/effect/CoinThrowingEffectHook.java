package cn.li.mc1201.entity.hook.effect;

import cn.li.mc1201.entity.ScriptedEffectEntity;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Client-side visual hook for EntityCoinThrowing.
 *
 * Coin physics are owned by ScriptedEffectEntity tick to keep server/client state aligned.
 * This hook remains for compatibility with existing hook ids and spec wiring.
 */
public final class CoinThrowingEffectHook implements ScriptedEffectHook {
    @Override
    public void onClientTick(ScriptedEffectEntity entity, ClientLevel level) {
        // no-op by design
    }
}
