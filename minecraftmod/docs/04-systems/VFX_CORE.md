# VFX Core 维护手册

> 语言本体见 [NODE_LANGUAGE.md](NODE_LANGUAGE.md) §7（场景 DSL）——本文只讲
> vfx-core 如何使用它、模块边界、以及排障。VFX 的**执行**侧（采样/实例生命周期/
> 渲染）和**内容元数据加载**侧（`combat-catalog.clj`，读 skill tree UI/trigger
> 需要的字段）现在都只读 `ac/vfx-v4/*.edn`——旧内容目录
> （`旧 VFX effect 目录`/`ac/vfx/manifest.edn`）和旧加载器
> （`ac/ability/final_catalog.clj`、`vfx-core/vocabulary.clj`/
> `system_compiler.clj`）**已全部删除**，见下方"内容加载侧"一节。

## 系统职责

`vfx-core` 是特效侧的执行引擎。特效被采样成一帧份的中立绘制/音频/相机 op
（`{:kind :ring|:beam|:emitter|:audio-one-shot|... ...args}`），从不直接持有
Minecraft 渲染状态；真正的渲染由 `platform-src` 的 loader 消费这些 op。

## 执行引擎：已切换，旧引擎已删除

`cn.li.vfx.final-engine`（`sample-node`，树遍历）、`cn.li.vfx.final-client`
（客户端 per-instance runtime）已经删除——不是"保留但不再实机调用"，是文件本身
不存在了。现在唯一的执行路径：

| | 现状（唯一路径） |
|---|---|
| 入口 | `cn.li.vfx.scene`（`compile-doc!`/`sample!`，surface DSL → IR → 闭包） |
| 词汇表 | `cn.li.vfx.dsl-vocabulary`（`nodes`，扁平 `:do` 序列里的普通调用） |
| 客户端实例存储 | `cn.li.vfx.runtime`（`create-client-runtime`/`dispatch-signal!`/`client-tick!`/`sample-client-frame!`）——复刻了旧 `final-client` 的 event-seq/state-seq 去重 + tombstone + 帧池化语义，键控在 `instance-key` 上而非旧引擎自己的合成 id，`instance-for-owner` 之外全是 O(1) |
| Java 帧投影 | `cn.li.vfx.frame/->java-frame`：把 `scene/sample!` 的 op 翻译成旧引擎曾经产出的同一套 `VfxBatch`/`VfxOutput`/`VfxFrame`（`legacy-op` 表，字段映射对照真实内容核实过，不是猜的），下游渲染桥接（`ability-runtime` 的 `compose.clj`、每个 loader 的 `presentation_world_renderer.clj`）零改动 |
| 客户端组合根 | `cn.li.ability.client-vfx-v2`（`ability-runtime`），真实调用点：`ac/content/ability_client.clj` 的 `init-client-fx!`、`ac/client/vfx_host.clj` 的 `install!`、`ac/gui/reactive/register.clj` 的 `sampled-vfx-frame!`、`ac/ability/client/reactive_hud.clj` 的 storm-wing/flashing 状态读取 |
| 内容资源 | `ac/vfx-v4/*.edn`（每个文件都是可校验的 `:ac/vfx-v4` map，执行图位于 `:graphs :render`） |
| 复用单元 | 无（36 个真实效果都不需要跨效果复用；见 NODE_LANGUAGE.md §8） |
| 粒子模拟 | `cn.li.vfx.compile`（Niagara 模块栈 + SoA 布局）证明了模型，但没有真实内容在用它 |

## 内容加载：现在跟执行引擎读同一套内容

VFX 生产资源只来自 `ac/vfx-v4/*.edn`，由 `fx-catalog` 扫描并校验；技能元数据
由 `skills-catalog` 扫描 `ac/skills-v4/*.edn`。旧 manifest、旧 effect 目录和
旧 composite 资源均已删除，V4 不再存在第二条内容读取路径。
## 历史记录：旧引擎里约一半的组件种类从未真正渲染过（S6 转换决策依据）

`cn.li.vfx.final-engine`/`final_catalog.clj` 均已删除；本节保留作为
`ac/vfx-v4/*.edn` 里为什么某些效果的 `:graphs :render` 是诚实的空图的历史依据，不再
描述任何仍在运行的代码。`cn.li.vfx.final-engine/sample-node`（已删除）的
`case` 分支只覆盖：`:vfx/let :vfx/repeat
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

V4 不再保留零调用的 composite 文件，也不存在旧的 `load-vfx` 读取路径。需要复用的
场景结构直接写入 `ac/vfx-v4/*.edn` 的 `:graphs :render`，运行时由 V4 graph compiler 生成统一 IR；
无渲染效果使用诚实的空 render graph。通用 schema/export 代码只服务于编辑器元数据导出，
不参与生产内容加载。
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
  `ac/vfx-v4/*.edn` 内容在用；是给未来需要真正 CPU 端逐粒子模拟的内容留的
  能力，不是当前 36 个效果缺的东西。

## 内容加载侧的模块边界

- `ac/src/main/clojure/cn/li/ac/vfx/fx_catalog.clj`：扫描 `ac/vfx-v4/*.edn`，是
  VFX 执行引擎唯一的内容来源。
- `ac/src/main/clojure/cn/li/ac/ability/service/combat_catalog.clj`：技能元数据
  侧读 `ac/skills-v4/*.edn`，不再走独立 manifest 或旧 composite 加载路径。
- `vfx-core/src/main/clojure/cn/li/vfx/dsl_vocabulary.clj` 与
  `node-core` composite/schema 命名空间仍是编辑器 schema-export 基础设施，
  但不读取任何旧生产资源。
- `ability-runtime/src/main/clojure/cn/li/ability/compose.clj`：`merge-draw-lists`/
  `merge-vfx-into-frame` 是真实的帧合并调用点；旧 catalog 指纹函数不再接线。
## 生命周期与网络

信号操作：`spawn/update/trigger/destroy/clear-owner/snapshot`。实例身份由
`effect-id + owner + world-id + instance-key` 组成；`state-seq`/`event-seq`
独立检查，`cn.li.vfx.runtime` 的 tombstone 表防止延迟到达的 `:spawn`/
`:snapshot` 复活一个已被权威销毁的实例。`mcmod/runtime/fixed_channel.clj`
对完整 signal 做有界二进制编码，`max-vfx-frame-bytes` 是协议上限。

## 排障手册

- 一份 `ac/vfx-v4/*.edn` 效果编译报 `unknown-node` → 对照 `dsl_vocabulary.clj`
  声明的叶子节点名字/字段。
- 需要按 `:progress`/年龄插值的字段（旧的 `{:from :to}` 隐式 lerp）→ 显式写
  `(math/lerp from to ?progress)`，见 NODE_LANGUAGE.md §7。`cn.li.vfx.runtime/
  sample-frame!` 里的 `progress-of` 用 `age / (:duration-ticks user)`（缺省时
  退化为 `age / 1.0`）算 `?progress`，跟旧引擎的 `fade-factor` 同一套公式。
- 需要渐隐效果（旧的 `:vfx/fade` 包裹修饰器）→ 本地算 alpha，直接传给叶子
  节点的 `:alpha` 字段，不要找"包裹"语法——新引擎没有。
- 特效在游戏里完全不出现，但 `ac:runAcClojureTests` 里的 `cn.li.ac.vfx.
  fx-test` 是绿的 → 先确认 `ac/client/vfx_host.clj`、`ac/gui/reactive/
  register.clj`、`ac/ability/client/reactive_hud.clj` 三处是不是都指向
  `cn.li.ability.client-vfx-v2`——之前这里有过一个真实 bug：只切换了信号
  分发/catalog 注册，没切实际喂给渲染器的那次采样调用（`register.clj` 的
  `sampled-vfx-frame!`），导致签名/注册已经在新引擎上、但真正渲染出来的
  内容仍来自旧引擎（那时旧引擎还没删）。

## 验收门

```text
verifyNoGeneratedClojureTypes
verifyVfxJavaBoundary
verifyVfxPrimitiveVocabulary
verifyVfxSingleTickPath
vfx-core:checkClojure
vfx-core:runVfxClojureTests
ability-runtime:runAbilityClojureTests   （cn.li.ability.client-vfx-v2-test）
ac:runAcClojureTests   （包含 cn.li.ac.vfx.fx-test，36/36 效果的 compile+sample 证明）
```

实机渲染效果本身（画面是否好看、粒子数值是否合适）不在任何自动化验收范围
内——这些门禁证明的是"编译通过、数据形状正确、翻译桥接产出跟旧引擎相同的
Java 类型"，不是"游戏里看起来对"。

