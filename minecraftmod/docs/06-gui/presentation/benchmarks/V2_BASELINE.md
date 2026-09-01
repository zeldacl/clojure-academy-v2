# Presentation Runtime v2 — 性能基线（重构前）

采集于 UI 系统重构（Presentation Runtime v2 → v3）Phase 0 结束时，Phase 3 会
用 Java 引擎内核替换 `paint.clj`/`runtime.clj` 的几何代码——这是重构前最后一次
能测到旧实现真实数据的窗口。数字必须留到重构完成后再对比，否则收益无法证明。

- 基准源码：`tools/benchmarks/src/jmh/java/cn/li/tools/benchmark/PresentationBaselineBenchmark.java`
- Clojure 治具：`tools/benchmarks/src/jmh/resources/cn/li/tools/benchmark/presentation_baseline_support.clj`
- 运行方式：`./gradlew :tools:benchmarks:jmhPresentationBaseline`
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

## 重构后的验收目标

按重构计划 §13：

- `extractSteadyState` 等价物（状态不变的干净帧）：从 516,040 B/op 降到 **0 B/op**（记忆化命中，返回同一个 `UiDrawList`）。
- 单 binding 变化帧：< 1 KB/op。
- `pointerMoveHitTest` 等价物：从 219,887 B/op 降到 **0 B/op**（单趟扫描读已提交的 arena，无 seq/无向量分配）。

重新采集时使用同一测试树形状（或迁移后的真实 `combat_hud.ui.edn`），保持
可比性；新基准命名为 `PresentationCleanFrameBenchmark`/`PresentationHitTestBenchmark`
等（见重构计划 §13），本文件在新基准落地后更新对比表，不删除本节旧数据。
