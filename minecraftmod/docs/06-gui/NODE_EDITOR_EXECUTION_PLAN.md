# 可视化编辑器重构执行计划（复核版）

本计划以当前源码为基线，不把设计愿望当成已存在的平台能力。目标是让技能图编辑器、场景效果编辑器和玩家法术合成器达到“信息少而完整、操作可预测、错误就地反馈”的体验。

## 1. 已确认的设计结论

| 参考产品 | 应吸收的原则 | 不直接照搬的部分 |
| --- | --- | --- |
| Psi / Hex Casting | 以语义步骤和可读的施法链为主；复杂参数逐步展开 | 不把所有表达式都拆成密集小节点 |
| Ars Nouveau | 分类调色板、即时合法性反馈、常用操作短路径 | 不把内容知识硬编码进通用编辑器核心 |
| Blueprint | 执行流与数据流分层、语义 pin、可移动节点、可平移画布 | 不引入无类型的任意连线 |
| Niagara | 模块栈、参数面板、预览与生产运行时隔离 | 当前没有 `:emitters` 内容时不虚构发射器编辑器 |

当前代码已经具备：V3 文档读写、执行/数据图、语义连线、节点移动与画布平移、调色板点击/拖放插入、ghost 与 drop 校验、诊断/代价读数、参数检查器（literal/vector/map 字面量）、法术合成器的有界参数校验、独立场景预览和工作区/导出双路径。

## 2. 复核后纠正的矛盾

- “参数检查器尚未实现”已过时：节点检查器现在根据 palette schema 显示类型，并安全提交 `double/int/long/bool/keyword/vec3`；由 sigil、调用或其他节点驱动的输入保持只读。
- “画布不能拖动”已过时：节点移动、空白画布平移、pin 连线和调色板拖放已接入 `presentation-core` 的 pointer 路由。
- “粒子编辑器延期”不是实现遗漏：当前 `ac/vfx-v3/*.edn` 没有任何 `:emitters` 内容，只有 `vfx-core` 的编译机制；必须先有内容 schema 和样例，再启动 UI。
- glyph 物品、法术存储 NBT、准星锚点、滚轮缩放、文件选择器都需要当前仓库尚未提供的资源或平台契约，不能在现有中立 Presentation 层伪造完成。

## 3. 可执行阶段

### P0：合并当前已验证改动

1. 保留 `node_editor_reactive.clj` 的参数字段模型、解析、提交和非法值状态反馈。
2. 保留 `node_editor.ui.edn` 的选中节点参数滚动面板；小屏幕只显示有限高度，超出内容滚动。
3. 保留测试：插入 palette 节点、编辑数值 literal、提交后重建 graph/document、dirty 状态和状态文案。
4. 门禁：`compilePresentationViews`、节点编辑器定向测试、全量 `ability-runtime`/`ac` Clojure 测试。

### P1：提高编辑效率（不改变文档契约，已完成）

1. 已为调色板条目增加明确的 pointer-drag 生命周期：`drag-start` 携带 palette id，画布 drop 使用当前坐标插入节点；点击仍保留为无拖拽的快捷插入。
2. 已加入临时 ghost 节点、有效/无效 drop 颜色和 Esc 取消；drop 只调用 `graph/insert-palette-node`，不直接拼接 EDN。
3. 已在 `presentation-core` 增加通用 drag payload/capture 原语，并为旧按钮行为补回归测试。
4. 已验证拖动、点击回退、取消、越界和重复拖动；无 pointer-drag 的宿主仍走点击路径。

### P2：减少认知负担

1. 调色板改为“搜索 + 分类折叠 + 最近使用”三层结构；保留当前按 category/id 的稳定排序作为无搜索回退。
2. 画布增加缩放前置设计：先在 Presentation 输入契约中定义 wheel/pinch 的中立事件，再实现 50%–200% 限幅、以光标为中心缩放和缩放复位；没有该事件契约前不在控制器中猜事件字段。
3. 节点检查器按参数类型渲染专用控件（number slider/stepper、bool、keyword 选择、vec3 三轴），文本框作为无 schema/高级模式回退。
4. 验收：键盘可达、非法值不污染 graph、撤销/重做边界明确、缩放不改变保存坐标语义。

### P3：内容与游戏集成

1. 先定义 spell-storage 数据组件和网络版本号，再实现 glyph 物品、模型/贴图、配方和 NBT 迁移；客户端界面只提交 canonical glyph vector，服务端继续作为最终校验者。
2. 定义客户端相机/准星锚点 SPI（位置、方向、生命周期、无玩家时的降级），再把场景预览的目标点绑定到该 SPI；预览 runtime 与生产 runtime 继续隔离。
3. 当首个 V3 场景真正包含 `:emitters`、模块参数和可编译样例后，建立粒子发射器编辑器：emitter/module 树、参数面板、独立 preview、编译诊断。
4. 增加文件选择器前，先定义允许目录、扩展名、路径穿越防护、外部修改指纹和失败回退；现有 caller-supplied path 作为安全后备。

## 4. 每阶段统一验收清单

- 纯函数层：graph round-trip、pin 方向、稳定 `:nid`、非法引用诊断。
- Presentation 层：view schema、focus/submit/change、pointer capture、Esc、scroll 不回归。
- 内容层：全量技能/VFX corpus 能打开、保存后仍通过 V3 schema 和 catalog 校验。
- 运行时层：编辑器预览不写生产 runtime；服务端拒绝非法法术和超限参数。
- 人机评估：真实游戏中验证 480×360/320×240 的文字截断、滚动、点击命中、拖拽手感和帧时间；这些不能由当前离线门禁替代。

## 5. 当前状态与下一步

P0/P1 已完成并通过门禁；P2 的缩放依赖新的输入原语，P3 的物品/NBT、准星和 emitter 依赖内容/平台契约。下一次实现迭代应从 P2 的输入契约和专用参数控件开始，而不是先扩展更多节点类型。
