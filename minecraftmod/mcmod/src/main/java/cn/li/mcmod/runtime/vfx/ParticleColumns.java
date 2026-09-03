package cn.li.mcmod.runtime.vfx;

/** Layout-driven structure-of-arrays particle storage: column count and
 *  meaning are entirely determined by the VFX layout builder at compile
 *  time, not fixed fields. Column c, particle i -&gt; flat offset
 *  c*capacity+i.
 *
 *  A new class rather than a ParticleBuffer rewrite: ParticleBuffer's
 *  fixed 10-field layout is still used by the currently-live VFX client
 *  runtime, which is not touched until content is rewritten onto this
 *  model (see the redesign plan's staging notes). mcmod must stay a
 *  neutral ABI and never name a vfx-core namespace, not even in a
 *  comment -- verifyVfxDependencyDirection scans this module's source
 *  text for exactly that. */
public final class ParticleColumns {
    private final int capacity;
    private int size;
    private final float[] floats;
    private final int[] ints;

    public ParticleColumns(int capacity, int floatCols, int intCols) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
        this.floats = new float[Math.max(0, floatCols) * capacity];
        this.ints = new int[Math.max(0, intCols) * capacity];
    }

    public int reserve(int requested) {
        int accepted = Math.min(Math.max(requested, 0), capacity - size);
        int start = size;
        size += accepted;
        return start;
    }

    public void swapRemove(int index, int floatCols, int intCols) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("index");
        int last = --size;
        if (index == last) return;
        for (int c = 0; c < floatCols; c++) floats[c * capacity + index] = floats[c * capacity + last];
        for (int c = 0; c < intCols; c++) ints[c * capacity + index] = ints[c * capacity + last];
    }

    public void clear() { size = 0; }
    public int size() { return size; }
    public int capacity() { return capacity; }
    public float[] floats() { return floats; }
    public int[] ints() { return ints; }
}
