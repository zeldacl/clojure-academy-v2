# 可视化编辑器重构执行计划（复核版）

本计划以当前源码为基线，不把设计愿望当成已存在的平台能力。目标是让技能图编辑器、场景效果编辑器和玩家法术合成器达到“信息少而完整、操作可预测、错误就地反馈”的体验。

## 1. 已确认的设计结论

| 参考产品 | 应吸收的原则 | 不直接照搬的部分 |
| --- | --- | --- |
| Psi / Hex Casting | 以语义步骤和可读的施法链为主；复杂参数逐步展开 | 不把所有表达式都拆成密集小节点 |
| Ars Nouveau | 分类调色板、即时合法性反馈、常用操作短路径 | 不把内容知识硬编码进通用编辑器核心 |
| Blueprint | 执行流与数据流分层、语义 pin、可移动节点、可平移画布 | 不引入无类型的任意连线 |
| Niagara | 模块栈、参数面板、预览与生产运行时隔离 | 当前没有 `:emitters` 内容时不虚构发射器编辑器 |

当前代码已经具备：V4 文档读写、自由二维执行/数据图、固定端口语义连线、节点移动与画布平移、调色板点击/拖放插入、ghost 与 drop 校验、实时编译诊断/代价读数、固定节点和组件的参数插槽、法术合成器的有界参数校验、两个编辑器可提交的 repeater 草稿、独立场景预览和工作区/导出双路径。节点编辑器还提供可由按钮或 Esc 关闭的 viewport 画布层，将有限的 128px 画布扩展为独立的 464×278px 操作区，同时保留原有缩放/平移相机；法术合成器的 effect slot 会按 augment 数量动态增高，augment 纵向堆叠并逐行删除，避免横向溢出固定控制区。

固定宽度的节点、调色板、诊断、glyph、参数和状态标签现在在控制器的显示模型层按实际字体宽度（无字体时用确定性回退）加省略号；原始节点文本、诊断数据和参数草稿不被截断。Presentation V4 的通用 `:ellipsize` 仍是独立能力，未被伪装成已实现。

## 2. 复核后纠正的矛盾

- “参数检查器尚未实现”已过时：节点检查器现在根据 palette schema 显示类型，并安全提交 `double/int/long/bool/keyword/vec3`；由 sigil、调用或其他节点驱动的输入保持只读。
- “画布不能拖动”已过时：节点移动、空白画布平移、pin 连线和调色板拖放已接入 `presentation-core` 的 pointer 路由。
- “法术合成器只维护数据、不展示分组细节”已过时：效果行现在显示并可单独删除已添加的 augment；效果重排/删除会同步重映射参数草稿，未完成或越界的草稿不能进入施法请求。
- “粒子编辑器延期”不是实现遗漏：当前 `ac/vfx-v4/*.edn` 的顶层 `:emitters` 全部为空；部分 `:graphs :render` 虽然已有 `:component :emitter` 调用，但这不是可供模块栈编辑器直接编辑的 emitter 数据。仍须先有内容 schema 和非空顶层样例，再启动 UI。
- glyph 物品、法术存储 NBT、准星锚点和文件选择器都需要当前仓库尚未提供的资源或平台契约，不能在现有中立 Presentation 层伪造完成；滚轮缩放已使用现有 `:scroll`，只有 pinch 仍等待独立手势契约。

## 3. 可执行阶段

### P0：合并当前已验证改动

1. 保留 `node_editor_reactive.clj` 的参数字段模型、解析、提交和非法值状态反馈。
2. 保留 `node_editor.ui.edn` 的选中节点参数滚动面板；小屏幕只显示有限高度，超出内容滚动。
3. 保留测试：插入 palette 节点、编辑数值 literal、提交后重建 graph/document、dirty 状态和状态文案。
4. 门禁：`compilePresentationViews`、节点编辑器和法术合成器定向测试、全量 `node-core`/`ability-runtime`/`combat-core`/`vfx-core`/`presentation-core`/`ac` Clojure 测试。

### P1：提高编辑效率（不改变文档契约，已完成）

1. 已为调色板条目增加明确的 pointer-drag 生命周期：`drag-start` 携带 palette id，画布 drop 使用当前坐标插入节点；点击仍保留为无拖拽的快捷插入。
2. 已加入临时 ghost 节点、有效/无效 drop 颜色和 Esc 取消；drop 只调用 `graph/insert-palette-node`，不直接拼接 EDN。
3. 已在 `presentation-core` 增加通用 drag payload/capture 原语，并为旧按钮行为补回归测试。
4. 已验证拖动、点击回退、取消、越界和重复拖动；无 pointer-drag 的宿主仍走点击路径。

### P2：减少认知负担（基础交互已完成，仍有增强项）

1. 调色板改为“搜索 + 分类折叠 + 最近使用”三层结构；保留当前按 category/id 的稳定排序作为无搜索回退。
2. 画布缩放基础版已使用 Presentation 既有的中立 `:scroll` 事件，实现 50%–200% 限幅、以光标为中心缩放和缩放复位；viewport 模式用覆盖层提供大画布，Esc 可退出且不改变图坐标；pinch 仍等待独立的中立手势契约。
3. 节点检查器已按类型提供 bool 切换、数值 stepper；schema 提供 `:choices` 时 keyword 使用循环选择，vec3 字面量使用 x/y/z 三轴输入；无元数据时仍保留安全文本回退。
4. 合成器的效果重排按钮在首/末项通过 `:visible` 绑定隐藏，避免显示点击后无效的边界控制；施法按钮保持可见，由 action 校验并就地显示缺失表单、参数或服务端拒绝原因。
5. 验收：键盘可达（运行时支持 Tab/Shift-Tab 环回，覆盖 repeater 实例）、非法值和非法索引 payload 不污染 graph/组合状态（包括 phase、参数/vec3 轴草稿、glyph、effect 选择和 augment 删除）、节点编辑器撤销/重做边界明确；合成器本轮明确只提供 Clear 重置，不伪造跨会话 undo/redo、缩放不改变保存坐标语义。

### P3：内容与游戏集成

1. 先定义 spell-storage 数据组件和网络版本号，再实现 glyph 物品、模型/贴图、配方和 NBT 迁移；客户端界面只提交 canonical glyph vector，服务端继续作为最终校验者。
2. 定义客户端相机/准星锚点 SPI（位置、方向、生命周期、无玩家时的降级），再把场景预览的目标点绑定到该 SPI；预览 runtime 与生产 runtime 继续隔离。
3. 当首个 V4 场景真正包含 `:emitters`、模块参数和可编译样例后，建立粒子发射器编辑器：emitter/module 树、参数面板、独立 preview、编译诊断。
4. 增加文件选择器前，先定义允许目录、扩展名、路径穿越防护、外部修改指纹和失败回退；现有 caller-supplied path 作为安全后备。

P3 的执行顺序固定为“契约 → 中立实现 → 平台适配 → UI 接入 → 迁移/回滚”，不得跳过契约直接在编辑器里写临时格式：

| 工作包 | 先决条件 | 可交付物 | 验收门槛 |
| --- | --- | --- | --- |
| S：法术存储 | 选定数据 owner；确认物品生命周期 | `spell-schema-version`、canonical glyph codec、物品读写适配、迁移器、配方和模型 | 旧 NBT 可读、新旧版本可回滚；客户端只发 canonical vector；服务端拒绝未知 glyph/超长参数 |
| C：相机锚点 | 平台能提供位置、方向、生命周期和无玩家降级 | 中立 `camera-anchor` SPI、Minecraft 适配、预览 target binding | 无相机时预览仍可打开但显示明确降级状态；生产 runtime 不依赖预览实例 |
| E：发射器编辑器 | 至少一个真实 V4 `:emitters` fixture，且能通过 schema/compile | emitter/module 树、参数检查器、独立 preview、诊断 | fixture round-trip、编译错误就地显示、预览停止不残留生产实体 |
| F：文件选择 | 确认允许根目录、扩展名和外部修改检测策略 | 安全路径校验器、文件选择 UI、caller-supplied fallback | 路径穿越/越权扩展名拒绝；外部修改提示覆盖/另存；无选择器平台仍可编辑 |

版本边界需要明确区分：`ac/ability/messages.clj` 的 AC 运行时消息目录声明的是 Protocol v2；`mcmod/runtime/fixed_channel.clj` 的二进制帧头当前仍是独立的 `protocol-version 1`。S 工作包默认新增独立的 `spell-schema-version`，只有改变固定帧 envelope 时才升级后者，并同时提供兼容窗口和拒绝原因。这样不会把“消息目录 v2”“固定帧 v1”和“法术数据 schema 版本”误写成同一个版本号。

## 4. 每阶段统一验收清单

- 纯函数层：graph round-trip、pin 方向、稳定 `:nid`、非法引用诊断。
- Presentation 层：view schema、focus/submit/change、pointer capture、Esc、scroll 不回归。
- 内容层：全量技能/VFX corpus 能打开、保存后仍通过 V4 schema 和 catalog 校验。
- 运行时层：编辑器预览不写生产 runtime；服务端拒绝非法法术和超限参数。
- 人机评估：真实游戏中验证 480×360/320×240 的文字截断、滚动、点击命中、拖拽手感和帧时间；这些不能由当前离线门禁替代。

## 5. 当前状态与下一步

P0/P1 已完成并通过门禁；P2 基础版及 schema 驱动的 keyword/vec3 控件、Tab/Shift-Tab 焦点遍历和 viewport 大画布模式已完成并有回归测试，剩余是 pinch 和真实游戏人机评估。P3 的物品/NBT、准星和 emitter 仍依赖内容/平台契约；这些不是 UI 层可以单方面“补齐”的项目，必须按上面的前置契约逐项解锁。这里将“本轮 UI 代码交付”与“产品发布验收”分开：前者以步骤 1–4 和工作区边界为准，后者再叠加步骤 5；P3 是后续独立里程碑，不阻塞本轮编辑器代码交付。

为避免把“离线验证通过”误写成“整体验收完成”，当前执行状态明确如下：

| 步骤 | 状态 | 证据/输出 | 仍需动作 |
| --- | --- | --- | --- |
| 1 源码/视图收束 | 已完成 | viewport、坐标逆变换、repeater 命中包装器、phase item payload 均有源码和回归覆盖 | 无 |
| 2 视图产物 | 已完成 | `:ac:compilePresentationViews`、`verifyPresentationGoldenArtifacts` 通过；golden 已同步 | 无 |
| 3 定向回归 | 核心已完成，AC 当前受基线阻断 | 本轮重新执行的 node-core 131/320、ability-runtime 80/182 均 0 failures/0 errors；AC 定向测试无法进入测试运行器，因为 `:ac:compileClojure` 先在既有 `cn.li.ac.block.developer.presentation` 的缺失 `cn.li.ac.block.developer.console` 处失败；此前覆盖的 schema 标量越界、pointer 拖拽清理、drop-zone 拒绝、滚轮边界、长标签省略、vec3 草稿和 320×240 压力用例仍保留在源码 | 补回/修复该既有 namespace 后，从定向 AC 测试开始重跑 |
| 4 全量门禁 | 核心通过，AC 受既有基线阻断 | 本轮 node-core 131/320、ability-runtime 80/182、combat-core 56/178、vfx-core 37/118、presentation-core 48/178、`verifyNodeLayerDiscipline` 和 `verifyPresentationGoldenArtifacts` 均通过；AC `checkClojure` 在加载既有 `cn.li.ac.block.developer.presentation` 时因缺失 `cn.li.ac.block.developer.console` 失败，不能标记 AC 全量通过 | 补回/修复该既有缺失 namespace 后再运行 AC 全量门禁 |
| 5 真实游戏验收 | 待外部 | 本轮遵守“不跑 `runClient`”，没有伪造画面/手感结论 | 由具备游戏窗口的验收者按第 5 步记录可复现结果 |
| 6 提交边界 | 已提交 | 相关源码、视图、测试、文档和 golden 已限定；无关未跟踪文件保留；后续参数草稿、端口和验证记录均已分别提交 | 保持只提交本计划涉及文件 |

## 6. 当前工作树的可执行收束步骤

以下步骤是把本次复核后的实现安全交付的最短路径；每一步失败都应先修复再进入下一步：

1. **源码/视图收束**：确认 `node_editor_reactive.clj` 的 viewport 状态、按钮 action、Esc 关闭和旧 canvas 拖拽分支同时存在；phase tab action 必须同时兼容顶层和 repeater `:item` payload；确认 `node_editor.ui.edn` 用 `:stack` 将固定高度的 `:node-editor/base` 列与 464×278 viewport 覆盖层分离，紧凑画布、覆盖层及状态绑定成对出现，避免隐藏 overlay 仍参与 column 流布局；画布 repeater 必须使用 `:direction :none`，并以条目自身的 x/y/w/h 作为绝对命中包装器，composite 使用局部偏移；屏幕指针进入拖放、ghost 和滚轮缩放前必须扣除当前画布起点并按 zoom 逆变换，节点拖动增量也必须换算回图坐标。坐标审计要以运行时全局 design-space 为准：根布局的 `{:x 8 :y 8}` 必须计入，紧凑画布起点为 `{:x 8 :y 88}`，viewport 内层画布起点为 `{:x 8 :y 54}`；同时确认基础列声明高度不小于所有固定行高度之和。
2. **视图产物**：运行 `cmd /c gradlew.bat :ac:compilePresentationViews --quiet`，将 `build/neutral/ac/generated/resources/presentation/assets` 下的变更同步到 `docs/06-gui/presentation/golden/assets`，再运行 `cmd /c gradlew.bat :verifyPresentationGoldenArtifacts --quiet`。
3. **定向回归**：运行 `cmd /c "gradlew.bat -Dac.test.only=cn.li.ac.ability.client.screens.node-editor-reactive-test,cn.li.ac.ability.client.screens.spell-composer-reactive-test :ac:runAcClojureTestsFast --quiet"`；必须覆盖 viewport 展开/渲染可见性/Esc 关闭、节点拖拽不产生 palette ghost、紧凑/viewport 屏幕坐标逆变换、缩放锚点、缩放后拖动和保存坐标，以及法术效果/augment 重排、增幅纵向布局与独立删除命中、参数草稿、非法值拒绝和法术合成器在 320×240 宿主窗口下的布局边界。随后运行 Presentation core 测试中的 compiled node-editor 与 spell-composer smoke，覆盖实际 golden artifact：节点编辑器在 480×360 与 320×240 宿主下的绘制/命中，法术合成器在 480×320 设计尺寸与 320×240 宿主缩放下的绘制，以及 8 个 augment 最大列表的滚动布局和第 8 行关键条目命中。
4. **全量门禁**：依次运行 `cmd /c gradlew.bat :node-core:runNodeCoreClojureTests --quiet`、`cmd /c gradlew.bat :ability-runtime:runAbilityClojureTests --quiet`、`cmd /c gradlew.bat :combat-core:runCombatClojureTests --quiet`、`cmd /c gradlew.bat :vfx-core:runVfxClojureTests --quiet`、`cmd /c gradlew.bat :presentation-core:runCoreClojureTests --quiet`、`cmd /c gradlew.bat :ac:runAcClojureTests --quiet` 和 `cmd /c gradlew.bat verifyCurrentPlatforms --stacktrace`；与本改动相关的失败必须修复，若被已存在的外部基线问题阻断，则记录精确错误、确认核心定向测试仍通过，并不得将该门禁标为通过。
5. **真实游戏验收（外部人工步骤）**：在 480×360 与 320×240 两种宿主窗口验证节点编辑器默认紧凑模式、viewport 打开/关闭、拖拽/滚轮缩放、Tab 焦点和文本截断；在 320×240 宿主窗口额外验证 480×320 设计的法术合成器是否可滚动且不遮挡提交控件。记录帧时间与命中问题，只有真实宿主缺陷才进入 pinch/SPI/P3 队列。本轮按用户约束不启动 `runClient`，因此此项保持“待外部验收”而不是伪造完成；具备游戏窗口的验收者应按此清单执行并回填记录。
6. **提交边界**：只提交本计划涉及的源码、视图、测试、文档和 golden；保留工作区中与本任务无关的生成目录/脚本，不做清理或 reset。

### 6.1 外部验收记录模板

具备游戏窗口的验收者按下表逐行填写；“证据”应是截图、录屏时间点或可复现操作描述，不能只写“正常”。

| 宿主尺寸 | 编辑器/模式 | 操作 | 预期结果 | 实际结果/证据 | 结论 |
| --- | --- | --- | --- | --- | --- |
| 480×360 | 节点/紧凑 | 选节点、拖动节点、空白处拖动 | 节点/视口分别移动，释放后不残留 ghost |  |  |
| 480×360 | 节点/viewport | 打开、平移、滚轮缩放、Esc 关闭 | 画布扩大；缩放以指针为锚；关闭后 layout 不变 |  |  |
| 320×240 | 节点/紧凑+viewport | Tab/Shift-Tab、长文本、参数滚动 | 焦点环回，文本可辨识，底部操作不被遮挡 |  |  |
| 480×320 | 法术合成器 | 添加 8 个 augment、滚动、删除第 8 行 | augment 纵向排列，滚动后命中正确，Cast/Clear 可见 |  |  |
| 320×240 | 法术合成器 | 重复上述操作并提交非法参数 | 宿主缩放不破坏命中；非法值就地拒绝且不改变已提交值 |  |  |

完成定义分两层，避免把无法在当前环境执行的动作误报为已完成：

- **本轮 UI 代码交付完成**：步骤 1 的源码审计、步骤 2 的视图产物、步骤 3 的定向回归和与本改动相关的步骤 4 门禁全部通过；`git diff --check` 通过；只保留本计划涉及的源码、视图、测试、文档和 golden 变更。若全量门禁被仓库既有缺失文件阻断，必须在交付说明中明确标注为“未通过”，不运行 `runClient` 也可以据此交付代码。
- **产品发布验收完成**：在代码交付完成的基础上，步骤 5 具有可复现记录。真实游戏验收由具备游戏窗口的验收者执行，本轮不因用户明确禁止 `runClient` 而伪造该记录。
- **P3 工作包完成**：S/C/E/F 各自满足“先决条件—交付物—验收门槛”，作为后续版本里程碑，不回填为本轮 UI 已完成项。

