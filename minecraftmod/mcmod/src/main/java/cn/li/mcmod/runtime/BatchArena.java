package cn.li.mcmod.runtime.effect;

/** Primitive batch backing storage; fill/reset logic lives in Clojure. */
public final class BatchArena {
    public final double[] doubles;
    public final long[] longs;
    public final int[] ints;
    public final Object[] objects;
    public int count;

    public BatchArena(double[] doubles,
                      long[] longs,
                      int[] ints,
                      Object[] objects) {
        this.doubles = doubles;
        this.longs = longs;
        this.ints = ints;
        this.objects = objects;
    }
}
