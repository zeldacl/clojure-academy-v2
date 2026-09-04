# Node Editor

三个规划中的可视化编辑器（技能/VFX 图编辑器、玩家法术合成器、粒子发射器编辑器）
里，前两个已实现；第三个按设计文档自己的决策点评估后暂不做（`ac/vfx/fx/*.edn`
里没有任何一个文件用 `:emitters`，见下）。设计过程记录在
`C:\Users\lxy\.claude\plans\vfx-psi-hex-casting-ars-nouveau-niagara-tidy-puffin.md`。

语言层规格见 [NODE_LANGUAGE.md](../04-systems/NODE_LANGUAGE.md)（尤其是其中
`:nid` 稳定性契约那一节）——本文档只描述编辑器本身。

## 模块落点

```
ability-runtime/src/main/clojure/cn/li/ability/editor/   纯函数、无内容知识
    palette.clj    词汇表 + 纯 op + fn 库,按 :category 分组、按 :effects 过滤
    document.clj   打开/undo/redo/外科式保存(splice-program)
    graph.clj      表层 form ⇄ 节点/连线图,双向无损
    check.clj      诊断(cn.li.node.compile :collect 模式) + 静态代价读数
    render.clj     图 → 扁平 composite-item 向量(exec 链视图) + 正交连线
    hit.clj        指针事件 → 拖拽状态机(节点选中/移动、平移)

ac/src/main/clojure/cn/li/ac/ability/client/screens/
    node_editor_reactive.clj      技能模式 + 场景效果模式,同一个屏幕
    spell_composer_reactive.clj   玩家法术合成器

ac/src/presentation/resources/academy/app/
    node_editor.ui.edn       480×360
    spell_composer.ui.edn    320×240
```

`verifyContentModuleCoreIsolation` 允许 `ac` 直接 require 的核心命名空间里，
`cn.li.node.ops`/`cn.li.vfx.api` 是专为这套编辑器加的（词汇表调色板与场景模式的
per-file capabilities 推导需要）。`ability-runtime/editor/*` 本身不含任何具体
技能/效果 id——`verifyCoreNoSkillKnowledge` 会扫描这一点；词汇表、文档列表、效果
`:inputs` 表全部由 `ac` 侧作为数据注入。

## 保存：外科式拼接，不是重新打印

`ac/skills/*.edn`/`ac/vfx/fx/*.edn` 不是裸 DSL 文档，是一个包装 map，DSL 以
字符串形式存在 `:program`（技能）/`:scene`（VFX）字段里。编辑器的保存路径
（`document/splice-program`）只替换这一个字段的字符串值，包装层的其余字段
（`:tunables`/`:costs`/`:cooldown`/`:doc`……）一字节不动——定位靠对源文本做
quote-aware 扫描，不是把整个 map 重新序列化一遍。这是保存路径**不会**把
`;;` 风格注释悄悄丢掉的前提：所有真实作者注释已经在语言重构阶段迁移成 `:doc`
向量字段（75 个真实内容文件全部迁移，逐字节验证过除 `:doc` 外其余字段与迁移前
完全一致），`verifyContentEdnNoRawComments` 门禁保证新内容不会引入裸注释——一
个裸注释一旦写在字符串以外的位置，编辑器保存时会原样保留（因为压根不碰那部分
字节），但下次有人手写编辑那个字段整体重排就可能丢；转成 `:doc` 之后不存在这个
风险，因为它是数据，走跟其余字段完全一样的保留路径。

`:tunables` 目前拆成两半——包装层写 `:curve`（`skill_config.clj` 用来算熟练度
插值），`:program`/`:scene` 字符串内部写 `:type`（编译期类型检查用）。编辑器改
一个 tunable 的类型必须两处都改，这是既有设计的 wart，不是编辑器引入的，合并
两处是独立重构。

## `graph.clj` 的范围

支持 `let`/裸调用/`when`/`if`/`each`/`finish`/`state!`/`set!`/`event!`/`vfx!`
语句，任意深度嵌套（`if`/`when`/`each` 互相嵌套没有层数限制），以及任意深度的
纯表达式树。这个范围不是猜的——写 `graph_test.clj` 之前先 grep 了真实内容：
`if` 最初被想当然地认为很少见、准备跳过不支持，结果发现 39 个技能文件里 31 个
用了它，于是老老实实实现了，不是留一个"documented gap"。`ac/.../editor_corpus_
test.clj` 拿全部 39 个技能 + 36 个场景效果（204 个 phase/scene 条目）跑
`form → graph → form` 往返测试，这是真正证明覆盖率的测试，不是挑几个样例文件
自证。

`render.clj` 目前只画 exec 语句链本身（每条语句一个方框，标签是
`graph/stmt-text`——`pr-str` 该语句的表层 form，保证画布上看到的文字永远和保存
会写出的内容一致），不画每个嵌套纯表达式的独立子节点/连线——那是真正的
Blueprint 式全图渲染，需要大量视觉设计判断（方框大小、引脚间距）没法在这个
环境里不看实机画面就定下来，留给用过 exec-chain 视图之后的真实迭代。

## 已知不做的部分（不是遗漏）

- **拖拽新增节点 / 拖线连接引脚**：需要能感知"松开时鼠标下面是什么"的几何命中
  测试；这套 presentation runtime 的 `:down` 命中路径（走 `:activate` 自定义
  动作）会带 `:item`/`:index`，但 `:up`（走通用 `:input/pointer` 兜底）不会
  ——没有现成的命中信息可用，要自己写一套基于当前渲染坐标的命中测试。移动已有
  节点是安全的（`:input/pointer` 的 `:drag` 事件带的是运行时自己算好的**每帧
  增量** `:drag-x`/`:drag-y`，不是起点到终点的位移，累加式操作不需要知道松开
  时鼠标下面是什么），已实现。
- **玩家法术的 glyph 物品 / 法术存储物品 NBT**：需要贴图、模型 json、合成表，
  这个环境创建不了也验证不了。法术合成器屏幕今天靠直接调用 `open!` 打开，不挂
  在任何物品上；服务端提交/校验/派发路径（`MSG-REQ-SPELL-SUBMIT` →
  `combat-runtime/dispatch-player-spell!`）本身已经完整可用。
- **粒子发射器编辑器**：`vfx-core/compile.clj` 的 Niagara 式模块栈机制存在且
  有测试，但 `ac/vfx/fx/*.edn` 里零文件使用 `:emitters`——这个编辑器服务的是
  尚不存在的内容。按设计文档自己定的决策点：没有真实 `:emitters` 内容就不做，
  不是欠债。
- **参数微调**：技能/场景效果模式的检查器目前只能看某个节点的完整文本，不能
  就地拖拽修改一个数值参数；法术合成器里的 glyph 全部用固定默认参数
  （`:amount 2.0`、`:range 16.0` 等），没有强度滑杆。两者都是明确的后续增量。
- **场景效果准星实时预览**（计划 Phase 4 第 3 项：编辑时在玩家准星处播放正在
  编辑的效果，保存时重新发布）：查过代码不是漏做——`ac/.../ability/client/`
  整棵树里没有任何现成的客户端"取玩家视线/眼位置"辅助函数可复用，需要新写一个
  且要同时构造并发布一个真实的 `vfx_contract/signal`（`:effect-id`/`:owner`/
  `:event-seq` 加 spawn/update 等操作各自要求的实例身份）。跟上面已完成的布局
  旁车、真实保存/重载不同（那些是纯 Clojure/文件 I/O，这个开发环境里就能完整
  测试），这里是全新的、本仓库没有先例的 Minecraft 客户端 API 接触面，而且结果
  "特效是否真的出现在准星处"这件事本身就没法在不启动游戏的情况下验证。跟拖线
  连接、glyph 物品一样，是有理由的暂缓，不是静默缺口。

## 验证状态

架构门（`verifyCurrentPlatforms`）、真实 AOT 编译（forge-1.20.1、
neoforge-1.21.1）、单元测试（`ability-runtime`/`combat-core`/`vfx-core`/`ac`
四个模块）、以及 `.ui.edn` 自身的 schema 校验（`compilePresentationViews`，
在这套开发环境里就能跑，不需要启动游戏）全部通过，且在开发过程中真的抓到过
几个实质 bug（`:semantics {:role :status}` 不是合法角色、一个没有默认分支的
`case` 会在点击节点标签时抛异常、`(name :form/self)` 会悄悄丢掉命名空间）。

**没有验证、也无法在这个环境里验证的**：画面实际渲染效果、点击/拖拽的手感、
480×360 / 320×240 这两个设计尺寸下四个面板是否真的挤得下。这部分需要在真实
游戏里试。
