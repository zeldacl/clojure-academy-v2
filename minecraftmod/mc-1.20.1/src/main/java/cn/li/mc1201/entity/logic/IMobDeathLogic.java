package cn.li.mc1201.entity.logic;

import cn.li.mc1201.entity.ScriptedMobEntity;
import net.minecraft.world.damagesource.DamageSource;

public interface IMobDeathLogic {
    void onDie(ScriptedMobEntity mob, DamageSource source);
}
