# 工程布局与命名空间（人类可读）

与 Cursor 规则 [`.cursor/rules/project-structure.mdc`](../../.cursor/rules/project-structure.mdc) 描述一致；**以本文与 `settings.gradle` 为准** 修改布局时，请同步更新该规则文件，避免分叉。

## 顶层 Gradle 模块

| 模块 | 职责 |
|------|------|
| **`api`** | 对外 Java API（如互操作用的接口包），无 Clojure 游戏逻辑 |
| **`mcmod`** | 平台无关：协议、DSL、`registry.metadata`、事件/GUI/NBT 等元数据；**禁止** `net.minecraft.*` 与 Loader API |
| **`ac`** | 游戏内容与域逻辑；**禁止**直接引用 Forge/Fabric/Minecraft API；通过 `mcmod` 与约定边界交互 |
| **`forge-1.20.1`** | Forge 入口、注册、桥接 Java、实现 `mcmod` 协议；允许通过受控运行时桥接使用 `ac` 能力 |
| **`fabric-1.20.1`** | 可选 Fabric 适配；默认可能未在 `settings.gradle` 中 `include` |

## 依赖红线（以“静态耦合”约束为主）

- **禁止** `ac` 对 `forge-1.20.1` 建立静态依赖（命名空间/类依赖）。
- **禁止**在 `mcmod` 与 `ac` 中引入平台 API（`net.minecraft.*` / Forge/Fabric）。
- **允许** `forge-1.20.1` 对 `ac` 进行受控运行时桥接（动态入口），用于装配与平台绑定。
- 运行时桥接必须：
  1. 有明确入口函数；
  2. 在文档中可追踪；
  3. 不把跨层实现细节固化为稳定 API。

## 源码路径与 Clojure 命名空间

- **`mcmod`**：`mcmod/src/main/clojure/cn/li/mcmod/...` → 命名空间前缀 **`cn.li.mcmod.*`**
- **`ac`**：`ac/src/main/clojure/cn/li/ac/...` → **`cn.li.ac.*`**
- **`forge-1.20.1`**：`forge-1.20.1/src/main/clojure/cn/li/forge1201/...` → **`cn.li.forge1201.*`**

资源与注册用 id 仍以根目录 **`gradle.properties`** 的 `mod_id`（如 `my_mod`）、**`assets/my_mod/`**、`data/my_mod/` 为准。

## 新增内容应落在何处

1. 在 **`mcmod`** 扩展 DSL / 元数据 / 协议（若涉及新抽象）。
2. 在 **`ac`** 实现方块、物品、业务逻辑，使用 `defblock` / `defitem` 等写入 `mcmod` registry。
3. 仅在需要 Loader 专用胶水时改 **`forge-1.20.1`**（或启用后的 Fabric 模块），并保持适配层薄。
