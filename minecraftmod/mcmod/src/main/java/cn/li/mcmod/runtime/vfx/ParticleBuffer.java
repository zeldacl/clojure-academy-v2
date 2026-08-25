package cn.li.mcmod.runtime.vfx;

/** Fixed-capacity structure-of-arrays particle storage. */
public final class ParticleBuffer {
    private final int capacity;
    private int size;
    private final float[] positionX, positionY, positionZ;
    private final float[] velocityX, velocityY, velocityZ;
    private final float[] age, lifetime;
    private final int[] color, flags;
    public ParticleBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
        positionX = new float[capacity]; positionY = new float[capacity]; positionZ = new float[capacity];
        velocityX = new float[capacity]; velocityY = new float[capacity]; velocityZ = new float[capacity];
        age = new float[capacity]; lifetime = new float[capacity]; color = new int[capacity]; flags = new int[capacity];
    }
    public int reserve(int requested) {
        int accepted = Math.min(Math.max(requested, 0), capacity - size);
        int start = size; size += accepted; return start;
    }
    public int reservedEnd() { return size; }
    public void swapRemove(int index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("index");
        int last = --size; if (index == last) return;
        positionX[index] = positionX[last]; positionY[index] = positionY[last]; positionZ[index] = positionZ[last];
        velocityX[index] = velocityX[last]; velocityY[index] = velocityY[last]; velocityZ[index] = velocityZ[last];
        age[index] = age[last]; lifetime[index] = lifetime[last]; color[index] = color[last]; flags[index] = flags[last];
    }
    public void clear() { size = 0; }
    public int size() { return size; }
    public int capacity() { return capacity; }
    public float[] positionX() { return positionX; }
    public float[] positionY() { return positionY; }
    public float[] positionZ() { return positionZ; }
    public float[] velocityX() { return velocityX; }
    public float[] velocityY() { return velocityY; }
    public float[] velocityZ() { return velocityZ; }
    public float[] age() { return age; }
    public float[] lifetime() { return lifetime; }
    public int[] color() { return color; }
    public int[] flags() { return flags; }
}