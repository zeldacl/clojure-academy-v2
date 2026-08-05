package cn.li.mc262.entity.logic;

import cn.li.mc262.entity.ScriptedMobEntity;
import net.minecraft.world.damagesource.DamageSource;

public interface IMobDeathLogic {
    void onDie(ScriptedMobEntity mob, DamageSource source);
}
