package cn.li.mc1201.entity.logic;

import cn.li.mc1201.entity.ScriptedMobEntity;
import net.minecraft.world.damagesource.DamageSource;

public interface IMobHurtLogic {
    /**
     * Return {@link Float#NaN} to cancel hurt entirely; otherwise return (possibly modified) damage.
     */
    float onIncomingDamage(ScriptedMobEntity mob, DamageSource source, float amount);
}
