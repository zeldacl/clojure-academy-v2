# VFX Core 维护手册

> 语言本体见 [NODE_LANGUAGE.md](NODE_LANGUAGE.md) §7（场景 DSL）——本文只讲
> vfx-core 如何使用它、模块边界、以及排障。**先读 NODE_LANGUAGE.md §0**：两套
> 引擎并存，本文档同时描述两者，注意区分。

## 系统职责

`vfx-core` 是特效侧的执行引擎。特效被采样成一帧份的中立绘制/音频/相机 op
（`{:kind :ring|:beam|:emitter|:audio-one-shot|... ...args}`），从不直接持有
Minecraft 渲染状态；真正的渲染由 `platform-src` 的 loader 消费这些 op。

## 两套引擎，同一份内容目录树的两个版本

| | 旧（当前实机路径） | 新（已验证，未接入实机） |
|---|---|---|
| 入口 | `cn.li.vfx.final-engine`（`sample-node`，树遍历） | `cn.li.vfx.scene`（`compile-doc!`/`sample!`，surface DSL → IR → 闭包） |
| 词汇表 | `cn.li.vfx.vocabulary`（`component-specs`，~24 种叶子 + 结构节点） | `cn.li.vfx.dsl-vocabulary`（`nodes`，扁平 `:do` 序列里的普通调用） |
| 内容资源 | `ac/vfx/effects/*.edn`（`:control-graph` 字段，旧节点树） | `ac/vfx/fx/*.edn`（`:scene` 字段内嵌新 DSL 文本，其余顶层键——`:schema-version`/`:asset/type`/`:asset/version`/`:id`/`:revision`/`:lifecycle`/`:audience`/`:inputs`/`:state-slots`——跟旧文件逐字节相同；`:bounds`/`:control-graph` 没有对应物，见下） |
| 复用单元 | `vfx-core/composites/*.edn`（旧宏替换式 composite） | 无（36 个真实效果都不需要跨效果复用；见 NODE_LANGUAGE.md §8） |
| 粒子模拟 | 客户端渲染器自己做逐粒子演化；图这一层只有"发射器"这一条声明式指令 | `cn.li.vfx.compile`（Niagara 模块栈 + SoA 布局）证明了模型，但没有真实内容在用它 |

旧文件在新版本转换完成后**原样保留，未被删除或修改**——`ac/vfx/fx/*.edn` 是
`ac/vfx/effects/*.edn` 的**新增同级文件**，不是替换。

## 关键事实：旧引擎里约一半的组件种类从未真正渲染过

`cn.li.vfx.final-engine/sample-node` 的 `case` 分支只覆盖：`:vfx/let :vfx/repeat
:vfx/timeline :vfx/group :vfx/branch :vfx/fade :vfx/ring :vfx/beam :vfx/ray-beam
:vfx/line :vfx/quad :vfx/emitter :vfx/audio :vfx/audio-one-shot :vfx/audio-loop
:vfx/camera :vfx/camera-fov :vfx/camera-shake :vfx/post-process`，其余任何
`"vfx"` 命名空间下的组件 id 落进一个把字段原样打包成 `:typed-vfx` quad op 的
兜底——这个兜底本身也没有渲染器认识 `:typed-vfx`，所以**这些组件今天在游戏里
本来就不产生任何真实像素**：`charge-slow`/`charge-ring`/`directional-wave`/
`vortex-column`/`impact-burst`/`mark-sparks`/`particle-trail`/`block-progress`/
`channel-arc`/`first-person-motion`/`block-scan`/`billboard-sequence`/
`trajectory-ribbon`/`humanoid-marker`/`beam-arc-fade`/`arc-strike`/`ray-fan`/
`arc-field`。

两个真实例外：`:vfx/beam-arc-fade` 和 `:vfx/humanoid-marker` 是**composite**
（`vfx-core/composites/beam_arc_fade.edn`/`humanoid_marker.edn`），`final_catalog.
clj` 的 `load-vfx` 在采样前就把它们展开成真正的子树——`beam-arc-fade` 展开后确实
含有能画的 `:vfx/beam`/`:vfx/ring`；`humanoid-marker` 展开后唯一的子节点
`:vfx/model-marker` 恰好也没有 `sample-node` 分支，所以展开了也还是不画东西。
`vfx-core/composites/` 下另外三个（`charge_ring.edn`/`block_progress.edn`/
`trajectory_ribbon.edn`）注册在 `:vfx.fx/*`（带额外的 `.fx` 段）命名空间下，跟
`ac/vfx/effects/*.edn` 里实际引用的 `:vfx/*`（不带 `.fx` 段）id 对不上，永远
不会被展开，是彻底不可达的孤儿资源。

转换到新引擎时，对应处理：确认无渲染的组件 → 诚实的空 `:scene`（不是发明新的
视觉设计）；`beam-arc-fade` → 把它的 composite 展开结果直接内联进
`ac/vfx/fx/beam_arc_fade.edn` 的 `:scene`（新引擎没有宏展开机制，`:defn` 组合
是唯一的复用单元，而这个效果只有一个调用点，不值得为它单独建一个组合）；其余
三个孤儿 composite 保持原样不动——它们零调用点、零真实渲染输出，转换成新语言
只会是凭空发明未被使用的基础设施。

## 场景 DSL 的模块边界（新引擎）

- `vfx-core/src/main/clojure/cn/li/vfx/dsl_vocabulary.clj`：场景叶子节点词汇
  表，每个节点 `:returns nil`（`:action-kind`——采样没有 host 好查，"调用"就是
  往这帧的 outbox 追加一条构造好的 op）。
- `vfx-core/src/main/clojure/cn/li/vfx/scene.clj`：`compile-doc!`（surface DSL
  文本 + 效果自己声明的 `:user` capability 类型 → IR）、`compile-program`（IR →
  闭包，`host` 固定成"往 frame.actions 里 append"）、`sample!`（跑一次，返回
  这次采样产出的 op 向量）。
- `vfx-core/src/main/clojure/cn/li/vfx/layout.clj` + `compile.clj`：Niagara
  模块栈机制，粒子属性 → SoA 列布局 → 编译好的逐粒子闭包。目前没有真实
  `ac/vfx/fx/*.edn` 内容在用；是给未来需要真正 CPU 端逐粒子模拟的内容留的
  能力，不是当前 36 个效果缺的东西。

## 旧引擎的模块边界（仍是实机路径）

- `vfx-core/src/main/clojure/cn/li/vfx/final_engine.clj`：headless/fake-host
  的 final graph sampler，`sample-node`/`sample-graph`。
- `vfx-core/src/main/clojure/cn/li/vfx/final_client.clj`：客户端 per-instance
  runtime、四阶段采样、Java frame 投影。
- `vfx-core/src/main/clojure/cn/li/vfx/vocabulary.clj`：旧词汇表
  （`component-specs`/`vfx-runtime-specs`/`composite-only-ids`），
  `environment`/`descriptor-specs` 是加载入口。
- `ac/src/main/clojure/cn/li/ac/ability/final_catalog.clj`：`load-vfx` 读取
  `ac/vfx/effects/*.edn` + `ac/vfx/manifest.edn`，展开 composite。
- `ability-runtime/src/main/clojure/cn/li/ability/compose.clj`：把 VFX catalog
  与 Combat、Presentation、NodeEnvironment 组合。

## 生命周期与网络

信号操作：`spawn/update/trigger/destroy/clear-owner/snapshot`。实例身份由
`effect-id + owner + world-id + instance-key` 或远端 `instance-id` 组成；
`state-seq`/`event-seq` 独立检查。`mcmod/runtime/fixed_channel.clj` 对完整
signal 做有界二进制编码，`max-vfx-frame-bytes` 是协议上限。这一层新旧两套
引擎共用，没有变化。

## 排障手册

**新引擎**（`ac/vfx/fx/*.edn` 编译/测试相关）：

- 一份效果编译报 `unknown-node` → 对照 `dsl_vocabulary.clj` 声明的叶子节点
  名字/字段；旧内容常见字段名对不上是因为旧 `component-specs` 声明了字段但
  `sample-node` 从不读它（比如 `:vfx/ray-beam` 声明过 `:life-ticks` 但从没读
  过）——新词汇表按`真正被读的字段`设计，不是照抄旧声明。
- 需要按 `:progress`/年龄插值的字段（旧的 `{:from :to}` 隐式 lerp）→ 显式写
  `(math/lerp from to ?progress)`，新引擎没有这个糖，见
  NODE_LANGUAGE.md §7。
- 需要渐隐效果（旧的 `:vfx/fade` 包裹修饰器）→ 本地算 alpha，直接传给叶子
  节点的 `:alpha` 字段，不要找"包裹"语法——新引擎没有。

## 验收门

```text
verifyNoGeneratedClojureTypes
verifyVfxJavaBoundary
vfx-core:checkClojure
vfx-core:runVfxClojureTests
ac:runAcClojureTests   （包含 cn.li.ac.vfx.fx-test，36/36 新效果的 compile+sample 证明）
ac:runAcEdnCoverageTests
```

实机渲染、多人可见性、材质数值和 loader datagen 属于后续运行时任务，不在新引擎
的验收范围内（新引擎的验收标准是"能编译、能针对假 host 正确采样"，见
NODE_LANGUAGE.md §0）。
