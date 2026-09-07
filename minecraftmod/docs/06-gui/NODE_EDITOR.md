# Node Editor

三个规划中的可视化编辑器（技能/VFX 图编辑器、玩家法术合成器、粒子发射器编辑器）
里，前两个已实现；第三个按设计文档自己的决策点评估后暂不做（`ac/vfx-v3/*.edn`
里没有任何一个文件用 `:emitters`，见下）。设计过程记录在
`C:\Users\lxy\.claude\plans\vfx-psi-hex-casting-ars-nouveau-niagara-tidy-puffin.md`。

语言层规格见 [NODE_LANGUAGE.md](../04-systems/NODE_LANGUAGE.md)（尤其是其中
`:nid` 稳定性契约那一节）——本文档只描述编辑器本身。

## 游戏内入口

两个屏幕原本都没有任何触发方式（`open!` 只在自己的源文件和
`presentation_surface_manifest.clj` 清单里出现过）——`/aim` 这类命令是服务端
Brigadier 命令，而这两个屏幕的 `open!` 挂载的是客户端 Presentation 视图，中间
没有现成桥（也没打算建，属于新平台面、这个环境验证不了，见上面几节同类判断的
一贯标准）。现有入口都是纯客户端事件，照抄 `preset-editor` 绑 `N` 键的先例：

- **G 键**——打开节点编辑器（技能模式，固定加载 `ac/skills-v3/thunder_bolt.edn`
  作为示例文件；目前没有文件选择 UI，是后续增量，不是这次遗漏）。
- **K 键**——打开玩家法术合成器（不需要文件参数）。
- **`editor_dev_tool` 道具**——右键效果同 G 键；图标复用 `developer_portable`
  已有贴图（`developer_portable_full.png`），不含能量/3D 模型那套逻辑；没有
  合成表，只能创造模式获取。
- **`spell_composer_dev_tool` 道具**——右键效果同 K 键；与 `editor_dev_tool`
  完全同构（同一张贴图、同样无能量/无合成表/仅创造模式），只是目标换成
  `spell-composer/open!`。

以上均在 `cn.li.ac.input-ids`（键位注册，走 `:alternative` scheme，平台侧
`register-all-keybindings-from-ac!` 是数据驱动的通用循环，不需要逐平台改
Java）与 `cn.li.ac.item.editor-dev-tool` / `cn.li.ac.item.spell-composer-dev-tool`
（道具注册）里。

## 模块落点

```
ability-runtime/src/main/clojure/cn/li/ability/editor/   纯函数、无内容知识
    palette.clj    词汇表 + 纯 op + fn 库,按 :category 分组、按 :effects 过滤
    document.clj   打开/undo/redo/结构化 V3 保存
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

## 保存：结构化 V3 文档

`ac/skills-v3/*.edn` 与 `ac/vfx-v3/*.edn` 都是完整的结构化 V3 map，节点、阶段、
参数和 `:nid` 同时属于持久化模型。编辑器通过 `document/open-v3` 解析并调用
`node.document/validate-document!`，保存时由 `editor.v3/form->document` 重建 map，
再以 EDN 序列化写入工作区或显式导出路径。不存在旧的 `:program`/`:scene` 字符串
读取器，也不存在字节 splice；因此不会出现“运行时读取一套、编辑器保存另一套”的
双格式问题。

作者说明放在 `:doc` 数据字段中。`verifyContentEdnNoRawComments` 会扫描两个 V3
目录，禁止新增裸 EDN 注释；这使得编辑器保存、目录扫描和运行时加载都遵循同一份
结构化数据契约。

技能目录由 `skills-catalog-v3` 扫描，VFX 目录由 `fx-catalog-v3` 扫描；目录中的每个
文件都必须通过 V3 schema、节点唯一性和对应 capability 编译校验。编辑器 corpus
测试会逐个打开全部 50 个技能和 36 个 VFX 文件，确保真实内容与 UI 入口使用同一条
读取路径。

## `graph.clj` 的范围

支持 `let`/裸调用/`when`/`if`/`each`/`finish`/`state!`/`set!`/`event!`/`vfx!`
语句，任意深度嵌套（`if`/`when`/`each` 互相嵌套没有层数限制），以及任意深度的
纯表达式树。这个范围不是猜的——写 `graph_test.clj` 之前先 grep 了真实内容：
`if` 最初被想当然地认为很少见、准备跳过不支持，结果发现 50 个技能文件里 31 个
用了它，于是老老实实实现了，不是留一个"documented gap"。`ac/.../editor_corpus_test.clj` 会打开全部 50 个技能与 36 个场景效果，验证结构化 V3 schema、节点 ID 唯一性和编辑器读取路径，这是真实 corpus 校验，不是挑几个样例文件自证。

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
  有测试，但 `ac/vfx-v3/*.edn` 里零文件使用 `:emitters`——这个编辑器服务的是
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
- **画布缩放**：画布平移（拖空白处移动视口）已实现——`cn.li.ability.editor.
  hit` 的 `:panning` 模式一直存在且有测试，只是之前螢幕控制器没接（发现即修）。
  缩放不同：`presentation-core` 整套运行时里没有任何滚轮/捏合输入原语可用（查过
  代码，不是漏查）——平移是"模式已定义只是没接线"，缩放是"连能触发它的手势都不
  存在"，两者不是同一类缺口。

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
