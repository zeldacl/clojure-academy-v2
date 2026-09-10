# Node Editor

三个规划中的可视化编辑器（技能/VFX 图编辑器、玩家法术合成器、粒子发射器编辑器）
里，前两个已实现；第三个按设计文档自己的决策点评估后暂不做（`ac/vfx-v4/*.edn`
里没有任何一个文件包含非空的顶层 `:emitters`（system render 内的 `:component :emitter`
调用不等同于顶层 emitter 模块数据，见下）。设计结论、复核结果和可执行步骤统一记录在
[NODE_EDITOR_EXECUTION_PLAN.md](NODE_EDITOR_EXECUTION_PLAN.md)；不依赖工作区外的个人计划文件。

语言层规格见 [NODE_LANGUAGE.md](../04-systems/NODE_LANGUAGE.md)（尤其是其中
`:nid` 稳定性契约那一节）——本文档只描述编辑器本身。

复核后的分阶段实施、验收门禁和依赖关系见
[NODE_EDITOR_EXECUTION_PLAN.md](NODE_EDITOR_EXECUTION_PLAN.md)。

## 游戏内入口

两个屏幕原本都没有任何触发方式（`open!` 只在自己的源文件和
`presentation_surface_manifest.clj` 清单里出现过）——`/aim` 这类命令是服务端
Brigadier 命令，而这两个屏幕的 `open!` 挂载的是客户端 Presentation 视图，中间
没有现成桥（也没打算建，属于新平台面、这个环境验证不了，见上面几节同类判断的
一贯标准）。现有入口都是纯客户端事件，照抄 `preset-editor` 绑 `N` 键的先例：

- **G 键**——打开节点编辑器（技能模式，固定加载 `ac/skills-v4/thunder-bolt.edn`
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
    document.clj   打开/undo/redo/结构化 V4 保存契约
    graph.clj      表层 form ⇄ 节点/连线图,双向无损
    check.clj      诊断(cn.li.node.compile :collect 模式) + 静态代价读数
    render.clj     图 → 扁平 composite-item 向量(exec 链视图) + 正交连线
    hit.clj        指针事件 → 拖拽状态机(节点选中/移动、平移)

ac/src/main/clojure/cn/li/ac/ability/client/screens/
    node_editor_reactive.clj      技能模式 + 场景效果模式,同一个屏幕
    spell_composer_reactive.clj   玩家法术合成器

ac/src/presentation/resources/academy/app/
    node_editor.ui.edn       480×360
    spell_composer.ui.edn    480×320（另有 320×240 宿主窗口压力测试）
```

`verifyContentModuleCoreIsolation` 允许 `ac` 直接 require 的核心命名空间里，
`cn.li.node.ops`/`cn.li.vfx.api` 是专为这套编辑器加的（词汇表调色板与场景模式的
per-file capabilities 推导需要）。`ability-runtime/editor/*` 本身不含任何具体
技能/效果 id——`verifyCoreNoSkillKnowledge` 会扫描这一点；词汇表、文档列表、效果
`:inputs` 表全部由 `ac` 侧作为数据注入。

## 保存：结构化 V4 文档

`ac/skills-v4/*.edn` 与 `ac/vfx-v4/*.edn` 都是完整的结构化 V4 map，节点、阶段、
参数和 `:nid` 同时属于持久化模型。编辑器通过 `document/open-v4` 解析并调用
`node.api/validate-v4-document!`，保存时由 `editor.document/save` 校验并重建 map，
再以 EDN 序列化写入工作区或显式导出路径。工作区与源文件写入使用临时文件 + 原子替换；
导出前比较打开时的源文件指纹，外部修改会阻止覆盖并要求 Reload。不存在旧的 `:program`/`:scene` 字段
读取器，也不存在字节 splice；嵌套在 damage policy 中的 `:program` 是 Combat Core
当前策略载荷字段，不是旧技能文档包装器；因此不会出现“运行时读取一套、编辑器保存另一套”的
双格式问题。

V4 图的固定节点集合为 `start`、`component`、`branch`、`merge`、`foreach`、
`repeat`、`loop-end`、`end`、`literal`、`context-ref`、`parameter-ref`、
`state-ref`、`local-get`、`local-set`。其中数据节点只提供 `value` 输出；
组件的数据输入由 inline `:inputs` 或数据线目标端口声明。所有固定端口、循环边和
分支收敛由 node-core validator 与 graph compiler 双重检查。

作者说明放在 `:doc` 数据字段中。`verifyContentEdnNoRawComments` 会扫描两个 V4
目录，禁止新增裸 EDN 注释；这使得编辑器保存、目录扫描和运行时加载都遵循同一份
结构化数据契约。

技能目录由 `skills-catalog` 扫描，VFX 目录由 `fx-catalog` 扫描；目录中的每个
文件都必须通过 V4 schema、节点唯一性和对应 capability 编译校验。编辑器 corpus
测试会逐个打开全部 50 个技能和 36 个 VFX 文件，确保真实内容与 UI 入口使用同一条
读取路径。

## `graph.clj` 的范围

支持 `let`/裸调用/`when`/`if`/`each`/`finish`/`state!`/`set!`/`event!`/`vfx!`
语句，任意深度嵌套（`if`/`when`/`each` 互相嵌套没有层数限制），以及任意深度的
纯表达式树。这个范围不是猜的——写 `graph_test.clj` 之前先 grep 了真实内容：
`if` 最初被想当然地认为很少见、准备跳过不支持，结果发现 50 个技能文件里 31 个
用了它，于是老老实实实现了，不是留一个"documented gap"。`ac/.../editor_corpus_test.clj` 会打开全部 50 个技能与 36 个场景效果，验证结构化 V4 schema、节点 ID 唯一性和编辑器读取路径，这是真实 corpus 校验，不是挑几个样例文件自证。

`render.clj` 对生产用 V4 文档直接绘制自由二维节点图：执行节点、数据节点和两类
语义连线分别渲染，节点位置来自 layout sidecar，节点内部显示实际参数槽和 wired
状态；viewport 只是同一张图的放大操作层，不改变文档坐标。`graph/exec-flatten`/
`graph->form` 只属于中立表层 DSL 编译器的内部测试/降级路径，不读取或写入 V4
技能文档，也不是 V3 编辑器回退；生产编辑器不会把 V4 图重新压扁成一条执行链。V4 节点通过固定端口约束连接：普通执行节点
最多一个后继，branch 只有 true/false，foreach/repeat 有 body/completed，所有
多路汇合必须显式经过 merge；保存和编译都会再次校验这些约束。

## 已知边界与后续工作（不是遗漏）

- **调色板交互（基础版已完成）**：调色板支持搜索、分类折叠、最近使用、点击或鼠标拖拽插入执行节点，并将声明参数的默认值写入节点内置输入槽；拖拽期间显示 ghost，释放位置有有效/无效反馈，Esc 可取消，
  无移动的点击仍走快捷插入。执行节点移动、选择、空白画布平移，以及表达式输出到节点
  输入的语义连线也已实现。若 schema 提供 `:choices`，keyword 使用循环选择；vec3 字面量显示 x/y/z 三轴输入；无元数据时仍保留文本回退。法术合成器的效果行会显示并可删除 augment，重排/删除效果不会错配参数草稿。后续体验迭代是 pinch 缩放和真实游戏人机评估。
  固定宽度的显示标签会在控制器层按字体宽度加省略号，完整值仍保留在状态和文档中；这不等同于 Presentation V4 尚未提供的通用 `:ellipsize`。
- **玩家法术的 glyph 物品 / 法术存储物品 NBT**：需要贴图、模型 json、合成表，
  这个环境创建不了也验证不了。当前合成器由 K 键和
  `spell_composer_dev_tool` 开发道具打开，不依赖 glyph 或存储物品；服务端提交/校验/派发路径
  （`MSG-REQ-SPELL-SUBMIT` → `combat-runtime/dispatch-player-spell!`）本身已经完整可用。
- **粒子发射器编辑器**：`vfx-core/compile.clj` 的 Niagara 式模块栈机制存在且
  有测试，但 `ac/vfx-v4/*.edn` 的顶层 `:emitters` 全部为空（system render 内的
  `:component :emitter` 只是调用）——这个编辑器服务的是
  尚不存在的内容。按设计文档自己定的决策点：没有真实 `:emitters` 内容就不做，
  不是欠债。
- **参数编辑边界（基础版已完成）**：法术合成器提供基于 glyph schema 的有界数值输入（非法、越界和
  非有限值会保留旧值并给出状态提示）；技能/场景节点检查器也已把 palette schema
  映射到选中节点的参数检查器，可编辑 `literal`、向量字面量和映射字面量，并在提交
  前按 `double/int/bool/keyword/vec3` 做解析、有限值和 schema `min/max` 校验。由其他节点、sigil 或调用
  驱动的输入保持只读，必须通过语义连线修改，避免检查器悄悄改变图的拓扑。
  两个编辑器的 repeater 文本输入都通过独立 `draft-key` 回写 view state，字符输入、退格
  和 Enter 提交不会读取旧快照。
- **场景效果准星定位**：场景模式已有 Preview/Stop 生命周期，使用独立的
  `client-vfx-v2` runtime、只读场景输入并在 screen tick 中推进，关闭窗口会清理
  owner；它不会污染生产 runtime。当前仍未绑定玩家准星，因为仓库没有可复用的
  客户端相机锚点契约；待该契约建立后再增加位置发布。
- **画布缩放与 viewport**：画布平移和基于既有 `:scroll` 事件的 50%–200% 光标锚定缩放已实现，
  保存的 layout 仍保持图坐标。点击 cost 行的“Expand canvas”会打开独立 viewport 操作层（464×278px），
  画布不再被参数/诊断区挤压；按钮或 Esc 关闭后恢复紧凑布局。运行时已支持 Tab/Shift-Tab 在可见控件间环回，当前剩余是等待独立中立 pinch 手势契约。

## 验证状态

核心架构/AOT 门禁、`compilePresentationViews`、node-core 与 ability-runtime 定向
测试已通过，且在开发过程中真的抓到过几个实质 bug（`:semantics {:role :status}`
不是合法角色、一个没有默认分支的 `case` 会在点击节点标签时抛异常、
`(name :form/self)` 会悄悄丢掉命名空间）。developer console namespace 已恢复；本轮 AC 全量测试与相关架构门禁已重新执行并通过。

**没有验证、也无法在这个环境里验证的**：画面实际渲染效果、点击/拖拽的手感、
480×360（节点编辑器设计尺寸）与 320×240（缩放后的宿主窗口）下四个面板是否真的挤得下。这部分需要在真实
游戏里试。

