package cn.li.mc1201.shim;

import cn.li.mc1201.entity.logic.IMobHurtLogic;
import cn.li.mc1201.entity.ScriptedMobEntity;
import net.minecraft.world.damagesource.DamageSource;
import clojure.lang.IFn;

public class FnMobHurtLogic implements IMobHurtLogic {
    private final IFn fn;
    public FnMobHurtLogic(IFn fn) { this.fn = fn; }
    @Override public float onIncomingDamage(ScriptedMobEntity mob, DamageSource src, float amt) {
        Object r = fn.invoke(mob, src, amt);
        return r instanceof Number ? ((Number) r).floatValue() : Float.NaN;
    }
}
