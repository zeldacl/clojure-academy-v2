package cn.li.mcmod.runtime.effect;

import java.util.Map;

/** Immutable compiled-program storage: one clojure.lang.IFn per IR block
 *  plus constant pools and named entry points; walking blocks and invoking
 *  them is implemented in Clojure (cn.li.mcmod.runtime.effect-emit).
 *
 *  Reshaped from an original int-opcode/int-operand bytecode layout (this
 *  carrier had zero callers, so nothing depended on that shape): the
 *  language kernel actually built (cn.li.node.compile) compiles a surface
 *  DSL to a flat block IR that gets turned into a Clojure closure per
 *  block, not a linear int bytecode stream -- closer to how Blueprint
 *  compiles to an executable graph than to a tree-walking interpreter (see
 *  the redesign plan). `blocks` holds those closures directly. */
public final class CompiledProgram {
    public final Object[] blocks;
    public final double[] doubleConstants;
    public final long[] longConstants;
    public final boolean[] booleanConstants;
    public final Object[] objectConstants;
    /** phase/entry keyword -> starting block index (cn.li.node.compile's
     *  IR :entries, e.g. {:start 0, :pulse 4}). */
    public final Map<Object, Integer> entries;
    public final int doubleRegisterCount;
    public final int longRegisterCount;
    public final int booleanRegisterCount;
    public final int objectRegisterCount;

    public CompiledProgram(Object[] blocks,
                           double[] doubleConstants,
                           long[] longConstants,
                           boolean[] booleanConstants,
                           Object[] objectConstants,
                           Map<Object, Integer> entries,
                           int doubleRegisterCount,
                           int longRegisterCount,
                           int booleanRegisterCount,
                           int objectRegisterCount) {
        this.blocks = blocks;
        this.doubleConstants = doubleConstants;
        this.longConstants = longConstants;
        this.booleanConstants = booleanConstants;
        this.objectConstants = objectConstants;
        this.entries = entries;
        this.doubleRegisterCount = doubleRegisterCount;
        this.longRegisterCount = longRegisterCount;
        this.booleanRegisterCount = booleanRegisterCount;
        this.objectRegisterCount = objectRegisterCount;
    }
}
