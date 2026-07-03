package cn.li.mc1201.shim;

import cn.li.mc1201.entity.logic.IMobTickLogic;
import cn.li.mc1201.entity.ScriptedMobEntity;
import clojure.lang.IFn;

public class FnMobTickLogic implements IMobTickLogic {
    private final IFn fn;
    public FnMobTickLogic(IFn fn) { this.fn = fn; }
    @Override public void aiStep(ScriptedMobEntity mob) { fn.invoke(mob); }
}
