package cn.li.mcmod.runtime.effect;

/** Struct-of-arrays block query result. Algorithms stay in Clojure. */
public final class BlockBatch {
    public final int[] xs;
    public final int[] ys;
    public final int[] zs;
    public final double[] hardness;
    public final boolean[] air;
    public final boolean[] breakable;
    public int count;

    public BlockBatch(int[] xs,
                      int[] ys,
                      int[] zs,
                      double[] hardness,
                      boolean[] air,
                      boolean[] breakable) {
        this.xs = xs;
        this.ys = ys;
        this.zs = zs;
        this.hardness = hardness;
        this.air = air;
        this.breakable = breakable;
    }
}
