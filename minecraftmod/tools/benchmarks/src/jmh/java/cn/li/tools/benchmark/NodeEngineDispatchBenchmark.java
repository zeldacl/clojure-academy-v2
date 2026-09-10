package cn.li.tools.benchmark;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Acceptance benchmarks for the node-engine/VFX performance plan's
 * Phase A/B (docs: the plan file under {@code C:\Users\lxy\.claude\plans},
 * "node engine + VFX runtime performance refactor"). Direct answer to
 * whether the {@code cn.li.node.expr/evaluate} JIT-huge-method split
 * (Phase A1) and {@code cn.li.mcmod.runtime.effect-emit}'s primitive
 * {@code :pure} specialization (Phase B) actually deliver the measured
 * numbers, against the pre-refactor baseline recorded in the plan:
 *
 * <ul>
 *   <li>{@code elevenOpMathChain} baseline: 4594 ns / 7944 B/op (the
 *       generic, boxed {@code :pure} dispatch path). Target: real
 *       production dispatch through {@code cn.li.combat.run/dispatch!}
 *       measured at 389 ns / 1960 B/op after Phase B.</li>
 *   <li>{@code eachLoop64} baseline: 119118 ns / 92744 B/op (64 {@code
 *       :query!}-backed iterations testing {@code math/lte}/{@code
 *       math/sub} each pass).</li>
 *   <li>{@code realVocabularyDispatch}: the same real-vocabulary program
 *       {@code cn.li.combat.run-test} itself dispatches end-to-end
 *       (raycast + conditional damage + vfx! + cooldown/start) -- proves
 *       the specialization holds on mixed {@code :objects}-bank (vec3,
 *       host queries) and {@code :doubles}-bank content together, not
 *       just an isolated math chain.</li>
 * </ul>
 *
 * Deliberately does NOT load a persisted {@code ac/skills-v4/*.edn}
 * document: that needs {@code cn.li.node.graph-compile} plus a
 * synthetic :tunables/:capabilities map built from the document's own
 * declared :parameters, real plumbing worth doing once verified
 * end-to-end interactively, not hand-built blind inside a JMH harness.
 * combat-core's own {@code run-test} fixture already exercises real
 * vocabulary end-to-end and is the next best thing.
 */
@State(Scope.Thread)
public class NodeEngineDispatchBenchmark {
    private static final String ELEVEN_OP_CHAIN_DSL =
        "{:ability :bench-scalar-11 :activation :instant "
        + ":tunables {:a {:type :double} :b {:type :double}} "
        + ":do [(let x1 (math/add $a $b))"
        + "(let x2 (math/add x1 $b))(let x3 (math/add x2 $b))(let x4 (math/add x3 $b))"
        + "(let x5 (math/add x4 $b))(let x6 (math/add x5 $b))(let x7 (math/add x6 $b))"
        + "(let x8 (math/add x7 $b))(let x9 (math/add x8 $b))(let x10 (math/add x9 $b))"
        + "(let x11 (math/add x10 $b))"
        + "(finish {:outcome :performed})]}";

    private static final String EACH_LOOP_64_DSL =
        "{:ability :bench-each-64 :activation :instant :tunables {:energy {:type :double}} "
        + ":do [(let remaining $energy)"
        + "(let blocks (target/blocks {:shape {} :limit 64}))"
        + "(each block blocks"
        + "  (if (math/lte (:hardness block) remaining)"
        + "    [(block/break {:position (:position block)})"
        + "     (set! remaining (math/sub remaining (:hardness block)))]"
        + "    []))"
        + "(finish {:outcome :performed})]}";

    private static final String REAL_VOCAB_DSL =
        "{:ability :bench-real-vocab :activation :instant "
        + ":tunables {:range {:type :double} :damage {:type :double}} "
        + ":do [(let hit (target/raycast {:origin ?caster/eye :direction ?caster/aim :distance $range"
        + " :include-entities? true :include-blocks? true :living-only? true}))"
        + "(when (:entity-id hit) (combat/damage {:target (:entity-id hit) :amount $damage}))"
        + "(vfx! {:effect-id :arc-strike-transient :operation :spawn :start ?caster/eye})"
        + "(cooldown/start {:name :main :ticks 40})"
        + "(finish {:outcome :performed :end-ability? true})]}";

    private IFn dispatchBang;
    private Object elevenOpProgram;
    private Object eachLoopProgram;
    private Object realVocabProgram;
    private Object elevenOpInput;
    private Object eachLoopInput;
    private Object realVocabInput;

    @Setup(Level.Trial)
    public void setup() {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("cn.li.combat.run"));

        IFn compileDoc = Clojure.var("cn.li.combat.run", "compile-doc!");
        IFn compileProgram = Clojure.var("cn.li.combat.run", "compile-program");

        // A no-op host: query! always returns 64 zero-hardness blocks (each
        // loop's collection source) or a raycast miss, command! and
        // everything else no-ops -- this is a dispatch-cost benchmark, not
        // a correctness test (that is cn.li.combat.run-test's job, already
        // exercised on every build).
        IFn evalForm = Clojure.var("clojure.core", "eval");
        Object noOpHost = evalForm.invoke(Clojure.read(
            "(let [blocks (vec (repeat 64 {:hardness 0.0 :position {:x 0.0 :y 0.0 :z 0.0}}))]"
            + " {:query! (fn [cap _args _fr] (case cap :block/select blocks :raycast {:entity-id nil} nil))"
            + "  :command! (fn [_cap _args _fr] nil)})"));

        Object elevenOpIr = compileDoc.invoke(ELEVEN_OP_CHAIN_DSL);
        elevenOpProgram = compileProgram.invoke(elevenOpIr, noOpHost);
        elevenOpInput = Clojure.read("{:tunables {:a 1.5 :b 2.5} :capabilities {}}");

        Object eachLoopIr = compileDoc.invoke(EACH_LOOP_64_DSL);
        eachLoopProgram = compileProgram.invoke(eachLoopIr, noOpHost);
        eachLoopInput = Clojure.read("{:tunables {:energy 1.0e9} :capabilities {}}");

        Object realVocabIr = compileDoc.invoke(REAL_VOCAB_DSL);
        realVocabProgram = compileProgram.invoke(realVocabIr, noOpHost);
        realVocabInput = Clojure.read(
            "{:tunables {:range 24.0 :damage 8.0}"
            + " :capabilities {:caster/eye {:x 0.0 :y 1.0 :z 0.0} :caster/aim {:x 0.0 :y 0.0 :z 1.0}}}");

        dispatchBang = Clojure.var("cn.li.combat.run", "dispatch!");
    }

    @Benchmark
    public Object elevenOpMathChain() {
        return dispatchBang.invoke(elevenOpProgram, Keyword.intern(null, "default"), elevenOpInput);
    }

    @Benchmark
    public Object eachLoop64() {
        return dispatchBang.invoke(eachLoopProgram, Keyword.intern(null, "default"), eachLoopInput);
    }

    @Benchmark
    public Object realVocabularyDispatch() {
        return dispatchBang.invoke(realVocabProgram, Keyword.intern(null, "default"), realVocabInput);
    }
}
