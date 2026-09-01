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
 * Pre-refactor (Presentation Runtime v2) baseline for the UI engine rewrite
 * (see docs/dev plan "Presentation Runtime v3"). Run and record these numbers
 * BEFORE Phase 3 replaces paint.clj/runtime.clj's geometry code with the Java
 * engine kernels — this is the only way to prove the rewrite paid for itself.
 *
 * extractSteadyState reproduces claim 4: present! unconditionally marks
 * :paint dirty every frame, so every real HUD frame does a full repaint.
 *
 * pointerMoveHitTest reproduces claims 3/6: a single pointer move walks the
 * full tree in hit-hover, re-deriving layout via its own child-rects copy.
 */
@State(Scope.Thread)
public class PresentationBaselineBenchmark {
    private static final Keyword STEP = Keyword.intern(null, "step!");

    private IFn extractStep;
    private IFn hitTestStep;

    @Setup(Level.Trial)
    public void setup() {
        Clojure.var("clojure.core", "require")
                .invoke(Clojure.read("cn.li.tools.benchmark.presentation-baseline-support"));
        IFn makeExtractFixture = Clojure.var(
                "cn.li.tools.benchmark.presentation-baseline-support", "make-extract-fixture");
        IFn makeHitTestFixture = Clojure.var(
                "cn.li.tools.benchmark.presentation-baseline-support", "make-hit-test-fixture");
        extractStep = (IFn) ((clojure.lang.ILookup) makeExtractFixture.invoke()).valAt(STEP);
        hitTestStep = (IFn) ((clojure.lang.ILookup) makeHitTestFixture.invoke()).valAt(STEP);
    }

    @Benchmark
    public Object extractSteadyState() {
        return extractStep.invoke();
    }

    @Benchmark
    public Object pointerMoveHitTest() {
        return hitTestStep.invoke();
    }
}
