# Agent 与工具链约定（正文）

本文档是仓库内 Agent/开发辅助工具规则正文。根目录 `CLAUDE.md` 仅保留指向本文件的指针。

## 构建与开发基线（Windows 使用 `.\gradlew.bat`）

- **工作目录**：始终在包含根 `settings.gradle` 的 `minecraftmod` 目录执行命令。
- **默认构建平台**：根工程默认包含 `api`、`mcmod`、`ac`、`forge-1.20.1`；`fabric-1.20.1` 默认未 include。
- **Java 版本**：Java 17。
- **LSP 刷新**：修改 Java 后执行 `.\gradlew.bat :<subproject>:classes`。

## 常用命令矩阵

- **本地运行**：`.\gradlew.bat :forge-1.20.1:runClient` / `:forge-1.20.1:runServer` / `:forge-1.20.1:runData`
- **快速编译**：`.\gradlew.bat :ac:compileClojure`、`.\gradlew.bat :mcmod:compileClojure`、`.\gradlew.bat :forge-1.20.1:compileClojure`
- **测试与验证入口**：
  - `.\gradlew.bat unitTestCompile`
  - `.\gradlew.bat verifyForgeBaseline`
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
  - 所有跨层调用都应通过清晰入口函数与文档记录，避免隐式耦合蔓延。

## 开发工具实践

1. **语义导航优先**：优先使用 LSP；文本搜索优先 `rg`。
2. **精确读取**：优先小范围读取，避免一次性加载超大文件。
3. **上下文控制**：按任务最小化上下文，避免无关内容污染判断。
4. **搜索排除**：忽略 `build/`、`.gradle/`、历史归档目录等噪音路径。

## 代码与运行时规则

- **侧分离**：客户端代码放 `client` 侧；共享逻辑不得引用 `net.minecraft.client.*`。
- **反射与动态求值**：避免通过字符串反射解析 Minecraft/Forge 符号；`eval` 内禁止出现平台类名直连。
- **桥接约束**：`ac` 不得 `resolve` / `requiring-resolve` `cn.li.forge1201.*`。
- **DSL 内容注册**：内容定义优先通过 `mcmod` DSL 与元数据注册路径完成。

## 结构性校验（快速自检）

- `rg "cn\.li\.forge|cn\.li\.fabric" ac/src/` 应为空。
- `rg "net\.minecraft|net\.minecraftforge" ac/src/ mcmod/src/` 应为空（允许注释或字符串需人工甄别）。
- `rg "net\.minecraft\.client" ac/src/ mcmod/src/` 在非 client 目录应无泄漏。
