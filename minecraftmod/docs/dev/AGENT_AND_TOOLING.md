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
  - `cmd /c .\gradlew.bat verifyNoPlatformReflection`（正则扫描 main Clojure 源文本，禁止显式反射 API：`Class/forName`、`clojure.lang.Reflector`、`.getMethod` 等；仅 allowlist `ic2_energy.clj`。**不检测**编译器隐式反射——缺类型提示导致的反射 warning 需要 `checkClojure`）
  - `cmd /c .\gradlew.bat verifyForgeBaseline` / `verifyFabricBaseline`（2026-07 起两者均已把 `:forge-1.20.1:checkClojure` / `:fabric-1.20.1:checkClojure` 纳入依赖链，真正拦截 Clojure 编译器隐式反射 `reflection='fail'`；此前 `checkClojure` 虽然声明了 `reflection='fail'`，但从未被任何 baseline/PR 门禁调用到，`compileClojure` 任务本身也不产生反射 warning（`-Dclojure.compile.warn-on-reflection=true` 这个 JVM 系统属性对 Clojure 编译器无效，mcmod/build.gradle 里监听 "Reflection warning" 文本的 `StandardOutputListener` 因此一直是死代码）——这意味着此前 forge/fabric 平台专属 Clojure 源码的隐式反射长期零覆盖。审计时用探针文件验证实测确认了这一点，并顺带修复了当时唯二能找到的 6 处遗留反射警告（`gui/reactive/render.clj` 缺返回类型提示；`fabric1201` 的 `registerGlobalReceiver`/`getWindow`/`sendSystemMessage`/`Player.openMenu`——最后一处是真 bug：Fabric 端错写成 Yarn 映射名 `openHandledScreen`，官方映射下该方法根本不存在，运行时会直接抛 `NoSuchMethodException`）。`checkClojure` 会重新遍历全部命名空间（较慢），衡量是否值得为 baseline 增加这层时按此记录取舍。
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
    - `gui/menu/*`、`gui/provider/*`、`gui/screen/*`、`gui/slots/*`：按职责拆分的菜单桥接、provider、screen、slot 实现；旧的顶层 `*_core` / `*_common` / `*_bridge` GUI 文件不得回流。
    - `gui/cgui/font.clj`：MSDF 字体桥（唯一保留的 CGUI 文件，见下方「CGUI MSDF 字体」）；其余旧 CGUI 运行时文件已删除，替换为 `gui/reactive/*`（见 PROJECT_LAYOUT.md「反应式 UI 框架迁移」）。
    - `datagen/*_common.clj`：共享 datagen provider 实现
    - `datagen/provider_manifest.clj`：共享 datagen provider 顺序与逻辑 provider 集合；平台 datagen setup 只适配具体 Factory/API。
    - `block/logic/*`、`entity/logic/*`：BlockEntity / Mob 热路径 Java 接口与 bundle 类型
    - `block/logic_compile.clj`、`block/logic_pipeline.clj`、`entity/mob_logic_compile.clj`、`entity/mob_logic_pipeline.clj`：loader-neutral reify 编译与安装（Forge/Fabric 共用）
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

### Scripted 逻辑分发（强制，2026-06）

- BlockEntity tick / NBT / container / capability 与 Mob `aiStep` / hurt / death / loot **运行期**必须走 `IScriptedBlock` / `ScriptedEntityLogicRegistry` + Java 接口 bundle（`mc-1.20.1` 编译期 reify），**禁止**重新引入 `tile-logic-registry`、`container-registry`、`capability-registry`、`invoke-tick`、`RT.var` 查表或任何 `^:dynamic` 运行期 hook 注册表。
- 声明期元数据仍用 `mcmod.block.tile-dsl`、`mcmod.block.tile-kind`、`mcmod.entity.dsl`；`:container` 与 `:capability-keys` 写入 tile spec 后由 `logic-pipeline` 一次性编译安装。
- 平台层（Forge/Fabric）只负责 `compile-all-bundles` → `install-bundle-to-block!` / `install-mob-bundle!`，不得复制 bundle 编译逻辑。
- 设计文档：[SCRIPTED_LOGIC_DISPATCH.md](../04-systems/SCRIPTED_LOGIC_DISPATCH.md)。

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

### AOT 跨 namespace 引用规则（强制）

当 `clojurephant` 执行 AOT 编译时，会将多个源目录（如 `forge-1.20.1/src/main/clojure` 与 `mc-1.20.1/src/main/clojure`）的所有 namespace 合并为一个批次传给 `clojure.lang.Compile`。批次内的编译顺序由 clojurephant 决定，**不保证** namespace 按依赖拓扑排序。

**关键差异**：AOT 编译器对两种引用使用不同的解析机制：

| 引用方式 | 解析路径 | 能找 `.clj` 源文件？ |
|---------|---------|-------------------|
| `:require`（ns 表单） | `require` → `RT.load()` | ✅ 是（从 classpath 源目录查找） |
| 内联全限定调用 `some.ns/fn` | `Class.forName()` | ❌ 否（只查找 `.class` 文件） |

**后果**：若 namespace A 使用内联全限定调用引用 namespace B（如 `cn.li.mc1201.gui.cgui.draw-ops-host/draw-ops-host!`），而 B 在编译批次中排在 A 之后，A 编译时 B 的 `.class` 尚未生成，则抛出 `ClassNotFoundException`。

**强制规则**：

- **所有被引用的 namespace 必须显式 `:require`**，即使仅通过全限定名间接调用也须如此。
- 禁止依赖编译批次顺序使内联引用"恰好"编译通过——不同环境/版本下顺序可能变化。
- 反例（禁止）：
  ```clojure
  ;; init.clj — 未 require draw-ops-host 直接内联调用
  (ns cn.li.forge1201.client.init
    (:require ...))  ;; 缺少 [cn.li.mc1201.gui.cgui.draw-ops-host :as draw-ops-host]
  
  (defn register-bridge [...]
    {:draw-ops-host! (fn [parent ops-fn]
                       (cn.li.mc1201.gui.cgui.draw-ops-host/draw-ops-host! parent ops-fn))})
  ```
- 正例（必须）：
  ```clojure
  (ns cn.li.forge1201.client.init
    (:require ...
              [cn.li.mc1201.gui.cgui.draw-ops-host :as draw-ops-host]  ;; 显式 require
              ...))
  
  (defn register-bridge [...]
    {:draw-ops-host! (fn [parent ops-fn]
                       (draw-ops-host/draw-ops-host! parent ops-fn))})  ;; 使用别名
  ```

**排查方法**：遇到 `Syntax error (ClassNotFoundException) compiling at (…:N:N). some.namespace.name` 时，检查报错位置是否有未 `:require` 的内联全限定引用。

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

### AOT 编译安全准则 — `into` 与 Transducer（强制）

**核心问题**：`(into {} (map (fn ...)) coll)` 的 **3 参数** transducer 形式在 Minecraft 全量 AOT 编译时会触发 Clojure 编译器激进的内联宏展开与融合优化（Inlining & Macro Expansion），导致匿名函数内引用的外部符号在编译期无法解析，产生 `Unable to resolve symbol` 的运行时崩溃。

**安全/不安全判定**：

| 形式 | 安全性 | 说明 |
|------|--------|------|
| `(into {} (map (fn ...) coll))` | ✅ 安全 | 2 参数 `into`，不触发 transducer 编译期优化 |
| `(into {} (map (fn ...)) coll)` | ❌ 危险 | 3 参数 `into`，AOT 必爆 |
| `(reduce-kv (fn [m k v] ...) {} coll)` | ✅ 安全 | 无 transducer，无中间 vector 分配 |

**两条黄金铁律**：

1. **Map → Map 字典转换（方块、物品、NBT、实体数据）**：**100% 强制使用 `reduce-kv`**，绝对禁止 `into`。
   - 理由：AOT 免疫 + 零 GC 压力（避免 `[k v]` 中间 vector 分配）。
   ```clojure
   ;; ❌ 禁止（3 参数 into + map）
   (into {} (map (fn [[k v]] [(name k) v])) some-map)
   
   ;; ✅ 正确（reduce-kv）
   (reduce-kv (fn [m k v] (assoc m (name k) v)) {} some-map)
   ```

2. **List/Seq → 集合转换**：可安全使用 **2 参数** `(into #{...} (filter ... coll))`。
   - 理由：不涉及 Transducer 宏展开，编译器只生成普通字节码。
   ```clojure
   ;; ✅ 安全（2 参数 into）
   (into #{} (map :id handlers))
   ;; ✅ 安全
   (into {} (filter some-pred entries))
   ```

3. **若必须使用 3 参数 transducer 形式（如 `comp` 管道）**：匿名函数内只能包含本地变量。引用外部命名空间函数必须用 `#'`（Var 引用）。
   ```clojure
   ;; ✅ 安全（Var 引用隔开外部符号）
   (into {} (map (fn [spec] (#'tile-kind/merge-tile-kind-defaults spec))) coll)
   ```

**最佳实践**：优先 `reduce-kv`（处理 map 数据），其次 2 参数 `into`（处理 seq 数据），禁止 3 参数 `into` + 匿名函数。

### AOT 八大铁律（完整版）

在 Minecraft 全量 AOT 编译 + 游戏主线程卡顿极度敏感的双重约束下，以下八条铁律与 `into` Transducer 规则同源——均因 Clojure 编译器在 AOT 阶段的激进内联/宏展开优化而引发运行时崩溃或性能雪崩。

---

#### 铁律一：禁止关键字作为隐式函数（强制）

**根源**：`(map :key coll)` 中关键字 `:key` 作为 `IFn` 调用时，Clojure 1.10+ 编译器在 AOT 阶段会尝试生成特化常数类以优化查找性能。在 Minecraft 多 ClassLoader 环境下，该常数类可能在运行时无法被正确加载，导致 `Unable to resolve symbol`（与 3 参数 `into` 同根因）。

| 禁止模式 | 安全替代 |
|----------|---------|
| `(map :key coll)` | `(map #(get % :key) coll)` |
| `(mapv :key coll)` | `(mapv #(get % :key) coll)` |
| `(filter :key coll)` | `(filter #(get % :key) coll)` |
| `(group-by :key coll)` | `(group-by #(get % :key) coll)` |
| `(sort-by :key coll)` | `(sort-by #(get % :key) coll)` |
| `(map (juxt :k1 :k2) coll)` | `(map #(vector (get % :k1) (get % :k2)) coll)` |
| `(map (comp f :key) coll)` | `(map #(f (get % :key)) coll)` |
| `(some :key coll)` | `(some #(get % :key) coll)` |
| `(keep :key coll)` | `(keep #(get % :key) coll)` |

**心法**：老老实实用 `get` 函数。`get` 是行为绝对确定的标准底层函数，100% 免疫 AOT 宏污染。

---

#### 铁律二：delay 必须在主线程预热（强制）

**根源**：`delay` 底层用 Java `synchronized` 实现互斥锁。Minecraft 1.20+ 的世界生成、区块异步加载、Netty 网络线程是并行的。若 Schema 很大且恰好在异步线程和主线程同时首次 `deref`，主线程被锁死 → 服务器零帧 → Watchdog 崩溃。

**安全实践**：
```clojure
;; ① 定义时用 delay 包裹
(def my-validator (delay (m/validator my-schema)))

;; ② Mod 主入口生命周期（FMLCommonSetupEvent / onInitialize）中强制预热
@my-validator  ;; 在主线程完成编译，消灭运行期锁竞争
```

**判定**：所有 `delay` 包装的 validator 必须在平台初始化阶段（`init-platform!` 或等效入口）完成 `deref` 预热。

---

#### 铁律三：:gen-class 必须带 :load-ns false（强制）

**根源**：不带 `:load-ns false` 时，Clojure AOT 会在生成的 `.class` 静态初始化块中植入 `clojure.main/load` 调用。在 Forge ModClassLoader 加载该类时，Clojure 运行时环境可能尚未初始化完毕，直接抛 `ClassNotFoundException: clojure.main`。

**安全模式**：
```clojure
(ns cn.li.mcmod.block.MyTileEntity
  (:gen-class
   :extends net.minecraft.world.level.block.entity.BlockEntity
   :load-ns false))  ;; ← 极其关键
```

> 当前项目未使用 `:gen-class`，此铁律作为未来参考。

---

#### 铁律四：公开 API 禁止返回惰性序列（强制）

**根源**：`map`/`filter`/`for` 默认返回 `LazySeq`，求值时机推迟到"被消费时"。若惰性序列挂在全局或跨帧边界，未求值的延迟计算会在某一帧突然集中爆发 → Tick Timeout → 服务器踢出所有玩家。

**安全实践**：
```clojure
;; ❌ 危险：返回惰性序列
(defn get-positions [schema]
  (for [x (range (:w schema)) y (range (:h schema))]
    [x y]))

;; ✅ 安全：用 vec 在当前帧结清所有计算
(defn get-positions [schema]
  (vec (for [x (range (:w schema)) y (range (:h schema))]
         [x y])))
```

**判定**：
- 公共 API 函数 -> **必须**用 `vec`/`mapv`/`filterv`/`doall` 收拢
- Tick/事件回调内部 -> **必须**强制求值，禁止返回 lazy seq 到全局
- 内部辅助函数、纯计算管道 -> 惰性序列可接受（调用方会 force）

---

#### 铁律五：defprotocol / extend-type / extend-protocol / extend 彻底禁用（强制）

**根源**：Minecraft 全量 AOT + 静态混淆环境下，`defprotocol` 生成静态接口 `.class` 文件，混淆器（SpecialSource/ProGuard）无法正确重映射其方法签名中的原版类参数，导致运行时 `AbstractMethodError` 或 `NoSuchMethodError`。`extend-type` 将未混淆类名硬编码进 Clojure 协议注册图，混淆后 `ClassNotFoundException` 闪退。底层全局同步锁在高并发下引发 TPS 雪崩。

**平替方案**：
- **平台 SPI**：Framework `[:platform :key]` 下的数据驱动函数 Map（VTable）
- **MC 类行为扩展**：普通 `defn`（带类型提示，仅 mc-1.20.1 层）+ Framework 函数 Map

**判定**：七大禁区之一。全项目已消除 58/60 defprotocol（剩余 2 个在注释中）。

---

#### 铁律六：binding 不得跨越异步边界（强制）

**根源**：`binding` 底层依赖 `ThreadLocal`。Minecraft 的 `ForkJoinPool` / `WorkStealingPool` 会在工作线程上执行异步任务，此时 `binding` 绑定的动态变量会丢失。

**安全实践**：
```clojure
;; ❌ 危险：binding 作用域内触发了异步任务
(binding [*current-tile-context* ctx]
  ;; 若 execute-logic 内部触发了 Forge 事件或 CompletableFuture，
  ;; 异步工作线程上的 *current-tile-context* 将直接变成 nil
  (execute-logic))

;; ✅ 安全：显式传参，拒绝隐式绑定
(execute-logic ctx)
```

**判定**：当前项目所有 `binding` 调用在同步执行路径内（`(binding [...] (f))` 同步返回），无跨线程异步闭包传播。新增代码必须遵守此边界。

---

#### 铁律七：reify / proxy 分水岭（强制）

**根源**：全量 AOT 下 `reify`/`proxy` 生成匿名类，当实现 **Minecraft 混淆接口**（`net.minecraft.*`、`net.minecraftforge.*` 等）时，编译器将开发期（Mojmap）类名与方法签名**固化**进 `.class` 字节码。编译阶段往往仍能通过；混淆/remap 后运行时类名已变 → `NoClassDefFoundError` / `AbstractMethodError`。`proxy` 同理。

**`definterface` 与项目自有接口**：`definterface` + `deftype`/`reify` 实现 **项目自有**接口（`cn.li.*`、`api` 模块 Java 接口）时，AOT 固化的符号名稳定、与 MC 混淆链无关，**安全**。示例：`ac/.../wireless_matrix/capability.clj` 的 `IMatrixJavaProxy` + `MatrixJavaProxy`（`deftype` 实现项目 `definterface`，非 MC 接口）。

**分水岭判定**：

| 接口来源 | reify | proxy | deftype / definterface | 平替 |
|---------|-------|-------|------------------------|------|
| `net.minecraft.*` / `net.minecraftforge.*` / Fabric API | 🔴 禁区 | 🔴 禁区 | 🔴 禁止对 MC 接口 | Java 骨架类（`mc-1.20.1/shim/`） |
| 项目自有（`cn.li.*`、`api`） | 🟢 安全 | 🟢 安全 | 🟢 `definterface` + `deftype` 安全 | — |
| `java.lang.*` / `java.util.function.*`（JDK 核心） | 🟢 安全 | 🟢 安全 | 🟢 安全 | 可选：`FnConsumer`/`FnSupplier`/`FnPredicate` |

**JDK 安全原理**：Mojang 混淆器绝对不碰 JDK 核心类（`Runnable`、`Consumer`、`Supplier` 等），方法签名（`run()`、`accept()`、`get()`）永久固化。且启动期 one-shot 执行无高并发风险。

**平替方案（MC 接口 → 三层 IoC 架构）**：
1. **ac 层**：纯逻辑函数 → Framework `[:registry :tiles block-id]`
2. **mc-1.20.1 层**：Java 骨架（`UniversalEnergyStorage`、`UniversalItemHandler`）+ 桥接代码注入
3. **Forge/Fabric**：使用 Java 骨架实例注册 Capability

Java 骨架类：`mc-1.20.1/src/main/java/cn/li/mc1201/shim/`
- `FnConsumer`/`FnSupplier`/`FnPredicate` — JDK 函数式接口适配器（可选）
- `DynamicSlot` — 通用 Slot 子类
- `UniversalEnergyStorage` — IEnergyStorage 通用骨架
- `UniversalItemHandler` — IItemHandler 通用骨架

**判定**：MC 接口 → 必须 Java 骨架（勿以「能编过」为准，看运行时 remap 后是否 AbstractMethodError）。项目自有接口 → `definterface` + `deftype`/`reify` 安全。JDK 接口 → `reify` 安全可用。

---

#### 铁律八：非数学常数禁止 ^:const（强制）

**根源**：`^:const` 使 Clojure 编译器在 AOT 时将值内联硬编码到所有调用方的 `.class` 字节码中。若后续修改该值但未全量重编译，旧值残留 → 数据不一致，排查极度困难。

**安全实践**：
```clojure
;; ❌ 危险：能量转换率可能通过配置调整，但被硬编码到字节码
(def ^:const default-fe-to-content-rate 4.0)

;; ✅ 安全：保持为普通 Var，可通过动态寻址或配置覆盖
(def default-fe-to-content-rate 4.0)

;; ✅ 例外：真正常数可以保留 ^:const
(def ^:const int-min -2147483648)   ;; Int32 最小值
(def ^:const PI 3.141592653589793)  ;; 数学常数
```

**判定**：仅以下情形可保留 `^:const`：
1. 数学常数（PI、epsilon）
2. 整型范围限值（Int32 min/max）
3. 命名空间限定的哨兵值（`::missing-op`）
4. 编译期确定的模组元数据（MOD-ID）

---

#### 铁律九：禁止顶层 memoize 缓存 Malli Validator（强制）

**根源**：`clojure.core/memoize` 在顶层被调用时产生两个叠加风险：

1. **AOT 编译期泄露**：`memoize` 在顶层被执行时，立即创建一个持有私有 `HashMap` 的匿名闭包对象。AOT 编译器将该闭包类硬编码进 `.class` 字节码，导致闭包内部捕获的 `schema/validator` 引用在 Minecraft 多 ClassLoader 环境下无法对齐——与 3 参数 `into` 同根因的符号丢失。

2. **运行期内存泄露（OOM）**：`memoize` 内部是一个**无界、无淘汰、强引用**的全局 Map。即使仅有少数动态生成的 Schema，该 Map 永不释放条目，随着服务器运行时间增长 → `OutOfMemoryError`。

**禁止模式**：
```clojure
;; ❌ 高危：顶层 memoize
(def ^:private validator-for (memoize schema/validator))
;; 或
(let [validator-for (memoize schema/validator)]
  (defn valid-foo? [x] (schema/valid? (validator-for foo-schema) x)))
```

**同样禁止：用 `delay` 包装 validator 编译**：
```clojure
;; ❌ 禁止：delay 是宏，AOT 展开为匿名函数类 → Malli eval 上下文符号污染
(def ^:private foo-validator (delay (schema/validator foo-schema)))
;; 运行时触发：Can't take value of a macro: #'clojure.core/delay
```
`delay` 宏在 AOT 下展开为 `(new clojure.lang.Delay (fn [] ...))`，内部匿名函数类会被 `malli.core` 的 `eval` 上下文捕获，导致 `delay` 宏的 Var 被错误解析为运行时的值引用。参见铁律一（宏在 AOT 下不可作为值传递）的同源机制。

**安全替代方案**（按 Schema 数量决定）：

**方案 A — Schema 数量固定、编译期已知（本项目采用）**：`schema/lazy-validator`（纯函数式惰性缓存，零宏依赖）。
```clojure
;; in schema/core.clj:
(defn lazy-validator
  "Returns a 0-arg fn that compiles `schema` on first call and caches the result.
  Uses `atom` for AOT-safe lazy caching — never the `delay` macro."
  [schema]
  (let [cache (atom nil)]
    (fn []
      (if-let [c @cache]
        c
        (let [v (validator schema)]
          (reset! cache v)
          v)))))

;; usage site:
(def ^:private foo-validator (schema/lazy-validator foo-schema))
(defn- valid-foo? [x] (schema/valid? (foo-validator) x))
```
优点：零宏依赖、AOT 免疫、无缓存膨胀风险。与 `delay` 的区别：`lazy-validator` 是普通函数，返回的 thunk 也是普通 `fn`——AOT 生成的匿名函数类不携带宏符号，Malli eval 上下文安全。本项目全部 30 个 validator 均采用此模式。

**方案 B — Schema 在运行时动态生成（如基于玩家输入/NBT 标签动态构造）**：使用 Guava Cache（Minecraft 原生自带）。
```clojure
(:import [com.google.common.cache CacheBuilder CacheLoader])

(def ^:private validator-cache
  (delay  ;; Guava Cache 自身初始化用 delay 保护是安全的——它不进入 Malli eval 上下文
    (-> (CacheBuilder/newBuilder)
        (.maximumSize 500)  ;; LRU 淘汰，免疫 OOM
        (.build (proxy [CacheLoader] []
                  (load [schema] (schema/validator schema)))))))

(defn validator-for [schema]
  (.get @validator-cache schema))
```
判定：仅当 Schema 确实在运行时动态生成时才引入 Guava Cache。本项目 Schema 全为静态，采用方案 A。

**判定**：
- Schema 静态已知 → `schema/lazy-validator`（纯函数，零宏，AOT 安全）
- Schema 运行时动态生成 → Guava Cache with `maximumSize`
- 禁止 → 顶层 `memoize`（双重重灾区：AOT + OOM）
- 禁止 → `delay` 直接包裹 `schema/validator`（宏泄漏至 Malli eval 上下文）

---

#### 铁律十：禁止 `{:pre}` / `{:post}` 断言宏（强制）

**根源**：Clojure 的 `{:pre [...]}` 和 `{:post [...]}` 是**宏**。AOT 编译器编译包含断言的函数时，会将断言表达式提取为独立的匿名函数类进行内联优化。在命名空间半编译状态下，该匿名函数类引用的符号可能尚未在 Var 映射表中完成注册，导致 `Unable to resolve symbol` 运行时闪退（与 3 参数 `into` 同根因的匿名函数类 AOT 符号逃逸）。

此外，Minecraft 主线程高频 Tick 路径中，`:pre`/`:post` 会在**每一次调用**时强制执行类型检查，带来额外性能损耗。

**禁止模式**：
```clojure
;; ❌ 高危：:pre 断言在 AOT 下半编译状态触发符号逃逸
(defn merge-with-kind [kind-cfg normalized]
  {:pre [(map? kind-cfg) (or (map? normalized) (nil? normalized))]}
  ...)

;; ❌ 同样危险：:post 断言
(defn create-energy [max-capacity]
  {:post [(pos? %)]}
  ...)
```

**安全替代**：显式 `when-not` + `throw`。
```clojure
;; ✅ AOT 安全：标准运行时流程控制，零宏依赖
(defn merge-with-kind [kind-cfg normalized]
  (when-not (and (map? kind-cfg) (or (map? normalized) (nil? normalized)))
    (throw (IllegalArgumentException. "merge-with-kind: invalid arguments")))
  ...)
```

**判定**：
- 生产代码 → 一律使用显式 `when-not` + `throw`
- 开发调试期 → 可通过全局 `*assert*` 动态变量控制，但打包时必须设为 `false`
- 测试代码 → 可保留，测试不在 AOT 路径中

---

#### 铁律十一：所有系统级状态通过 Framework 访问（强制）

**根源**：项目中的内容注册表（block/item/particle 等 DSL）和运行时服务（lifecycle、ability-runtime 等）已从散落的 `defonce`/`delay`/`^:dynamic` 单例迁移到统一的 Framework atom（`cn.li.mcmod.framework/*framework*`）。新代码不得创建顶层可变状态。

**禁止模式**：
```clojure
;; ❌ 禁止：创建新的顶层 delay / atom / defonce
(def ^:private _my-registry (delay (create-my-registry-runtime)))
(defonce ^:private my-state* (atom {}))
(def ^:dynamic *my-runtime* nil)
```

**正确模式**：从 Framework 读写。
```clojure
;; ✅ 读取：Framework [:registry :domain]
(get-in @fw/*framework* [:registry :blocks id])

;; ✅ 写入（仅限 init 阶段，通过保安函数）：
(fw.registry/register! fw/*framework* :blocks id spec)
```

**唯一例外**：
- `*framework*`（`cn.li.mcmod.framework/*framework*`）— 框架唯一的全局根变量；**非 `^:dynamic`**，靠 `alter-var-root` 设根绑定（对所有线程可见，零 ThreadLocal 开销）
- 平台 SPI 适配器 `^:dynamic *runtime*` — 仅在 `mcmod/platform/*.clj` 中，且正逐步迁移至 Framework `[:platform :adapter-key]`
- 渲染资源（texture registry、shader）— OpenGL 绑定，不入 Framework
- 客户端 session 状态 — 闭包工厂管理，不入 Framework（详见下方 ThreadLocal 模式）

**exactly-once 守卫只走两个原语（`cn.li.mcmod.runtime.install`，强制）**：新代码需要"只执行一次"的初始化守卫（原 `defonce-guard` / `^:dynamic` 布尔 + `Object` 锁 / 顶层 `let` 闭包 atom 三种历史形态均禁止新增）时，必须用：
- `framework-once!`——守卫标志存于 Framework `[:service :install :flags]`；`with-fresh-framework` 注入新 atom 时自动复位，天然解决单测重复注册被进程级 guard 挡住的问题。适用于任何效果作用域限于 Framework 注册表/服务的初始化。
- `process-once!`——守卫标志存于 `runtime.install` 内唯一保留的进程级 atom（全仓白名单里的唯一新豁免）。仅用于真正的 JVM 级一次性副作用（事件总线监听器注册、native/GLFW 挂接、`defmethod` 派发表加载），Framework 重建不应重做。
- `install-root!`——替代『`^:dynamic` + `Object` 锁 + `alter-var-root`』SPI holder 三件套；单一写者在 init/install 期调用，读者直读普通 var（无 thread-binding frame 查找）。

`docs/dev/TOP_LEVEL_STATE_GOVERNANCE.md` 与 `docs/dev/dynamic-var-binding-audit.md` 记录当前迁移进度与 `^:dynamic` keep/kill 分类依据。

#### ThreadLocal + 高阶函数：客户端/服务端 session 上下文（强制）

**背景**：`*client-session-id*` 与 `*player-state-owner*` 原为 `^:dynamic` Var，通过 `binding` 提供每线程隔离。铁律十一禁止新增 `^:dynamic` 后，改为基于 `java.lang.ThreadLocal` 的高阶函数模式。

**核心实现**（`cn.li.mcmod.hooks.core`）：

```clojure
(def ^:private client-ctx-thread-local (java.lang.ThreadLocal.))

(defn with-client-ctx-fn
  "ThreadLocal-based client context HOF。在当前线程的 ThreadLocal 中设置 ctx-map，
   执行 thunk，finally 恢复。"
  [ctx-map thunk]
  (let [old (.get client-ctx-thread-local)]
    (.set client-ctx-thread-local (merge (or old {}) ctx-map))
    (try
      (let [result (thunk)]
        (if (instance? clojure.lang.LazySeq result)
          (doall result)  ;; 铁律四：返回值不能是 LazySeq
          result))
      (finally
        (if (nil? old)
          (.remove client-ctx-thread-local)
          (.set client-ctx-thread-local old))))))
```

**调用约定**：

```clojure
;; 设置 session-id：
(with-client-ctx-fn {:session-id sid} #(do-work))

;; 设置 player-owner：
(with-client-ctx-fn {:player-owner owner} #(do-work))

;; 同时设置两者：
(with-client-ctx-fn {:session-id sid :player-owner owner} #(do-work))
```

**读取约定**（仅用于 `cn.li.mcmod.hooks.core` 内部或经过其 reader 函数）：

```clojure
(defn *client-session-id* [] (:session-id (.get client-ctx-thread-local)))
(defn *player-state-owner* [] (:player-owner (.get client-ctx-thread-local)))
```

**设计决策**：

| 决策 | 理由 |
|------|------|
| ThreadLocal 而非 Framework atom | 每线程隔离。Server 线程和 Render 线程共享一个 Framework atom 会产生读写竞态，丢失上下文 |
| 高阶函数（HOF）而非宏 | 宏展开在 AOT 下不增加安全性；HOF 可以在 finally 中 `doall` 截断 LazySeq，防止上下文逃逸 |
| `^:dynamic` 不适用 | 铁律十一禁止新增 `^:dynamic`；且 Minecraft `enqueueWork` 模式下 ThreadLocal 的"跨线程丢失"恰好满足铁律六（不跨越异步边界） |
| 顶层 LazySeq 检查而非 `postwalk` | `postwalk` 会误入 Minecraft 原生对象（Level、BlockEntity），仅检查顶层 `instance? LazySeq` 足够——内部嵌套的懒序列在后续访问时仍在当前线程求值 |

**clj-kondo 适配**：`*client-session-id*` 和 `*player-state-owner*` 使用 earmuffs 命名但是 `defn` 函数（非 `^:dynamic`），clj-kondo 会报警告。需在 `.clj-kondo/config.edn` 中配置 `:linters {:earmuffed-var-not-dynamic {:exclude [cn.li.mcmod.hooks.core/*client-session-id* cn.li.mcmod.hooks.core/*player-state-owner*]}}`。同时配置 `:discouraged-var` 禁止在 `hooks.core` 外部直接构造 `ThreadLocal`。

**ThreadLocal 上下文调用规范（强制）**：

1. **唯一入口**：设置/恢复上下文必须通过 `with-client-ctx-fn` 或 `with-player-state-owner-fn`，禁止直接调用 `.get`/`.set`/`.remove`
2. **网络重建**：所有 Packet Handler 必须在分派前用 `with-client-ctx-fn` 重建上下文。Forge/Fabric 各端 4 个 handler 已合规，新增 handler 必须遵守
3. **异步边界**：传递给 `enqueueWork` / `future` / `CompletableFuture` 的闭包必须在内部重新建立上下文（铁律六要求，ThreadLocal 天然阻断跨线程泄漏）
4. **读取规范**：`*client-session-id*` 和 `*player-state-owner*` 是函数，必须加括号调用：`(hooks/*player-state-owner*)` 而非 `hooks/*player-state-owner*`
5. **新增 hook handler**：新增 `:on-player-tick!` 等生命周期 handler 如需要读上下文，必须在调用链入口确保已设置

---

#### 铁律十二：Per-player 可变状态不入 Framework（强制）

**根源**：玩家实时数据（技能冷却 CD、蓝量 CP、充能层数 charge-ticks、连击 Buffer）必须在物理上隔离存储。将 per-player 可变状态存入 Framework 的单一 atom 会导致多人联机时 CAS 自旋风暴——50 人同时释放技能 = 50 条线程并发 `swap!` 竞争同一 atom → TPS 雪崩。

**正确归属**：
| 数据类别 | 存储位置 |
|---------|---------|
| 玩家 CD/CP/充能/context-registry | Player NBT / 私有 atom（`AtomAbilityStore` per-player） |
| 无线网络拓扑 | Minecraft World SavedData（per-world） |
| 方块实体实例数据（电量/物品槽） | Minecraft BlockEntity NBT |
| 技能配置/VTable/粒子类型 | Framework `:registry/*`（只读，frozen） |
| 平台适配器函数 | Framework `:platform/*`（只读） |

**实现**：`ac/ability/service/runtime_store.clj` 的 `AtomAbilityStore` 使用 `ConcurrentHashMap<String, Atom>` —— 每个 player 独立 atom，`swap!` 只影响该 player，零跨玩家竞争。

---

#### 铁律十三：禁止循环/高频路径内的闭包捕获外层变量（强制）

**根源**：Minecraft 1.20.1 全量 AOT 编译 + Forge ModLauncher 环境下，若匿名函数 `(fn [...] ...)` 嵌套在 `reduce`/`map`/`doseq`/`filter` 等循环构造内，且该匿名函数**捕获了外层函数参数或 let 绑定中的变量**（非自身参数），Clojure AOT 编译器无法将此匿名函数固化为单一静态类。运行时每次调用外层函数时，JVM 都会在 Metaspace 中**动态生成全新的闭包类**。

后果：
- **Metaspace 内存泄漏**：每个新类约占用 1-2KB Metaspace，高频 Tick 路径（每秒 20 tick × N 个方块）在数分钟内产生数万个临时类，最终 `OutOfMemoryError: Metaspace`。
- **Full GC 卡顿**：JVM 回收这些类需要 Full GC（Stop-The-World），导致游戏间歇性冻结。
- **`re:classloading` 堆栈污染**：Forge ModLauncher 对每个动态生成类标记 `re:classloading`，异常栈中出现数百次重复标记。

**危险模式 vs 安全模式**：

```clojure
;; ❌ 危险：reduce 内的 fn 捕获了外层 state、state-def、pos
(defn build-updater [fields]
  (let [specs (precompile fields)]
    (fn [state level pos]
      (let [bs (get-block-state level pos)
            sd (get-state-def bs)]
        (reduce (fn [acc spec]              ;; ← 此 fn 捕获 state, sd, pos, bs
                  (let [prop (get-property sd (:prop spec))]
                    (set-property acc prop (get state (:key spec)))))
                bs
                specs)))))

;; ✅ 安全：提取为顶层 defn-，通过 partial 显式传参
(defn- step-property [state bs-state state-def pos acc spec]
  (let [prop (get-property state-def (:prop spec))]
    (set-property acc prop (get state (:key spec)))))

(defn build-updater [fields]
  (let [specs (precompile fields)]
    (fn [state level pos]
      (let [bs (get-block-state level pos)
            sd (get-state-def bs)]
        (reduce (partial step-property state bs sd pos)  ;; partial 零运行时开销
                bs
                specs)))))
```

**判定标准**：

| 场景 | 安全性 | 说明 |
|------|--------|------|
| `(map (fn [x] ...) coll)` — fn 只使用自身参数 `x` | ✅ 安全 | AOT 固化为静态类 |
| `(reduce (fn [acc x] ...) init coll)` — fn 只使用自身参数 | ✅ 安全 | AOT 固化为静态类 |
| 上述任一模式 + fn 引用外层 let/param 变量 | ❌ 危险 | 运行时每次调用生成新类 |
| 顶层 `def` 中的 `(into {} (map (fn ...) coll))` | ✅ 安全 | 只执行一次，不反复生成 |
| 仅为启动/数据生成路径（非 Tick/帧循环） | 🟡 低风险 | 生成次数有限，但建议修复 |

**修复口诀**：
1. 将闭包提取为顶层 `defn-`（命名函数）。
2. 原外层变量通过 `partial` 或显式参数传入。
3. **结构静态化，数据动态化** — 函数逻辑编译期固定，只有数据在运行期变化。

**已知高风险文件**（待修复）：
- `ac/ability/effects/beam.clj:28-38` — 4 个 fn 捕获外层变量（beam-candidates 热路径）
- `mc-1.20.1/runtime/entity_damage_core.clj:43` — reduce 捕获 5 个外层变量（AOE damage tick）
- `ac/ability/server/damage/entity.clj:25-29` — 3 个 fn 捕获外层变量（reflection chain）
- `ac/content/ability/meltdowner/electron_missile.clj:60-62` — filter + sort-by 捕获外层（missile targeting）
- `ac/content/ability/meltdowner/ray_barrage.clj:139-146` — remove + sort-by 捕获外层（scatter targeting）

**已修复（2026-07）**：
- `ac/block/ability_interferer/logic.clj` — `player-in-aabb?` + `partial`
- `mcmod/block/state_schema.clj` — `schema->field-index` + `ConcurrentHashMap` 缓存
- `ac/block/role_impls.clj` — `assoc-energy` + `partial`
- `ac/content/ability/vecmanip/vec_deviation.clj` / `vec_reflection.clj` — uuid filter + `partial`
- `ac/content/ability/electromaster/mag_manip.clj` / `thunder_bolt.clj` — segment/exclusion filter + `partial`
- `ac/content/ability/teleporter/shift_teleport.clj:164-178` — 已确认仅用 acc/entity 参数，安全

**技能回调 positional 契约（禁止 evt Map）**：

所有 `:actions` 回调统一 arity：
`[ctx-id player-id skill-id exp cost-ok? hold-ticks cost-stage player-ref]`

- 实现：`ac/ability/service/skill_callback.clj` + `context_state.clj` dispatch
- `:cost` / `:cooldown-ticks` 动态 fn 签名为 `(fn [player-id skill-id exp] ...)`
- **禁止关闭 AOT**；修复闭包热点用 `defn-` + `partial`，不用运行时编译

---

### ac → mcmod → mc-1.20.1 三层 IoC 架构（强制）

ac（内容层）、mcmod（框架中转层）、mc-1.20.1（游戏适配层）形成严格的单向依赖链。**每层有且仅有一个职责**，禁止跨层感知。

```
ac ──→ mcmod ──→ mc-1.20.1 ──→ forge-1.20.1 / fabric-1.20.1
 │         │            │                │
 │ 纯逻辑   │ 纯中转     │ Java骨架+桥接   │ 平台注册
 │ 零MC感知 │ 零MC感知   │ MC感知         │ MC感知
```

#### 第一层：ac（内容层 / Addons）——"纯逻辑函数提供者"

**职责**：提供纯 Clojure 计算函数，存储于 Framework `[:registry :tiles block-id]` 的 VTable Map 中。**对 Minecraft 完全不可见，对 mc-1.20.1 完全不可见**。

```clojure
;; ✅ ac 层：纯逻辑，零 MC 依赖
(defn diamond-receive-energy [current max-capacity max-receive simulate?]
  (let [space (- max-capacity current)]
    (min space max-receive)))

;; ac 层唯一的出口：通过纯 Clojure Map（VTable）宣告能力
(defn get-addon-manifest []
  {:addon-id "diamond_furnace"
   :tiles {"furnace" {:energy-logic {:receive-fn diamond-receive-energy
                                      :extract-fn (fn [& _] 0)
                                      :get-max-fn (fn [] 100000)}}}})
```

**红线**：
- ❌ 禁止 import `net.minecraft.*`
- ❌ 禁止 import `cn.li.mc1201.*`（连 mc-1.20.1 都不可见）
- ✅ 只能依赖 mcmod 的纯中转 API（`platform/be`、`platform/nbt` 等）
- ✅ 升级 MC 版本时，ac 层代码**零改动**即可复用

#### 第二层：mcmod（框架中转层）——"纯数据契约定义者"

**职责**：定义 Framework 路径、install 函数、wrapper 函数。**零 MC 类导入，零类型提示，零直接 .method 调用**。

```clojure
;; ✅ mcmod 层：纯中转，从 Framework 查找函数并调用
(defn nbt-set-int! [compound key value]
  (when-let [f (get-in @(fw/fw-atom) [:platform :nbt-ops :nbt-set-int!])]
    (f compound key value)))
```

**红线**：
- ❌ 禁止 `(:import net.minecraft.*)`
- ❌ 禁止类型提示 `^BlockPos`、`^CompoundTag` 等
- ❌ 禁止直接 `.putInt`、`.getX`、`.isEmpty` 等 MC 互操作
- ✅ 所有 MC 互操作通过 `installer_core.clj`（在 mc-1.20.1 层）安装进 Framework

#### 第三层：mc-1.20.1（游戏适配层）——"海关 / 织入官"

**职责**：唯一可与 Minecraft 类交互的共享层。
1. **实现 MC 互操作**：`installer_core.clj` 将所有 `.putInt`、`.getX` 等 MC 直接调用封装为函数 Map，安装进 Framework
2. **Java 骨架实例化**：从 Framework 取出 ac 的纯函数，注入到 Java 骨架类，交给 Forge/Fabric Capability 系统

```clojure
;; ✅ mc-1.20.1 层：桥接代码，唯一的"织入官"
(defn create-energy-storage [block-id]
  (when-let [energy-logic (get-in @(fw/fw-atom) [:registry :tiles block-id :energy-logic])]
    (UniversalEnergyStorage.
      (:receive-fn energy-logic)
      (:extract-fn energy-logic)
      (:get-stored-fn energy-logic)
      (:get-max-fn energy-logic)
      (:can-extract-fn energy-logic)
      (:can-receive-fn energy-logic))))
```

**Java 骨架类位置**：`mc-1.20.1/src/main/java/cn/li/mc1201/shim/`（Forge/Fabric 共用）
- `FnConsumer.java` / `FnSupplier.java` / `FnPredicate.java` — Java 函数式接口适配器
- `DynamicSlot.java` — 通用 Slot 子类（替代 9 个 proxy 模式）
- `UniversalEnergyStorage.java` — IEnergyStorage 通用骨架
- `UniversalItemHandler.java` — IItemHandler 通用骨架

**红线**：
- ✅ 可 import `net.minecraft.*`
- ✅ 可 import `cn.li.mc1201.shim.*`（本层的 Java 骨架）
- ❌ 禁止在业务代码中使用 `reify` / `proxy` / `deftype`（用 Java 骨架替代）
- ❌ 禁止跨层感知（不引入 ac 业务命名空间）

#### ac 层调用 Java 骨架的标准姿势（IoC 反转）

ac 不调用 Java 骨架——Java 骨架被"注入"ac 的函数：

```
ac 函数 → Framework [:registry :tiles block-id] → mc-1.20.1 桥接读取 → new Java骨架(ac函数)
```

ac 升级 MC 版本时：ac 代码零改动，只需在 mc-1.20.1 更新骨架类适配新版本 API。

---

### Schema 验证层：纯 Clojure Spec v1（已移除 Malli）

Malli 已被完全移除，替换为 `cn.li.mcmod.schema.core` 中的纯 Clojure 实现（Spec v1）。动机：

1. **dynaload AOT 死结**：Malli 的传递依赖 `borkdude/dynaload`（通过 `fipp`）使用宏级别的动态类载入，在 Minecraft 全量 AOT + Jar 着色/瘦身构建环境中，`dynaload` 源文件未被正确包含进最终 Jar 包，触发 `No such var: dynaload/dynaload` 运行时崩溃。
2. **零依赖 + AOT 免疫**：纯 Clojure 实现不需任何第三方库，所有 schema 编译为普通函数调用，100% 兼容 Minecraft 全量 AOT。

**支持的全部 Schema 类型**：

| 类型 | 语法 | 说明 |
|------|------|------|
| 谓词函数 | `keyword?`, `string?`, `fn?`, `map?` ... | 直接用作 validator |
| `:or` | `[:or schema1 schema2 ...]` | 任一子 schema 通过即通过 |
| `:and` | `[:and schema1 schema2 ...]` | 全部子 schema 通过才通过 |
| `:enum` | `[:enum :a :b :c]` | 值必须在枚举集合中 |
| `:=` | `[:= :exact-value]` | 值必须精确匹配 |
| `:map` | `[:map [:k1 schema1] [:k2 {:optional true} schema2]]` | 验证 map 的每个键 |
| `:map-of` | `[:map-of key-schema val-schema]` | 验证 map 的每个键值对 |
| `:set` | `[:set item-schema]` | 验证 set 的每个元素 |
| `:vector` | `[:vector item-schema]` | 验证 vector 的每个元素 |
| `:sequential` | `[:sequential item-schema]` | 验证 sequential 的每个元素 |
| `:re` | `[:re #"regex"]` | 字符串正则匹配 |
| `:fn` | `[:fn named-fn]` | 具名函数谓词（Iron Rule 2 要求） |
| 符号引用 | `my-other-schema` | 解析为同命名空间的其他 schema |
| Var 引用 | `#'my-other-schema` | 跨命名空间 schema 引用 |

**API**（与 Malli 时期完全相同，业务代码无需改动）：

```clojure
(schema/validator my-schema)           ;; 编译 schema → 验证函数
(schema/valid? compiled-validator v)   ;; 返回 boolean
(schema/explain my-schema v)           ;; 返回失败说明
(schema/lazy-validator my-schema)      ;; 惰性编译（替代 delay）
(schema/require-valid schema compiled-v contract value)  ;; 失败时抛异常
```

### Schema 验证三问判定法则（强制）

面向未来所有新概念（如气压系统、药水效果、自定义维度），统一执行以下三问流程；**顺序不可交换**。底层使用纯 Clojure Spec v1（`cn.li.mcmod.schema.core`），不依赖任何第三方库。

1. **频率判定（第一优先级）**：该数据/约束是否处于每秒高频执行路径（Tick、移动、渲染、WorldGen、AI、内层数学、NBT 字段读写循环）？
  - 若是：**禁止 schema 验证**。做法：使用 Java interop + type hint + 已验证数据输入，确保热路径只做业务计算。
2. **边界判定**：该数据是否由玩家行为、系统事件或时间边界触发（发包、存盘、配置加载、资源重载、开界面、达成成就、命令、注册期）？
  - 若是：**使用 Spec v1**（`schema/lazy-validator` + compiled validator）；只在失败分支生成 explain。
  - 注：若某”边界”实际由 tick 高频触发（如每 tick 群发包），回退到第 1 条按高频禁用处理。
3. **前移判定**：该约束能否在打包 Jar、AOT、宏展开时定死？
  - 若能：必须前移到 macro/load-time/registration/datagen 阶段，在开发机与 CI 提前失败。
  - 目标：玩家运行时不承担 schema 成本，AOT 后热路径保持 0 额外开销。

#### 典型落位与禁用面

- **推荐（低频边界）**：配置/资源读取、datagen metadata、GUI message envelope、注册期 contract、成就触发数据、命令参数。
- **前移（编译/注册期）**：Block/Item/Fluid/Entity/Tile descriptor、recipe 常量、provider manifest、可静态确定的 schema。
- **绝对禁用（执行内层）**：render loop、worldgen 执行、tick 回调、AI/motion、NBT 读写内层循环。

#### PR 必填自证项

凡新增 schema 校验的 PR，必须说明：

1. 校验触发位置（macro/load-time/registration/runtime-boundary/test-only）。
2. 为什么不属于高频热路径。
3. 是否可进一步前移；若不能，给出原因。
4. 生产环境剩余开销预期（应为”低频可接受”或”0 热路径开销”）。

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
;; 历史示例（旧 panel.clj，已删除）— wireless 按钮包含 text_nodename 和 logo_node 子 widget，
;; 说明的是模式本身；当前反应式实现见 panel_reactive.clj。
(let [forward (fn [_] (on-wireless-click))]
  (events/on-left-click wbtn forward)
  (doseq [child (get-widgets wbtn)]
    (events/on-left-click child forward)))
```

**2. 键盘输入目标被子 widget 阻挡时**：父 widget 的 `:key` handler 需要在每个可能接收焦点的子 widget 上也挂载。

```clojure
;; 历史示例（旧 console.clj，已删除）— console panel 包含多个文字行子 widget，
;; 说明的是模式本身；当前反应式实现见 console_reactive.clj。
(doseq [{:keys [widget]} line-widgets]
  (events/on-left-click widget click-noop)   ;; 子 widget 可接收焦点
  (events/on-key-press widget key-handler))  ;; 键盘事件转给 console 逻辑
```

**3. 能力工厂函数签名**：`get-capability` 调用工厂时传 `(factory be side)`（2 参），工厂必须兼容：

```clojure
;; logic.clj — 接收 & _ 忽略可选的 side 参数
(defn create-dev-receiver-cap [be & _] ...)
```

旧 CGUI 栈（`mc-1.20.1/.../cgui/{input,traversal,renderer,runtime,assets}.clj`、`ac/.../developer/{gui,panel,console}.clj`）已删除；同一 deepest-only 语义现由反应式栈的 `mcmod/ui/layout.clj` `hit-test`（painter-order 逆序命中最深节点）承接，事件分发见 `mcmod/ui/events.clj`。

#### 涉及文件（反应式栈）

| 文件 | 相关改动 |
|------|---------|
| `mcmod/.../ui/layout.clj` | `hit-test` 在缓存 tape 上找最深命中节点（不冒泡）|
| `mcmod/.../ui/events.clj` | `mouse-click!`/`gain-focus!` 均基于 `hit-test` 结果分发 |
| `ac/.../developer/gui_reactive.clj` | overlay cover handler 无需坐标检测 |
| `ac/.../developer/panel_reactive.clj` | 按钮子 widget 转发；`refresh-linked-node-label!` 使用 owner |
| `ac/.../developer/console_reactive.clj` | console 行 widget 代理 key/click 事件 |
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
