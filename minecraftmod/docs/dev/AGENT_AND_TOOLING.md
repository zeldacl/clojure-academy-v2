# Agent 与工具链约定（正文）

本文档是仓库内 Agent/开发辅助工具规则正文。根目录 `CLAUDE.md` 仅为指向本文件的指针（不重复条文）；Clojure 原则见下文「代码与运行时规则」。

## 构建与开发基线（Windows 使用 `cmd /c .\gradlew.bat`）

- **工作目录**：始终在包含根 `settings.gradle` 的 `minecraftmod` 目录执行命令。
- **Windows 命令壳**：在当前 VS Code/PowerShell 环境中优先使用
  `cmd /c .\gradlew.bat ...`；带 `-D...` 的系统属性放在 task 前并加引号。
- **默认构建平台**：根工程默认包含 `api`、`mcmod`、`ac`、`forge-1.20.1`、`fabric-1.20.1`。
- **Fabric 维护级别**：`minimal maintenance`（至少保证 compile 与边界门禁，不承诺与 Forge 完全功能对齐）。
- **Java 版本**：Java 17。
- **LSP 刷新**：修改 Java 后执行 `cmd /c .\gradlew.bat :<subproject>:classes`。
- **性能采样**：门禁耗时使用 `powershell -ExecutionPolicy Bypass -File .\scripts\perf\record_gate_performance.ps1 -WarmDaemon -Iterations 3`，结果写入 `build/reports/gate-performance/`。

## 常用命令矩阵

- **本地运行**：`cmd /c .\gradlew.bat :forge-1.20.1:runClient` / `:forge-1.20.1:runServer` / `:forge-1.20.1:runData`
- **快速编译**：`cmd /c .\gradlew.bat :ac:compileClojure`、`:mcmod:compileClojure`、`:forge-1.20.1:compileClojure`
- **测试与验证入口**：
  - `cmd /c .\gradlew.bat :mcmod:compileClojure :ac:compileClojure :forge-1.20.1:compileJava :forge-1.20.1:compileClojure :fabric-1.20.1:compileJava :fabric-1.20.1:compileClojure -x :forge-1.20.1:processResources -x :fabric-1.20.1:processResources`（Windows 上优先用于源码级边界重构验证；规避 stale resource file lock 噪音）
  - `cmd /c .\gradlew.bat verifyArchitectureBoundaries`
  - `cmd /c .\gradlew.bat verifyAotBootstrapSafety`
  - `cmd /c .\gradlew.bat verifyNonAcNoBusinessRuntimeHookApis`
  - `cmd /c .\gradlew.bat verifyNonAcNoBusinessSemanticResidue`
  - `cmd /c .\gradlew.bat verifyCleanupResidueGuards`
  - `cmd /c .\gradlew.bat verifyAbilityArchitectureStrict`（能力 reducer-only / 禁止旁路写与已删 API）
  - `cmd /c .\gradlew.bat verifyAbilityNoDispatcherBusinessApiUsage`（禁止 `context-registry` 门面与 `ctx-reg/`）
  - `cmd /c .\gradlew.bat auditTopLevelMutableState`（报告型顶层可变状态审计；输出 `build/reports/top-level-state/audit.md`）
  - `cmd /c .\gradlew.bat unitTestCompile`
  - `cmd /c .\gradlew.bat runAcUnitTests`（执行 `ac` 的 `clojure.test`，入口为 `:ac:runAcClojureTests` / `cn.li.ac.test-runner`；可选 `"-Dac.test.only=cn.li.ac.foo-test,cn.li.ac.bar-test"`；输出慢测试 namespace Top 10）
  - `cmd /c .\gradlew.bat runMcmodUnitTests`（执行 `mcmod` 的 `clojure.test`，入口为 `:mcmod:runMcmodClojureTests` / `cn.li.mcmod.test-runner`；可选 `"-Dmcmod.test.only=cn.li.mcmod.foo-test,cn.li.mcmod.bar-test"`；输出慢测试 namespace Top 10）
  - `cmd /c .\gradlew.bat quickUnitTests`（聚合 `runAcUnitTests` + `runMcmodUnitTests`）
  - `cmd /c .\gradlew.bat verifyLocalPrGate`（聚合 `verifyCurrentPlatforms` + `quickUnitTests`）
  - `cmd /c .\gradlew.bat verifyAotBootstrapSafety`
  - `cmd /c .\gradlew.bat verifyNoPlatformReflection`（扫描 main Clojure 源，禁止 MC/Forge/Fabric 反射；仅 allowlist `ic2_energy.clj`）
  - `cmd /c .\gradlew.bat verifyForgeBaseline`
  - `cmd /c .\gradlew.bat verifyFabricBaseline`
  - `cmd /c .\gradlew.bat verifyFabricSmoke`
  - `cmd /c .\gradlew.bat verifyForgeHookCoverage`
  - `cmd /c .\gradlew.bat verifyFabricHookManifest`
  - `cmd /c .\gradlew.bat verifyPlatformHookCoverage`
  - `cmd /c .\gradlew.bat verifyPlatformNoBusinessHookIds`
  - `cmd /c .\gradlew.bat verifyCurrentPlatforms`
  - `cmd /c .\gradlew.bat runForgeGameTests`
  - `cmd /c .\gradlew.bat validateForgeGameTestLog`
  - `cmd /c .\gradlew.bat verifyForgeTesting`

## 任务语义（Run vs Package）

- **Run/开发任务**（`runClient`、`runServer`、`runData`、`runDatagen`、`runGametestServer`）：目标是“加载最新代码”，应保持轻路径；避免把 full `checkClojure`/LVT 打包链路硬塞入 run 图。
- **Package/产物任务**（`assemble`、`jar`、`shadowJar`、`remapJar`）：必须走完整包级门禁（包含 `checkClojure`、AOT、必要的 remap/package 处理）。
- 包级入口禁止与 `-PcheckNsOnly` / `-PcheckNsFile` 混用；需要做 focused check 时，单独执行检查任务，不要从 package 入口绕行。
- 平台模块（Forge/Fabric）应消费 `ac` / `mcmod` 的编译产物与运行时输出，不再把这两个模块源码注入平台 sourceSets；`mc-1.20.1` 共享源码注入策略保持现状。
- **故障定位**：
  - `cmd /c .\gradlew.bat :forge-1.20.1:bisectCompileClojure`
  - `cmd /c .\gradlew.bat :forge-1.20.1:bisectCheckClojure`
  - 快速候选定位可加 `-PbisectMode=fast`；默认 clean-safe 保留，fast 候选会自动 clean-safe 单 namespace 确认。
  - `-PcompileNsOnly=<ns1,ns2>` / `-PcheckNsOnly=<ns1,ns2>` / `-PcheckNsFile=<path>`

## 模块边界与依赖红线

### 严格业务下沉边界（2026-05-25）

- 非 Minecraft / Forge / Fabric 原生概念一律视为业务语义；业务语义只允许出现在现有 `ac` 模块。
- `mcmod` 只保存中性协议、descriptor registry 与 host-readable envelope；不得通过“通用命名 + 业务默认值/示例/测试夹具”的方式规避边界。
- `mc-1.20.1` 只执行 Minecraft-native host primitive 与 opaque descriptor；不得硬编码内容 ID、内容 NBT key、内容 UI key、内容 worldgen/sound/render 默认值。
- `forge-1.20.1` / `fabric-1.20.1` 只做 Loader glue、事件绑定与原生 API 适配；不得直接识别或分支处理具体内容业务。
- 禁止新增 `ac-mc-*`、`ac-forge-*`、`ac-fabric-*`、`content-version-*` 或等价 source set/adapter 逃生口。若业务逻辑无法表达为中性 descriptor / opcode / context，则继续拆分；仍不能表达的功能本轮下线。
- 内容初始化必须通过 ServiceLoader discovery 或 `mcmod` 中性 registry；平台不得硬编码单个内容模块。

- **`mcmod`**：平台无关协议、DSL、元数据与基础运行时；禁止引入 `net.minecraft.*` 与 Loader API。
- **`ac`**：业务内容层（能力、无线、GUI 业务逻辑等）；禁止直接引用 Forge/Fabric/Minecraft API。
- **`mc-1.20.1`**：共享 Minecraft 逻辑层；允许依赖 `net.minecraft.*`，但禁止依赖 Forge/Fabric Loader API。
  - **内部结构**（Phase C 后）：
    - `runtime/*_spi.clj`：3 个平台独立 SPI 契约
      - `server_context_spi.clj`：Server 上下文注册/获取契约
      - `network_transport_spi.clj`：网络消息传输契约
    - `gui/registry_api.clj`：GUI Menu 类型注册契约
    - `runtime/*_core.clj`：19+ 个运行时核心模块（实体、生命周期、网络、NBT 等）
      - `runtime/network_core.clj` 负责 runtime network 的共享 handler/route/send 注册；平台层只提供 loader-specific push transport 与 server-context installer。
      - `runtime/spi/server_context.clj` 暴露显式 server state、fail-fast `require-current-server` 与 server available/unavailable callbacks。
    - `lifecycle/platform_manifest.clj`：共享 Forge/Fabric lifecycle phase manifest；平台层只提供 action 函数与 Loader 事件绑定。
    - `integration/event_handlers.clj`：共享事件处理业务逻辑
    - `integration/event_helpers_core.clj`：共享事件辅助函数（runtime 检查、数据构建）
    - `client/overlay/renderer.clj`：共享 overlay 渲染核心
    - `gui/registry/common.clj` 与 `gui/registry/open.clj`：共享 GUI 容器创建与打开逻辑
    - `gui/init/orchestrator.clj` 与 `gui/init/checks.clj`：共享 GUI phase manifest 编排、safe phase execution 与初始化自检；平台 GUI init 只声明 common/server/client phase steps。
    - `gui/menu/*`、`gui/provider/*`、`gui/screen/*`、`gui/slots/*`、`gui/cgui/*`：按职责拆分的菜单桥接、provider、screen、slot 与 CGui 运行时实现；旧的顶层 `*_core` / `*_common` / `*_bridge` GUI 文件不得回流。
    - `datagen/*_common.clj`：共享 datagen provider 实现
    - `datagen/provider_manifest.clj`：共享 datagen provider 顺序与逻辑 provider 集合；平台 datagen setup 只适配具体 Factory/API。
- **`forge-1.20.1`**：Forge 事件绑定与 Loader 入口层（Phase C 后仅包含）；仅保留直接引用 Forge 的代码和事件注册胶水。
  - **结构规范**：
    - `mod/` 与 `mod.clj`：Loader 入口与初始化（Wave A：调用 `setup/forge_lifecycle_coordinator.clj` 编排生命周期）
    - `setup/event_registration_manifest.clj`：声明式事件注册规范（Wave A）
    - `setup/event_registration.clj`：统一事件绑定器（Wave A）
    - `runtime/server_context.clj`：Server 上下文 SPI 实现（Wave B）
    - `runtime/network.clj`：网络 SPI 实现（Wave B）
    - `gui/registry_impl.clj`：GUI Menu 类型 SPI 实现（Wave B）
    - `registry/`：Loader API 入口（DeferredRegister 等）
    - `client/`：Forge 事件绑定（`RenderGuiOverlayEvent` 等），仅做事件解包 → 调用 mc1201
    - `integration/events.clj`：Forge 事件处理（事件对象解包、回写结果），业务逻辑委托到 mc1201
    - `datagen/setup.clj`：Datagen 入口（Forge GatherDataEvent）
    - 不得包含与 Loader 无关的 Minecraft-only 逻辑
- **`fabric-1.20.1`**：Fabric 事件绑定与 Loader 入口层（Phase C 后仅包含）；Minecraft/Forge/Fabric 互操作经 `mc-1.20.1` 的 typed interop、共享 Java accessor 或平台注入 op，**禁止** `Class/forName` / `Reflector` / `java.lang.reflect` 访问 `net.minecraft*` / `net.fabricmc*`（Loom 会破坏反射符号）。
  - **结构规范**（同 Forge）：
    - `mod/` 与 `mod.clj`：Loader 入口（Wave A：调用 `setup/lifecycle_init.clj` 编排生命周期）
    - `setup/lifecycle_init.clj`：Fabric 生命周期编排（Wave A）
    - `runtime/server_context.clj`：Server 上下文 SPI 实现（Wave B）
    - `runtime/network.clj`：网络 SPI 实现（Wave B）
    - `gui/registry_impl.clj`：GUI Menu 类型 SPI 实现（Wave B）
    - `registry/`：Fabric API 入口（ScreenHandlerRegistry 等）
    - `client/`：Fabric 事件绑定（`HudRenderCallback` 等），仅做事件解包 → 调用 mc1201
    - `integration/events.clj`：Fabric 事件处理（事件对象解包、回写结果），业务逻辑委托到 mc1201
    - `datagen/setup.clj`：Datagen 入口（Fabric DataGeneratorEntrypoint）
    - 与 Forge 相同：**不得**用反射访问 Minecraft/Fabric API；Loader 差异通过平台注入 op 或 `mc-1.20.1` Java accessor 解决
- **`forge-1.20.1`**：Loader 入口与平台适配；可使用 Minecraft/Forge API。
- **Datagen shared core**：配方/语言等平台无关生成逻辑优先放在 `mc-1.20.1/src/main/clojure/cn/li/mc1201/datagen/*_core.clj`，Forge/Fabric provider 仅保留 PackOutput/Loader API 适配壳。
- **边界规则（更新）**：
  - 禁止 `ac` 对 `cn.li.forge1201.*` 建立静态编译依赖。
  - `forge-1.20.1` / `fabric-1.20.1` 中凡是“只依赖 Minecraft、与 Loader 无关”的逻辑，应优先迁入 `mc-1.20.1`；平台层不得长期保留纯代理 wrapper 或双端镜像实现。
  - `forge-1.20.1` 可通过受控桥接调用 `ac` 能力（例如动态 require / ns-resolve），但不得把 `ac` 实现细节固化为跨层 API。
  - 平台层只允许保留“实现适配键 -> 平台实现类/函数”映射；禁止直接硬编码业务内容 ID（技能/实体/玩法名）。
  - 业务 hook-id 到实现键的映射必须位于业务内容层（如 `ac`）；共享层/平台层只能通过 `mcmod` 的通用 resolver/provider 消费，不得承载业务语义。
  - Fabric 与 Forge 一样：对已解析 Minecraft 对象的操作逻辑在 `mc-1.20.1`；平台层只做 Loader 事件绑定与注入 op。可选第三方 mod（如 IC2 `ic2.api.*`）可在单一标注边界内保留反射，由 `verifyNoPlatformReflection` allowlist 守护。
  - Forge `ServerLifecycleHooks` 按需取 server 与 Fabric `server-context` 捕获 server 目前视为平台差异，不在本轮强行统一；shared core 应接收显式 server 或回调，不直接耦合 Loader 生命周期源。
  - 所有跨层调用都应通过清晰入口函数与文档记录，避免隐式耦合蔓延。

## 开发工具实践

1. **语义导航优先**：优先使用 LSP；文本搜索优先 `rg`。
2. **精确读取**：优先小范围读取，避免一次性加载超大文件。
3. **上下文控制**：按任务最小化上下文，避免无关内容污染判断。
4. **搜索排除**：忽略 `build/`、`.gradle/`、历史归档目录等噪音路径。
5. **禁止手动修改 generated 目录**：`src/generated/` 下的所有文件由 DataGen（`runData`）自动生成，**禁止手工编辑**。修改逻辑应改对应的 DataProvider 源（如 `lang_data.clj`、`item_model_provider.clj` 等），然后重新跑 `runData` 重新生成。
6. **禁止使用 `git stash` / `git stash pop`**：`git stash` 会隐藏未跟踪/已修改文件，`git stash pop` 可能引发合并冲突或覆盖当前工作，容易造成工作丢失或上下文混乱；应使用显式分支（`git checkout -b <branch>`）或 WIP commit（`git commit -m "WIP: ..."`）暂存工作，不依赖 stash 栈管理中间状态。

## 测试约定（ac / mcmod）

- 测试文件统一命名：`*_test.clj`；测试命名空间统一 `*-test`。
- `ac` 测试入口使用 `cn.li.ac.test-runner` 自动发现测试，不再手工维护列表。
- 支持按命名空间过滤执行：`-Dac.test.only=cn.li.ac.foo-test,cn.li.ac.bar-test`。
- `ac` 与 `mcmod` 的测试入口只保留模块参数，公共发现/过滤/执行逻辑位于 `test-support/src/main/clojure/cn/li/test_support/auto_test_runner.clj`。
- `ac` 专属测试 stub 放在 `ac/src/test/clojure/cn/li/ac/test/support/**`，避免在业务测试里重复造 stub。
- 优先断言公开边界和业务不变量；仅在平台桥接边界使用 `with-redefs`。
- 新增或重构测试时，禁止直接依赖私有实现细节（例如私有 helper 的调用序）。

### 覆盖率与 ratchet（ac）

- 生成报告：`cmd /c .\gradlew.bat :ac:coverageAcClojureTests`（HTML 在 `ac/build/reports/coverage/index.html`）。
- **Soft ratchet（手动）**：生成报告后对照 `ac/coverage-baseline.txt`（当前基线约 31% 行覆盖）；ratchet shell 脚本尚未接入仓库，勿假设 CI 会自动执行。
- 有意提升整体行覆盖后，将 `ac/coverage-baseline.txt` 更新为新百分比（可四舍五入一位小数），随 PR 提交。

### 覆盖率与 ratchet（mcmod）

- 生成报告：`cmd /c .\gradlew.bat :mcmod:coverageMcmodClojureTests`（HTML 在 `mcmod/build/reports/coverage/index.html`）。
- **Soft ratchet（手动）**：生成报告后对照 `mcmod/coverage-baseline.txt`（当前基线约 52.89% 行覆盖）；ratchet shell 脚本尚未接入仓库。
- 提升基线后更新 `mcmod/coverage-baseline.txt`（建议一位小数），随 PR 提交。
- `coverageMcmodClojureTests` 通过 `--ns-exclude-regex` 排除 `cn.li.mcmod.client.obj` 与 `cn.li.mcmod.platform.position`（前者体量与单测 ROI，后者避免 Cloverage 与 `IBlockPos` 测试桩的协议冲突）；详见 [BUILD_AND_VERIFY_PLAYBOOK.md](BUILD_AND_VERIFY_PLAYBOOK.md)。

### 单测优先域 vs 交给集成（粗略）

| 优先单测 | 低 ROI / 交给 GameTest 或手测 |
|----------|------------------------------|
| 纯函数 util、模型、registry、effect.core、wireless 数据层（可 stub 平台） | `*.block` / `*.gui` / `*.render` / `*-fx` / `client.*` / `*.platform-bridge` |
| 带注入的 pipeline（`with-redefs` 动态 var） | 纯 GUI 布局、粒子/音效注册胶水 |

## 代码与运行时规则

## AOT 启动期安全（Clojure 编译）

- 说明文档：[`AOT_BOOTSTRAP.md`](AOT_BOOTSTRAP.md)
- 快速排查：见 `AOT_BOOTSTRAP.md` 的「开发注意事项（必读）」与「出问题时怎么查（SOP）」
- 目标：禁止在命名空间顶层触发 Minecraft registry/bootstrap 相关访问，避免 `IllegalArgumentException: Not bootstrapped`。
- 静态门禁：`verifyAotBootstrapSafety`（`tools/aot-linter/aot_safety.clj` + `docs/dev/aot-linter-allowlist.edn`）。

### Clojure 设计与实现原则（强制）

编写或修改 `mcmod` / `ac` / 平台 Clojure 代码时遵守：

1. **函数式风格**：优先纯函数与不可变数据；副作用集中在显式边界（命名或模块上可识别），避免可变共享状态与命令式流程控制。
2. **Clojure 最佳实践**：遵循项目既有命名与抽象（`defprotocol`、数据驱动 DSL、threading macro 等）；不引入与 idiomatic Clojure 相悖的 Java 式层次或样板。
3. **最精简代码**：在满足可读性的前提下删冗余；不为“将来可能”增加分支、配置或抽象。
4. **单一最佳设计**：同一问题只保留一套方案；禁止新旧架构并存、双路径注册或“过渡层”长期留存。
5. **无薄封装残留**：删除仅转发一层的 wrapper、镜像模块与无增量的 indirection；逻辑应落在正确层级的一次实现上。
6. **不兼容旧代码与存档**：重构时不保留旧 API、迁移分支或读档兼容；直接改数据格式与注册路径，由调用方与资源一并更新。
7. **禁止冗余代码**：不得保留死代码、重复实现、未使用的 def/var 与仅为占位的大段注释块；发现即删或合并。
8. **相似模式须抽象**：同一文件或邻近模块内重复出现的结构（注册、校验、网络编解码、GUI 槽位等）应提取为共享函数或宏，禁止复制粘贴多份近似逻辑。
9. **目录与文件须合规**：新建或移动源码前对照 [PROJECT_LAYOUT.md](../01-overview/PROJECT_LAYOUT.md) 与模块既有子域划分；命名空间路径与目录一一对应，禁止在根目录或随意子包下堆放“临时”文件。
10. **慎用顶层全局变量**：非必要不得新增命名空间级 `def` / `defonce` / `^:private def`（含可变 atom 与“方便缓存”的单例）；优先参数传递、显式 owner（world/player/session 等）或不可变注册表。若确须保留，须在代码旁注明**无法编译**或**无法实现功能**的具体原因；可变顶层状态见 [TOP_LEVEL_STATE_GOVERNANCE.md](TOP_LEVEL_STATE_GOVERNANCE.md)，新增须跑 `auditTopLevelMutableState` 并更新白名单（如适用）。

### Malli 终极判定法则（强制）

面向未来所有新概念（如气压系统、药水效果、自定义维度），不再先查“静态矩阵”，而是统一执行以下三问流程；**顺序不可交换**：

1. **频率判定（第一优先级）**：该数据/约束是否处于每秒高频执行路径（Tick、移动、渲染、WorldGen、AI、内层数学、NBT 字段读写循环）？
  - 若是：**禁止 Malli**（含 `m/validate`、`m/explain`、生产期 instrumentation）。
  - 做法：使用 Java interop + type hint + 已验证数据输入，确保热路径只做业务计算。
2. **边界判定**：该数据是否由玩家行为、系统事件或时间边界触发（发包、存盘、配置加载、资源重载、开界面、达成成就、命令、注册期）？
  - 若是：**全面使用 Malli**，并优先用 compiled validator；只在失败分支生成 explain。
  - 注：若某“边界”实际由 tick 高频触发（如每 tick 群发包），回退到第 1 条按高频禁用处理。
3. **前移判定**：该约束能否在打包 Jar、AOT、宏展开或 REPL 保存时定死？
  - 若能：必须前移到 macro/load-time/registration/datagen 阶段，在开发机与 CI 提前失败。
  - 目标：玩家运行时不承担 schema 成本，AOT 后热路径保持 0 额外开销。

#### 典型落位与禁用面

- **推荐（低频边界）**：配置/资源读取、datagen metadata、GUI message envelope、注册期 contract、成就触发数据、命令参数。
- **前移（编译/注册期）**：Block/Item/Fluid/Entity/Tile descriptor、recipe 常量、provider manifest、可静态确定的 schema。
- **绝对禁用（执行内层）**：render loop、worldgen 执行、tick 回调、AI/motion、NBT 读写内层循环。

#### PR 必填自证项

凡新增 Malli 校验的 PR，必须说明：

1. 校验触发位置（macro/load-time/registration/runtime-boundary/test-only）。
2. 为什么不属于高频热路径。
3. 是否可进一步前移；若不能，给出原因。
4. 生产环境剩余开销预期（应为“低频可接受”或“0 热路径开销”）。

#### FP 通用原则 — P.I.C.A.S.O.（毕加索原则）

面向函数式编程的六条通用准则；编写 `mcmod` / `ac` 逻辑时作为上述强制原则的展开：

- **P — Pure Functions（纯函数）**：一个函数只做计算；相同输入必然相同输出。
- **I — Immutability（不可变性）**：数据一旦创建绝不就地修改；一切变化通过计算生成新数据。
- **C — Composition（函数组合）**：不写大段单体逻辑；用单一小函数像搭积木一样组合出复杂行为。
- **A — Actions vs. Calculations（动作与计算隔离）**：严格区分有副作用的 I/O 动作与纯计算，把副作用逼到系统最边缘。
- **S — Shared Nothing（无共享状态）**：函数间不共享全局变量；并发天然安全，消除死锁与隐式污染。
- **O — One generic data structure（单一通用数据结构）**：不为每个概念发明 Class/Record 壳；优先用 Map/Vector 等通用结构承载数据。

#### Clojure 专属原则 — S.I.D.E.（边际原则）

Rich Hickey 式 Clojure 设计哲学；日常编码与架构取舍时优先对照：

- **S — Simplicity（简单而非简便）**：拒绝把数据、逻辑、状态交织（braid）在一起；每个组件保持独立与纯粹。
- **I — Identity / State split（标识与状态分离）**：用 Atom/Ref 等代表标识，用不可变快照代表状态；以时间轴上的状态演变代替对象就地修改。
- **D — Data-Driven（数据驱动）**：业务逻辑、配置与系统状态建模为纯数据（Map/List），而非特异的代码与类型层级。
- **E — Expression Problem Solved（表达式问题）**：用 Protocol 与 Multimethod 扩展新数据与新行为，避免修改旧代码；开放扩展、封闭修改。

- **侧分离**：客户端代码放 `client` 侧；共享逻辑不得引用 `net.minecraft.client.*`。
- **反射与动态求值**：**禁止**通过字符串反射解析 Minecraft/Forge 符号，包括但不限于：`Class/forName`、`.getMethod`、`.getField`、`.getDeclaredMethod`、`.getDeclaredField`、`clojure.lang.Reflector`、Java Reflection API、`ns-resolve` 动态解析平台类等；`eval` 内禁止出现平台类名直连。
- **桥接约束**：`ac` 不得 `resolve` / `requiring-resolve` `cn.li.forge1201.*`。
- **DSL 内容注册**：内容定义优先通过 `mcmod` DSL 与元数据注册路径完成。

### CGUI 事件分发模型 — deepest-only（对齐原版 AcademyCraft / LambdaLib2）

项目 CGUI 采用 **deepest-only** 事件分发，对齐原版 AcademyCraft（LambdaLib2）语义，**不是** HTML DOM 的冒泡（bubbling）模型。

#### 核心规则

- `mouse-click!` 使用 `hit-test`（仅最深层的可见 widget），**不**向上冒泡查找祖先的 click handler。
- `gain-focus!` 也对最深层 widget 执行——点击文字标签时焦点落在标签 widget 上，而非父容器。
- 覆盖层（overlay）的"点击遮罩关闭"利用此特性：遮罩的 `on-left-click` 只在**直接点击遮罩空白区域**时触发，子面板上的点击不会冒到遮罩。

#### 开发约束

由于不冒泡，以下模式需要显式处理：

**1. 按钮包含子 widget（文字/图标）时**：必须在所有子 widget 上也挂载 click handler 转发到父逻辑。

```clojure
;; panel.clj — wireless 按钮包含 text_nodename 和 logo_node 子 widget
(let [forward (fn [_] (on-wireless-click))]
  (events/on-left-click wbtn forward)
  (doseq [child (cgui-core/get-widgets wbtn)]
    (events/on-left-click child forward)))
```

**2. 键盘输入目标被子 widget 阻挡时**：父 widget 的 `:key` handler 需要在每个可能接收焦点的子 widget 上也挂载。

```clojure
;; console.clj — console panel 包含多个文字行子 widget
(doseq [{:keys [widget]} line-widgets]
  (events/on-left-click widget click-noop)   ;; 子 widget 可接收焦点
  (events/on-key-press widget key-handler))  ;; 键盘事件转给 console 逻辑
```

**3. 能力工厂函数签名**：`get-capability` 调用工厂时传 `(factory be side)`（2 参），工厂必须兼容：

```clojure
;; logic.clj — 接收 & _ 忽略可选的 side 参数
(defn create-dev-receiver-cap [be & _] ...)
```

#### 涉及文件

| 文件 | 相关改动 |
|------|---------|
| `mc-1.20.1/.../cgui/input.clj` | `mouse-click!` 使用 `hit-test`（不冒泡）|
| `mc-1.20.1/.../cgui/traversal.clj` | `hit-test` / `hit-path` / `collect-widgets-z-ordered` |
| `ac/.../developer/gui.clj` | overlay cover handler 无需坐标检测 |
| `ac/.../developer/panel.clj` | 按钮子 widget 转发；`refresh-linked-node-label!` 使用 owner |
| `ac/.../developer/console.clj` | console 行 widget 代理 key/click 事件 |
| `ac/.../developer/logic.clj` | `create-dev-receiver-cap [be & _]` 兼容 2 参调用 |

## 结构性校验（快速自检）

- 推荐优先执行：`cmd /c .\gradlew.bat verifyArchitectureBoundaries`（自动汇总并定位违规文件/行号）。
- `verifyArchitectureBoundaries` 已按文件单次扫描并声明输入/通过标记输出；连续热运行若源文件未变应显示 `UP-TO-DATE`。
- `verifyCleanupResidueGuards` 子任务已声明共享源输入与通过 marker；热运行应避免重复执行 25+ 个扫描型守卫。
- `rg "cn\.li\.forge|cn\.li\.fabric" ac/src/` 应为空。
- `rg "net\.minecraft|net\.minecraftforge" ac/src/ mcmod/src/` 应为空（允许注释或字符串需人工甄别）。
- `rg "net\.minecraft\.client" ac/src/ mcmod/src/` 在非 client 目录应无泄漏。

## Hook 覆盖契约（平台一致性）

- AC hook catalog：`ac/src/main/clojure/cn/li/ac/entity/hook_catalog.clj`
- 通用 resolver seam：`mcmod/src/main/clojure/cn/li/mcmod/entity/hook_resolver.clj`
- Fabric 缺口清单：`docs/dev/fabric-hook-support.properties`
- 执行：`cmd /c .\gradlew.bat verifyPlatformHookCoverage`
- 规则：
  - Forge 必须覆盖共享 catalog 声明的实现键。
  - Fabric 若暂未实现，必须在缺口清单显式声明；新增实现键时清单必须同步更新。

## 平台层去业务化回归守卫

- 执行：`cmd /c .\gradlew.bat verifyPlatformNoBusinessHookIds`
- 执行：`cmd /c .\gradlew.bat verifyNonAcNoBusinessRuntimeHookApis`
- 执行：`cmd /c .\gradlew.bat verifyNonAcNoBusinessSemanticResidue`
- 聚合执行：`cmd /c .\gradlew.bat verifyCleanupResidueGuards`
- 规则：
  - 平台 hook 适配文件不得出现来自业务实体定义中的 hook-id 字面量。
  - 若任务失败，需把业务 hook-id -> impl-key 映射迁回业务内容层（如 `ac`），共享/平台层只保留通用 resolver 或 impl-key -> 实现映射。
  - 用户态 hook/request API 名、skill/preset/cooldown/ability 等业务术语、AC NBT/wire/gui/worldgen/render fixture、无线/终端/铁路炮/虚相等内容名不得出现在 `mcmod`、`mc-1.20.1`、`forge-1.20.1`、`fabric-1.20.1` main 源码或资源元数据中。
  - `mcmod` 可暴露中性 descriptor / opcode / envelope；具体业务 key、NBT key、payload key、screen key、integration target 与 smoke manifest 必须由 `ac` 注册。
  - Guard 误报优先通过中性示例/注释/fixture 清理解决；只有 Minecraft/Forge/Fabric 原生术语（如 Forge `capability`、动画 `phase`、GUI `imageWidth`）才允许保留。

## 能力系统架构（AC，reducer-only）

能力域**只**允许 reducer 架构，无第二套状态写路径。维护手册：[ABILITY_SYSTEM_MAINTENANCE.md](../04-systems/ABILITY_SYSTEM_MAINTENANCE.md)。

| 层级 | 模块 | 职责 |
|------|------|------|
| 命令壳 | `service.command-runtime` | 唯一 `run-command!` 入口；提交后写 `runtime-store` |
| 归约 | `service.reducer` | `apply-command`；纯函数更新 `:ability-data`、`:resource-data`、`:cooldown-data`、`[:context-registry]` 等 |
| 存储 | `service.runtime-store` | 玩家状态 SSoT |
| 副作用 | `effects.interpreter` + `effects.*` | 执行 reducer 返回的 effects |
| 技能写 | `service.context-skill-state` → `skill-state-commands` | 技能内改 skill-state，必须走 reducer |
| 适配器灌入 | `adapters.server-hooks` / `client-ui-hooks` | 仅用 `:hydrate-player-state`，不用 `:sync-*-data` |
| Context 读/传输 | `service.context-dispatcher`（`:as ctx`） | lifecycle、合并读；**无** `context-registry` 门面 |

**Agent 修改能力代码时：**

- 新增或修改玩家状态 → 在 `reducer.clj` 增加/调整 `defmethod apply-command`，调用方发 `{:command ...}`。
- 技能改 skill-state → `ctx-skill/assoc-skill-state!` 等，禁止 `update-context!`。
- 不要新增 `store/set-player-state!*` 调用（白名单见维护手册）。
- 验证：`verifyAbilityArchitectureStrict`、`verifyAbilityNoDispatcherBusinessApiUsage`、`cn.li.ac.ability.architecture-guard-test`。

## Runtime message 边界

- AC ability message catalog：`ac/src/main/clojure/cn/li/ac/ability/messages.clj`
- 通用 message registry seam：`mcmod/src/main/clojure/cn/li/mcmod/hooks/messages.clj`
- 规则：
  - `ability:ctx/*`、`ability:skill/*`、`ability:sync/*`、`ability:req/*` 等 wire id 属于 AC 业务协议，必须保留在 `ac`。
  - `mc1201` / loader 平台层通过通用 message key 解析 wire id，不硬编码 AC message 字符串，也不静态依赖 `cn.li.ac.*`。
  - `ac.core.init/init` 负责调用 `cn.li.ac.ability.messages/install!`，在 runtime network 使用前安装业务 message id。
- 守卫：`cmd /c .\gradlew.bat verifyPlatformNoAbilityMessageIds`
