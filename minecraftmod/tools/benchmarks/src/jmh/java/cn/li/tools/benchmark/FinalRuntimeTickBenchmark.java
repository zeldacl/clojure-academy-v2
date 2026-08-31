package cn.li.tools.benchmark;

import clojure.java.api.Clojure;
import clojure.lang.Atom;
import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentArrayMap;
import clojure.lang.PersistentTreeMap;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Measures the common empty scheduled-tick path of the final runtime.
 *
 * The benchmark source set is deliberately isolated from every platform
 * source set: it is a measurement harness, never a runtime dependency.
 */
@State(Scope.Thread)
public class FinalRuntimeTickBenchmark {
    private static final Keyword SCHEDULED = Keyword.intern(null, "scheduled");

    private IFn tick;
    private IPersistentMap runtime;

    @Setup(Level.Trial)
    public void setup() {
        Clojure.var("clojure.core", "require")
                .invoke(Clojure.read("cn.li.ac.ability.final-runtime"));
        tick = Clojure.var("cn.li.ac.ability.final-runtime", "tick!");
        runtime = PersistentArrayMap.EMPTY.assoc(SCHEDULED, new Atom(PersistentTreeMap.EMPTY));
    }

    @Benchmark
    public Object emptyScheduledTick() {
        return tick.invoke(runtime, 0L);
    }
}
