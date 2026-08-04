package cn.li.mc1211.shim;

import cn.li.mc1211.entity.logic.IMobDeathLogic;
import cn.li.mc1211.entity.ScriptedMobEntity;
import net.minecraft.world.damagesource.DamageSource;
import clojure.lang.IFn;

public class FnMobDeathLogic implements IMobDeathLogic {
    private final IFn fn;
    public FnMobDeathLogic(IFn fn) { this.fn = fn; }
    @Override public void onDie(ScriptedMobEntity mob, DamageSource source) { fn.invoke(mob, source); }
}
