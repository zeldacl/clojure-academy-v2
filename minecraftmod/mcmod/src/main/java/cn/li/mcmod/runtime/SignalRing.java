package cn.li.mcmod.runtime.effect;

/** Bounded signal ring storage; enqueue/dequeue logic lives in Clojure. */
public final class SignalRing {
    public final Object[] signals;
    public final long[] sequences;
    public long head;
    public long tail;

    public SignalRing(Object[] signals,
                      long[] sequences) {
        this.signals = signals;
        this.sequences = sequences;
    }
}
