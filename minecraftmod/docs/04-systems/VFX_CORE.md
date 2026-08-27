# VFX Core 维护手册

本文描述当前唯一的 final VFX 路径。旧的 `vm.clj`、`recipe.clj`、`runtime.clj`、component registry 和 singleton 聚合模型不属于当前生产架构；源码级检查不允许它们重新成为入口。

## 模块边界

- `mcmod/src/main/java/cn/li/mcmod/runtime/vfx/`：Java ABI 与热路径。包含 `ParticleBuffer` SoA 存储、`ParticleKernel`、system/emitter carrier、render frame/batch/output、带 neutral geometry payload 的 `VfxBatch`、packet identity、wire codec 和 channel 常量。
- `vfx-core/src/main/clojure/cn/li/vfx/compiler.clj`：独立的 VFX composite 展开器。VFX 不依赖 Combat 的运行时解释器，但与 Combat 共享 node-core 的描述符、表达式和作用域语言内核。
- `vfx-core/src/main/clojure/cn/li/vfx/final-engine.clj`：headless/fake-host 的 final graph sampler 与生命周期测试端口。
- `vfx-core/src/main/clojure/cn/li/vfx/final-client.clj`：客户端 per-instance runtime、四阶段采样、Java frame 投影、乱序/墓碑处理。
- `vfx-core/src/main/clojure/cn/li/vfx/replication.clj`：服务端 tracking、baseline、snapshot/replay、release/destroy 生命周期。
- `ac/src/main/clojure/cn/li/ac/ability/final_catalog.clj`：读取 final VFX system EDN、展开 composite、生成 emitter stages 和静态 descriptor，再通过 ability-runtime 组合 catalog。
- `ability-runtime/src/main/clojure/cn/li/ability/compose.clj`：将 VFX catalog 与 Combat、Presentation、NodeEnvironment 组合；VFX Core 本身不反向依赖这些内容模块。
- `ac/src/main/clojure/cn/li/ac/client/effect_controller.clj`：AC composition root；只安装 catalog、转发 signal、采样 frame，不持有旧 handler 或 singleton aggregate。
- Combat Core 只产生中立 VFX Intent；按 self/tracking/world audience 的路由由 ability-runtime 与 AC adapter 完成，VFX Core 不提供 Combat 发布器。

## 执行模型

每个 VFX system 明确包含：

```text
System
 └─ Emitter*
     ├─ Spawn stage       emission / allocation
     ├─ Initialize stage  initial attributes
     ├─ Update stage      modules / integration / compaction
     └─ Output stage      render batches / audio / camera / post
```

阶段顺序由 catalog 生成的 opcode/stage vector 固定，不依赖 map 遍历顺序。粒子数据在 Java `ParticleBuffer` 中以 SoA 保存；`ParticleKernel` 使用有界容量和原地 compact，禁止热路径隐式扩容。

## 生命周期与网络

信号操作只有 `spawn/update/trigger/destroy/release/clear-owner/snapshot`。实例身份由 `effect-id + owner + world-id + instance-key` 或远端 `instance-id` 组成；`state-seq` 和 `event-seq` 独立检查。`snapshot` 只建立 baseline，不重放历史 event；`release` 只删除 tracking client 的本地副本；`destroy` 写入 tombstone，阻止延迟 update 复活。

网络分两层：

1. `VfxPacketKind`/`VfxLifecyclePacket` 提供 Java typed identity 和方向验证；
2. mcmod fixed channel 对完整 signal 做 bounded binary encoding，服务端只发送 catalog hello、VFX 生命周期和 combat feedback，客户端先解码/校验再进入 final-client。

服务端必须先完成 catalog schema/hash 握手；客户端不能提交技能图、目标、伤害或 VFX recipient。参数 update 只能携带 dirty mask 指示的变化字段；固定通道还强制每包最多 64 个参数和 1 个 64-bit dirty-mask word。

## 扩展规则

1. 新效果必须是 `ac/vfx/effects/*.edn` 中的 `:vfx/system`，并登记到 manifest。
2. 可复用结构必须是 EDN composite，由 `cn.li.vfx.compiler/expand-graph` 展开；不得增加第二套运行时 composite loader。
3. 参数必须声明 type/scope/mutability/default；网络字段必须进入 catalog schema，不能通过任意 map 字段绕过校验。
4. 新输出必须映射到 neutral `draw-batch`、audio、camera 或 post operation，并能投影到 Java `VfxFrame`。
5. Java carrier 直接写 Java；不得在 VFX ABI 中新增 `deftype`、`defrecord` 或 `definterface`。

## 验收门

```text
verifyNoGeneratedClojureTypes
verifyVfxJavaBoundary
vfx-core:checkClojure
vfx-core:runVfxClojureTests
ac:runAcEdnCoverageTests
```

实机渲染、多人可见性、材质数值和 loader datagen 属于后续运行时任务；它们不能反向引入旧 VFX runtime 或兼容 facade。
