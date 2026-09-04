# Node Language 规格

> 唯一权威来源。`combat-core`/`vfx-core` 的词汇表、`node-core` 的实现，以及
> [COMBAT_CORE.md](COMBAT_CORE.md)/[VFX_CORE.md](VFX_CORE.md) 的模块描述都必须与本文一致；
> 三者出现分歧时，先假设本文档正确，去找代码或另一篇文档的 bug。
>
> 本文档描述的是 2026-09 重写后的语言（surface DSL + 扁平 IR + 分型寄存器），**不是**
> 旧的"节点树 + `:component`/`:bind`/`{:ref [:local ...]}`"设计——本文早前的版本描述的
> 是那套旧设计，已整篇替换。设计过程记录在
> `C:\Users\lxy\.claude\plans\vfx-psi-hex-casting-ars-nouveau-niagara-tidy-puffin.md`。

## 0. 当前状态：执行引擎已全部切换并删除；内容目录加载仍是旧格式（独立的、
   仍需要的一层，不是清理疏漏）

**战斗/技能 dispatch 和 VFX 执行都已经切到新引擎，两侧的旧执行引擎文件都已
经删除**（不是"不再调用但留着"，是文件本身不存在了）：

- **战斗/技能 dispatch**：`cn.li.ac.ability.service.combat-runtime/dispatch-
  intent-v2!` 是唯一的技能 dispatch 入口——`network.clj`、`server_hooks.clj`、
  `location_teleport_rpc.clj`、以及 `combat_runtime.clj` 自己的
  `dispatch-trigger!`/`dispatch-event!`/`pulse-active-sessions!` 全部调用它，
  内部走本文其余章节描述的 surface DSL → `node-core` IR → `cn.li.mcmod.
  runtime.effect-emit` 闭包流水线，读取 `ac/skills/*.edn`（39/39，经
  `cn.li.ac.ability.skills-catalog` 加载）。旧的 `dispatch-intent!`/`cn.li.
  ability.engine`、combat-core 的 `final_engine.clj`/`final_compiler.clj`、
  node-core 的 `kernel.clj`，连同它们各自专属的测试，**已全部删除**。
- **VFX 执行**：`cn.li.vfx.runtime` + `cn.li.vfx.frame` +
  `cn.li.ability.client-vfx-v2` 是唯一渲染路径，读取 `ac/vfx/fx/*.edn`
  （36/36）。`vfx-core/final_engine.clj`、`vfx-core/final_client.clj`、
  `ability-runtime/client_vfx.clj` 连同它们各自专属的测试**已删除**——详见
  [VFX_CORE.md](VFX_CORE.md)。
- **`final_engine.clj`/`final_compiler.clj`/`node-core/kernel.clj` 为什么现在
  能删了**：早先认为它们被 `cn.li.ac.ability.service.combat-catalog`（真实
  生产启动时调用，被测试套件广泛依赖）经由 `cn.li.ac.ability.final-catalog-
  service` 当作内容元数据编译器使用，是真正的依赖。追查后发现这个结论只对了
  一半：`combat-catalog.clj` 确实读取 `final-catalog-service` 的输出，但**只
  读注册/来源元数据**（`name-key`/`actions`/`passive-effects`/`mark-policies`/
  trigger 索引），从来不读 `:compiled`/`:program`——而 `combat-api/execute!`/
  `create-engine`（真正会跑一份已编译图的函数）在仓库里已经零调用点：自从
  `dispatch-intent!`/`cn.li.ability.engine` 被删除后，就没有任何东西再执行
  编译产物了。`final-catalog-service` 自己那步"编译每个注册"因此是纯浪费——
  已经移除，只保留 `strict-graphs!` 的结构校验（`node.scope`/`node.validate`，
  跟旧引擎的编译器无关，独立检查图结构合法）。这才真正让 `final_engine.clj`/
  `final_compiler.clj`（以及只被它们用到的 `node-core/kernel.clj`）失去了
  最后的调用点，可以删除。
- **`kernels.clj`/`final_damage.clj`/`node-core/expr.clj` 为什么还留着**：
  `kernels.clj` 被 `combat-core/vocabulary.clj`（内容加载仍在用的旧词汇表，见
  下）在**模块顶层直接 require**，不装载 `vocabulary.clj` 就编译不过，跟"编译
  过没有被执行"无关，是硬编译期依赖。`final_damage.clj`（伤害反应引擎）和
  `node-core/expr.clj`（SplitMix64/vec3-components 的唯一定义点，新引擎的
  `ops.clj` 委托给它）是另一类：两者都是**真正共享、正确工作的基础设施**，不
  是遗留代码，永久保留。
- **旧内容目录仍在被加载，这是独立的、仍然需要的一层**：`ac/combat/
  abilities/*.edn`（39 个）、`ac/vfx/effects/*.edn`（36 个）、两侧的
  `composites/` 目录，全部仍被 `ac/ability/final_catalog.clj` 加载——供
  `combat-catalog.clj` 的技能元数据表使用。这条加载路径本身（组合展开 +
  结构校验，`node.composite`/`composite-loader`/`node.scope`/`node.validate`/
  `node.flow`/`node.descriptor`/`node.environment`）**不依赖被删除的旧引擎**，
  一直就是纯数据处理，不涉及执行——所以旧内容目录和这几个 node-core 文件不是
  "还没来得及删"，是这条独立管线本来就需要它们，删除需要先重写
  `combat-catalog.clj` 自己的元数据来源，一个独立、更大的项目，不在本次改动
  范围内。真正执行/渲染的内容只来自 `ac/skills/*.edn`/`ac/vfx/fx/*.edn`；旧
  目录的 `:program`/`:control-graph` 字段从未被新引擎读取。

修改本文档或新增新语言内容前，请先确认自己在哪一侧工作：**执行引擎**（已经
只有一套，旧引擎文件已删除）还是**内容元数据加载**（`combat-catalog.clj` 那
条独立管线，仍读旧格式内容，但不涉及任何"引擎"执行），不要把两者的概念混着
写进同一份技能/特效文档。

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
  `combat-core/lib/blink_release.edn`，已由 `ac/skills/flashing.edn` 的真实 dispatch
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
五个，目的是证明这条准入机制本身能跑通，不是要交付的内容广度——本次重写不含
任何编辑器 UI，glyph 目录扩充是后续任务。

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
真正需要 CPU 端逐粒子模拟的**未来**内容用的——36 个已转换的 `ac/vfx/fx/*.edn`
效果一个都不需要它：旧引擎里"发射器"类效果本质上也只是**每帧一条声明式绘制指令**
（真正的逐粒子演化在客户端渲染器里做，不在这层图里），跟 `:ring`/`:beam` 这些
叶子节点是同一类东西，不是需要模块栈的那类内容。

## 8. 迁移状态与已知空缺

- `ac/skills/*.edn`（39/39）、`ac/vfx/fx/*.edn`（36/36）已全部转换并有真实
  compile+dispatch 测试覆盖，`ac/src/test/clojure/cn/li/ac/skills/skills_test.clj`
  / `ac/src/test/clojure/cn/li/ac/vfx/fx_test.clj`。
- 旧引擎里约 17 个 VFX 组件种类（`charge-slow`/`charge-ring`/`directional-wave`/
  `vortex-column`/`impact-burst`/`mark-sparks`/`particle-trail`/`block-progress`/
  `channel-arc`/`first-person-motion`/`block-scan`/`billboard-sequence`/
  `trajectory-ribbon`/`humanoid-marker`/`beam-arc-fade`/`arc-strike`/`ray-fan`/
  `arc-field`）在 `final_engine.clj` 的 `sample-node` 里根本没有对应分支，落进一个
  同样画不出来的 `:typed-vfx` 兜底——这些组件今天在游戏里本来就不产生任何真实像素
  （`:vfx/beam-arc-fade`/`:vfx/humanoid-marker` 是例外：它们是**composite**，会在
  加载期被展开成真正能画的子树；展开细节见各自 `ac/vfx/fx/*.edn` 文件自己的
  docstring）。新版本据实转换：确认无渲染的组件对应一个诚实的空 `:scene`，不是
  发明新的视觉设计。
- `combat-core/player.clj`（S7）已实现并测试，但没有对应的物品/合成/交互层
  （"glyph 物品"本身——存法术数据的 Minecraft 物品、右键施法交互——是平台层内容，
  不在本次重写范围）。
- 旧路径的删除、`verifyNodeKernelSingleSource` 之类门禁的改写、真正把 `cn.li.
  combat.api`/AC composition root 切到新引擎上，是独立的、有意留待以后做的一步，
  §0 已经说明原因。
