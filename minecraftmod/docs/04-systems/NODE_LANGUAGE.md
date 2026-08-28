# Node Language 规格（schema v3）

> 唯一权威来源。`combat-core`/`vfx-core` 的组件注册表、`node-core` 的实现、以及
> [COMBAT_CORE.md](COMBAT_CORE.md)/[VFX_CORE.md](VFX_CORE.md) 的模块描述都必须与本文一致；
> 三者出现分歧时，先假设本文档正确，去找代码或另一篇文档的 bug。
>
> 背景与设计过程记录在 `C:\Users\lxy\.claude\plans\ac-combat-core-combat-core-vfx-core-ac-mossy-wren.md`
> （诊断、决策依据、执行阶段）。本文档只记录**最终规格**，不记录过程。

## 0. 目标

技能与特效内容用一棵节点树描述，分四类节点：

```
技能 source 图 (source + primitive/composite, EDN)  ──展开──>  执行图 (primitive, EDN ABI)  ──宿主结算──>  Minecraft adapter
```

四类节点共用同一套语言（描述符、表达式、作用域、composite 展开），语言本体不依赖
Minecraft，放在 `node-core` 模块。`combat-core`/`vfx-core` 各自在其上注册词汇表。

设计目标（用户确认的硬约束）：
1. 节点必须能自由组合——**不允许隐式依赖**：一个节点的行为只由它自己声明的输入决定，不读任何未声明的环境/全局状态，搬到任意位置行为不变。
2. **只有底层原语可以用函数实现**（因为需要经 mcmod 调 Minecraft API）；**组合层节点必须是纯 EDN**，由底层原语组合而成，不允许有自己的 Clojure 实现。
3. 语言本身要足够机器可读，能驱动一个类似 Unreal 蓝图的可视化技能编辑器：每个节点的输入/输出要有类型、范围、默认值、文档。

## 1. 四类节点规则

| 层 | `:layer` | 形态 | 可以做什么 | 注册方式 |
|---|---|---|---|---|
| 底层原语 | `:primitive` | Clojure 函数 | 可调用 mcmod / Minecraft；**不可调用其它节点** | `register-primitive!`，必须带 `:impl` |
| 组合层语义 | `:composite` | 纯 EDN（composite 文档） | 只能组合已注册的原语/组合层节点 | `load-composite!`，**禁止** `:impl` |
| 上层技能 | `:source`/ability 文档 | 纯 EDN（technique 文档） | 组合 composite 与 primitive；唯一允许使用 source 节点的层 | manifest 文档 |

机械强制（见 §7 门禁）：
- 注册期直接拒绝 `:layer :composite` 且携带 `:impl` 的描述符——语言实现层面不给"组合层用函数抄近路"留出口。
- 静态扫描：任何 `:layer :composite` 的组件 id，不得出现在任何 `defmethod`/handler-table/capability-table 里——防止有人绕开 `load-composite!` 直接在解释器里为某个"组合层" id 加分支。
- 原语总数被测试钉死（一个显式的 pin 常量），新增原语必须显式修改这个 pin——防止组合层逻辑在无人注意时悄悄"降级"为一批新原语。

## 2. 描述符 = 完整端口契约

```clojure
;; 底层原语示例：combat-core 里唯一允许触碰 mcmod 的地方之一
(node/register-primitive!
 {:id       :target/raycast
  :revision 3
  :layer    :primitive
  :doc      "沿方向投射一条射线，返回第一个命中实体或方块。"
  :category :targeting
  :inputs   {:origin    {:type :vec3   :doc "起点（世界坐标）"}
             :direction {:type :vec3   :doc "方向，需为单位向量"}
             :distance  {:type :double :min 0.0 :max 256.0 :default 32.0 :doc "最大距离（格）"}}
  :outputs  {:hit {:type :hit-result :doc "命中结果；未命中为 nil"}}
  :effects  #{:query}
  :impl     (fn [{:keys [origin direction distance]} _ctx]
              {:hit (raycast/cast origin direction distance)})})
```

字段：

- `:id` — 命名空间关键字，`<domain>/<name>`。
- `:revision` — 正整数，破坏性变更时递增；旧内容编译期报错而不是静默改变行为。
- `:layer` — `:primitive` | `:composite` | `:source`（见 §5）。
- `:doc` — 一句话说明，编辑器节点面板的悬浮提示。
- `:category` — 编辑器面板分组（`:targeting` `:elemental` `:motion` ...）。
- `:inputs` — `{key {:type t :min :max :default :doc}}`。**强制**：文档里出现未声明的键是编译错误；声明了 `:default` 的键可省略；类型不匹配是编译错误。
- `:outputs` — `{key {:type t :doc}}`。多输出端口原生支持（不再是 `:produces` 时代的"实际只支持一个"）。
- `:children` — 仅结构性节点使用，声明子树位置：`{key {:kind :single|:seq|:case-map :required? bool}}`。
- `:effects` — `#{:pure :query :mutate :emit}`，纯度标记，供 `guard/*` 类节点与编辑器高亮使用。
- `:impl` — 仅 `:primitive` 合法。签名 `(fn [inputs ctx] outputs-map)`。调用边界是 `cn.li.node.runtime/invoke-primitive!`：它先把节点已求值字段 `select-keys` 到该描述符 `:inputs` 声明的键集合，再调用 `:impl`——`:impl` 物理上不可能读到一个自己没声明的字段，这是描述符与实现不可能漂移的机械保证（vfx-core 现存的 8 处漂移就是没有这层强制导致的）。

## 3. 类型格

浅结构式，够表达当前内容即可，不追求泛型：

- 标量：`:double :long :boolean :keyword :string`
- 复合字面量：`:vec3 :color`
- 不透明句柄（原语间传递、编辑器画成"对象"引脚，不可展开成基础类型）：
  `:hit-result :destination :entity-ref :entity-list :block-list :owner-snapshot
   :item-snapshot :terrain-plan :beam-result :energy-target :render-op`
- 结构：`:node`（子树，见 §6 回调型输入）、`[:list-of t]`、`:map`

## 4. 连线 = 显式端口 + 词法作用域

不存在全局 slot 命名空间。绑定通过 `:bind` 声明局部名，读取通过 `{:ref [:local name & path]}`：

```clojure
{:component :flow/sequence
 :steps [{:component :target/raycast
          :origin {:ref [:local :eye]} :direction {:ref [:local :aim]} :distance 32.0
          :bind {:hit :aim-hit}}
         {:component :combat/damage
          :target {:ref [:local :aim-hit :entity]}
          :amount 10.0}]}
```

作用域规则：

- `:flow/sequence` 开一层作用域；`:bind` 只对其后的兄弟节点及其后代可见。
- `:flow/branch` / `:flow/window` / `:txn/atomic` 每个分支是独立作用域，**绑定不逃逸**——需要跨分支使用的值必须在分支之前绑定，或由分支节点自己声明输出。
- `:flow/foreach` 体是封闭作用域，循环变量是普通局部名，不外泄。
- **composite 体是封闭作用域**：只能看见自己声明的 `:inputs`（以及回调型输入注入的 `:scope`，见 §6），只能通过声明的 `:outputs` 导出。
- 除 `:local` 外没有其它可读作用域——没有 `:slot`、`:from`、`:tunable`、`:context`。环境读取只能通过 §5 的 source 节点，且仅在上层技能文档合法。

`node-core/scope.clj` 做作用域链 + 类型检查（combat-core 现有 `dataflow.clj` 的"扁平集合确定赋值分析"的升级版），错误带 `:path`（节点路径向量），供编辑器直接定位画波浪线。

## 5. Source 节点：唯一允许的环境读取

Source 节点是"环境边界"的显式化——环境读取无法被消除，只能被显式化、类型化、可枚举。`:layer :source`，只能出现在**上层技能文档顶层**，`:composite`/`:primitive` 中出现即编译错误。

combat-core 的 source 节点（六个）：

| id | 作用 | 输出 |
|---|---|---|
| `:ability/caster` | 施法者位置/朝向快照 | `:eye :aim :body`（均 `:vec3`） |
| `:ability/tunable` | 读取本技能声明的一个 tunable | `:value` |
| `:ability/budget` | 读取本技能声明的一个成本预算 | `:budget`（`:map`） |
| `:ability/progression` | 读取本技能声明的一个进度/经验条目 | `:progression`（`:map`） |
| `:ability/cooldown` | 读取本技能声明的一个冷却条目 | `:cooldown`（`:map`） |
| `:ability/invariant` | 读取本技能文档自己的 `:invariants` 常量 | `:value` |

```clojure
:program
{:component :flow/sequence
 :steps
 [{:component :ability/caster  :bind {:eye :eye :aim :aim :body :body}}
  {:component :ability/tunable :name :beam-damage :bind {:value :dmg}}
  {:component :ability/budget  :name :activate    :bind {:budget :budget}}
  {:component :fx/lightning-strike                       ; 组合层节点，纯 EDN
   :position  {:ref [:local :aim]}
   :power     {:ref [:local :dmg]}
   :budget    {:ref [:local :budget]}}]}
```

descriptor 用 `:reads-environment #{:tunables}` 之类的标记声明它读取哪张文档级表；编译期对照该技能文档实际声明的 `:tunables`/`:costs`/`:progression`/`:cooldown`/`:invariants` 校验，缺失即编译失败（不再是过去那种运行期才抛的 `caster facade does not provide this capability`）。

**为什么这满足硬约束 1**：非 source 节点搬到文档任何位置、被任何 composite 调用，行为都不变——这正是"自由组合"要的性质。Source 节点按定义就是唯一的例外，且被限定在文档顶层、类型化、可枚举、编译期校验。

## 6. 回调型输入：带类型作用域的子图参数

取代旧的 `:iterates`/`:expr-per-item`（那套机制靠调用方对齐被调 composite 的**内部循环名**，本身是隐式依赖）。被调方在 `:inputs` 里把某个输入声明为 `:node` 类型并附带 `:scope`：

```clojure
;; combat-core/components.clj 里 :combat/area-damage 的输入声明
:inputs {:center {:type :vec3} :radius {:type :double}
         :on-each-target {:type :node
                          :scope {:target   {:type :entity-ref}
                                  :distance {:type :double}}}}
```

调用方传入的子树在编译期被展开进一个新的封闭作用域，该作用域 = 外层可见的 `:local` 绑定 ∪ 被调方声明的 `:scope`：

```clojure
{:component :combat/area-damage
 :center {:ref [:local :impact]} :radius 4.0
 :on-each-target {:component :combat/damage
                  :target {:ref [:local :target]}     ; :scope 提供
                  :amount {:expr :math/mul :args [8.0 {:ref [:local :distance]}]}}}
```

`:scope` 里的名字是**被调方的公开契约**，不是内部实现细节——调用方永远知道自己能在回调里看见什么，不需要读被调方源码去猜循环变量叫什么。

## 7. Composite（组合层）文档

```clojure
;; ac/src/main/resources/ac/combat/composites/target_raycast_destination.edn
{:kind :composite :id :target/raycast-destination :revision 1 :layer :composite
 :doc "沿方向找一个可放置/命中的落点。"
 :category :targeting
 :inputs  {:origin {:type :vec3} :direction {:type :vec3} :distance {:type :double :default 32.0}}
 :outputs {:destination {:type :destination :from [:local :dest]}}
 :body
 {:component :flow/sequence
  :steps [{:component :target/raycast
           :origin {:ref [:input :origin]} :direction {:ref [:input :direction]}
           :distance {:ref [:input :distance]}
           :bind {:hit :h}}
          {:component :target/resolve-destination
           :hit {:ref [:local :h]} :origin {:ref [:input :origin]}
           :direction {:ref [:input :direction]} :distance {:ref [:input :distance]}
           :bind {:destination :dest}}]}}
```

`:outputs` 的 `:from` 指向 body 作用域内的一个局部名——这是 composite 唯一允许向外暴露的东西。没有声明 `:outputs` 的 composite 只产生副作用（伤害/位移/VFX 等），不返回值，这是完全合法的（例如 §8 的例子）。

`:kind :composite` 文档本身没有 `:layer` 之外的运行时形态——它在编译期被**展开**（宏替换）进调用方的树，不是运行期函数调用。展开器（`node-core/composite.clj`）做深度上限、节点数上限、循环检测（同 combat-core 现行 `recipe.clj` 的预算）。

## 8. 完整例子：释放闪电

用户举的例子。这是一个组合层节点，纯 EDN，组合三个已有原语/composite：

```clojure
;; ac/src/main/resources/ac/combat/composites/lightning_strike.edn
{:kind :composite :id :fx/lightning-strike :revision 1 :layer :composite
 :doc "在一点召唤闪电：范围伤害 + 闪电视觉 + 冲击特效 + 雷鸣，一次调用。"
 :category :elemental
 :inputs {:position {:type :vec3}
          :power    {:type :double :min 0.0 :doc "中心伤害"}
          :radius   {:type :double :default 4.0}
          :budget   {:type :map :doc "由 :ability/budget 提供的成本预算"}}
 :outputs {}
 :body
 {:component :flow/sequence
  :steps
  [{:component :cost/spend :budget {:ref [:input :budget]}
    :on-insufficient {:component :flow/finish :outcome :insufficient-resource}}
   {:component :world/lightning :position {:ref [:input :position]}}
   {:component :combat/area-damage
    :center {:ref [:input :position]} :radius {:ref [:input :radius]}
    :on-each-target {:component :combat/damage
                     :target {:ref [:local :target]}
                     :amount {:ref [:input :power]}}}
   {:component :effect/vfx :effect-id :lightning-impact :operation :spawn
    :audience {:scope :tracking :radius 64.0}
    :payload  {:position {:ref [:input :position]} :radius {:ref [:input :radius]}}}
   {:component :effect/vfx :effect-id :thunder-clap-audio :operation :spawn
    :audience {:scope :tracking :radius 96.0}
    :payload  {:position {:ref [:input :position]}}}]}}
```

技能文档只需要一行：

```clojure
{:component :fx/lightning-strike :position {:ref [:local :aim]} :power {:ref [:local :dmg]} :budget {:ref [:local :budget]}}
```

## 9. VFX 层的额外规则

vfx-core 词汇表复用 §1-§7 的全部规则，额外约定：

- **渲染 op 契约**：vfx 底层原语的 `:effects` 输出必须是 `platform-src` 渲染器已认识的 op 形状——`{:kind :line|:quad|:plasma-body}` + 材质标志 `:texture :additive? :no-fog? :no-depth-test? :no-depth-write? :translucent?`（见 `presentation_world.clj` 的 `sort-ops`）。这不是新造的契约，是渲染器现有输入契约；vfx 原语的职责就是产出它。
- 结构原语：`:vfx/timeline :vfx/repeat :vfx/transform :vfx/let :vfx/curve :vfx/branch`。
- 叶子原语：`:vfx/line :vfx/quad :vfx/plasma-body :vfx/audio :vfx/camera :vfx/post`。
- 环境传播（旧 `ctx :modifiers` 机制：`:vfx/fade`/`:vfx/scale` 悄悄改后代节点的 alpha/scale）被删除——一律用显式 `:vfx/transform` 包裹 + 显式颜色/alpha 输入。
- `:state-slots` 是真正的类型化每实例状态（`{:key {:type t}}`），由 `:vfx/let`/`:vfx/curve` 之类的节点读写，不再是死数据。
- 10 个现存的"语义大节点"（`directional-wave`/`impact-burst`/`arc-strike`/`channel-arc`/`block-scan`/`charge-ring`/`trajectory-ribbon`/`vortex-column`/`block-progress`/`first-person-motion`）全部是 `:layer :composite` 的 composite，不是 Clojure 函数。

## 10. 伤害反应（reactions）并入同一 VM

现行 `combat-core/reactions.clj` 是第二套解释器：自己的表达式求值器、自己的作用域（`:context :request :param :session :state`，没有 `:slot`/`:from`），`:damage/reflect`/`:absorb`/`:critical`/`:reduce` 五个节点只在这里合法。这违反"一套节点模型"。

目标形态：伤害反应在**同一个 node-core VM**、同一套 §4 作用域规则下执行；命中事实由一个新增 source 节点 `:ability/damage-request` 显式提供（而不是隐式挂在 `:context` 里）；`:damage/reflect`/`:absorb`/`:critical`/`:reduce` 从 Clojure 原语降级为 `:layer :composite` 的 EDN composite——它们各自的本质是"算术 + 扣费 + 给经验 + 产伤害"的组合，正是组合层该有的形态，底层只需要 `:combat/damage`、`:resource/*`、`:score/mark` 这些已有原语。

## 11. 错误契约

所有编译期失败是 `ex-info`，`ex-data` 至少含：

```clojure
{:path [...]        ; 节点路径向量，供编辑器定位
 :component :kw     ; 出错节点的 component id（如适用）
 :reason :keyword}  ; 机器可读原因，如 :unknown-component / :missing-required-field
                     ; / :type-mismatch / :unbound-local / :scope-escape
                     ; / :source-node-outside-ability / :composite-layer-has-impl
```

沿用 combat-core 现行的 fail-closed 策略（Design E）：一份技能/效果文档编译失败只disable它自己，不影响其它文档；错误集中收集，不止进日志（见计划 R6，暴露到 dev 命令/网络）。

## 12. 与旧设计的映射（迁移期间的对照表）

| 旧概念 | 新概念 |
|---|---|
| combat `:slot` 全局命名空间 | `:local` 词法作用域 + `:bind` |
| `{:from :caster/eye}` | `:ability/caster` source 节点 |
| `{:tunable :x}` | `:ability/tunable` source 节点 |
| `{:invariant :x}` | `:ability/invariant` source 节点 |
| `:cost/spend`/`:score/mark`/`:cooldown/start` 隐式读文档表 | 显式接收 `:budget`/`:progression`/`:cooldown` 输入 |
| `:owner/patch`/`:session/patch` 裸路径 | `:session/read`/`:session/write` + 文档顶层 `:session-state` 声明 |
| `:iterates`/`:expr-per-item` | 回调型输入 `{:type :node :scope {...}}` |
| combat `dataflow.clj`（确定赋值分析） | `node-core/scope.clj`（作用域链 + 类型检查） |
| combat 26 操作码字节码 VM（`ir.clj`，不可达） | 删除；解释器是树遍历，不再假装有字节码层 |
| vfx `ctx :modifiers`（`:vfx/fade`/`:vfx/scale` 环境传播） | 显式 `:vfx/transform` + 显式颜色/alpha 输入 |
| vfx 10 个语义大节点（Clojure 函数） | 同名 `:layer :composite` composite |
| `reactions.clj` 独立解释器 | 并入同一 node-core VM，`:damage/*` 降级为 composite |


