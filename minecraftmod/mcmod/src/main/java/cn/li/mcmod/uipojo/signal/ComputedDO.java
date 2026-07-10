package cn.li.mcmod.uipojo.signal;

import clojure.lang.Cons;
import clojure.lang.IFn;
import clojure.lang.ISeq;
import java.util.ArrayList;

/**
 * Double-driven object computed signal.
 * Sources are IDoubleSource (e.g. clock-ms) → f receives doubles → returns Object.
 * Fills the gap between ComputedD (IDoubleSource→double) and ComputedO (ISigO→Object).
 */
public final class ComputedDO implements ISigO, IDep, ISupportsOuts {

    private final IDoubleSource s0;
    private final IDoubleSource s1;
    private final IDoubleSource s2;
    private final IDoubleSource[] moreSources;
    private final IFn f;
    private final int nSources;
    private Object value;
    private boolean dirty;
    private final ArrayList<IDep> outs;

    public ComputedDO(IDoubleSource s0,
                      IDoubleSource s1,
                      IDoubleSource s2,
                      IDoubleSource[] moreSources,
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
        throw new UnsupportedOperationException("ComputedDO is read-only");
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
                return f.invoke(s0.readAsDouble());
            case 2:
                return f.invoke(s0.readAsDouble(), s1.readAsDouble());
            case 3:
                return f.invoke(s0.readAsDouble(), s1.readAsDouble(), s2.readAsDouble());
            default:
                double[] args = new double[nSources];
                args[0] = s0.readAsDouble();
                if (nSources > 1) {
                    args[1] = s1.readAsDouble();
                }
                if (nSources > 2) {
                    args[2] = s2.readAsDouble();
                }
                if (moreSources != null) {
                    for (int i = 0; i < moreSources.length; i++) {
                        args[3 + i] = moreSources[i].readAsDouble();
                    }
                }
                return f.applyTo(toSeq(args));
        }
    }

    private static ISeq toSeq(double[] args) {
        ISeq seq = null;
        for (int i = args.length - 1; i >= 0; i--) {
            seq = new Cons(args[i], seq);
        }
        return seq;
    }
}
