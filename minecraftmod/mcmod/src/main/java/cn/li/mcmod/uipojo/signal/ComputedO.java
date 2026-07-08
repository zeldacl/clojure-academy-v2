package cn.li.mcmod.uipojo.signal;

import clojure.lang.Cons;
import clojure.lang.IFn;
import clojure.lang.ISeq;
import java.util.ArrayList;

public final class ComputedO implements ISigO, IDep, ISupportsOuts {

    private final ISigO s0;
    private final ISigO s1;
    private final ISigO s2;
    private final ISigO[] moreSources;
    private final IFn f;
    private final int nSources;
    private Object value;
    private boolean dirty;
    private final ArrayList<IDep> outs;

    public ComputedO(ISigO s0,
                     ISigO s1,
                     ISigO s2,
                     ISigO[] moreSources,
                     IFn f,
                     int nSources,
                     Object value,
                     boolean dirty,
                     ArrayList<IDep> outs) {
        this.s0 = s0;
        this.s1 = s1;
        this.s2 = s2;
        this.moreSources = moreSources;
        this.f = f;
        this.nSources = nSources;
        this.value = value;
        this.dirty = dirty;
        this.outs = outs;
    }

    @Override
    public void depMarkDirty() {
        if (!dirty) {
            dirty = true;
            SignalSupport.notifyOuts(outs);
        }
    }

    @Override
    public Object sGet() {
        if (dirty) {
            dirty = false;
            value = recompute();
        }
        return value;
    }

    @Override
    public void sSet(Object v) {
        throw new UnsupportedOperationException("ComputedO is read-only");
    }

    @Override
    public ArrayList<IDep> getOuts() {
        return outs;
    }

    private Object recompute() {
        switch (nSources) {
            case 0:
                return f.invoke();
            case 1:
                return f.invoke(s0.sGet());
            case 2:
                return f.invoke(s0.sGet(), s1.sGet());
            case 3:
                return f.invoke(s0.sGet(), s1.sGet(), s2.sGet());
            default:
                Object[] args = new Object[nSources];
                args[0] = s0.sGet();
                if (nSources > 1) {
                    args[1] = s1.sGet();
                }
                if (nSources > 2) {
                    args[2] = s2.sGet();
                }
                if (moreSources != null) {
                    for (int i = 0; i < moreSources.length; i++) {
                        args[3 + i] = moreSources[i].sGet();
                    }
                }
                return f.applyTo(toSeq(args));
        }
    }

    private static ISeq toSeq(Object[] args) {
        ISeq seq = null;
        for (int i = args.length - 1; i >= 0; i--) {
            seq = new Cons(args[i], seq);
        }
        return seq;
    }
}
