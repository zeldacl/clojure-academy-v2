package cn.li.mc262.entity.logic;

import cn.li.mc262.entity.ScriptedMobEntity;
import net.minecraft.world.damagesource.DamageSource;

public interface IMobLootLogic {
    /**
     * Return true when loot was handled and vanilla {@code dropFromLootTable} should be skipped.
     */
    boolean dropLoot(ScriptedMobEntity mob, DamageSource source, boolean recentHit);
}
