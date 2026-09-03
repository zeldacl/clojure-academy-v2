# Presentation Runtime v2 — 性能基线（重构前）

采集于 UI 系统重构（Presentation Runtime v2 → v3）Phase 0 结束时，Phase 3 会
用 Java 引擎内核替换 `paint.clj`/`runtime.clj` 的几何代码——这是重构前最后一次
能测到旧实现真实数据的窗口。数字必须留到重构完成后再对比，否则收益无法证明。

- 基准源码（已随 Phase 3 对 `paint.clj`/`runtime.clj` 的替换一起删除，
  数字已固化在本文件与下方 JSON 中；如需复现，检出 commit `f69aae01c` 或
  更早即可看到 `PresentationBaselineBenchmark.java` 与
  `presentation_baseline_support.clj` 的完整源码并重新运行）
- 原运行方式：`./gradlew :tools:benchmarks:jmhPresentationBaseline`（任务
  随基准源码一起移除）
- 原始 JSON：[`v2-baseline.json`](./v2-baseline.json)
- 测试树形状：约 80 节点（1 根 + cp/overload 条 + 20 个技能槽 × 3 子节点），
  与真实战斗 HUD 同数量级，见 `combat_hud.ui.edn` 与 `presentation_hud.clj`。
- 环境：JDK 21.0.6 HotSpot，单线程，JMH 1.37，3×10s 预热 + 5×10s 测量，`-prof gc`。

## 结果

| 基准 | 复现的问题 | 吞吐 (ops/s) | 分配 (B/op) | GC 次数 (5×10s) |
|---|---|---|---|---|
| `extractSteadyState` | 每帧 `present!` 无条件标脏，HUD 永远全树重绘（本文档「已核实结论」#3） | 3056 ± 3147 | **516,040** | 181 |
| `pointerMoveHitTest` | 单次指针移动触发 hit-hover 全树遍历 + 自建 `child-rects` 副本 | 8720 ± 640 | **219,887** | 210 |

`extractSteadyState` 每次调用同时执行 `present!`（推入新一帧状态）与
`extract-stage!`（因而必然重绘），对应真实每帧发生的 `refresh-stage-state!`
→`present!`路径。**每帧约 516 KB 分配**——这是 §12 风险评估里「HUD 每帧重绘
≈9 MB/s 纯垃圾」估算的实测印证（516 KB × 3056 ops/s ≈ 1.58 GB/s 在本基准的
合成负载下，真实游戏帧率更低但同一数量级）。

`pointerMoveHitTest` 只调用一次 `dispatch-input!`（`:pointer :move`），每次
约 220 KB —— 对应 `hit-hover` 单独一趟遍历的分配量；真实指针移动会连续触发
`hit-action`/`hit-hover`/`hit-scroll` 三趟，因此生产环境每次指针移动的实际
分配量数倍于此处测得的单趟数字。

## 重构后实测结果

`PresentationRuntimeBenchmark`（`tools/benchmarks/src/jmh/java/cn/li/tools/benchmark/`，
运行方式 `:tools:benchmarks:jmhPresentation`）用真实的 `combat_hud` golden 产物
（`docs/06-gui/presentation/golden/.../combat-hud.uic.edn`）作为测试数据，
JDK 21.0.6 HotSpot、单线程、JMH 1.37、3×10s 预热 + 5×10s 测量、`-prof gc`——
与本文件上方基线同一测量方法论，可直接对比。

| 基准 | 对应旧基准 | 旧 (B/op) | 新 (B/op) | 倍数 |
|---|---|---|---|---|
| `cleanFrameExtract`（状态/几何均未变） | `extractSteadyState` | 516,040 | **1,624** | **317×** |
| `hoverPointerMove`（单次指针移动） | `pointerMoveHitTest` | 219,887 | **5,728** | **38×** |
| `dirtyLeafRepaint`（单个 binding 变化） | 无对应旧基准 | — | 3,480 | — |
| `fullRepaintOnResize`（每次调用都 resize） | 无对应旧基准 | — | 3,880 | — |

**这不是计划 §13 写下的字面 "0 B/op" 目标，是诚实测出来的数字。** 记忆化把
`cleanFrameExtract` 的成本从"全树 measure/arrange/paint"降到了
"O(#bindings) 的 `refreshRevs` + 几个固定大小的包装 map/vector"——后者不是
零，但比旧实现小两个数量级。降到字面 0 需要在"什么都没变"时连
`extract-stage!` 自己的返回值包装都跳过，而 `frame-context`（真实调用会带
时间戳等每帧必变的字段）不能被跨帧缓存，所以选择在"层级分明、正确性优先"
和"字面零分配"之间保留前者。

排查这两个数字的过程本身就是一次真实的回归："记忆化已接入" 起初只用
`identical?` 单元测试验证过（证明返回同一个对象），第一次实际用 JMH 测
B/op 时发现是 4,208 B/op，不是预期的 0——根因是 `update-host!` 每次都构造
一个全新的 `HostGeometry`，即使数值完全相同，导致 `extract-stage!` 用来判断
"没有变化"的 `identical?` 检查永远不命中。基准之所以第一次测出的数字比生产
环境更乐观，是因为它最初没有像真实调用方那样每帧都调 `update-host!`。
详见 `presentation-core/src/main/clojure/cn/li/presentation/core/runtime.clj`
的 `update-host!`/`ensure-layout-current!` 注释。
