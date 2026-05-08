# Agent 与工具链约定（正文）

本文档是仓库内 Agent/开发辅助工具规则正文。根目录 `CLAUDE.md` 仅保留指向本文件的指针。

## 构建与开发基线（Windows 使用 `.\gradlew.bat`）

- **工作目录**：始终在包含根 `settings.gradle` 的 `minecraftmod` 目录执行命令。
- **默认构建平台**：根工程默认包含 `api`、`mcmod`、`ac`、`forge-1.20.1`、`fabric-1.20.1`。
- **Fabric 维护级别**：`minimal maintenance`（至少保证 compile 与边界门禁，不承诺与 Forge 完全功能对齐）。
- **Java 版本**：Java 17。
- **LSP 刷新**：修改 Java 后执行 `.\gradlew.bat :<subproject>:classes`。

## 常用命令矩阵

- **本地运行**：`.\gradlew.bat :forge-1.20.1:runClient` / `:forge-1.20.1:runServer` / `:forge-1.20.1:runData`
- **快速编译**：`.\gradlew.bat :ac:compileClojure`、`.\gradlew.bat :mcmod:compileClojure`、`.\gradlew.bat :forge-1.20.1:compileClojure`
- **测试与验证入口**：
  - `.\gradlew.bat verifyArchitectureBoundaries`
  - `.\gradlew.bat unitTestCompile`
  - `.\gradlew.bat runAcUnitTests`（执行 `ac` 的 `clojure.test`，入口为 `:ac:runAcClojureTests` / `cn.li.ac.test-runner`）
  - `.\gradlew.bat runMcmodUnitTests`（执行 `mcmod` 的 `clojure.test`，入口为 `:mcmod:runMcmodClojureTests` / `cn.li.mcmod.test-runner`；可选 `-Dmcmod.test.only=cn.li.mcmod.foo-test,cn.li.mcmod.bar-test`）
  - `.\gradlew.bat verifyForgeBaseline`
  - `.\gradlew.bat verifyFabricBaseline`
  - `.\gradlew.bat verifyFabricSmoke`
  - `.\gradlew.bat verifyForgeHookCoverage`
  - `.\gradlew.bat verifyFabricHookManifest`
  - `.\gradlew.bat verifyPlatformHookCoverage`
  - `.\gradlew.bat verifyPlatformNoBusinessHookIds`
  - `.\gradlew.bat verifyCurrentPlatforms`
  - `.\gradlew.bat runForgeGameTests`
  - `.\gradlew.bat validateForgeGameTestLog`
  - `.\gradlew.bat verifyForgeTesting`
- **故障定位**：
  - `.\gradlew.bat :forge-1.20.1:bisectCompileClojure`
  - `.\gradlew.bat :forge-1.20.1:bisectCheckClojure`
  - `-PcompileNsOnly=<ns1,ns2>` / `-PcheckNsOnly=<ns1,ns2>` / `-PcheckNsFile=<path>`

## 模块边界与依赖红线

- **`mcmod`**：平台无关协议、DSL、元数据与基础运行时；禁止引入 `net.minecraft.*` 与 Loader API。
- **`ac`**：业务内容层（能力、无线、GUI 业务逻辑等）；禁止直接引用 Forge/Fabric/Minecraft API。
- **`forge-1.20.1`**：Loader 入口与平台适配；可使用 Minecraft/Forge API。
- **边界规则（更新）**：
  - 禁止 `ac` 对 `cn.li.forge1201.*` 建立静态编译依赖。
  - `forge-1.20.1` 可通过受控桥接调用 `ac` 能力（例如动态 require / ns-resolve），但不得把 `ac` 实现细节固化为跨层 API。
  - 平台层只允许保留“实现适配键 -> 平台实现类/函数”映射；禁止直接硬编码业务内容 ID（技能/实体/玩法名）。
  - 业务 hook-id 到实现键的映射必须位于共享层（`mcmod`），平台层不得承载业务语义。
  - 所有跨层调用都应通过清晰入口函数与文档记录，避免隐式耦合蔓延。

## 开发工具实践

1. **语义导航优先**：优先使用 LSP；文本搜索优先 `rg`。
2. **精确读取**：优先小范围读取，避免一次性加载超大文件。
3. **上下文控制**：按任务最小化上下文，避免无关内容污染判断。
4. **搜索排除**：忽略 `build/`、`.gradle/`、历史归档目录等噪音路径。

## 测试约定（ac / mcmod）

- 测试文件统一命名：`*_test.clj`；测试命名空间统一 `*-test`。
- `ac` 测试入口使用 `cn.li.ac.test-runner` 自动发现测试，不再手工维护列表。
- 支持按命名空间过滤执行：`-Dac.test.only=cn.li.ac.foo-test,cn.li.ac.bar-test`。
- 共享测试工具放在 `ac/src/test/clojure/cn/li/ac/test/support/**`，避免重复造 stub。
- 优先断言公开边界和业务不变量；仅在平台桥接边界使用 `with-redefs`。
- 新增或重构测试时，禁止直接依赖私有实现细节（例如私有 helper 的调用序）。

### 覆盖率与 ratchet（ac）

- 生成报告：`.\gradlew.bat :ac:coverageAcClojureTests`（HTML 在 `ac/build/reports/coverage/index.html`）。
- **Soft ratchet**：CI 用 `scripts/ac_coverage_ratchet.sh` 从 `ac/build/reports/coverage/index.html` 的 **Totals** 行解析 **% Lines**，要求不低于 `ac/coverage-baseline.txt` 中的基线减去 **0.5** 个百分点。
- 有意提升整体行覆盖后，将 `ac/coverage-baseline.txt` 更新为新百分比（可四舍五入一位小数），随 PR 提交。

### 覆盖率与 ratchet（mcmod）

- 生成报告：`.\gradlew.bat :mcmod:coverageMcmodClojureTests`（HTML 在 `mcmod/build/reports/coverage/index.html`）。
- **Soft ratchet**：CI 用 `scripts/mcmod_coverage_ratchet.sh` 从 `index.html` 的 **Totals** 行解析 **% Lines**，要求不低于 `mcmod/coverage-baseline.txt` 中的基线减去 **0.5** 个百分点。
- 提升基线后更新 `mcmod/coverage-baseline.txt`（建议一位小数），随 PR 提交。
- `coverageMcmodClojureTests` 通过 `--ns-exclude-regex` 排除 `cn.li.mcmod.client.obj` 与 `cn.li.mcmod.platform.position`（前者体量与单测 ROI，后者避免 Cloverage 与 `IBlockPos` 测试桩的协议冲突）；详见 [BUILD_AND_VERIFY_PLAYBOOK.md](BUILD_AND_VERIFY_PLAYBOOK.md)。

### 单测优先域 vs 交给集成（粗略）

| 优先单测 | 低 ROI / 交给 GameTest 或手测 |
|----------|------------------------------|
| 纯函数 util、模型、registry、effect.core、wireless 数据层（可 stub 平台） | `*.block` / `*.gui` / `*.render` / `*-fx` / `client.*` / `*.platform-bridge` |
| 带注入的 pipeline（`with-redefs` 动态 var） | 纯 GUI 布局、粒子/音效注册胶水 |

## 代码与运行时规则

- **侧分离**：客户端代码放 `client` 侧；共享逻辑不得引用 `net.minecraft.client.*`。
- **反射与动态求值**：避免通过字符串反射解析 Minecraft/Forge 符号；`eval` 内禁止出现平台类名直连。
- **桥接约束**：`ac` 不得 `resolve` / `requiring-resolve` `cn.li.forge1201.*`。
- **DSL 内容注册**：内容定义优先通过 `mcmod` DSL 与元数据注册路径完成。

## 结构性校验（快速自检）

- 推荐优先执行：`.\gradlew.bat verifyArchitectureBoundaries`（自动汇总并定位违规文件/行号）。
- `rg "cn\.li\.forge|cn\.li\.fabric" ac/src/` 应为空。
- `rg "net\.minecraft|net\.minecraftforge" ac/src/ mcmod/src/` 应为空（允许注释或字符串需人工甄别）。
- `rg "net\.minecraft\.client" ac/src/ mcmod/src/` 在非 client 目录应无泄漏。

## Hook 覆盖契约（平台一致性）

- 共享 hook catalog：`mcmod/src/main/clojure/cn/li/mcmod/entity/hook_catalog.clj`
- Fabric 缺口清单：`docs/dev/fabric-hook-support.properties`
- 执行：`.\gradlew.bat verifyPlatformHookCoverage`
- 规则：
  - Forge 必须覆盖共享 catalog 声明的实现键。
  - Fabric 若暂未实现，必须在缺口清单显式声明；新增实现键时清单必须同步更新。

## 平台层去业务化回归守卫

- 执行：`.\gradlew.bat verifyPlatformNoBusinessHookIds`
- 规则：
  - 平台 hook 适配文件不得出现来自业务实体定义中的 hook-id 字面量。
  - 若任务失败，需把业务 hook-id -> impl-key 映射迁回共享层（`mcmod`）。
