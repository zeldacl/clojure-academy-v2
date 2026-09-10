# Node Engine / VFX Runtime Performance Baseline

Numbers behind the node-engine/VFX performance plan (four phases: A —
`cn.li.node.expr` JIT-huge-method split + VFX scene-program caching, B —
primitive `:pure` dispatch specialization, C — VFX per-tick/per-frame
instance-map rebuilds, D — regression gates + these benchmarks). Run
`:tools:benchmarks:jmh` (`cn.li.tools.benchmark.NodeEngineDispatchBenchmark`
and `cn.li.tools.benchmark.VfxFrameBenchmark`) to reproduce; JSON lands at
`tools/benchmarks/build/reports/jmh/results.json`.

All numbers below are from real production dispatch code
(`cn.li.combat.run/dispatch!`, `cn.li.vfx.runtime/sample-client-frame!`),
never a hand-rolled stand-in, measured with `getThreadAllocatedBytes` for
allocation and JMH's own `-prof gc` for the benchmark numbers. Absolute
values are machine-dependent; the ratios are not.

## Root causes found

1. **`cn.li.node.expr/evaluate` was never JIT-compiled.** Its single flat
   `case` over every opcode compiled to a 20,461-byte `invokeStatic`
   method — 2.56x past HotSpot's `HugeMethodLimit` (8000 bytes) — so it
   ran in the bytecode interpreter for the entire process lifetime, on
   every single `:pure` instruction dispatch. `verifyNoJitHostileMethods`
   is the permanent regression guard (proven to catch the exact original
   regression, not just pass on the fix — see that gate's own test log).
2. **VFX scene programs were recompiled on every spawn**, even though
   they never depend on instance data (only the Niagara emitter stack
   does). Up to 1.8ms / ~1MB per spawn on the most expensive shipped
   effects.
3. **`:pure` dispatch boxed every double/long register read and write**
   crossing the `(fn [frame] ...)` closure boundary, plus allocated a
   fresh arg vector and result map on every dispatch, even though the
   underlying register banks are already primitive JVM arrays.
4. **`cn.li.vfx.runtime/tick!` rebuilt the whole `:instances` map every
   tick** just to increment each instance's age by one.

## Node engine dispatch (`NodeEngineDispatchBenchmark`)

| Workload | Before (Phase A/B) | After | Change |
|---|---:|---:|---:|
| 11-op `:math/add` chain, real dispatch | 4594 ns / 7944 B | 389 ns / 1960 B | **11.8x**, ~4x less garbage |
| 11-op chain, isolated closures (upper bound) | 4594 ns / 7944 B | 111 ns / 600 B | 41x |
| `each` loop, 64 iterations (host-backed) | 119118 ns / 92744 B | 50113 ns / 92744 B | 2.4x (mixed :objects-bank content, see below) |

The 41x isolated-closure number is the ceiling: it is what a hand-built
primitive-only closure chain achieves with no IR/dispatch-loop overhead
at all. Real dispatch through `cn.li.mcmod.runtime.effect-emit` lands at
11.8x because per-dispatch overhead (frame construction, block-loop
indirection) and any `:objects`-bank content in the same program (vec3,
collections, host queries — never boxed to begin with, so Phase B's
unboxing has nothing to save there) are not eliminated, only the boxed
`:doubles`/`:longs`/`:booleans` register traffic is.

## VFX per-frame path (`VfxFrameBenchmark`)

Registry is the 19 real `ac/vfx-v4/*.edn` effects with a non-stub
`:render` graph (see `cn.li.ac.vfx.empty-render-graph-audit-test` —
17 of the 36 shipped effects compile to an empty `start->end` graph and
would understate real per-instance cost by roughly 2x).

| Live instances | `sample-client-frame!` before | after | `tick!` before | after |
|---:|---:|---:|---:|---:|
| 100 | 399267 ns / 632348 B | 250898 ns / 393636 B | 31516 ns / 42904 B | 7044 ns / 11640 B |
| 200 | 1017711 ns / 1271804 B | 473303 ns / 787796 B | 57878 ns / 84640 B | 9716 ns / 24160 B |

`tick!`'s improvement (Phase C) is the cleanest result in this whole
plan: age tracking moved from a plain immutable map value (forcing a
full `:instances` map rebuild every tick) to a `(long-array 1)` mutated
in place, so the instance map is no longer touched at all on a tick where
nothing spawns or expires.

## What did not pan out

- **Caching a scene op's `:material` hash** (`cn.li.vfx.frame/
  op->java-batch`): real content's materials are often per-frame-dynamic
  (age/progress-derived), and even where static, a value-keyed cache
  would not avoid computing the hash anyway — a map lookup needs the
  key's hash too. Not implemented (see `cn.li.vfx.runtime`'s Phase C
  commit message for the reasoning). The one real, safe win found in that
  area was a pure identity-copy `sample-client-frame!` was doing on
  `group-by`'s already-correct result — removed.
- **Migrating the node engine to runtime `eval`** (the question that
  started this investigation): rejected. ~97% of the boxed-dispatch cost
  was recoverable without leaving the closure-emitter design (see Phase
  A/B above); `eval` itself costs 1.8-2.3ms and ~2.25MB per compile, and
  VFX's per-spawn compilation model (before the Phase A2 cache) would
  have meant minting JVM classes on every effect spawn.

## Reproducing

```
scripts\target-gradle.ps1 forge-1.20.1 :tools:benchmarks:jmh
```

Runs `NodeEngineDispatchBenchmark` and `VfxFrameBenchmark` together
(`PresentationRuntimeBenchmark` is unrelated to this plan and has its own
`jmhPresentation` task and baseline doc under
`docs/06-gui/presentation/benchmarks/`).

`verifyNoJitHostileMethods` and `verifyNoUnclassifiedEmptyVfxRenderGraphs`
(root `build.gradle`) are the permanent regression gates for root causes
1 and 2 above, respectively.
