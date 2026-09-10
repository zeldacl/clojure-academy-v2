package cn.li.tools.benchmark;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Acceptance benchmarks for the node-engine/VFX performance plan's
 * Phase C (cn.li.vfx.runtime's per-tick/per-frame instance-map rebuilds).
 * Registry is the 19 REAL {@code ac/vfx-v4/*.edn} effects whose :render
 * graph is non-stub (see cn.li.ac.vfx.empty-render-graph-audit-test,
 * Phase D2, for the full 36-effect classification) -- the other 17 compile
 * to an empty start-&gt;end graph and would understate real per-instance
 * cost by roughly 2x.
 *
 * <p>Baseline (pre-Phase-C, {@code sample-client-frame!} = full per-frame
 * path, {@code tick!} at the 20 Hz server tick rate):
 * <ul>
 *   <li>100 live instances: sample-client-frame! 399267 ns / 632348 B/op;
 *       tick! 31516 ns / 42904 B/op.</li>
 *   <li>200 live instances: sample-client-frame! 1017711 ns / 1271804 B/op;
 *       tick! 57878 ns / 84640 B/op.</li>
 * </ul>
 * Measured after Phase C: 200-instance tick! 45405 -&gt; 9716 ns/op (the
 * (long-array 1) age box removing the whole-map rebuild every tick).
 */
@State(Scope.Thread)
public class VfxFrameBenchmark {
    // The 19 ac/vfx-v4 effect-ids with a non-stub :render graph, confirmed
    // via cn.li.ac.vfx.empty-render-graph-audit-test's own compiled-IR
    // check, not assumed from naming.
    private static final String NON_STUB_EFFECT_IDS_FORM =
        "[:arc-ring-fade-audio :arc-ring-session :audio-loop-session :audio-one-shot"
        + " :beam-arc-fade :beam-fade-audio :beam-session :camera-fov-session :endpoint-burst"
        + " :energy-orb-session :particle-burst :particle-session :particle-trail-audio-transient"
        + " :ray-beam-transient :ring-fade-audio :ring-particle-field :teleport-marker"
        + " :teleport-trail-transient :terrain-shockwave-transient]";

    @Param({"25", "100", "200"})
    public int liveInstances;

    private Object runtime;
    private IFn sampleClientFrame;
    private IFn tickBang;

    @Setup(Level.Trial)
    public void setup() {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("cn.li.vfx.runtime"));
        require.invoke(Clojure.read("clojure.edn"));
        require.invoke(Clojure.read("clojure.java.io"));

        IFn evalForm = Clojure.var("clojure.core", "eval");
        Object setupForm = Clojure.read(
            "(let [ids " + NON_STUB_EFFECT_IDS_FORM + "\n"
            + " load-doc (fn [id] (clojure.edn/read-string"
            + "                    (slurp (clojure.java.io/resource"
            + "                            (str \"ac/vfx-v4/\" (name id) \".edn\")))))\n"
            + " registry (into {} (map (fn [id] [id {:document (load-doc id) :lifecycle :transient}])) ids)\n"
            + " rt (cn.li.vfx.runtime/create-client-runtime registry {:max-frames 8})\n"
            + " n " + liveInstances + "]\n"
            + " (dotimes [i n]\n"
            + "   (let [id (nth ids (mod i (count ids)))\n"
            + "         doc (:document (get registry id))\n"
            + "         param-keys (keys (or (:inputs doc) (:parameters doc)))\n"
            + "         params (into {:duration-ticks 100000} (map (fn [k] [k 1.0])) param-keys)]\n"
            + "     (cn.li.vfx.runtime/dispatch-signal!\n"
            + "      rt {:op :spawn :effect-id id :owner (str \"p\" i) :instance-key [:bench i]\n"
            + "          :seed i :params params :event-seq 1 :state-seq 1})))\n"
            + " rt)");
        runtime = evalForm.invoke(setupForm);

        sampleClientFrame = Clojure.var("cn.li.vfx.runtime", "sample-client-frame!");
        tickBang = Clojure.var("cn.li.vfx.runtime", "tick!");
    }

    @Benchmark
    public Object sampleClientFrame() {
        return sampleClientFrame.invoke(runtime);
    }

    @Benchmark
    public Object tick() {
        tickBang.invoke(runtime, 0.05);
        return runtime;
    }
}
