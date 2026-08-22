# VFX Core 维护手册

> 语言本体的完整规格见 [NODE_LANGUAGE.md](NODE_LANGUAGE.md)（§9 是 vfx 层的专属规则）——本文只讲 vfx-core 如何使用这套语言、实例生命周期、模块边界与排障。

## 系统职责

`vfx-core` 加载 `node-core` 语言之上的**渲染词汇表**（原语 + 中层 composite），执行全部技能视觉/听觉效果，并管理特效实例的生命周期（spawn/signal/destroy、`instance-key` 幂等去重、`event-seq` 排序、tombstone、seed 确定性、`bounds` 剔除）。它独占这组"实例/生命周期"职责；具体怎么画（顶点、材质）留给平台渲染器——vfx-core 的输出是中立的渲染 op 批次。

## 模块边界

- `vfx-core/src/main/clojure/cn/li/vfx/components.clj`：渲染词汇表——底层原语（`:layer :primitive`，产出 `:line`/`:quad`/`:plasma-body`/`:audio`/`:camera`/`:post` op，见 NODE_LANGUAGE.md §9）与结构原语（`:vfx/timeline`/`:repeat`/`:transform`/`:let`/`:curve`/`:branch`）。
- `vfx-core/src/main/clojure/cn/li/vfx/vm.clj`：树遍历采样器，每帧对每个存活实例求值一次图，把叶子节点的输出送进 `sink`。
- `vfx-core/src/main/clojure/cn/li/vfx/recipe.clj`：effect 文档加载/编译，composite 展开。
- `vfx-core/src/main/clojure/cn/li/vfx/runtime.clj`：唯一的实例运行时——注册表冻结、`:lifecycle`（`:transient`/`:session`/`:persistent`）、per-instance 状态机、`tick!`/`sample-frame!`。
- `ac/src/main/clojure/cn/li/ac/client/effect_controller.clj`：AC 侧安装点，`install-catalog!` 登记 descriptor，把采样结果喂给 Presentation 帧合并（`register.clj` 的 `merge-vfx-passes`）。

## 渲染 op 契约（关键——决定一个原语是否真的会显示）

vfx 底层原语必须产出平台渲染器已经认识的 op 形状：`{:kind :line|:quad|:plasma-body}` + 材质标志（`:texture :additive? :no-fog? :no-depth-test? :no-depth-write? :translucent?`），对应 `platform-src/.../client/effects/presentation_world.clj` 的 `sort-ops`/`render-presentation-geometry!`。六个 loader 的 `:draw-batch!` 按 `:primitive` 分派——**只有产出这个契约认识的形状，效果才会被画出来**；产出任意其它 payload 形状（例如旧 `:mesh`/`:variant` 语义节点直接吐参数 map）不会报错，只会静默不渲染。新增原语或改渲染契约时，先确认改动同时覆盖了三条平台目录（mc-1.20.1/mc-1.21.1/mc-26.2）× loader 那一侧的分派。

## 词汇表分层（详见 NODE_LANGUAGE.md §1、§9）

```
:layer :primitive   Clojure 函数，产出上面的渲染 op       components.clj 的 register-primitive!
:layer :mid          纯 EDN composite（"释放闪电"的视觉部分就是这层）   ac/src/main/resources/ac/vfx/components/*.edn
:layer :ability       纯 EDN 效果文档                              ac/src/main/resources/ac/vfx/effects/*.edn
```

## 实例模型

三种 `:lifecycle`：

- **`:transient`**——一次性效果，一个 (owner, 一次触发) 对应一个真实实例，走 `instance-key`/`event-seq`/tombstone 幂等分派。
- **`:session`**——跨多个 tick 存活的效果（充能/引导/持续光束），由技能显式 `:destroy` 信号结束。
- **`:persistent`**——按 world-id + 位置/方块实体身份索引，机器/方块挂载特效用。

不再有 `:singleton`（旧版本"所有玩家共用一个聚合实例"的形态，已随旧客户端栈一起删除）——每个实例天然按 owner/world 索引，`clear-owner!`/`clear-world!` 精确清理匹配实例，不会误伤其他玩家的实例。

## 运行时流程

combat-core 的 `:effect/vfx` 节点产出携带 `instance-key`/`audience`/`payload` 的信号 → `combat-core/vfx-publish` 按 audience（`{:scope :self|:tracking :radius}`，唯一拼写）广播 → 客户端 `effect_controller/dispatch-signal!` → `vfx-core/runtime` 的幂等分派（`instance-key`/`event-seq`/tombstone）→ 每帧 `tick!` 推进状态机、`sample-frame!` 对可见实例（`bounds` 剔除后）求值图产出 op 批次 → `register.clj` 的 `merge-vfx-passes` 并入同一个 Presentation `FramePacket`。

## 扩展点

- 新增底层渲染原语：在 `components.clj` 登记完整 v3 描述符，`:impl` 产出的 op 必须落在上面"渲染 op 契约"描述的形状里；同时检查 loader 侧的 `:draw-batch!` 分派确实会处理这个 `:primitive`。
- 新增中层视觉语义（如"环形爆发"、"闪电冲击视觉"）：在 `ac/src/main/resources/ac/vfx/components/*.edn` 加一个 `:layer :mid` composite，登记进 `vfx/components_manifest.edn`。**禁止**给它写 Clojure 实现。
- 新增效果文档：在 `ac/src/main/resources/ac/vfx/effects/*.edn` 加文档，登记进 `vfx/manifest.edn`，声明 `:lifecycle`/`:audience`/类型化 `:inputs`（按 `:spawn`/`:update`/`:destroy` 分组）/`:state-slots`。

## 排障手册

- 效果编译通过但屏幕上什么都没有 → 先检查它的底层原语是否真的产出"渲染 op 契约"里的形状；旧的 `:mesh`/`:variant` 语义节点是这类问题的历史根源（详见迁移计划 R3）。
- `unknown VFX effect` → combat-core 发出的 `:effect-id` 未在 `vfx/manifest.edn` 注册。
- 效果实例好像永远不消失 → 检查 `:destroy` 信号是否真的被发出；`:transient` 效果没有显式 `:duration-ticks`/`:life-ticks` 就不会自然结束。
- 同一效果被两个玩家同时触发时互相干扰 → 确认 `:instance-key` 确实按 owner 命名空间化（技能编译时会自动加 ability-id 前缀，但效果文档自己不应该硬编码一个跨 owner 共享的字面量 key）。
- 远处效果不显示 → 检查该效果编译后的 `:bounds` 是否返回了 `nil`（等价于永不可见）。

## 变更风险

- `:layer :mid` 组件不得有 `:impl`——`verifyNodeLayerDiscipline` 强制。
- 中层/底层节点不得读取环境形式（`{:from …}`/`{:tunable …}`/环境 `ctx :modifiers` 传播）——`verifyNoImplicitDependency` 强制；alpha/scale 之类的调制必须用显式 `:vfx/transform` 包裹。
- `vfx-core` 不得直接依赖 presentation-core；两者唯一交汇点是 AC 侧 `register.clj`/`effect_controller.clj`。
- `verifyVfxSingleTickPath`：每帧只允许一条 tick 路径驱动实例状态机。

## 兼容性约束

- `vfx-core` 依赖 `node-core` 与 `mcmod`，不引用 presentation-core/combat-core/AC 的具体类型，由 `verifyVfxDependencyDirection` 强制。
- `node-core` 不得依赖 `vfx-core`，由 `verifyNodeCoreDependencyDirection` 强制。
