# 工程布局与命名空间（人类可读）

与 Cursor 规则 [`.cursor/rules/project-structure.mdc`](../../.cursor/rules/project-structure.mdc) 描述一致；**以本文与 `settings.gradle` 为准** 修改布局时，请同步更新该规则文件，避免分叉。

## 顶层 Gradle 模块

| 模块 | 职责 |
|------|------|
| **`api`** | 对外 Java API（如互操作用的接口包），无 Clojure 游戏逻辑 |
| **`mcmod`** | 平台无关：协议、DSL、`protocol.metadata`、事件/GUI/NBT 等元数据；**禁止** `net.minecraft.*` 与 Loader API |
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

### `mcmod` 关键基础命名空间（AOT/运行时）

- `mcmod/src/main/clojure/cn/li/mcmod/aot.clj` → `cn.li.mcmod.aot`
  - 编译期检测与运行期护栏：`compiling?` / `compile-context` / `ensure-runtime!`
- `mcmod/src/main/clojure/cn/li/mcmod/runtime/deferred.clj` → `cn.li.mcmod.runtime.deferred`
  - 统一惰性初始化持有器（替代平台各自实现），防止 AOT 期间误触发 registry/bootstrap 路径

资源与注册用 id 仍以根目录 **`gradle.properties`** 的 `mod_id`（如 `my_mod`）、**`assets/my_mod/`**、`data/my_mod/` 为准。

## 数据终端（`ac/terminal`）

函数式拆分，服务端与客户端命名空间分离：

| 路径 | 命名空间前缀 | 说明 |
|------|----------------|------|
| `ac/.../terminal/model.clj` 等 | `cn.li.ac.terminal.*` | 会话状态、`catalog`、网络、`messages` |
| `ac/.../terminal/client/` | `cn.li.ac.terminal.client.*` | Shell、runtime、app launcher、`client.actions` 侧载入口 |

维护说明见 [TERMINAL_SYSTEM_MAINTENANCE.md](../04-systems/TERMINAL_SYSTEM_MAINTENANCE.md)。勿再引入 `registry` 包装层、`client/bridge` 或 manifest/SPI 式应用注册。

## 无线能源（`ac/wireless`）

单一函数式运行时，对外经 `cn.li.ac.wireless.api`：

| 路径 | 命名空间 | 说明 |
|------|----------|------|
| `wireless/api.clj` | `cn.li.ac.wireless.api` | 查询与拓扑命令的唯一对外入口 |
| `wireless/service/commands.clj`、`queries.clj` | `service.*` | 写/读编排（模块内） |
| `wireless/domain/` | `domain.topology`、`domain.transfer` | 纯 world-state 与能量计划 |
| `wireless/data/world.clj` | `data.world` | **仅**生命周期与 SavedData |
| `wireless/data/world_registry.clj` | `data.world-registry` | `transact!` 可变提交 |
| `wireless/runtime/effects.clj` | `runtime.effects` | capability 能量 IO |
| `ac/block/wireless_*` | `cn.li.ac.block.wireless-*` | 方块 tick/事件；经 `wireless.api` 改拓扑 |

契约与排障：[WIRELESS_REFACTOR_CONTRACTS.md](../05-wireless/WIRELESS_REFACTOR_CONTRACTS.md)、[WIRELESS_SYSTEM_MAINTENANCE.md](../04-systems/WIRELESS_SYSTEM_MAINTENANCE.md)。已删除 `topology-service`、`query-service`、`topology-index` 等并行层。

## 能力系统（`ac/ability` + `ac/content/ability`）

**Reducer-only（强制）**：玩家状态唯一写路径为 `command-runtime` → `reducer` → `runtime-store`；副作用走 `effects.interpreter`。无 `context-registry` 门面、无 `:sync-*-data` reducer 命令、无 `update-context!` 旁路。

| 路径 | 命名空间 | 说明 |
|------|----------|------|
| `ability/service/command_runtime.clj` | `service.command-runtime` | 命令执行壳 |
| `ability/service/reducer.clj` | `service.reducer` | 玩家状态归约 |
| `ability/service/context_dispatcher.clj` | `service.context-dispatcher` | Context transport + lifecycle + 合并读（`:as ctx`） |
| `ability/service/context_manager.clj` | `service.context-manager` | 服务端 activate / keepalive / abort |
| `ability/service/context_skill_state.clj` | `service.context-skill-state` | 技能侧读写入口（`:as ctx-skill`） |
| `ability/service/context_projection.clj` | `service.context-projection` | 仅 store 投影读 |
| `ability/effects/*` | `effects.*` | 服务端效果（勿恢复 `ability/server/effect/*`） |
| `ability/adapters/runtime_bridge.clj` | `adapters.runtime-bridge` | 安装 mcmod hooks |
| `content/ability/*` | `cn.li.ac.content.ability.*` | 各技能 `defskill` 实现 |

维护说明：[ABILITY_SYSTEM_MAINTENANCE.md](../04-systems/ABILITY_SYSTEM_MAINTENANCE.md)。

## CGUI MSDF 字体（`mc-1.20.1` + `ac` 注册）

零资源 MTSDF 阴影字体，替代已删除的 TTF virtual-pack 栈。CGUI 作用域（`:ac-normal` / `:ac-bold` / `:ac-italic`）；HUD 与其它 vanilla 文本不在此路径。

| 路径 | 命名空间 / 类 | 说明 |
|------|----------------|------|
| `mc-1.20.1/.../font/msdf/*.java` | `cn.li.mc1201.client.font.msdf.*` | STB 加载、`MsdfEngine` MTSDF 生成、多页 atlas（LRU + 异步预烘焙）、`GlyphProvider` SPI、`MsdfRenderTypes`、shader uniform |
| `mc-1.20.1/.../font/msdf_setup.clj` | `cn.li.mc1201.client.font.msdf-setup` | 系统字体探测 → `MsdfFontManager` 初始化 |
| `mc-1.20.1/.../font/msdf_tick.clj` | `cn.li.mc1201.client.font.msdf-tick` | ClientTick 发光呼吸等动画 |
| `mc-1.20.1/.../gui/cgui/font.clj` | `cn.li.mc1201.gui.cgui.font` | CGUI 桥：`text-width` / `draw-text!`、分段 MSDF/vanilla、per-glyph 标志（顶点色蓝通道低 3 位）、`with-text-fx` |
| `mc-1.20.1/.../gui/reactive/render.clj` | `cn.li.mc1201.gui.reactive.render` | `:text` 组件接线（`render-text!`/`bake-text!`，替代已删除的旧 `gui/cgui/renderer.clj`） |
| `ac/.../client/font_init.clj` | `cn.li.ac.client.font-init` | 注册 `:ac-*` 字体关键字（flag-only） |
| `ac/.../assets/my_mod/shaders/core/msdf_text.*` | — | MSDF 文本 shader（median + fwidth AA + 效果层） |
| `forge-1.20.1/.../ForgeClientRenderRegistry` | — | `RegisterShadersEvent` 注册 `my_mod:msdf_text` |
| `fabric-1.20.1/.../FabricClientRenderSetup` | — | `CoreShaderRegistrationCallback` 同等注册 |

**Follow-up 能力（已实现）**：单字符串内 per-glyph bold/outline/glow（shader 解码顶点色标志）；`getGlyph` 触发的异步 MTSDF 预烘焙；atlas LRU（默认 4096 glyph）；`start-glow-breath!` ClientTick 呼吸发光。

**字号契约**：`:font-size N` = 屏幕上 **N 像素高**（同 LambdaLib2 `FontOption.fontSize`）。STB 在 8px em 下烘焙；`scale = N / 8`。布局 quad 用 typographic bounds，不含 MSDF bake padding（AC 原版栅格 cell 24×1.4 仅作参考常量 `AC_CHAR_SIZE`）。

## 反应式 UI 框架迁移（已完成）

旧 CGUI 框架（`mcmod/gui/cgui_core.clj` 等 8 文件、`mc-1.20.1/gui/cgui/{renderer,traversal,input,runtime,assets}.clj`）与其消费者已全部删除，替换为 `mc-1.20.1/gui/reactive/*`（signal 驱动、dirty-gated）+ `mcmod` signal core。保留：`mc-1.20.1/gui/cgui/font.clj`（MSDF 桥，仍在用）、`mcmod/gui/tabbed_gui.clj` + `spec.clj`（平台无关的 tab 同步/GUI spec 校验，被 `gui/menu/proxy.clj`、`gui/slots/sync.clj`、`gui/reactive/host_container.clj`、多个 `*_reactive.clj` 消费，非旧框架残留）。

**平台初始化**：Forge / Fabric `client/init` 调用 `msdf-setup/init!`；`runtime_bridge` 每 tick 调用 `msdf-tick/client-tick!`。

## Scripted 逻辑分发（`mc-1.20.1` + 平台注册）

BlockEntity 与 Mob 热路径经 Java 接口 + reify bundle，无运行期 `^:dynamic` 查表。详见 [SCRIPTED_LOGIC_DISPATCH.md](../04-systems/SCRIPTED_LOGIC_DISPATCH.md)。

| 路径 | 说明 |
|------|------|
| `mc-1.20.1/.../block/logic/*.java` | `ITile*Logic`、`TileLogicBundle`、`IScriptedBlock` |
| `mc-1.20.1/.../block/logic_compile.clj`、`logic_pipeline.clj` | tile bundle 编译与安装 |
| `mc-1.20.1/.../entity/ScriptedMobEntity.java`、`entity/logic/*` | Mob bundle 与 `ScriptedEntityLogicRegistry` |
| `mc-1.20.1/.../entity/mob_logic_compile.clj`、`mob_logic_pipeline.clj` | mob bundle 编译与安装 |
| `mcmod/.../block/tile_kind.clj` | 声明期 tile-kind 默认（合并于 compile） |
| `forge-1.20.1/.../registry/content_registration.clj` | 注册期调用 pipeline |

## 新增内容应落在何处

1. 在 **`mcmod`** 扩展 DSL / 元数据 / 协议（若涉及新抽象）。
2. 在 **`ac`** 实现方块、物品、业务逻辑，使用 `defblock` / `defitem` 等写入 `mcmod` registry。
3. 仅在需要 Loader 专用胶水时改 **`forge-1.20.1`**（或启用后的 Fabric 模块），并保持适配层薄。
4. 新终端应用：改 `terminal/catalog.clj` + `terminal/client/apps/*` + `client/apps.clj` 的 `launchers`。
