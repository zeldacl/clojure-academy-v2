package cn.li.mcmod.runtime.effect;

/** Struct-of-arrays query result. Algorithms stay in Clojure. */
public final class EntityBatch {
    public final Object[] ids;
    public final Object[] types;
    public final double[] xs;
    public final double[] ys;
    public final double[] zs;
    public final long[] ages;
    public final double[] progress;
    public int count;

    public EntityBatch(Object[] ids,
                       Object[] types,
                       double[] xs,
                       double[] ys,
                       double[] zs,
                       long[] ages,
                       double[] progress) {
        this.ids = ids;
        this.types = types;
        this.xs = xs;
        this.ys = ys;
        this.zs = zs;
        this.ages = ages;
        this.progress = progress;
    }
}
