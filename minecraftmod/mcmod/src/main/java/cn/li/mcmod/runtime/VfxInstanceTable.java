package cn.li.mcmod.runtime.effect;

/** Fixed-layout VFX storage; lifecycle and rendering remain Clojure logic. */
public final class VfxInstanceTable {
    public final boolean[] alive;
    public final int[] effectIndices;
    public final Object[] owners;
    public final Object[] worldIds;
    public final long[] seeds;
    public final double[] ages;
    public final long[] eventSequences;
    public final int[] freeList;
    public int freeCount;

    public VfxInstanceTable(boolean[] alive,
                            int[] effectIndices,
                            Object[] owners,
                            Object[] worldIds,
                            long[] seeds,
                            double[] ages,
                            long[] eventSequences,
                            int[] freeList) {
        this.alive = alive;
        this.effectIndices = effectIndices;
        this.owners = owners;
        this.worldIds = worldIds;
        this.seeds = seeds;
        this.ages = ages;
        this.eventSequences = eventSequences;
        this.freeList = freeList;
    }
}
