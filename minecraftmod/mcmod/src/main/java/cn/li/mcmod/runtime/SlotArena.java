package cn.li.mcmod.runtime.effect;

/** Fixed-layout generic slot storage; lifecycle operations live in Clojure. */
public final class SlotArena {
    public final double[] doubles;
    public final long[] longs;
    public final Object[] objects;
    public final int[] freeList;
    public int freeCount;

    public SlotArena(double[] doubles,
                     long[] longs,
                     Object[] objects,
                     int[] freeList) {
        this.doubles = doubles;
        this.longs = longs;
        this.objects = objects;
        this.freeList = freeList;
    }
}
