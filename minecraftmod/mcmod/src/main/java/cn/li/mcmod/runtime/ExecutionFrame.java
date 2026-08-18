package cn.li.mcmod.runtime.effect;

import java.util.ArrayList;

/** Reusable per-dispatch storage; transitions are implemented in Clojure. */
public final class ExecutionFrame {
    public final double[] doubles;
    public final long[] longs;
    public final boolean[] booleans;
    public final Object[] objects;
    public final ArrayList<Object> actions;
    public final ArrayList<Object> vfx;
    public final ArrayList<Object> events;
    public final int[] touchedObjects;
    public int touchedCount;

    public ExecutionFrame(double[] doubles,
                          long[] longs,
                          boolean[] booleans,
                          Object[] objects,
                          ArrayList<Object> actions,
                          ArrayList<Object> vfx,
                          ArrayList<Object> events,
                          int[] touchedObjects) {
        this.doubles = doubles;
        this.longs = longs;
        this.booleans = booleans;
        this.objects = objects;
        this.actions = actions;
        this.vfx = vfx;
        this.events = events;
        this.touchedObjects = touchedObjects;
    }
}
