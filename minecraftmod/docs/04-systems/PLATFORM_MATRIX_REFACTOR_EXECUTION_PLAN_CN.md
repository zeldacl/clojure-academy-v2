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
2. 注册只发生在 bootstrap；tick/render/网络回调的获取阶段禁止 `resolve`、Framework atom/map 查找、匿名 reify 或临时闭包。dispatch 采用索引/数组遍历，异常策略为一次记录后禁用坏回调；延迟任务按 deadline 有序 bucket 取出，tick 不扫描全部未到期任务。输入会话 `receive!` 直接链接 `fixed-channel/decode-intent`，每包路径不再调用 `requiring-resolve`；server bridge 在 bootstrap 固化不可变回调表，VFX/服务端转发不再每次读取 Framework atom。事件 fan-out 的 recipient/payload 组装限定在事件边界、受预算约束，并由 JFR 验证其分配率。
3. combat audience 直接遍历原生玩家/AABB 发送，不在每 tick 路径生成 UUID 集合；VFX world-stage queue 使用 session-owned 可复用缓冲区，tick 末清空。AC 的 VFX nearby/all 查询和反射伤害回调在首次生产 runtime 创建时冻结为具体函数；平台网络 SPI 只在 bootstrap 阶段解析一次，信号/伤害热路径不再执行命名空间解析或 Var 查找。AC imag-phase/cat-engine 的 viewer/camera/raycast 回调、HUD 按键显示名和 vanilla override 的 key-code 回调同样在客户端 bridge 安装时冻结为直接 IFn；渲染/tick 代码只读取本地 Var，不再调用 `call-adapter` 或 Framework map。
4. 交互统一返回 `pass/handled/open-gui`；禁止把任意 truthy 值当作 consumed。
5. 用 JMH（仅 benchmark source set）和代表性 JFR 验收：CPU p95 不得比基线增加 5%，稳态 allocation rate 与 live-set 增加不超过 5%，tick 99p 不得产生不可界定的短命对象。当前已提供可选的无头调度器采样：`cmd /c gradlew.bat :ac:runAcClojureTestsFast "-Dac.test.only=cn.li.ac.ability.final-runtime-perf-test" "-Dac.test.jfr=build/reports/jfr/final-runtime-scheduler-<date>.jfr" --stacktrace`；该基准只验证 deadline bucket 的调度开销，不替代真实游戏实例 JFR/JMH。现已补建 `tools:benchmarks` 隔离 JMH source set/任务与 `FinalRuntimeTickBenchmark`；JMH 依赖、生成 class 和结果不得进入任一运行时 Jar。真实游戏 JFR/JMH 本轮按用户要求延期，不作为本轮代码交付阻塞；恢复该验收时仍须执行低/中/压力场景并满足上述阈值。

## 阶段 4：JEI 与 IC2 可选集成

### JEI

- Forge 1.20.1、Fabric 三目标和 NeoForge 1.21.1/26.2 都使用真实 typed JEI adapter；不得保留 Neo stub。
- API 使用 `compileOnly`，开发运行时使用对应 `runtimeOnly`；主 Jar 仍为一个，不能把 JEI API/实现打包进去。
- JEI 缺失时主 mod 必须正常启动；存在时由 loader discovery 自动注册分类、配方和 catalyst。Forge/NeoForge 额外注册 GUI handler/subtype；Fabric API 不提供等价的容器 GUI handler，不能伪造跨平台实现。
- JEI 适配器只调用 neutral recipe provider，不得在 render/tick 热路径解析命名空间。

### IC2

- IC2 是第三方可选接入，不属于 Minecraft/loader/Academy 的 Loom 混淆或热路径反射禁令范围；第三方适配器在确有需要时可以使用反射，但必须隔离在接入层、缓存结果且不得把反射调用带入 tick、回调、渲染或网络热路径。当前 IC2 的反射只保留在 `ic2_energy` 文件中；JEI 选择 typed adapter，不依赖反射。
- `Class/forName`、`Proxy` 只允许出现在该文件；检测状态（absent/present/incompatible）和接口 Class 必须缓存一次，缺失/不兼容只记录一次并禁用。
- 能力查询使用直接 `cn.li.mcmod.capability.registry` 回调；禁止 `requiring-resolve`。proxy 按 block entity/side/mode 缓存，世界卸载时清理。
- IC2 API 不嵌入主 Jar；无 IC2 时零错误，有 IC2 时自动启用 EU 转换与 sink/source。

### Loom 反射清理

- Loom 平台代码不得保留跨版本反射兼容层；`DistAccess` 已按 NeoForge 1.21.1/26.2 拆成直接 API，Forge/NeoForge 的 DSL entity kind 也已改为显式 `EntityFactory` 分发表。
- 核心反射清理已完成：`mc-26.2` `WorldEntity` 不再访问私有 LargeFireball 字段，而以弱键 side-table 保存 Academy 显式设置的 explosion power；`base` `AbstractHookRegistry` 改为启动期注册的静态 `Supplier` 工厂表，Hook tick/render 路径只做 Map 直取。`ScriptedRenderAccess`（1.20.1/1.21.1）、`ClientHelper.registerMenuScreen` 和 NeoForge 26.2 entity constructor 也已改为直接调用。后续扫描若发现新的 Minecraft/loader 反射，必须归零；第三方 IC2 适配层仍按窄 allowlist 保留。

提交点：`integration: keep typed JEI and cached optional IC2 adapters`、`integration: complete Fabric JEI entrypoints`、`integration: cover NeoForge 26.2 JEI`（均已完成；验证门覆盖六目标声明、入口和实现文件）。

## 阶段 5：反射、AOT 和 Loom 边界

1. Loom 混淆模块的 Minecraft/loader/Academy 访问必须是静态 Java/Clojure interop；禁止 `Reflector`、`setAccessible`、任意 `Class/forName` 和动态方法名。
2. 第三方可选集成（当前 IC2，未来同类集成）不受 Minecraft/loader/Academy 的 Loom 反射禁令约束；若适配器确需反射，必须单独列出文件和固定类名 allowlist、缓存检测结果，并保持接入层隔离，不能放宽为整个 loader 包或进入热路径。
3. AOT 后运行 `verifyNoGeneratedClojureTypes`、Loom remap、Jar overlap scan；确认不存在 AOT class 与 source 并存或 AOT class 与同名 Clojure source 被不同 classloader 加载。

## 阶段 6：六目标最终验收

按目标分别运行（不得用默认目标代替）：

```text
cmd /c gradlew.bat verifyCurrentPlatforms --stacktrace
cmd /c gradlew.bat :platform:check :platform:jar "-PplatformTarget=forge-1.20.1" --stacktrace
cmd /c gradlew.bat :platform:check :platform:remapJar "-PplatformTarget=fabric-1.20.1" --stacktrace
cmd /c gradlew.bat :platform:check :platform:remapJar "-PplatformTarget=fabric-1.21.1" --stacktrace
cmd /c gradlew.bat :platform:check :platform:remapJar "-PplatformTarget=neoforge-1.21.1" --stacktrace
cmd /c platform-builds\gradle-9.2\gradlew.bat :platform:check :platform:jar "-PplatformTarget=neoforge-26.2" -x test --stacktrace
cmd /c platform-builds\gradle-9.7.1\gradlew.bat :platform:check :platform:jar "-PplatformTarget=fabric-26.2" -x test --stacktrace
```

共享 neutral/combat/vfx/mcmod headless tests 只运行一次，平台专属 `:platform:runPlatformClojureTests` 按六目标逐个运行；Jar overlap 由每个目标的 `:platform:verifyNeutralClojurePackaging`/`verifyClojureRuntimeRepresentation` 执行，发布 Jar 还必须在 `jar/remapJar` 后运行 `:platform:verifyPackagedOptionalIntegrations`，检查 JEI/IC2 外置、typed JEI 入口、loader 元数据、datagen manifest 和 class/source XOR。代表性 JFR 在共享模块和可运行目标实例分别采集。共享 IC2 隔离测试由 `:platform:runPlatformClojureTests` 执行，使用临时编译类和隔离 classloader 覆盖 IC2 缺失、接口存在、接口形状不兼容，并验证状态缓存；第三方 fixture 不进入主 Jar。JEI 仍由六目标 typed adapter/入口门禁覆盖。当前源码只有 IC2 使用反射，门禁对该文件做精确 allowlist；若新增第三方反射适配器，必须增加独立隔离声明和非热路径测试。任何失败只允许归类为源码回归、测试支撑缺失或外部工具链阻塞；不能通过恢复旧实现、反射或双轨逻辑规避。

共享门禁可直接执行：`cmd /c gradlew.bat :node-core:runNodeCoreClojureTests :combat-core:runCombatClojureTests :vfx-core:runVfxClojureTests :mcmod:runMcmodClojureTests :ac:runAcEdnCoverageTests --stacktrace`；平台门禁使用 `scripts\\target-gradle.ps1 <target-id> :platform:runPlatformClojureTests`，其中 `<target-id>` 必须依次为六个 catalog id，不能省略目标参数。

真实客户端 JFR 场景使用统一脚本：`powershell -File scripts\\perf\\run_script_render_perf_capture.ps1 -PlatformTarget <target-id> -Scenario <scenario> -Mode <low|medium|stress>`。脚本通过 `target-gradle.ps1` 解析 catalog 对应的 wrapper/JDK，输出按 target 隔离到 `build/reports/script-render-perf/<target-id>/`；`-PperfJfrFile` 由构建层注入 `runClient/runServer` forked game JVM（不是 Gradle daemon），关闭客户端后才会写入 JFR，并自动生成同目录 `summary-<timestamp>.txt`；标准输出和错误分别写入 `run-<timestamp>.stdout.log`、`run-<timestamp>.stderr.log`，避免 Windows 双重重定向句柄冲突；游戏进程非零退出、JFR 缺失或摘要失败均使脚本失败，不得带警告继续签核。该步骤需要人工在游戏内执行代表性低/中/压力场景；本轮按用户要求不执行实机测试，未来恢复时不得用无头基准替代；JMH 则由独立 benchmark 任务运行，不通过该脚本代替。

六目标发布任务已逐个执行并通过：Forge 1.20.1 `check + jar`、Fabric 1.20.1/1.21.1 `check + remapJar`、NeoForge 1.21.1 `check + remapJar`、NeoForge 26.2（Gradle 9.2）和 Fabric 26.2（Gradle 9.7.1）`check + jar`（26.2 目标按 `-x test` 排除 Gradle 9.x 的空测试发现校验）。其中 NeoForge 26.2 与 Fabric 26.2 在本轮重新执行了完整 datagen；Forge/Fabric 1.20.x/1.21.x 的 datagen 资源已由此前本机 Loom 资产缓存验证结果复用。为支持无 datagen 外部启动环境下的显式发布检查，`processResources` 已排除只由 `jar` 直接加入的 `META-INF/academy-datagen-hashes.json`，消除了 Gradle 8/9 的隐式任务依赖错误；不改变最终 Jar 内容。

修复后的最新回归证据为：`cmd /c gradlew.bat test verifyCorePerformance --stacktrace` 通过（80 tasks），`cmd /c gradlew.bat verifyCurrentPlatforms --stacktrace` 通过（99 tasks）；两者均覆盖当前构建层和共享运行时改动。三种工具链（Loom 8.8、ModDevGradle 9.2、Fabric named 9.7.1）的 `runClient --dry-run -PperfJfrFile=...` 均通过。

针对重构要求的最新专项门禁：`cmd /c gradlew.bat verifyOptionalIntegrations verifyNoCompatibilityResidues verifyNoDuplicateCapabilities verifyNoLegacyArchitecture --stacktrace` 通过（8 tasks），确认 JEI/IC2 接入、迁移残留、重复能力和旧架构路径均未回归。

已额外启动 Forge 1.20.1 `runServer -PperfJfrFile=...` 做 JVM 链路烟测：游戏 JVM 正常加载 Academy/JEI 并写出 15 秒 JFR（246 execution samples、1,607 allocation samples），随后因运行目录 `platform/run/forge-1.20.1/server/eula.txt` 缺失或仍为 `eula=false` 正常退出（当前文件为 `eula=false`）。该文件仅证明 JFR 注入和关闭落盘链路，不计入代表性 tick 性能验收；接受 Minecraft EULA 必须由用户在本机完成。用户完成该文件后，可直接重跑同一 `runServer` 命令并发送 `stop`，再执行客户端低/中/压力场景采样。

当前执行证据：`cmd /c gradlew.bat verifyCurrentPlatforms --stacktrace`、`verifyCorePerformance` 和根 `gradlew.bat test` 均通过；node-core 与 AC 测试已迁移到当前显式 environment/内容入口，删除了依赖已移除 UI、external-provider 和全局 node registry 的测试残留。Forge 1.20.1、Fabric 1.20.1、Fabric 1.21.1、NeoForge 1.21.1 均已用本机 Loom 资产缓存完整执行 `:platform:runData`/`:platform:runDatagen` 并生成 hash manifest（分别 455、450、450、455 个文件）；日志中的 Fabric data fixer、缺少 neutral classpath 和 NeoForge 资产 index 是开发环境警告，任务退出码均为 0。此前的 `C:\Users\CodexSandboxOffline\.gradle\caches\fabric-loom\assets` 阻塞已通过复用 `C:\Users\lxy\.gradle\caches\fabric-loom\assets` 的 junction 解除。NeoForge 26.2 已用 `platform-builds/gradle-9.2/gradlew.bat` 完成 `:platform:check :platform:jar` 并跑通 521 文件 datagen；Fabric 26.2 已用 `platform-builds/gradle-9.7.1/gradlew.bat` 完成 `:platform:check :platform:jar` 并跑通 516 文件 datagen。26.2 的命名映射目标采用 source-first：平台 `.clj` 必须由 `stagePlatformClojureSources` 打入资源；不得依赖旧 AOT 输出残留。验证过程中发现 Gradle 可能把 source staging 任务判定为 up-to-date，导致旧 `__init.class` 遮蔽已更新 `.clj`；现已让 source-first 清扫任务每次执行，并让 `copyClojureClassesToJavaOutput` 在 source-first 目标直接跳过，确保 AOT class/source 互斥。进一步发现命名目标的 Clojure 编译仍可能传递生成中性 AOT class；这些 class 只用于清理报告，不得删掉源码资源，现已将“检测到的 AOT”与“允许打包的 AOT”分离：named/source-first 始终保留全部中性 `.clj/.cljc`，Loom remapped 才按闭包过滤。另将中性命名空间的旧 `__init.class` 清扫挂到 source staging，并在每个目标的实际 `classes/java/main`（仅 remapped 额外检查 `clojure/main`）执行最终 XOR，避免复用目标目录时旧类遮蔽源码。修正后 NeoForge 26.2 与 Fabric 26.2 均完整 `check + jar` 通过并成功运行 datagen。Fabric/NeoForge JEI catalyst 数组均已补静态类型提示，六目标 `checkClojure` 不再产生 JEI 反射警告。Forge 与 NeoForge 的重复 IC2 实现已提取为 `platform/shared` 的单一 `cn.li.platform.optional.ic2-energy` namespace，所有 loader 仅直接引用它；无 IC2 时仍通过一次缓存检测安全停用；同名但非接口或缺少必要方法的第三方 API 会被分类为 `:incompatible` 并一次性停用。`verifyOptionalIntegrations` 现扫描全部 `platform-src/**/src/main`，确保 IC2 是唯一允许反射的第三方文件。`state-schema-test` 曾发现 source-first 热路径通过 `partial` 生成 2 个运行时类，现已改为显式循环；随后 mcmod 194 tests/577 assertions 与 Flight Recorder fast 测试均为 0 failures/0 errors；NeoForge 26.2 平台测试现为 106 tests/249 assertions、0 failures/0 errors（含 IC2 三态隔离），JFR 摘要记录 60 秒、2,476 个 execution sample、14,559 个 allocation sample。新增 final-runtime 无头调度器基准 1 test/1 assertion：200,000 次 deadline bucket tick 用时 369.743 ms（约 540,916 ops/s）；对应 JFR 12 秒记录含 338 execution samples、990 allocation samples、13,570 GC phase events。`fixed_channel` 的 primitive case 警告也已消除。新增 `tools:benchmarks` JMH 试跑已成功并持久化到 `tools/benchmarks/build/reports/jmh/results.json`：`FinalRuntimeTickBenchmark.emptyScheduledTick` 复测 5 次测量平均 5,091,808.373 ops/s（99.9% 误差 158,746.163，JDK 21.0.6，单线程），GC 分配率约 2,136.556 MB/s、440.000 B/op、245 次 GC；上一轮为 4,920,892.151 ops/s，复测吞吐方向一致且单次分配量不变。六个最新发布 Jar 的 benchmark class 泄漏扫描均为 0。该结果是微基准，不替代真实游戏实例的 CPU/分配率/live-set/tick 99p 验收。静态性能门 `verifyCorePerformance` 已通过；仍需在可运行的目标实例上补采游戏内 JFR/JMH CPU、分配率和 live-set 数据，作为发布前性能证据。构建日志中的 OSHI/JEI 可选 mixin 警告属于运行环境或第三方可选项，不得作为源码反射豁免。

平台测试明细：Forge 1.20.1 为 110 tests/262 assertions，NeoForge 1.21.1 为 103/240，NeoForge 26.2 为 106/249，Fabric 1.20.1、Fabric 1.21.1、Fabric 26.2 各为 11/32；六目标均为 0 failures/0 errors，且 IC2 缺失、有效接口、不兼容接口三态隔离均已执行。后续发现 final compiler 曾只保存原始 graph，导致运行时丢失子节点 kind；现已回写完整编译子树并将 final runtime 的 API Var 在启动期缓存，combat 35 tests/92 assertions、AC final-runtime 9/20、EDN catalog 42/130 均通过，dispatch/tick 不再执行 `requiring-resolve`；VFX nearby/all 与反射伤害的外部回调也已移到首次 runtime 安装时缓存，运行时只做直接 IFn 调用（平台命名空间保持 neutral 编译依赖隔离）。新增到期 scheduled node 回归测试，确认 tick 使用缓存 API、显式 node environment 并实际执行结果；tick 队列采用 transient 缓冲区合并一次提交，避免每个 tick 的多次 atom swap。进一步将 `flow/after` 子节点在 dispatch 调度边界封装为唯一的预编译一步程序，tick 仅筛选到期项并执行，调度记录不再重复保存原始 `:node`；随后将调度容器改为 deadline 有序 bucket，避免每次 tick 扫描所有未到期任务，并新增 dispatch 入桶回归测试。最新又增加无到期任务快速返回，空 scheduled tick 不再创建 transient/persistent 集合或写回 atom；回归为 9 tests/20 assertions、0 failures/0 errors，200,000 次基准本轮约 385.759 ms（约 518,458 ops/s）。服务器协调器随后把冻结的玩家 tick 回调在单次 server tick 外层预取，避免每个玩家重复读取 runtime 接口和回调 map；`test verifyCorePerformance` 最新 80 tasks 通过。

发布 Jar 直接审计（2026-09-01）已补齐：六个最新 `platform/libs/AcademyCraft-2.0.0-alpha2-*` 产物均包含各自 loader 元数据、平台入口、`JEIPluginWrapper` 和 datagen hash manifest；`mezz/jei` 与第三方 `ic2/` 类均为 0。Forge 1.20.1 旧 `alpha3` Jar 曾残留 `cn/li/forge1201/integration/ic2_energy`，经重新执行 `:platform:jar :platform:remapJar` 后新的 `alpha2` 发布 Jar 已为 `legacyIC2=0`，只保留 shared IC2 适配。26.2 两个 named/source-first 产物只含 `cn/li/platform/optional/ic2_energy.clj`，四个 Loom remapped 产物只含该 namespace 的 AOT class；没有任何目标出现 class/source 共存。该门禁已在 Forge 1.20.1、Fabric 26.2（Loom named）和 NeoForge 26.2（ModDevGradle）实际执行通过。旧 Jar 文件仍可能留在本地 `build/targets` 缓存中，但不属于发布输入，发布脚本必须只取本轮任务生成且时间戳最新的单一 artifact。

在最新代码提交上又逐目标重跑了最终编译：Forge 1.20.1 `check + remapJar`（91 tasks）、Fabric 1.20.1/1.21.1 `check + remapJar`（各 91）、NeoForge 1.21.1 `check + remapJar`（91）、NeoForge 26.2（Gradle 9.2）`check + jar`（74，`-x test`）和 Fabric 26.2（Gradle 9.7.1）`check + jar`（70，`-x test`）均成功。随后对六个最新 Jar 做等价 ZIP 实物扫描：每个目标 `metadata=1`、`manifest=1`、`jeiWrapper=1`、`embeddedJei=0`、`embeddedIc2=0`、`classSourceOverlap=0`；这组结果覆盖本轮重编译后的实际 artifact，而非旧缓存。

在同一提交上重新执行六目标 `runPlatformClojureTests`：Forge 1.20.1 为 110 tests/262 assertions，Fabric 1.20.1、Fabric 1.21.1、Fabric 26.2 各为 11/32，NeoForge 1.21.1 为 103/240，NeoForge 26.2 为 106/249；全部 0 failures/0 errors。IC2 不兼容 fixture 的一次性 warning 属于预期隔离测试输出，不代表运行时失败。

随后又清理了两个热路径残留：AC 的 VFX/反射回调在生产 runtime 首次创建时冻结，`mcmod` 输入会话直接调用 `fixed-channel/decode-intent`；server bridge 也改为 bootstrap 固化根回调表，发送路径不再读取 Framework atom。`mcmod:checkClojure :mcmod:test`（20 tasks）和六目标 `verifyCurrentPlatforms`（99 tasks）在该改动后均通过；对应提交为 `93868faa9`、`173f37b56`、`4864e6a4c`。

在上述提交之后重新执行六目标发布任务：Forge 1.20.1 `check + jar` 61 tasks，Fabric 1.20.1/1.21.1 与 NeoForge 1.21.1 `check + remapJar` 各 63 tasks，NeoForge 26.2 `check + jar` 64 tasks，Fabric 26.2 `check + jar` 60 tasks，全部成功。对这些最新 Jar 的 ZIP 实物扫描均为 `meta=1|manifest=1|jeiWrapper=1|embeddedJei=0|embeddedIc2=0|classSourceOverlap=0`；其中 26.2 两个 source-first 目标实际 `platform AOT=0`，其余 Loom 目标仅打包允许闭包内的 AOT。

在当前提交上再次执行根工程回归与专项门禁：`cmd /c gradlew.bat test verifyCorePerformance verifyOptionalIntegrations verifyNoCompatibilityResidues verifyNoDuplicateCapabilities verifyNoLegacyArchitecture --stacktrace` 于 2026-09-01 成功（84 actionable tasks，0 failures）。输出再次确认 neutral 无 Minecraft API、JEI typed adapter/IC2 反射隔离、核心性能预算、兼容残留、重复能力和旧架构门禁全部通过；该结果不替代尚未完成的真实游戏 JFR。

热路径复审又发现 neutral VFX 旧 seam 在每次访问时仍经 `Framework → client-bridge map → apply`，并重建 host API map；现已删除该旁路，改为 AC 客户端 bootstrap 一次安装并缓存不可变 host API map，tick/render 仅做直接 Var/函数调用。新增 `vfx` 回归测试验证安装后不再调用 `client-runtime/call-adapter`；同步更新 `verifyVfxDirectHostBoundary`，禁止恢复 bridge 路径。修复后 `cmd /c gradlew.bat verifyCurrentPlatforms --stacktrace` 通过（99 tasks），根 `cmd /c gradlew.bat test verifyCorePerformance --stacktrace` 通过（80 tasks）。

该热路径修复提交后又逐目标重跑发布任务：Forge 1.20.1 `check + jar`（61 tasks）、Fabric 1.20.1/1.21.1 `check + remapJar`（各 63）、NeoForge 1.21.1 `check + remapJar`（63）、NeoForge 26.2 `check + jar`（64）和 Fabric 26.2 `check + jar`（60）全部成功；六个新 Jar 的 ZIP 实物扫描再次得到 `meta=1|manifest=1|jeiWrapper=1|embeddedJei=0|embeddedIc2=0|classSourceOverlap=0`，其中两个 26.2 目标 `platform AOT=0`。

随后复审 Presentation 渲染/输入入口又发现同类遗漏：neutral Presentation 原先在每帧通过 lifecycle host map 获取 AC API。现已改为 AC 客户端 bootstrap 安装并缓存单一不可变 host API map，渲染、输入和资源回调只走直接 Var；新增 `verifyPresentationDirectHostBoundary` 与渲染路径回归测试，防止恢复动态/lifecycle 查找。修复后 `verifyCurrentPlatforms`（103 tasks）和根 `test verifyCorePerformance`（80 tasks）均通过，六目标发布任务及 Jar XOR/JEI/IC2 实物扫描再次全部通过；对应代码提交为 `b97d35a12`。

最后一轮热路径扫描又发现 AC HUD 按键显示名与 vanilla override 的 key-code 读取仍经 `client-bridge/call-adapter`；现已把 `local-player-pos`、`camera-position`、`camera-raycast-visible?`、`settings-key-name`、`keybind-get-key-code` 纳入 mcmod 客户端 bridge 的安装期直接函数表，imag-phase/cat-engine/HUD/keybinds 只读取本地 Var。新增 `verifyClientRenderBridgeBoundary` 覆盖四个调用方；`checkClojure/test`、`verifyCurrentPlatforms`（101 actionable tasks）和 `verifyCorePerformance` 均通过，对应提交为 `843bc7a16`、`c74f36df5`。该修复不改变 Fabric 缺省 nil 回退，也未引入 AOT class/source 共存。

在上述修复后逐目标重跑发布构建：Forge 1.20.1（91 tasks）、Fabric 1.20.1/1.21.1（各 91）、NeoForge 1.21.1（91）、NeoForge 26.2（74，按 Gradle 9.2 工具链排除无发现测试任务）和 Fabric 26.2（70）均完成 datagen、check 与最终 Jar。六个 Jar 实物扫描均为 `meta=1|manifest=1|jeiWrapper=1|embeddedJei=0|embeddedIc2=0|classSourceOverlap=0`；26.2 两个 source-first 目标均 `platform AOT=0`。NeoForge 26.2 的 `:tools:target-launcher:test`/空测试发现错误属于 Gradle 9.2 配置行为，已通过 `-x test` 排除，平台 Clojure check、编译、datagen、Jar 和 XOR 门禁仍完整执行。

在当前提交 `0d80e7c14` 上再次执行根工程回归与全部架构门禁：`cmd /c gradlew.bat test verifyCorePerformance verifyOptionalIntegrations verifyNoCompatibilityResidues verifyNoDuplicateCapabilities verifyNoLegacyArchitecture verifyCurrentPlatforms --stacktrace` 成功（126 actionable tasks，0 failures）。同时重新扫描六个目标的最新 `platform/libs/AcademyCraft-*.jar`，六个目标均满足 `metadata=1|manifest=1|jeiWrapper=1|embeddedJei=0|embeddedIc2=0|classSourceOverlap=0`；该扫描使用归一化完整相对路径，避免仅按 basename 造成误报。本轮按用户要求不执行实机性能测试，真实游戏 JFR/JMH 作为后续可选验收，不阻塞当前代码交付。

范围修订优先于前述历史记录：本轮交付不要求真实游戏 JFR/JMH；文档中“仍需补采”仅描述未来恢复性能验收时的标准，不构成本轮阻塞或失败条件。不得为完成该项自动修改 EULA，也不得以实机测试替代已有的无头调度器与 JMH 验证。

## 2026-09-01 直接源码复审补充（以当前代码为准）

本轮重新扫描三平台、三版本源码及资源目录，不引用历史完成记录作为事实依据。结论与可执行动作如下：

1. seam 不能按文件名或单次相似度批量提升。逐文件 SHA-256 与版本 API 依赖核验显示，`RegistryDispatch` 三版本字节完全一致，已提升至 `minecraft-base`；另外 11 个 1.20.1/1.21.1 完全一致的 seam 已提升至仅供这两个版本使用的 `minecraft-classic`，`ItemStackEnchants` 的 1.21.1/26.2 共用实现已提升至 `minecraft-modern`。26.2 仍保留其 API 分叉适配。`verifyVersionSeamParity` 现在按每个目标合并 shared+version seam surface，确保每个目标最终只有一套实现。后续若要进一步减少 seam，必须先抽取版本无关契约/值对象，再让各版本适配器实现该契约，逐目标编译后才允许提升；禁止恢复双轨实现。
2. 三个 Fabric 目标中的独立 `assets/academy/lang/en_us.json` 已全部删除。`verifyDatagenOwnsLanguageFiles` 已加入根验证；每个 datagen manifest 还必须包含六种语言输出（`en_us`、`zh_cn`、`zh_tw`、`ja_jp`、`ko_kr`、`ru_ru`），确保翻译唯一来源是 datagen。
3. 平台资源已建立显式 allowlist：保留无法由 datagen 取代的 loader 元数据/混入配置、版本专属 `pack.mcmeta` 以及 26.2 专属 shader；共用 `academy-loader-hook-support.properties` 已移至 `mcmod` 资源，未引用的 base/NeoForge marker 已删除。`verifyPlatformResourceOwnership` 防止未来把普通资源重新放回平台层。模型、纹理、声音、配方、标签和翻译继续由共用资源/datagen 管理。
4. 热路径验收仍以静态边界、无头回归和编译为准；本轮不启动实机，不修改 EULA。26.2 若根 Gradle 8 wrapper 与插件发生 API 不兼容，必须使用目录指定的 Gradle 9.2/9.7.1 wrapper，并把该工具链差异记录为构建环境事实，不能通过兼容旧实现绕过。
5. `ac/build.gradle` 与 `mcmod/build.gradle` 曾出现零字节文件，导致中性项目插件、`classes`/`compileClojure`/`processResources` 任务以及所有平台运行时依赖链被配置阶段截断。现已恢复为有效脚本，并新增 `verifyNeutralBuildScripts`：文件缺失、为空或缺少 Java/Clojure/资源任务标记时立即失败，避免再次形成静默阻断。
6. Fabric 26.2 的空 OBJ 注册 seam 和未被 mixin 配置引用的空 OBJ mixin 已删除；该目标的生成 vanilla item model 是唯一实现。26.2 GUI 透视 seam 已改为由 NeoForge 显式安装 render-state 提交回调：没有活动透视时保持普通路径，活动透视时只创建必要的网格 state，不使用反射、通用代理或每帧 map 查找。
7. 复审发现 26.2 Presentation screen/container 曾遗漏 `draw-ui-model-preview!` backend context，导致 `:item-3d`/`:block-3d` 在该目标静默跳过；现已补上唯一 typed PIP 适配（`ReactivePreviewRenderState`），Fabric 仍按能力缺失返回 unsupported，不保留第二套渲染实现。
8. 删除 `ac/ability/service/combat_sessions.clj` 中未被任何调用点引用的批量 `tick!` 旧实现；当前 combat tick 只由 `combat_runtime`/final runtime 驱动，避免每 tick 重建整个 sessions map。
9. 复审发现 `mcmod` 的 pose 与 texture-binder seam 在渲染调用中仍通过 Framework/map 间接读取；现已在 bootstrap 安装时缓存固定 arity IFn，渲染调用只走直接 Var。`verifyClientRenderBridgeBoundary` 只检查实际热路径区域，避免把初始化期状态写入误判为热路径查找；同时移除 texture-binder 初始化辅助函数中的 `apply`，降低动态分配与门禁歧义。

执行顺序：先完成 seam 契约化候选清单与逐方法差异表 → 每次只提升一个经证明无版本依赖的实现 → 六目标 `compileJava/check/jar`（按各自 wrapper）→ datagen manifest/资源 allowlist/XOR/JEI/IC2 门禁 → 最后再考虑实机性能验收。任何阶段不得引入反射、旧逻辑兼容层或 class 与 `.clj` 共存。

### seam 可继续重构，但必须先规范再提升

seam 文件不是“只能原样复制”的禁区；正确顺序是先在各目标内做无语义改变的整理，再按 API 差异拆出可证明共用的部分。每个候选必须提交方法级差异表，标明返回类型、空值/异常语义、线程模型、分配行为和实际调用频率；只有所有差异都能落在版本适配边界内，才允许移动到 `minecraft-base`、`minecraft-classic` 或 `minecraft-modern`。不能用 `Object`、反射、通用代理、运行时 lambda/接口对象来“抹平”类型差异，否则会增加 Loom 风险和热路径开销。

当前复审还删除了已经没有调用者的迁移别名：各版本 `BlockRegistry`、`ItemRegistry`、`ItemInventory`、`DamageSourceAccess`、`BlockEntityRegistry` 转发类，26.2 的 `bridge/McAccess`、`bridge/NbtAccess`、旧 `RenderInterop`，Fabric 1.20.1 的 optional-integrations facade，以及 Fabric 26.2 的空 OBJ 兼容标记。调用点已直接指向唯一 owner；26.2 installer/runtime-ops 的导入也已改为 `mcbase`/`mcver`。第三方 IC2 的隔离反射边界和 JEI typed adapter 不属于这些迁移别名，继续保留。

本轮又删除了三个 MC 版本目录中 114 个明确标记为 `Thin re-export` 的 Clojure seam 外壳，并把仍存的调用点直接改到 `minecraft-base` owner；版本目录不再承担同一实现的命名空间转发。`verifyNoThinForwarders` 现在会扫描 `mc-*/src/main/clojure`，任何新的 thin re-export 都会使门禁失败。

本次 seam 清理不改变 tick/render 的调用形态：共享 seam 仍是静态 Java 方法，版本特有 API 仍在目标编译期绑定；不引入每次调用的 map 查找、反射、临时集合或包装对象。删除空壳/转发类反而减少类加载、链接和 AOT/Loom 扫描成本。执行删除后必须重新跑六目标 `compileJava`，并跑 `verifyNoThinForwarders`、`verifyNoCompatibilityResidues`、`verifyVersionSeamParity`；任何目标出现缺失 owner 或 class/source 重叠都立即停止提升并回退该单个候选。

本轮复审还收敛了两个 AC 内部双轨入口：技能 cost/cooldown/creative 函数现在只接收一个 context map，删除 `ArityException` 的位置参数回退和多余 `apply-cost!` 旧入口；开发者完成回调只通过中立 hooks 的 UUID 查询取得权威在线玩家，reset 在玩家不可用或物品校验失败时不再绕过校验，也不会清理未执行的会话。该查询不进入 tick 热路径，hooks 注册在平台网络初始化时完成。

## 明确排除的矛盾方案

- 不保留“source-first 默认 + full-AOT 开关”两套方案；目标 profile 是唯一选择。
- 不用反射替代静态平台 adapter；第三方适配器的反射例外只限其自身隔离层，不能扩散到 Minecraft/loader/Academy 代码或热路径。
- 不把 JEI/IC2 当作第二个主 Jar；它们始终是可选运行时能力。
- 不因中间阶段不能编译而回滚到旧逻辑；只在最终目标验收节点要求完整编译通过。

## 当前源码基线上的可执行收敛计划（2026-09-01）

以下步骤是唯一执行路径；历史段落只作审计背景，验收以当前源码和命令输出为准。

1. **阻断与配置预检**：运行 `verifyNeutralBuildScripts`，确认 `ac/build.gradle`、`mcmod/build.gradle` 存在、非零字节、非空白，并含 `java-library`、Clojure 插件、`compileJava`、`compileClojure`、`processResources`。随后运行 `verifyBuildProfiles`、`verifyTargetBuildEntrypoints`，每个目标只能选择一个 profile；禁止 source/AOT 双轨和 `.class`/`.clj` 同名共存。
2. **所有权与 seam 收敛**：先生成逐方法差异表（签名、空值/异常、线程模型、分配和调用频率），再按“版本无关契约 → 唯一 shared owner → typed 版本 adapter”顺序移动。删除无调用者的旧 tick、thin re-export、迁移别名；禁止用 `Object`、反射、通用代理或运行时 lambda 消除 API 差异。每次只处理一个候选，立即运行 `verifyNoThinForwarders`、`verifyNoCompatibilityResidues`、`verifyVersionSeamParity`。
3. **运行时效率边界**：tick、事件转发、渲染调用只使用 bootstrap 安装的固定 arity Java/Clojure IFn 或静态 Java 方法；不得在热路径读取 Framework atom/map、`requiring-resolve`、`apply`、反射或创建临时集合/代理。初始化期可以写 Framework 状态，但必须与调用期直接 Var/typed callback 分离。用 `verifyCorePerformance`、`verifyClientRenderBridgeBoundary`、`verifyPresentationDirectHostBoundary`、`verifyVfxDirectHostBoundary` 做静态验收；本轮不跑实机 JFR/JMH。
4. **资源和可选接入**：模型、纹理、声音、配方、标签、翻译全部由共用资源/datagen 生成；不提交独立 `en_us.json`。平台层只保留 loader metadata、mixin/入口声明、版本专属 `pack.mcmeta`/shader 等 allowlist 项。JEI 保持六目标 typed adapter；IC2 仅在隔离第三方边界允许反射，禁止扩散到 Minecraft/loader/Academy 主路径。运行 `verifyDatagenOwnsLanguageFiles`、`verifyPlatformResourceOwnership`、`verifyOptionalIntegrations`。
5. **六目标无头编译验收**：依次执行 `:platform:classes`，目标为 Forge 1.20.1、Fabric 1.20.1、Fabric 1.21.1、NeoForge 1.21.1、NeoForge 26.2、Fabric 26.2；26.2 必须使用目录指定的 Gradle 9.2/9.7.1 wrapper。随后执行根 `verifyCurrentPlatforms`（包含 neutral build script、AOT XOR、资源、JEI/IC2、架构门禁）。中间阶段允许失败，但不得以旧实现补洞；最终六目标必须全部通过。
6. **发布前验收**：在六目标 classes 通过后，按各目标 wrapper 执行 `check` 与 `jar`/`remapJar`，扫描最终 Jar：loader metadata/manifest 各一份、JEI wrapper 恰一份、不得嵌入 JEI/IC2、不得出现 class/source 重叠。任何单个 seam 候选失败只回退该候选，不恢复整套旧架构。
