package cn.li.mcmod.runtime.effect;

/** Immutable compiled instruction storage; execution is implemented in Clojure. */
public final class CompiledProgram {
    public final int[] opcodes;
    public final int[] operands;
    public final double[] doubleConstants;
    public final long[] longConstants;
    public final Object[] objectConstants;
    public final int[] entryPoints;
    public final int doubleCount;
    public final int longCount;
    public final int booleanCount;
    public final int objectCount;

    public CompiledProgram(int[] opcodes,
                           int[] operands,
                           double[] doubleConstants,
                           long[] longConstants,
                           Object[] objectConstants,
                           int[] entryPoints,
                           int doubleCount,
                           int longCount,
                           int booleanCount,
                           int objectCount) {
        this.opcodes = opcodes;
        this.operands = operands;
        this.doubleConstants = doubleConstants;
        this.longConstants = longConstants;
        this.objectConstants = objectConstants;
        this.entryPoints = entryPoints;
        this.doubleCount = doubleCount;
        this.longCount = longCount;
        this.booleanCount = booleanCount;
        this.objectCount = objectCount;
    }
}
