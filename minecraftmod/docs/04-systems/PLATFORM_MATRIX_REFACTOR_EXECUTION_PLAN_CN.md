# 三平台 × 三版本重构执行计划（最终版）

本计划覆盖 Forge、NeoForge、Fabric 以及 1.20.1、1.21.1、26.2 的实际代码矩阵。最终只保留每个能力的一套实现；旧迁移层、兼容 facade、旧回调旁路和重复版本实现必须删除。允许中间提交不能编译，但每个阶段结束必须有可回滚提交，最终六个目标全部通过各自的发布任务。

## 目标矩阵与构建规则

当前目录实际支持六个目标：

| 目标 | 工具链 | Loom 混淆 | 运行时表示 |
|---|---|---:|---|
| Forge 1.20.1 | Loom | 是 | 平台入口 AOT；neutral 命名空间只保留 source 或闭包内 class |
| Fabric 1.20.1 | Loom | 是 | 同上 |
| Fabric 1.21.1 | Loom | 是 | 同上 |
| NeoForge 1.21.1 | Loom | 是 | 同上 |
| NeoForge 26.2 | ModDevGradle | 否 | source-first；不强制平台 AOT |
| Fabric 26.2 | Loom named | 否 | source-first；不执行 remap AOT |

`platform-catalog.json` 的 `buildProfile.obfuscationMode` 是唯一决策源。只有 `remapped` 目标把 `:platform` 放入 AOT 必需闭包；不能再提供用户可切换的全量 AOT 开关。任一最终 Jar/运行时类路径中，同一 Clojure namespace 的 `__init.class` 与 `.clj/.cljc` 不得同时存在。传递 AOT 可以发生，但必须按 namespace 过滤 source，并禁止 AOT class 与 source 共存。

## 阶段 0：冻结和基线

1. 记录 `git status`、当前提交、JDK/Gradle 版本和六目标 catalog hash；只对明确文件分批提交，禁止 `git add -A`。
2. 运行 `verifyPlatformCatalog`、`verifyCurrentPlatforms`，保存日志。
3. 建立 `reports/refactor/<target>/`：编译时间、Jar 大小、JFR allocation/CPU 基线。

退出条件：六个目标均能完成 Gradle 配置，或明确记录“插件版本与当前 wrapper 不兼容”的外部阻塞；该阻塞不能被伪装成源码通过。

## 阶段 1：AOT 闭包和类/源码互斥

1. 保留 `platformRemapAotEnabled`、`aotRequiredProjects`、neutral source-first 规则；删除所有旧全量 AOT 属性、注释和分支。
2. `platform-target.gradle` 从选中的 source roots 计算 AOT 闭包；neutral source 过滤、stale class 清理和 Jar staging 使用同一份 namespace manifest。
3. `verifyNeutralClojurePackaging` 检查 source manifest 与复制结果；`verifyPlatformAotCoverage` 仅在 remapped 目标检查平台入口 class。
4. 新增最终输出扫描：将 `resources/main/**/*.clj*` 与 `classes/java/main/**/__init.class` 归一化后求交集，交集非空立即失败。
5. 每个 remapped 目标执行 `compileClojure → classes → jar/remapJar`；named 目标执行 `classes → jar`，确认只出现一种表示。

提交点：`build: derive AOT closure from target remap policy`（已完成）。

## 阶段 2：平台职责和重复代码收敛

1. 以 `platform-src/minecraft/{base,mc-1.20.1,mc-1.21.1,mc-26.2}`、`loader/{forge-1.20.1,neoforge-1.21.1,neoforge-26.2,fabric-*}` 为输入，生成重复文件清单；只有 API/映射真正不同的部分留在版本目录。
2. 把无 Minecraft 类型的算法、协议、状态机、配方模型提升到 neutral/mcmod；loader 层只做事件注册、对象适配和生命周期桥接。
3. 删除迁移残留：旧 facade、旧 `pending-*` 队列、旧 `recipe/vm/composite` 运行时、同一事件的第二注册点、永远不会被 catalog 引用的 wrapper/stub。
4. 每次提升必须同步删除旧调用者；禁止“新实现 + 旧实现保留备用”。

验收：`rg` 检查每个 capability 只有一个 owner；依赖图满足 `node-core ← combat/vfx ← ability-runtime ← ac`，平台 API 只能经 mcmod contract 进入 neutral。

## 阶段 3：热路径回调、tick、render

1. 将回调集合固定为预分配数组/不可变快照：`ServerTickCallbacks`、`ClientRuntimeCallbacks`、`PresentationClientRuntime`、`WorldStageQueue`、`AudienceSender`、`InteractionOutcome`。
2. 注册只发生在 bootstrap；tick/render/网络回调禁止 `resolve`、map 构造、`vec`/`seq`、匿名 reify 或临时闭包。dispatch 采用索引/数组遍历，异常策略为一次记录后禁用坏回调。
3. combat audience 直接遍历原生玩家/AABB 发送，不生成 UUID 集合；VFX world-stage queue 使用 session-owned 可复用缓冲区，tick 末清空。
4. 交互统一返回 `pass/handled/open-gui`；禁止把任意 truthy 值当作 consumed。
5. 用 JMH（仅 benchmark source set）和代表性 JFR 验收：CPU p95 不得比基线增加 5%，稳态 allocation rate 与 live-set 增加不超过 5%，tick 99p 不得产生不可界定的短命对象。

## 阶段 4：JEI 与 IC2 可选集成

### JEI

- Forge 1.20.1、Fabric 三目标和 NeoForge 1.21.1/26.2 都使用真实 typed JEI adapter；不得保留 Neo stub。
- API 使用 `compileOnly`，开发运行时使用对应 `runtimeOnly`；主 Jar 仍为一个，不能把 JEI API/实现打包进去。
- JEI 缺失时主 mod 必须正常启动；存在时由 loader discovery 自动注册分类、配方和 catalyst。Forge/NeoForge 额外注册 GUI handler/subtype；Fabric API 不提供等价的容器 GUI handler，不能伪造跨平台实现。
- JEI 适配器只调用 neutral recipe provider，不得在 render/tick 热路径解析命名空间。

### IC2

- IC2 是第三方可选接入，不属于 Minecraft/loader/Academy 的 Loom 混淆或热路径反射禁令范围；允许且仅允许在 `ic2_energy` 文件中为第三方 API 检测和 proxy 创建使用反射。该反射不得进入 tick、回调、渲染或网络热路径。
- `Class/forName`、`Proxy` 只允许出现在该文件；检测状态（absent/present/incompatible）和接口 Class 必须缓存一次，缺失/不兼容只记录一次并禁用。
- 能力查询使用直接 `cn.li.mcmod.capability.registry` 回调；禁止 `requiring-resolve`。proxy 按 block entity/side/mode 缓存，世界卸载时清理。
- IC2 API 不嵌入主 Jar；无 IC2 时零错误，有 IC2 时自动启用 EU 转换与 sink/source。

### Loom 反射清理

- Loom 平台代码不得保留跨版本反射兼容层；`DistAccess` 已按 NeoForge 1.21.1/26.2 拆成直接 API，Forge/NeoForge 的 DSL entity kind 也已改为显式 `EntityFactory` 分发表。
- 核心反射清理已完成：`mc-26.2` `WorldEntity` 不再访问私有 LargeFireball 字段，而以弱键 side-table 保存 Academy 显式设置的 explosion power；`base` `AbstractHookRegistry` 改为启动期注册的静态 `Supplier` 工厂表，Hook tick/render 路径只做 Map 直取。`ScriptedRenderAccess`（1.20.1/1.21.1）、`ClientHelper.registerMenuScreen` 和 NeoForge 26.2 entity constructor 也已改为直接调用。后续扫描若发现新的 Minecraft/loader 反射，必须归零；第三方 IC2 适配层仍按窄 allowlist 保留。

提交点：`integration: keep typed JEI and cached optional IC2 adapters`、`integration: complete Fabric JEI entrypoints`、`integration: cover NeoForge 26.2 JEI`（均已完成；验证门覆盖六目标声明、入口和实现文件）。

## 阶段 5：反射、AOT 和 Loom 边界

1. Loom 混淆模块的 Minecraft/loader/Academy 访问必须是静态 Java/Clojure interop；禁止 `Reflector`、`setAccessible`、任意 `Class/forName` 和动态方法名。
2. 第三方可选集成（当前 IC2，未来同类集成）单独列 allowlist；allowlist 必须按文件和固定类名精确匹配，不能放宽为整个 loader 包。
3. AOT 后运行 `verifyNoGeneratedClojureTypes`、Loom remap、Jar overlap scan；确认不存在 AOT class 与 source 并存或 AOT class 与同名 Clojure source 被不同 classloader 加载。

## 阶段 6：六目标最终验收

按目标分别运行（不得用默认目标代替）：

```text
cmd /c gradlew.bat verifyCurrentPlatforms --stacktrace
cmd /c gradlew.bat :platform:check :platform:jar "-PplatformTarget=forge-1.20.1" --stacktrace
cmd /c gradlew.bat :platform:check :platform:remapJar "-PplatformTarget=fabric-1.20.1" --stacktrace
cmd /c gradlew.bat :platform:check :platform:remapJar "-PplatformTarget=fabric-1.21.1" --stacktrace
cmd /c gradlew.bat :platform:check :platform:remapJar "-PplatformTarget=neoforge-1.21.1" --stacktrace
cmd /c platform-builds\gradle-9.2\gradlew.bat :platform:check :platform:jar "-PplatformTarget=neoforge-26.2" --stacktrace
cmd /c platform-builds\gradle-9.7.1\gradlew.bat :platform:check :platform:jar "-PplatformTarget=fabric-26.2" --stacktrace
```

每个目标还必须运行 neutral/combat/vfx/mcmod headless tests、JEI absent/present fixture、IC2 absent/present/incompatible fixture、Jar overlap scan 和代表性 JFR。任何失败只允许归类为源码回归、测试支撑缺失或外部工具链阻塞；不能通过恢复旧实现、反射或双轨逻辑规避。

当前执行证据：`cmd /c gradlew.bat verifyCurrentPlatforms --stacktrace`、`verifyCorePerformance` 和根 `gradlew.bat test` 均通过；node-core 与 AC 测试已迁移到当前显式 environment/内容入口，删除了依赖已移除 UI、external-provider 和全局 node registry 的测试残留。Forge 1.20.1 使用 `:platform:check :platform:jar -x :platform:runData -x :platform:generateDatagenHashManifest` 完成源码编译、Clojure 检查、测试和 Jar；其完整 `runData` 受当前沙盒缺失 `C:\Users\CodexSandboxOffline\.gradle\caches\fabric-loom\assets` 阻塞。Fabric 1.20.1、Fabric 1.21.1 均完成 `:platform:check :platform:remapJar`；NeoForge 1.21.1 使用同样的两个 `-x` 选项完成源码编译、Clojure 检查、测试和 remapJar，完整 `runData` 仅受上述外部资产目录阻塞。NeoForge 26.2 已用 `platform-builds/gradle-9.2/gradlew.bat` 完成 `:platform:check :platform:jar` 并跑通 521 文件 datagen；Fabric 26.2 已用 `platform-builds/gradle-9.7.1/gradlew.bat` 完成 `:platform:check :platform:jar` 并跑通 516 文件 datagen。26.2 的命名映射目标采用 source-first：平台 `.clj` 必须由 `stagePlatformClojureSources` 打入资源；不得依赖旧 AOT 输出残留。验证过程中发现 Gradle 可能把 source staging 任务判定为 up-to-date，导致旧 `__init.class` 遮蔽已更新 `.clj`；现已让 source-first 清扫任务每次执行，并让 `copyClojureClassesToJavaOutput` 在 source-first 目标直接跳过，确保 AOT class/source 互斥。Fabric/NeoForge JEI catalyst 数组均已补静态类型提示，六目标 `checkClojure` 不再产生 JEI 反射警告。Forge 与 NeoForge 的重复 IC2 实现已提取为 `platform/shared` 的单一 `cn.li.platform.optional.ic2-energy` namespace，所有 loader 仅直接引用它；无 IC2 时仍通过一次缓存检测安全停用。`verifyOptionalIntegrations` 现扫描全部 `platform-src/**/src/main`，确保 IC2 是唯一允许反射的第三方文件。`state-schema-test` 曾发现 source-first 热路径通过 `partial` 生成 2 个运行时类，现已改为显式循环；随后 mcmod 194 tests/577 assertions 与 Flight Recorder fast 测试均为 0 failures/0 errors，JFR 摘要记录 60 秒、2,476 个 execution sample、14,559 个 allocation sample。`fixed_channel` 的 primitive case 警告也已消除。静态性能门 `verifyCorePerformance` 已通过；仍需在可运行的目标实例上补采游戏内 JFR/JMH CPU、分配率和 live-set 数据，作为发布前性能证据。构建日志中的 OSHI/JEI 可选 mixin 警告属于运行环境或第三方可选项，不得作为源码反射豁免。

## 明确排除的矛盾方案

- 不保留“source-first 默认 + full-AOT 开关”两套方案；目标 profile 是唯一选择。
- 不用反射替代静态平台 adapter；IC2 的第三方反射岛不代表 Minecraft/loader 代码可以反射。
- 不把 JEI/IC2 当作第二个主 Jar；它们始终是可选运行时能力。
- 不因中间阶段不能编译而回滚到旧逻辑；只在最终目标验收节点要求完整编译通过。
