# Node Language 规格

> 唯一权威来源。`combat-core`/`vfx-core` 的词汇表、`node-core` 的实现，以及
> [COMBAT_CORE.md](COMBAT_CORE.md)/[VFX_CORE.md](VFX_CORE.md) 的模块描述都必须与本文一致；
> 三者出现分歧时，先假设本文档正确，去找代码或另一篇文档的 bug。
>
> 本文档描述的是 2026-09 重写后的语言（surface DSL + 扁平 IR + 分型寄存器），**不是**
> 旧的"节点树 + `:component`/`:bind`/`{:ref [:local ...]}`"设计——本文早前的版本描述的
> 是那套旧设计，已整篇替换。设计过程记录在
> `C:\Users\lxy\.claude\plans\vfx-psi-hex-casting-ars-nouveau-niagara-tidy-puffin.md`。

## 0. 当前状态：执行引擎与内容元数据加载都已切到新格式；旧目录已删除

**战斗/技能 dispatch、VFX 执行、内容元数据加载三条路径现在全部读同一套
`ac/skills-v3/*.edn`/`ac/vfx-v3/*.edn` 内容**——旧执行引擎、旧内容加载器、旧内容
目录都已删除（不是"不再调用但留着"，是文件/目录本身不存在了）：

- **战斗/技能 dispatch**：`cn.li.ac.ability.service.combat-runtime/dispatch-
  intent-v2!` 是唯一的技能 dispatch 入口——`network.clj`、`server_hooks.clj`、
  `location_teleport_rpc.clj`、以及 `combat_runtime.clj` 自己的
  `dispatch-trigger!`/`dispatch-event!`/`pulse-active-sessions!` 全部调用它，
  内部走本文其余章节描述的 surface DSL → `node-core` IR → `cn.li.mcmod.
  runtime.effect-emit` 闭包流水线，读取 `ac/skills-v3/*.edn`（50/50，经
  `cn.li.ac.ability.skills-catalog` 加载）。旧的 `dispatch-intent!`/`cn.li.
  ability.engine`、combat-core 的 `final_engine.clj`/`final_compiler.clj`、
  node-core 的 `kernel.clj`，连同它们各自专属的测试，**已全部删除**。
- **VFX 执行**：`cn.li.vfx.runtime` + `cn.li.vfx.frame` +
  `cn.li.ability.client-vfx-v2` 是唯一渲染路径，读取 `ac/vfx-v3/*.edn`
  （36/36）。`vfx-core/final_engine.clj`、`vfx-core/final_client.clj`、
  `ability-runtime/client_vfx.clj` 连同它们各自专属的测试**已删除**——详见
  [VFX_CORE.md](VFX_CORE.md)。
- **内容元数据加载**：`cn.li.ac.ability.service.combat-catalog`（真实生产启动
  时调用，被测试套件广泛依赖）现在直接读 `cn.li.ac.ability.skills-catalog/
  assemble` 的输出（`ac/skills-v3/*.edn` + `the ac/skills-v3 directory`）——跟
  dispatch 引擎读的是**同一份内容**，只取其中 dispatch 不需要的字段
  （`name-key`/`icon`/`actions`/`category-id`/`passive-effects`/
  `external-triggers`/registration `:bindings`）。`ac/ability/
  final_catalog.clj`/`final_catalog_service.clj`（旧 manifest 加载 +
  composite 展开 + `strict-graphs!` 结构校验）、旧内容目录（`ac/combat/
  abilities/*.edn`〔39 个〕、`ac/combat/manifest.edn`、`combat-core/
  composites/*.edn`〔17 个〕）连同 `vfx-core/vocabulary.clj`/
  `system_compiler.clj`（同样只为旧 `final_catalog.clj` 的 `load-vfx` 服务）
  **已全部删除**：`combat-catalog.clj` 曾是它们最后一个真实调用点，切换元数据
  来源后二者都变成零调用点。
- **`kernels.clj`/`node-core/expr.clj` 为什么还留着**：`kernels.clj` 被
  `combat-core/vocabulary.clj`（下面单独说明）在**模块顶层直接 require**，跟
  "编译过没有被执行"无关，是硬编译期依赖。`node-core/expr.clj`（SplitMix64/
  vec3-components 的唯一定义点，新引擎的 `ops.clj` 委托给它）是另一类：**真正
  共享、正确工作的基础设施**，不是遗留代码，永久保留。伤害反应引擎
  （`combat/damage.clj`，取代已删除的 `final_damage.clj`——聚合算法逐字节
  port，只把线性扫描换成 mark-type+priority 索引查找，见 COMBAT_CORE.md）
  跟这两者同属一类：真正共享、独立于 dispatch 引擎，永久保留。
- **`combat.vocabulary`/`combat.kernels`/`vfx.vocabulary`(vfx-core 的这份未删)/
  `node.composite`/`composite-loader`/`scope`/`validate`/`environment`/
  `flow`/`descriptor`/`schema-export`/`node-core/api.clj` 为什么还留着，即使
  已经零真实调用点**：`combat/api.clj` 导出的 `descriptor-specs`/
  `kernel-descriptors`（来自 `combat.vocabulary`/`combat.kernels`）目前确实
  没有任何真实调用方——但这是有意为将来的图形化编辑器 UI 预留的 schema-export
  基础设施（见本文档 §2.11、`node.schema-export` 自己的定位），**不是**待清理
  的死代码，删除前需要一次关于"这层是否还要保留"的独立决策，不属于本次内容
  目录清理的范围。`node-core/api.clj` 的 docstring 虽然写着"sized from
  final_catalog.clj/final_catalog_service.clj 的两个消费点"（两者都已删除），
  但它转达的 `node.composite`/`composite-loader`/`scope`/`validate`/
  `environment` 这套结构校验能力本身跟 schema-export 是同一批"编辑器/工具链
  预留基础设施"，处理方式一致：留着，不重新接线，也不删除。

修改本文档或新增新语言内容前，请先确认自己在哪一侧工作：**执行引擎**（已经
只有一套）还是**内容元数据加载**（`combat-catalog.clj`，现在跟执行引擎读同一
份内容），两者现在共享同一套 `ac/skills-v3/*.edn`/`ac/vfx-v3/*.edn` 内容，不会
再有"两个目录、两份真相"的问题。

## 1. Surface DSL：纯 EDN，无 eval

```clojure
{:ability :thunder-bolt
 :activation :instant
 :requires [:caster/eye :caster/aim :world/id]
 :tunables {:range {:type :double} :damage {:type :double}}
 :do
 [(let hit (target/raycast {:origin ?caster/eye :direction ?caster/aim
                            :distance $range :include-entities? true :living-only? true}))
  (when (:entity-id hit)
    (combat/damage {:target (:entity-id hit) :amount $damage}))
  (vfx! {:effect-id :arc-strike-transient :operation :spawn :start ?caster/eye})
  (cooldown/start {:name :main :ticks 40})
  (finish {:outcome :performed :end-ability? true})]}
```

`cn.li.node.surface/parse` 用 `clojure.edn/read`（不是 `clojure.core/read`，不是
`eval`）读取整份文档；list/symbol 天然就是 EDN 的一部分，`(v+ a b)` 这样的调用形式
读进来就是普通数据，没有 reader-macro 也没有 eval 面。

**Sigil**（只在符号上识别）：

- `$x` — 读一个 tunable：`[:tunable :x]`。
- `?x` — 读一个 capability/系统输入：`[:capability :x]`。
- 裸符号 — 局部名，必须由 `let`/`each`/`:params` 绑定过才能读。

**语句形式**（`:do` / `:phases` 的每个 phase / `:events` 的每个 event 都是一个语句
向量）：

| 形式 | 含义 |
|---|---|
| `(let name expr)` | 绑定一个局部；`expr` 里出现 host 查询/组合调用必须先 `let` 绑定，不能作为另一个纯表达式的直接子表达式（IR 无短路求值，见 §3）。|
| `(set! name expr)` | 重新赋值一个已绑定的局部（`let` 绑定的初始值即使是字面量，也一律提升为可写寄存器，见 `compile-let`）。|
| `(when cond stmt...)` | `cond` 必须是 `:boolean` 或 `:any` 类型；`:any` 按 Clojure 真值语义（`nil`/`false` 假，其余真）判定。|
| `(if cond [then...] [else...])` | 两臂都是语句向量，不是可变参数体。|
| `(each item coll stmt...)` / `(each [item index] coll stmt...)` | 遍历，可选取索引绑定。|
| `(state! :key value)` | 写 session state（跨 tick 保留，见 `:session-state`/`:state`）。|
| `(event! {...})` / `(vfx! {...})` | 追加一条事件/VFX 信号到本次 dispatch 的 outbox，不查询 host。|
| `(finish {:outcome kw :end-ability? bool :next-phase kw})` | 设置本次 dispatch 的 `.-result`；不写 `:end-ability?`/`:next-phase` 时默认 `false`/`nil`。一个 phase/event 走到结尾都没调用 `finish` 也不是错误——`.-result` 会是 `{:outcome :ended :next-phase nil :end-ability? false}`（`cn.li.mcmod.runtime.effect-emit` 的隐式收尾），不是 `nil`。|
| `(fn-name a b c)` | 调用一个词汇表节点或 `:defn` 组合（词法上完全相同，编译期查 `:vocab` 再查 `:fns` 决定是哪一种）。|

**`:defn` 组合**（跨 ability/跨 event 复用的具名函数，取代旧设计的 composite 宏替换）：

```clojure
{:defn :target/directional-destination
 :params [{:name origin :type :vec3} {:name look :type :vec3} {:name eye-y :type :double}
          {:name direction :type :keyword} {:name distance :type :double} {:name policy :type :any}]
 :do [(let dest (target/directional-destination-query
                 {:origin origin :look look :eye-y eye-y :direction direction
                  :distance distance :policy policy}))]
 :returns dest}
```

- 只能通过 `:params` 声明的形参取值——`$`/`?` sigil 在 `:defn` 体内是编译错误（不像
  composite 宏替换时代那样意外读到调用方的动态作用域）。
- `compile-fn-call` 把整个函数体**内联**到调用点，不是运行时函数调用边界——一个
  `:defn` 体内的 `finish` 会终止调用方自己的 block，就像直接写在调用点一样（见
  `combat-core/lib/blink_release.edn`，已由 `ac/skills-v3/flashing.edn` 的真实 dispatch
  测试验证）。
- 库文件是**显式文件名列表**（`combat-core/lib.clj`/`combat-core/dsl_vocabulary.clj`
  同级），不是目录扫描——一个文件不在列表里就永远不可达，这是设计选择：12 个文件量级
  上目录扫描买不来什么，而显式列表在文件名打错时立刻在加载期炸掉，不会悄悄少加载
  一个函数。

## 2. 类型格

```clojure
:double :long :boolean :keyword :string :vec3 :any :entity-ref
[:list-of t]
```

`cn.li.node.types/assignable?`：`:any` 双向兼容（声明成 `:any` 的形参接受一切；一个
静态类型是 `:any` 的值——比如字段访问的结果——也能喂给任何具体类型的形参，真正的
形状检查留给 host/emitter）；`:long` 单向加宽到 `:double`（反过来——往 `:long` 形参
喂一个字面量小数——是真实的作者错误，不是隐式收窄）；其余必须精确匹配。寄存器组
（`bank`）由静态类型推导：`:double` 类型进 doubles 数组、`:long` 进 longs、
`:boolean` 进 booleans、其余（`:vec3`/`:string`/`:keyword`/`:entity-ref`/`:any`/…）
进 objects——数值运算全程留在同组数组里就不装箱。

## 3. IR：扁平块 + 分型寄存器，块内 SSA

`cn.li.node.compile/compile!` 把 surface 文档编译成：

```clojure
{:id :thunder-bolt
 :entries {:default 0}
 :blocks [{:instrs [{:op :cap :nid "n01" :dst [:objects 0] :key :caster/eye}
                    {:op :tun :nid "n02" :dst [:doubles 0] :key :range}
                    {:op :query :nid "n03" :dst [:objects 1] :node :target/raycast
                     :args {:origin [:objects 0] :distance [:doubles 0] ...}}
                    {:op :branch :nid "n04" :test [:objects 2] :then 1 :else 2}]}
          ...]}
```

不变量：**块内 SSA**（同一块内每个寄存器只写一次）；**跨块**用 `:branch`/`:jump` 的
目标块号显式连接，循环携带值靠预声明再 `set!`，不是全局"每寄存器恰好写一次"（循环
归纳变量天然违反那个更强的说法）。

完整 op 集合：`:const :cap :tun :pure :get :query :action :state-read :state-write
:vfx :branch :jump :phi :finish`——`:query`/`:action` 的 `:node` 字段就是词汇表里的
DSL 可见节点 id（`:target/raycast`、`:combat/damage`……），`cn.li.node.cost/analyze`
按这个字段去查词汇表的 `:cost`/`:effects`。

**IR 没有短路求值**：一个 `:pure` op 的每个参数都先各自编译成独立指令，再由 op
组合——`(bool/and present? (collection/contains? ... (value/normalize-id x)))` 里
`value/normalize-id` 无论 `present?` 是否为真都会执行。历史上这在 `x` 可能是
`nil` 时炸过至少一次真实内容（`mag_manip.edn`，修法是让 `value/normalize-id`
本身对 `nil` 安全，而不是试图在 DSL 层面模拟短路）。

**`:nid` 的稳定性契约**：`cn.li.node.compile/nid-for!` 在作者可见的构造点（节点/
`:defn` 调用、sigil 读、字段访问、纯 op 调用、`when`/`if`/`each`/`finish`/
`state!`/`set!`/`event!`/`vfx!`）优先读 form 自己的 `:nid` 元数据，没有才退回
每次编译重新分配的计数器——纯编译器内部指令（bank 转换、`each` 自己的计数/索引
记账、`when`/`if` 分支隐式的 `:jump`、兜底补上的收尾 `:finish`）没有稳定身份，
永远用计数器。作者/编辑器要让某个节点的身份跨编辑保持稳定，只需要在源码里写
`^{:nid "n7"} (form ...)`——`clojure.edn/read` 对带显式 `^{...}` 的 form 才会
附带 `:line`/`:column`（对没有显式元数据的 form 不会，这条曾经在旧文档里被错误
描述成"总是有"，见 `cn.li.node.surface` 自己的 docstring），所以 `:nid` 标记同时
带回了诊断的真实源码位置。`cn.li.node.nid/stamp` 递归给一份文档里所有还没标记的
list/map 分配 `:nid`，幂等，插入新语句不会移动已有节点的 `:nid`——是节点编辑器
（[NODE_EDITOR.md](../06-gui/NODE_EDITOR.md)）用来让画布上的节点在编辑之间保持
可识别、可选中的机制。

## 4. 词汇表：一张表

```clojure
;; combat-core/src/main/clojure/cn/li/combat/dsl_vocabulary.clj
{:target/raycast
 (node {:origin (p* :vec3) :direction (p* :vec3) :distance (p* :double)
        :include-entities? (opt :boolean false) :include-blocks? (opt :boolean false)
        :living-only? (opt :boolean false) :policy (opt :any nil)}
       :any #{:world-read} :raycast 2)
 :combat/damage
 (node {:target (p* :entity-ref) :amount (p* :double) :damage-type (opt :keyword :generic)
        ...}
       nil #{:world-write} :entity/damage 3)}
```

每条目：`{:params {name {:type t :default v?}} :returns t-or-nil :effects #{...}
:capability kw :cost n}`。`:returns nil` 的节点是 action（走 host 的 `:command!`，
不产生值）；`:returns` 非 nil 的是 query（走 `:query!`，可以 `let` 绑定其结果）。

`:effects` 是新引擎才有的字段，两个消费者：

1. `cn.li.node.cost/analyze`（静态代价分析：complexity/host-commands/effects/
   max-iterations 之和/并集，`:max-iterations` 只要有一个循环的静态上界未知就整体
   poison 成 `nil`，绝不悄悄读成 0——见 §6）。
2. `cn.li.combat.player`（玩家法术准入的 effects 白名单，见 §6）。

当前实际出现过的 tag：`:world-read` `:world-write` `:owner-read` `:owner-write`
`:inventory-write` `:damage-context-write`。这张表目前的颗粒度不够细——比如
`:combat/damage` 和 `:block/break` 都是 `:world-write`，语义上"打一拳"和"炸一个
坑"没法在 `:effects` 层面分开——加更细的 tag 是自然的后续工作，不是本次重写要
解决的问题。

## 5. 能力（Capability）：`?x` 读什么

`combat-core/run.clj` 的 `capability-type`：一部分是**固定表**（`?caster/eye`
`?caster/aim` `?caster/body` `:vec3`，`?caster/id` `:entity-ref`，`?caster/
creative?` `:boolean`，`?world/id` `:string`，`?rng/seed` `:long`，`?progression/
mastery` `:double`……每加一个新的固定 capability 都要在这张表和它的注释里同时说明
"谁是第一个真实用它的内容"），一部分按**命名空间派生**（`?budget/*` 都是 `:any`
——一个已具体化的多资源预算描述符；`?cooldown/*` 都是 `:long`；`?progression/*`
都是 `:double`；`?invariant/*` 都是 `:double`；`?context/*`/`?targeting/*` 都是
`:any`；`?movement/*` 都是 `:vec3`）。这张表历史上出过至少两次"猜错类型"的真实
bug（`?movement/*` 最初猜成 `:boolean`，`:target/raycast-fan` 的 `:yaw-range-
degrees` 最初猜成标量而不是 `[min max]` pair）——都是**第一个真正用到它的内容**
才暴露出来的，不是设计阶段能穷举的，遇到就照这个模式修：改类型、写清楚"第一个
真实用户是谁"，不要事后猜第二次。

## 6. 静态代价分析与玩家法术准入

```clojure
;; node-core/src/main/clojure/cn/li/node/cost.clj
(cost/analyze ir vocab)
;; => {:complexity n :host-commands n :effects #{...} :max-iterations n-or-nil}
```

`:complexity` 是每条 `:query`/`:action` 指令的 `:cost` 之和（纯运算恒为 0）；
`:max-iterations` 只在每个 `each` 循环的产出源头调用点带了**字面量** `:limit`
参数时才能算出静态上界，否则整个结果 poison 成 `nil`（调用方必须把 `nil` 当拒绝
处理，不能当成 0）。

`cn.li.combat.player`（S7）是这套分析目前唯一的消费者：玩家用 Ars Nouveau 式的
线性 `[form effect augment*...]` glyph 向量组合法术（存在物品上的纯 EDN，从不是
客户端编译好的程序），`desugar` 把 glyph 向量翻译成跟手写技能完全同构的 surface
DSL 文本（复用同一个编译器，没有第二套玩家专用 VM），`admit` 用 `cost/analyze`
的结果做三道闸：complexity 上限、`:effects` 白名单（`:inventory-write`/
`:damage-context-write` 永远拒绝）、host-command 数与 `:max-iterations`（`nil`
按拒绝处理）。任何玩家提交的法术必须先过 `admit` 再编译成可执行体——`admit` 之前
拒绝的 IR 永远不会走到 `compile-program`/`dispatch!`。当前 glyph 目录只有
`:form/self`、`:form/touch`、`:effect/damage`、`:effect/push`、`:augment/amplify`
五个，目的是证明这条准入机制本身能跑通，不是要交付的内容广度——glyph 目录扩充
是后续任务。

`cn.li.combat.player/glyph-catalog`（节点编辑器项目新增）给出每个 glyph 的真实
`{:effects :cost :admissible?}`，不是第二张手写表：每个 glyph 的贡献靠实际编译一个
最小合法法术、跑 `cost/analyze`、再相对 `:form/self`（零成本基线，因为它自己的
DSL 只有一次 sigil 读，不产生 `:query`/`:action` 指令）取增量算出来的——`admit`
本身的判定逻辑一个字节都没有被复制。玩家法术合成器屏幕
（`ac/.../spell_composer_reactive.clj`）就是这张表的第一个真实消费者，见
[NODE_EDITOR.md](../06-gui/NODE_EDITOR.md)。

## 7. VFX 场景 DSL：跟战斗共享编译器，词汇表不同

`cn.li.vfx.scene` 用完全相同的 `cn.li.node.compile`/`cn.li.node.surface`，只是换了
一张词汇表（`cn.li.vfx.dsl-vocabulary`）和一个不查询 host、只往 frame 自己的
`.actions` 里追加构造值的假 host：

```clojure
{:ability :arc-strike-scene
 :do [(beam {:start ?start :end ?end :grow-ticks 4})
      (ring {:center ?start :radius 1.0 :segments 16})
      (finish {:outcome :performed})]}
```

VFX 场景词汇表里的每个节点都是 `:action-kind`（`:returns nil`）——采样一帧没有
host 好查，"调用"就是"往这帧的 outbox 追加一条 draw/audio/camera op"，`:capability`
名字直接就是构造出来那个 `{:kind cap ...args}` map 的 `:kind`。`?age`/`?progress`
是每次采样都有的通用 capability，效果自己声明的 `?start`/`?end`/... 由调用方在
`compile-doc!` 时提供类型。

**没有隐式的 `{:from :to}` 按 `:progress` 插值**（旧引擎的 `kernel/defresolver`
`:lerp?` 选项有这个糖，新引擎没有）——需要插值的字段一律写成显式的
`(math/lerp from to ?progress)`。**没有"包一层子树改 alpha"的 `:vfx/fade` 修饰器
概念**（旧引擎对被包裹子树的每条构造出的 op 做 `assoc-in [:material :alpha]`
后处理）——新引擎是扁平的 `:do` 序列，没有"包裹并后处理子节点产出"这种结构，需要
渐隐的场景在本地算出 alpha 值，直接传给叶子节点自己新增的 `:alpha` 字段
（`:ring`/`:beam` 这两个节点因此各多了一个 `:alpha (opt :double 1.0)`，就是这个
后处理唯一的真正落点）。

`cn.li.vfx.compile`（Niagara 风格的模块栈 + 粒子 SoA 布局）是另一套独立机制，给
真正需要 CPU 端逐粒子模拟的**未来**内容用的——36 个已转换的 `ac/vfx-v3/*.edn`
效果一个都不需要它：旧引擎里"发射器"类效果本质上也只是**每帧一条声明式绘制指令**
（真正的逐粒子演化在客户端渲染器里做，不在这层图里），跟 `:ring`/`:beam` 这些
叶子节点是同一类东西，不是需要模块栈的那类内容。

## 8. 迁移状态与已知空缺

- `ac/skills-v3/*.edn`（50/50）、`ac/vfx-v3/*.edn`（36/36）已全部转换并有真实
  compile+dispatch 测试覆盖，`ac/src/test/clojure/cn/li/ac/ability/editor/editor_corpus_test.clj`
  / `ac/src/test/clojure/cn/li/ac/ability/editor/editor_corpus_test.clj`。
- 旧引擎里约 17 个 VFX 组件种类（`charge-slow`/`charge-ring`/`directional-wave`/
  `vortex-column`/`impact-burst`/`mark-sparks`/`particle-trail`/`block-progress`/
  `channel-arc`/`first-person-motion`/`block-scan`/`billboard-sequence`/
  `trajectory-ribbon`/`humanoid-marker`/`beam-arc-fade`/`arc-strike`/`ray-fan`/
  `arc-field`）在 `final_engine.clj` 的 `sample-node` 里根本没有对应分支，落进一个
  同样画不出来的 `:typed-vfx` 兜底——这些组件今天在游戏里本来就不产生任何真实像素
  （`:vfx/beam-arc-fade`/`:vfx/humanoid-marker` 是例外：它们是**composite**，会在
  加载期被展开成真正能画的子树；展开细节见各自 `ac/vfx-v3/*.edn` 文件自己的
  docstring）。新版本据实转换：确认无渲染的组件对应一个诚实的空 `:scene`，不是
  发明新的视觉设计。
- `combat-core/player.clj`（S7）已实现并测试，现在有一个真实的合成/提交交互层：
  `ac/.../spell_composer_reactive.clj` 屏幕（选一个 form、叠 effect/augment、
  提交），走已经存在的 `MSG-REQ-SPELL-SUBMIT` 服务端处理器（`combat-runtime/
  dispatch-player-spell!`，desugar/compile/admit 全部服务端权威）。仍然缺的是
  **物品层**——存法术数据的 Minecraft 物品、拾取/合成 glyph 物品、右键施法——
  需要贴图/模型/合成表，是平台层内容，本次没有做（这个环境做不出、也验证不了
  贴图和模型），今天这个屏幕靠直接调用 `open!` 打开，不挂在任何物品上。
- 节点图编辑器（技能 + VFX 场景两种模式）已实现，见
  [NODE_EDITOR.md](../06-gui/NODE_EDITOR.md)——本节描述的语言本身现在具备被
  图形化编辑的性质：`cn.li.node.nid`（跨编辑稳定的节点 id）、`:doc` 数据字段
  （取代原始注释，编辑器保存路径可预测哪些字节会变）、`cn.li.ability.editor.
  graph`（表层 AST ⇄ 图的双向、无损转换）。
- 旧路径的删除、`verifyNodeKernelSingleSource` 之类门禁的改写、真正把 `cn.li.
  combat.api`/AC composition root 切到新引擎上，是独立的、有意留待以后做的一步，
  §0 已经说明原因。
