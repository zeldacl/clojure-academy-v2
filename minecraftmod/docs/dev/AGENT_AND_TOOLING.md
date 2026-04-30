# Agent 与工具链约定（原根目录 CLAUDE.md 全文）

本文档为 AI/开发者辅助工具使用的项目规则正文。仓库根目录的 `CLAUDE.md` 仅保留指向本文件的简短说明。

## 构建与开发（Windows：`./gradlew.bat`）

- **工作目录**：始终在包含根目录 `settings.gradle` 的 `minecraftmod` 目录下执行 Gradle。
- **LSP 索引**：修改 Java 后执行 `./gradlew :<subproject>:classes` 以刷新 LSP。
- **快速编译 Clojure**：`./gradlew :ac:compileClojure` 或 `./gradlew :mcmod:compileClojure`。
- **运行客户端**：`./gradlew :forge-1.20.1:runClient`。

## 工具与性能（Anti-429）

1. **LSP 优先**：导航以 `clojure-lsp` / `java-lsp` 为准；不要用 `grep` 找定义。
2. **精确读文件**：若工具支持 `pages` 参数，优先用分页而非大段 `offset`/`limit`。
3. **上下文**：注意 token 用量，必要时压缩上下文。
4. **搜索**：使用 ripgrep（`rg`），忽略 `build/`、`.gradle/` 等。
5. **LSP 约束**：`workspaceSymbol` 等不要把目录路径当作单文件 `filePath` 传入。

## 项目结构与依赖

- **依赖方向**：`forge-1.20.1` → `mcmod` → `api`，且 `ac` → `mcmod` → `api`。
- **红线**：**`forge-1.20.1` 与 `ac` 不得相互引用**（无直接命名空间依赖）。
- **模块角色**：
  - **`mcmod`**：协议、DSL、元数据；**禁止** `net.minecraft.*` 及 Loader API。
  - **`ac`**：游戏内容；仅通过 `mcmod` 与边界 API 与世界交互；**禁止** Forge/Fabric/Minecraft 直接引用。
  - **`forge-1.20.1`**：Forge 适配，用 Minecraft/Forge API 实现 `mcmod` 协议。

## 编码规则

- **官方 API 优先**：标准事件与平台 API，避免非官方 hack。
- **反射**：Java 互操作使用类型提示 `^TypeName`；建议 `*warn-on-reflection* true`。
- **Remap 安全**：不要用 `eval` / `Class/forName` 字符串去解析 Minecraft/Forge 符号。
- **Eval**：`eval` 表单内不得出现 `net.minecraft.*` / `net.minecraftforge.*` 类名等。
- **Resolve**：`ac` 不得 `resolve` / `requiring-resolve` `cn.li.forge1201.*`，用注入的桥接函数。
- **破坏性变更**：不刻意兼容已废弃的旧结构，优先清晰现代实现。
- **侧分离**：`**/client/**` 仅客户端；公共代码禁止 `net.minecraft.client.*`；Java 客户端类使用 `@OnlyIn(Dist.CLIENT)`。
- **DSL**：`ac` 中内容通过 `defblock` / `defitem` 等注册到 `mcmod` 元数据。

## 校验命令（架构）

- `rg "cn\.li\.forge|cn\.li\.fabric" ac/src/` 应为空。
- `rg "cn\.li\.ac" forge-1.20.1/src/` 应为空。
- `rg "net\.minecraft\.client" ac/src/`：在非 `client` 目录下应无泄漏。

## 关键代码位置

- **多方块 / Block DSL**：`mcmod/.../block/multiblock_core.clj`、`dsl.clj`。
- **无线逻辑**：`ac/.../wireless/network.clj`。
