package cn.li.mc1201.entity.logic;

import cn.li.mc1201.entity.ScriptedMobEntity;
import net.minecraft.world.damagesource.DamageSource;

public interface IMobLootLogic {
    /**
     * Return true when loot was handled and vanilla {@code dropFromLootTable} should be skipped.
     */
    boolean dropLoot(ScriptedMobEntity mob, DamageSource source, boolean recentHit);
}
