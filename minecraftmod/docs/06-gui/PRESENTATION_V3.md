# Presentation Runtime v3

This is the reference document several engine source files point to
(`LayoutKernel`, `NodeTable`, the compiler's `artifact.clj`). It covers the
layout algorithm, box model, opcode table, event model, and the IR/backend
contract. For module layering and data flow, see
[GUI_Architecture_Refactoring.md](GUI_Architecture_Refactoring.md).

## 1. Pipeline

```
.ui.edn (schema 2) --presentation-compiler--> .uic.edn (:pui5, schema 5)
    --nodetable/table-for--> NodeTable (immutable, shared per view-id)
    --LayoutKernel.expand--> LayoutArena (mutable, per-mount)
    --LayoutKernel.measure/arrange--> arena.rect[] (committed geometry)
    --PaintKernel.paint--> CmdBuf --.finish--> UiDrawList
    --version backend--> GuiGraphics/BufferSource
```

`NodeTable` is a flat, pre-order struct-of-arrays: parent/firstChild/nextSibling
indices instead of pointers, so `subtreeEnd[i]` gives O(1) "skip this whole
subtree" for both `LayoutKernel` and `HitKernel`. It is built once per
view-id and shared by every mount of that view (never mutated after
construction). `LayoutArena` is the per-mount, per-frame mutable output:
indexed by **instance**, not node, because a `:repeater`/`:grid` collection
expands one template subtree into N instances (`LayoutKernel.expand`).

## 2. Box model

margin → border (visual only, no layout effect) → padding → content, as CSS.
`arena.rect` stores the **border box** (margin excluded); margin only affects
how much space a sibling-packed child consumes. Percent resolves against the
parent's content box on that axis; when the parent's own size on that axis is
itself `:auto`, a percent child measures to 0 and is not re-resolved
(documented limitation, not a fixpoint solver).

Size mode encoding (`SizeMode`): `AUTO=0 FIXED=1 PERCENT=2 WEIGHT=3 FILL=4`.

## 3. Layout algorithm (`LayoutKernel`)

Two passes, Android `MeasureSpec`-style: `EXACTLY=0 AT_MOST=1 UNSPECIFIED=2`.

- **`:none` direction** (`:box`/`:stack`/`:absolute` with no `:direction`,
  i.e. `arrangeFree`): children measured against the full content box, then
  positioned at their own declared `x`/`y` (or a bound override via
  `Bindings.numberOverride`). Implemented and used by every real `.ui.edn`
  that needs absolute placement today (e.g. `tutorial.ui.edn`'s 427×240
  hand-tuned layout).
- **`:row`/`:column`** (`measureLinear`/`arrangeLinear`): non-weighted
  children measured first against the shrinking remainder, then weighted
  children share what's left proportionally (two measure passes over
  weighted children is unavoidable, same as Android `LinearLayout`).
  `justify` (`start/center/end/space-between/space-around`) positions along
  the main axis; `align-items`/`align-self` (`start/center/end/stretch`)
  position along the cross axis.
- **`:wrap`** (`measureWrapped`/`arrangeWrapped`/`placeLine`): greedy
  line-breaking. Each child is measured against the full content box (not
  the shrinking remainder non-wrap flow uses — an overflowing child starts a
  new line instead of being squeezed). The container's own auto cross-axis
  size is the sum of line cross-sizes plus inter-line gaps; auto main-axis
  size is the available box (a wrapping row doesn't shrink to content, only
  the cross axis grows). `arrangeWrapped` re-derives the identical line
  partition during arrange — children's measured sizes are already fixed by
  then, so no break-point state needs to persist in the arena. **Weighted
  children under `:wrap`, and `:wrap`+`:scroll` together, are not
  supported** (real complexity, but zero current `.ui.edn` content combines
  them).
- **`:grid` and anchor-based absolute placement** (`:anchor {:x :x2 :y :y2
  :offset}`) are **not implemented** — `:grid` currently degrades to a plain
  row/column (fine today: no real `.ui.edn` `:grid` usage specifies
  `:cols`/track config), and `:anchor` has zero real usage. Both are
  additive; nothing in the engine needs to change shape to add them later.
- **Clipping**: no clip stack. `arrange` carries `clipIdx` down; a
  `HAS_CLIP` node intersects its own content box with the inherited clip and
  pushes a new entry (`LayoutArena.pushClip`), passing the new index down.
  Every paint/hit command inherits its node's clip index directly — no
  push/pop bookkeeping anywhere downstream.
- **Text** (`measureText`): width comes from `UiTextMetrics.advance`, line
  height from `UiTextMetrics.lineHeight`. When no metrics implementation is
  installed (headless tests, JMH), a deterministic `0.6*len*fontSize`
  fallback keeps presentation-core testable without a Minecraft font
  backend — **production code must install a real implementation** (see §6).
  Text wrapping (multi-line `breakIndex` loop) and `:ellipsize` truncation
  are not implemented (zero real usage).

## 4. Memoization (`MemoKernel`/`MemoState`)

Whole-tree (not per-subtree) memoization, wired into
`cn.li.presentation.core.runtime/extract-stage!`:

1. `resolve-bindings!` refreshes a **reused, per-mount scratch `Object[]`**
   (never allocated fresh per frame) from the artifact's `:bindings` against
   current view-state.
2. `MemoState.refreshRevs` compares each slot `==` first, `.equals` as a
   fallback (a producer that rebuilds an equal value from scratch still
   counts as unchanged), bumping a per-binding revision only on real change.
   Cost is O(#bindings), never O(#nodes).
3. `MemoKernel.subtreeStamp` folds the compiler-assigned `dep-mask` (each
   node's bit-set of the binding ids its subtree depends on, computed
   bottom-up at compile time) into one hash per node, mixed with a
   geometry-invalidation counter and a text-metrics-epoch counter.
4. `extract-stage!` compares the root's stamp (node index 0 — its dep-mask
   is the full-tree closure) against the last frame's stamp. If unchanged
   **and** `(:geometry instance)` is `identical?` to last frame's, the
   entire measure/arrange/paint pass is skipped and the previous frame's
   result map (containing the same `UiDrawList` object) is returned as-is.

**Geometry identity, not equality, gates the skip** — and this only works
because `update-host!`/`update-stage-geometry!` both keep the *old*
`HostGeometry` reference when a freshly-constructed one is only
value-equal. Real callers reconstruct `HostGeometry` unconditionally every
frame; without that "keep old ref if equal" step, `identical?` would never
hold and the memoization would never fire in production even though a
synthetic benchmark that skips calling `update-host!` would look perfect.
This was caught by `PresentationRuntimeBenchmark` measuring real B/op (first
run: 4,208 B/op, not the expected near-zero), not by a unit test that only
checked object identity without exercising the real per-frame call pattern.

**Layout freshness and paint freshness are tracked separately**
(`:layout-stamp`/`:layout-geometry`/`:layout-metrics-epoch` vs.
`:paint-stamp`/`:last-result`), both gated through the shared
`ensure-layout-current!`. `dispatch!` (hit-testing) only ever needs a
current arena and goes through this same gate — before this was wired,
`dispatch!` called the unconditional `ensure-layout!` on every single input
event regardless of whether anything had changed, which is exactly the
"three independent full-tree traversals" problem `HitKernel`'s own docstring
says it replaces, just reintroduced one layer up. Separating the two stamps
matters: `dispatch!` can legitimately refresh the layout stamp (e.g. for
hit-testing against current geometry) without that ever being mistaken for
"the paint is also still valid" — a reducer-triggered state change applied
via `present!` after a hit only shows up in the *next* binding-value
resolution, not in the stamp `dispatch!` already computed, so `extract-stage!`
must always re-check bindings itself rather than trusting a stamp match left
over from a dispatch that happened before the state actually changed.

**Known gap**: `NodeFlags.ANIMATED` exists but nothing sets it (the compiler
never emits an animated node, no `.ui.edn` declares one) and there is no
`AnimKernel`. If animation is ever added without also adding a revision-bump
mechanism for it, memoization **will** freeze it — this is a live risk
introduced by wiring memoization, not just a missing feature.

## 5. Opcode table (`UiOp`)

Source vocabulary lowers to a small physical set before flattening —
runtime code only ever needs to understand these plus "not drawable":

| Source `:type` | Opcode | Notes |
|---|---|---|
| `:box`/`:row`/`:column`/`:stack`/`:absolute`/`:clip` | — (container, no draw op) | direction/clip are node flags |
| `:scroll`/`:repeater`/`:grid`/`:portal` | — (container) | collection/scroll flags |
| `:rect`/`:line` | `RECT` (0) | |
| `:image` | `IMAGE` (1) | |
| `:text` | `TEXT` (2) | |
| `:nine-slice`, lowered `:glow-line` | `NINE` (3) | |
| `:progress`/`:radial-progress` | `PROGRESS` (4) | decomposed by `PaintKernel` before reaching a backend |
| `:item-preview` | `ITEM` (5) | |
| `:model-preview` | `MODEL` (6) | |
| `:gradient` | `GRADIENT` (7) | decomposed by `PaintKernel` |
| `:composite` | `COMPOSITE` (8) | **kept deliberately** — see §7 |
| `:button`/`:text-input` | expand to a `RECT`+`TEXT` subtree at compile time | |
| `:slot-anchor` | `RECT` with a fixed tint | |

`UiOp.COUNT = 9`.

## 6. IR and backend contract

- `UiDrawList` (record, `mcmod.runtime.ui`): run-batched SoA command buffer.
  `CmdBuf.finish` hands out its **live, uncopied backing arrays** — by
  design, for zero-copy performance — on the contract that a `UiDrawList` is
  fully consumed before the next repaint overwrites those same arrays
  (single-threaded, one-frame-in-flight runtime). A caller that retains a
  `UiDrawList` across two `extract-stage!` calls and reads its arrays late
  will see corrupted values, not stale-but-consistent ones — always read
  what you need immediately, or hold the whole `UiDrawList` record (its
  identity as an object doesn't change) and read through it right when you
  use it that same frame.
- `UiTextMetrics` (interface, `mcmod.runtime.ui`): `epoch()`,
  `advance(fontId, text, fontSize)`, `lineHeight(fontId, fontSize)`,
  `breakIndex(...)`. Each MC version's `cgui-font/text-metrics` implements
  this against its own MSDF/vanilla measurement; `epoch()` must bump when
  the font face's ready state flips, so presentation-core treats that as a
  global text-node memoization invalidation instead of leaving pre-ready
  measurements wrong forever.
- **Installation**: `platform-src` cannot depend on `presentation-core`
  directly (`verifyPresentationDependencyDirection`), so the install point
  is a small neutral mailbox in `mcmod.runtime.presentation-bridge`
  (`install-text-metrics!`/`current-text-metrics`) — each backend's
  `create()` calls `install-text-metrics!` with its `cgui-font/text-metrics`,
  and `presentation-core/runtime.clj` (which already depends on `mcmod`)
  reads it back through the same namespace.
- `FramePacket(frameId, UiDrawList[] uiByStage, List<RenderPass> passes)` —
  `uiByStage` indexed by `RenderStage.ordinal()`; `passes` carries the 11
  remaining world/VFX `RenderCommand` variants (`Layer, Mesh, Billboard,
  ParticleBatch, Ribbon, Beam, CameraContribution, PostProcess,
  AudioContribution, OrderBarrier, Batch`) — UI has its own `UiDrawList`
  path and no longer shares the `RenderCommand` union.
- Each version backend dispatches `UiDrawList` runs by integer opcode
  (`case` → `tableswitch`, O(1)), never `instanceof` — enforced by
  `verifyPresentationBackendDispatch`.

## 7. Why `:composite` was kept

An earlier plan draft assumed `:composite` would be deleted once the
declarative primitive set covered "the overwhelming majority" of real
content. It wasn't deleted, on purpose: `combat_hud`, `developer`,
`skill_tree`, and `tutorial` all need heterogeneous per-collection-item
content (a skill slot's key-cap/icon/cooldown-wipe/glow stack, a condition
grid's icon-or-text-or-model item, a graph node) that the static primitive
set can't express statically. `COMPOSITE` (opcode 8) is the accepted escape
hatch: the collection item itself names its own kind
(`:quad`/`:image`/`:text`/`:condition`/`:model`) and paint parameters,
resolved at paint time via `BindResolver` into a `CompositeSpec` — the one
place the engine's `BindResolver` interface exists specifically to support.
Do not try to force full declarativization against this; it's load-bearing,
not tech debt. `verifyPresentationArtifacts`/`verifyAbilityCompositionBoundary`
deliberately do **not** ban `:composite`, contrary to what an early plan
draft's gate table proposed.

## 8. Gate ban-list (naming pitfalls)

`verifyPresentationRuntimeZeroResidues` bans these tokens (literally, even
in comments) across `ac/src/main`, `mcmod/src/main`, `platform-src`,
`presentation-core/src/main`, `presentation-compiler/src/main`:

```
CompiledTemplate  UiRt  mount-tree!  reconcile-tree!  layout-tree!  retained-tree  child-rects
```

`verifyPresentationAtomicSwitch` additionally bans `draw-plan`,
`overlay-plan`, `kind-renderer`, `level-renderer`, `build-overlay`, and any
`.xml` at those roots. Name new code `measure!`/`arrange!`/`extract!`/
`finish!`/`paint!`/`hit-test` — never `layout-tree!`.

## 8b. TechUI shell composition

Container TechUI views (wireless node / matrix / machine / interferer) compose
through compile-time `:include` + named `:slot`s. Contract:
[TECH_UI_SHELL.md](TECH_UI_SHELL.md).

## 9. Verification without a running game

- **Layout conformance**: `LayoutKernelTest`/`CmdBufTest`/`MemoKernelTest`/
  `PaintKernelTest`/`HitKernelTest`/`CompositeSpecTest` (JUnit, pure Java, no
  Minecraft) — `:presentation-core:test`.
- **Control-plane behavior**: `runtime_test.clj` (`clojure.test`, headless
  default text metrics) — `:presentation-core:runCoreClojureTests`.
- **Compiler**: `artifact_test.clj` — `:presentation-compiler:test`.
- **Golden artifacts**: `verifyPresentationGoldenArtifacts` diffs all 15
  compiled `.uic.edn` against `docs/06-gui/presentation/golden/` (the
  compiler is a pure, deterministic function of its `.ui.edn` source —
  verified byte-identical across independent runs before committing the
  fixtures). Update the fixtures alongside any intentional `.ui.edn` change.
- **Performance acceptance**: `:tools:benchmarks:jmhPresentation` runs
  `PresentationRuntimeBenchmark` against the pre-rewrite numbers in
  `docs/06-gui/presentation/benchmarks/V2_BASELINE.md`. Measured result:
  clean-frame extraction 516,040 → 1,624 B/op (317×), pointer-move hit-test
  219,887 → 5,728 B/op (38×) — real, verified numbers, not the plan's
  original literal "0 B/op" aspiration (see V2_BASELINE.md for why exactly
  zero isn't pursued further). Measure real per-frame allocation, not just
  object identity — a benchmark or test that skips a step real callers
  always do (e.g. reconstructing `HostGeometry` every frame, or never
  triggering a reducer-driven state change between a hit and the next
  extraction) will report a better number than production ever sees.
- **Architecture gates**: `verifyCurrentPlatforms` (aggregates all
  `verifyPresentation*` gates plus module dependency-direction checks).

## 10. Deliberately deferred (confirmed zero real `.ui.edn` usage)

- `:grid` real track layout (currently a plain row/column).
- Anchor-based absolute placement (`:anchor {...}`) — the shorthand spec for
  single-anchor-given semantics is genuinely ambiguous; implementing it
  without real content to validate against risks a plausible-looking but
  silently wrong feature.
- `AnimKernel` / animation revision bumping (see the memoization risk note
  in §4).
- `:ellipsize` text truncation.
- `OP_SCROLLBAR` visual drag-handle widget (`:type :scroll` itself works via
  `HitKernel`; no view renders a visible scrollbar thumb, same as
  pre-rewrite).
- A `_tokens.edn` style/color token file (colors are inline hex per
  `.ui.edn`; no functional gap, just unrealized indirection).
- Screen-space VFX particles (`charging-arcs`/`vm-waves`/`coin-qte` in
  `reactive_hud.clj`) moving into `vfx-core`: investigated and not
  attempted — `VfxRenderStage.SCREEN` has no rendering path wired anywhere
  today (the HUD renderer doesn't pass a `:backend-context`, and the only
  existing VFX draw-batch consumer is 3D-world-space only), so this would be
  building a new capability, not moving code.
- Generic Presentation editor tooling, hot reload, and in-game inspector
  (the separate engine plan's Phase 7) are not started. The AC-specific node
  editor and spell composer are tracked separately in
  `docs/06-gui/NODE_EDITOR_EXECUTION_PLAN.md`.
