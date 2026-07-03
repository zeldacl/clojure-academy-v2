package cn.li.mc1201.shim;

import cn.li.mc1201.entity.logic.IMobLootLogic;
import cn.li.mc1201.entity.ScriptedMobEntity;
import net.minecraft.world.damagesource.DamageSource;
import clojure.lang.IFn;

public class FnMobLootLogic implements IMobLootLogic {
    private final IFn fn;
    public FnMobLootLogic(IFn fn) { this.fn = fn; }
    @Override public boolean dropLoot(ScriptedMobEntity mob, DamageSource source, boolean recentHit) {
        Object r = fn.invoke(mob, source, recentHit);
        return r instanceof Boolean ? (Boolean) r : false;
    }
}
