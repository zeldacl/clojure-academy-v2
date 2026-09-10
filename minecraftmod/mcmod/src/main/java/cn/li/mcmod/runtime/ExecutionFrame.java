package cn.li.mcmod.runtime.effect;

import java.util.ArrayList;

/** Reusable per-dispatch storage; transitions are implemented in Clojure
 *  (cn.li.mcmod.runtime.effect-emit). doubles/longs/booleans/objects are the
 *  four typed register banks a compiled program's registers live in (see
 *  cn.li.node.types/bank); input/stateWrites/result are the boundary a
 *  dispatch crosses to read caller-supplied data and report its own
 *  outcome, added alongside the pre-existing register/output-accumulator
 *  fields (this carrier previously had no caller at all, so these are
 *  additive, not a break of any existing contract). */
public final class ExecutionFrame {
    public final double[] doubles;
    public final long[] longs;
    public final boolean[] booleans;
    public final Object[] objects;
    public final ArrayList<Object> actions;
    public final ArrayList<Object> vfx;
    public final ArrayList<Object> events;
    /** {:key :value} patches from :state-write instructions; the caller
     *  commits these to the owning session store after a dispatch finishes
     *  -- this carrier never mutates persistent state itself. */
    public final ArrayList<Object> stateWrites;
    /** Per-dispatch input a :tun/:cap/:state-read instruction reads from (a
     *  plain Clojure map, e.g. {:tunables {} :capabilities {} :state {}});
     *  shape is owned entirely by whatever compiled the program, not by
     *  this carrier. Read-only WITHIN one dispatch, but not final: a
     *  caller that owns a frame's whole lifecycle (see cn.li.mcmod.runtime.
     *  effect-emit/reset-frame!) may reuse it across dispatches against
     *  DIFFERENT input, avoiding a fresh frame allocation per dispatch --
     *  correctness there is the caller's responsibility (no concurrent
     *  in-flight dispatch against the same frame), same as reusing any of
     *  the mutable arrays/lists below already required. */
    public Object input;
    public final int[] touchedObjects;
    public int touchedCount;
    /** Set once by a :finish instruction (e.g. {:outcome :performed
     *  :next-phase nil :end-ability? true}); null until a program actually
     *  reaches one. */
    public Object result;

    public ExecutionFrame(double[] doubles,
                          long[] longs,
                          boolean[] booleans,
                          Object[] objects,
                          ArrayList<Object> actions,
                          ArrayList<Object> vfx,
                          ArrayList<Object> events,
                          ArrayList<Object> stateWrites,
                          Object input,
                          int[] touchedObjects) {
        this.doubles = doubles;
        this.longs = longs;
        this.booleans = booleans;
        this.objects = objects;
        this.actions = actions;
        this.vfx = vfx;
        this.events = events;
        this.stateWrites = stateWrites;
        this.input = input;
        this.touchedObjects = touchedObjects;
    }
}
