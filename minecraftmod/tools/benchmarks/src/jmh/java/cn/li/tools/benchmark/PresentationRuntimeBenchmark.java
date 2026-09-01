package cn.li.tools.benchmark;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import cn.li.presentation.core.HostGeometry;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Presentation Runtime v3 acceptance benchmarks (refactor plan §13/§12
 * risk item 1) - the direct answer to whether the whole-tree memoization
 * and the new engine actually deliver the numbers the plan promised
 * against the pre-rewrite v2 baseline recorded in
 * docs/06-gui/presentation/benchmarks/V2_BASELINE.md:
 *
 * <ul>
 *   <li>{@code extractSteadyState} baseline: 516,040 B/op (every frame
 *       unconditionally repainted). Target: 0 B/op via memoization
 *       returning the same UiDrawList object -- see
 *       {@link #cleanFrameExtract()}.</li>
 *   <li>{@code pointerMoveHitTest} baseline: 219,887 B/op (three
 *       independent full-tree traversals with their own child-rects
 *       copy). Target: near-zero, one reverse linear scan -- see
 *       {@link #hoverPointerMove()}.</li>
 * </ul>
 *
 * Test data is the real compiled combat_hud golden fixture (~80-100
 * nodes: cp/overload bars + repeated skill slots), matching the shape
 * the v2 baseline itself measured against, loaded from the same
 * checked-in artifact verifyPresentationGoldenArtifacts diffs against
 * (no compiler dependency needed here - the compiled EDN is data).
 */
@State(Scope.Thread)
public class PresentationRuntimeBenchmark {
    private Object runtime;
    private Object mount;
    private IFn presentBang;
    private IFn extractStage;
    private IFn dispatchBang;
    private IFn updateHost;

    private Object cleanFrameContext;
    private Object hudStageKeyword;
    private Object screenStageKeyword;
    private int resizeToggle;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("cn.li.presentation.core.runtime"));
        require.invoke(Clojure.read("clojure.edn"));

        IFn createRuntime = Clojure.var("cn.li.presentation.core.runtime", "create-runtime");
        IFn mountBang = Clojure.var("cn.li.presentation.core.runtime", "mount!");
        presentBang = Clojure.var("cn.li.presentation.core.runtime", "present!");
        extractStage = Clojure.var("cn.li.presentation.core.runtime", "extract-stage!");
        dispatchBang = Clojure.var("cn.li.presentation.core.runtime", "dispatch!");
        updateHost = Clojure.var("cn.li.presentation.core.runtime", "update-host!");

        IFn readString = Clojure.var("clojure.edn", "read-string");
        String edn;
        try (InputStream in = getClass().getResourceAsStream(
                "/assets/academy/presentation-compiled/academy.app/combat-hud.uic.edn")) {
            if (in == null) {
                throw new IOException("combat-hud.uic.edn golden fixture not found on jmh classpath");
            }
            edn = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Object artifact = readString.invoke(edn);

        runtime = createRuntime.invoke();

        IFn assoc = Clojure.var("clojure.core", "assoc");
        Object spec = Clojure.read("{:host {:stage :hud} :view-id :academy.app/combat-hud :state {}}");
        spec = assoc.invoke(spec, Keyword.intern(null, "artifact"), artifact);
        mount = mountBang.invoke(runtime, spec);

        updateHost.invoke(runtime, mount, new HostGeometry(0f, 0f, 320, 180, 1f));

        hudStageKeyword = Keyword.intern(null, "hud");
        screenStageKeyword = Keyword.intern(null, "screen");
        cleanFrameContext = Clojure.read("{:width 320 :height 180}");

        // Establish a first committed frame so the steady-state benchmark
        // measures the memoized-hit path from its very first invocation,
        // not a cold first paint.
        extractStage.invoke(runtime, hudStageKeyword, cleanFrameContext);
    }

    /**
     * Nothing changed since the last frame: should hit the MemoKernel skip
     * and cost ~0 B/op. Calls update-host! every iteration with the SAME
     * width/height, exactly like a real per-frame HUD renderer callback
     * does unconditionally - update-host!/update-stage-geometry! must both
     * keep the old HostGeometry reference when the new one is only
     * value-equal, or this benchmark would be measuring a scenario real
     * callers never hit.
     */
    @Benchmark
    public Object cleanFrameExtract() {
        updateHost.invoke(runtime, mount, new HostGeometry(0f, 0f, 320, 180, 1f));
        return extractStage.invoke(runtime, hudStageKeyword, cleanFrameContext);
    }

    /** One state change, then extract - exercises refreshRevs + a real repaint (not a skip). */
    @Benchmark
    public Object dirtyLeafRepaint() {
        Object next = Clojure.read("{:touched " + System.nanoTime() + "}");
        presentBang.invoke(runtime, mount, next);
        return extractStage.invoke(runtime, hudStageKeyword, cleanFrameContext);
    }

    /** Every call resizes the host, forcing invalidateGeometry - the worst case, full remeasure every frame. */
    @Benchmark
    public Object fullRepaintOnResize() {
        resizeToggle ^= 1;
        int w = 320 + resizeToggle;
        updateHost.invoke(runtime, mount, new HostGeometry(0f, 0f, w, 180, 1f));
        return extractStage.invoke(runtime, screenStageKeyword, Clojure.read("{:width " + w + " :height 180}"));
    }

    /** A pointer move: HitKernel's single reverse scan, no layout re-derivation. */
    @Benchmark
    public Object hoverPointerMove() {
        Object event = Clojure.read("{:type :pointer :event-type :move :x 40.0 :y 40.0}");
        return dispatchBang.invoke(runtime, mount, event);
    }
}
