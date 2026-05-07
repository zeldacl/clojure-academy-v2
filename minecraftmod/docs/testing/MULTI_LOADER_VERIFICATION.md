# Multi-Loader Verification Matrix

统一定义多 Loader / 多版本场景下的最小验证矩阵，避免出现“目录存在但无人验证”的伪支持。

## 当前状态

- 默认根工程交付线：`forge-1.20.1`（主线）
- `fabric-1.20.1`：已纳入根构建，当前维护级别为 **minimal maintenance**（至少 compile 级）
- `neoforge-*`：尚未正式接入

## 验证层级

### 1. Compile 级

目标：确认平台模块最小可编译。

每个平台至少应具备：

- `:module:compileJava`
- `:module:compileClojure`

适用：所有正式或准正式支持的平台模块。

### 2. 启动烟雾级

目标：确认 Loader 能发现入口并完成最小启动。

每个平台至少应覆盖：

- `runClient`
- `runServer`

适用：所有正式支持的平台模块。

### 3. Datagen 级

目标：确认数据生成入口正常接入。

每个平台至少应覆盖：

- `runData` 或 Loader 等价 datagen 入口

### 4. 边界/静态规则级

目标：确保共享层仍保持平台无关。

建议检查：

- `ac` / `mcmod` 中是否出现 Loader / Minecraft API 静态引用
- client-only API 是否泄漏到共享层或 dedicated server 路径
- 平台关键 `defmulti` 是否为已启用平台提供实现

### 5. 集成验证级

目标：确认平台侧运行时桥接与游戏内行为可用。

当前推荐：

- Forge：继续使用 GameTest / 日志校验链
- NeoForge：若后续具备等价能力，应纳入同类验证
- Fabric：至少保留 datagen 与启动烟雾验证；若后续增加更强的集成验证，可单列补充

## 推荐矩阵

| 平台模块 | Compile | runClient | runServer | Datagen | 集成验证 |
|----------|---------|-----------|-----------|---------|----------|
| `forge-1.20.1` | 必须 | 必须 | 必须 | 必须 | 推荐/现行 |
| `fabric-1.20.1` | 必须（compile 基线） | 推荐 | 推荐 | 推荐 | 可选（minimal maintenance） |
| `neoforge-*` | 必须 | 必须 | 必须 | 必须 | 推荐 |

## 推荐执行顺序

### 新平台首日接入

1. `:module:compileJava`
2. `:module:compileClojure`
3. `:module:runData`

目的：先保证入口、namespace、AOT、datagen bridge 全部打通。

### 新平台第二阶段

1. `:module:runServer`
2. `:module:runClient`

目的：确认 common path 无 client 泄漏，client path 能装载渲染/UI。

### 新平台进入正式支持前

1. 边界扫描
2. smoke 验证
3. datagen 验证
4. Forge/NeoForge 的 GameTest 或等价集成验证

## 命令落地建议

### Forge 主线

- `:forge-1.20.1:compileJava`
- `:forge-1.20.1:compileClojure`
- `:forge-1.20.1:runData`
- `:forge-1.20.1:runServer`
- `:forge-1.20.1:runClient`
- `runForgeGameTests`

### Fabric（当前已纳入根构建）

- `:fabric-1.20.1:compileJava`
- `:fabric-1.20.1:compileClojure`
- `:fabric-1.20.1:runData` 或 Fabric datagen 等价任务
- `:fabric-1.20.1:runServer`
- `:fabric-1.20.1:runClient`

### NeoForge（模板目标）

- `:neoforge-<mc-version>:compileJava`
- `:neoforge-<mc-version>:compileClojure`
- `:neoforge-<mc-version>:runData`
- `:neoforge-<mc-version>:runServer`
- `:neoforge-<mc-version>:runClient`

## 边界扫描建议

建议至少固化以下扫描：

- `ac/src/` 中不出现 Loader / Minecraft API 静态引用
- `mcmod/src/` 中不出现 Loader / Minecraft API 静态引用
- 共享层不出现 client-only API 静态引用
- 已启用平台的关键 `defmulti` 分支均有实现

这些扫描可以通过脚本、Gradle task 或 CI job 落地，但无论采用哪种方式，都应把结果纳入 `verification` 流程，而不是停留在文档建议层。

## 支持状态定义

### 正式支持

需满足：

- 纳入根构建或 CI 基线
- 至少具备 compile、runClient、runServer、datagen 验证
- 文档中明确列出为正式支持

### 可选 / Experimental

允许：

- 不纳入默认根构建
- 只保留 compile 或有限 smoke 验证

但必须：

- 文档中明确标注状态
- 不得在 README 中暗示其与主线同等稳定

## 推荐后续动作

1. 持续保持 `fabric-1.20.1` compile 基线可用（`verifyFabricBaseline` / `verifyCurrentPlatforms`）。
2. 新增 `neoforge-*` 时，从第一天起就接入 compile 与 smoke 验证。
3. 把边界扫描脚本或 Gradle 任务纳入统一 `check` / `verification` 体系。

## 进入“可直接执行”状态的标准

若一个平台模块满足以下条件，则本验证矩阵对它不再只是理论约束，而是可以直接执行的交付标准：

- 文档中列出了该模块的实际任务名；
- 根构建或 CI 至少接入 compile 验证；
- 执行顺序已被记录；
- 失败时有明确的归因方向（compile、smoke、datagen、边界、集成）。
