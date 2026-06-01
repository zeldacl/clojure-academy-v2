# AC 模块分层约定

## 背景

`ac` 是业务/content 层，承载能力、无线、电力、GUI 业务逻辑等内容。它必须保持平台无关：不静态依赖 `net.minecraft.*`、Forge/Fabric Loader API，也不依赖 `cn.li.forge1201.*` / `cn.li.fabric1201.*`。

本轮结构性重构采用 **stable public boundary + internal extraction**：对外保留当前正式入口，把实现拆进更小的 domain/data/service/client/block 组件，并删除过渡性旧结构。

## 目标依赖方向

推荐的单向调用流：

```text
foundation → domain/model → data/repository/persistence → service/application → api/facade → block/gui/content adapters
```

约束：

- `domain/model`：纯数据、规则和计算，不依赖 runtime tile/world/capability。
- `data/*`：持久化、索引、存储结构；不编排业务命令。
- `service/*`：应用级 orchestration；可以组合 data/domain，但应避免平台静态依赖。
- `api`：兼容公开入口与事件发射，尽量委托到 service。
- `block/*` / `content/*` / `client/*`：内容适配、GUI、客户端表现和运行时 glue。

## 稳定公开入口

下列 namespace 是对外或跨模块的稳定边界；**新增实现**应落在右侧列出的内部职责 namespace，而不是在入口层堆积逻辑：

| 公开入口 | 内部职责 | 说明 |
|----------|----------|------|
| `cn.li.ac.wireless.api` | `service.commands`、`service.queries`、事件发射 | 无线对外**唯一**入口；已删除 `topology-service` / `query-service` 等第二套路径。 |
| `cn.li.ac.wireless.data.world` | `data.persistence`、生命周期钩子 | **仅** SavedData 与 `on-world-load/save/tick`；不代理 lookup，也不 re-export 拓扑命令。可变状态提交在 `data.world-registry`。 |
| `cn.li.ac.wireless.core.vblock` | `data.vblock-codec`、`core.vblock-resolver` | runtime record 兼容保留，NBT codec 与 world resolver 分离。 |
| `cn.li.ac.block.wireless-node.logic` | `state`、`inventory`、`tick`、`capability` | 旧 logic 仅保留 facade 和方块事件 handler。 |
| `cn.li.ac.energy.api.impl` | `service.provider-registry`、`service.subscription`、`service.transfer-executor` | 协议实现瘦身为适配层。 |
| `cn.li.ac.ability.registry.developer-type` / developer block callers | `ability.domain.developer` | developer 类型、等级门槛、能量 pacing 单一事实源。 |

## 新增代码放置规则

### Wireless

- VBlock NBT 编解码：`cn.li.ac.wireless.data.vblock-codec`。
- VBlock → world/tile/capability runtime lookup：`cn.li.ac.wireless.core.vblock-resolver`。
- 查询：`cn.li.ac.wireless.service.queries`（只读，tile → vblock → lookup）。
- 拓扑命令：`cn.li.ac.wireless.service.commands`（`transact!` + `entity-commit`）。
- 纯拓扑状态变换：`cn.li.ac.wireless.domain.topology`。
- 能量计划与副作用：`domain.transfer`、`runtime.effects`。

### Wireless Node Block

- 状态 schema、默认值、tier、blockstate 投影：`cn.li.ac.block.wireless-node.state`。
- Slot schema 与 container 操作：`cn.li.ac.block.wireless-node.inventory`。
- Server tick：`cn.li.ac.block.wireless-node.tick`。
- Java/capability implementation：`cn.li.ac.block.wireless-node.capability`。
- 方块右键/放置/破坏事件 glue：保留在 `cn.li.ac.block.wireless-node.logic`。

### Energy

- Provider 推断、注册表、admin dump：`cn.li.ac.energy.service.provider-registry`。
- 订阅/通知：`cn.li.ac.energy.service.subscription`。
- transfer/drain 执行：`cn.li.ac.energy.service.transfer-executor`。
- 旧 `cn.li.ac.energy.operations` 继续作为业务兼容 facade。

### Client FX

- 通用 beam/ray render op 构建：`cn.li.ac.ability.client.effects.beam-render`。
- 技能专属状态、音效、charge ring、payload channel glue 留在各技能 FX namespace。

## 测试策略

- 每次拆分保留 facade 行为测试，优先断言公开函数和业务不变量。
- 对新内部纯逻辑添加 focused tests：例如 VBlock codec、`service.commands` / `service.queries`、`domain.topology`、beam render builder、energy API facade。
- `get_errors` 中 Clojure reload 的 `redefined var` 可视为噪声；最终以 Gradle 编译/单测/边界门禁为准。

## 验证命令

在仓库根目录使用：

```powershell
cmd.exe /c "gradlew.bat :ac:compileClojure"
cmd.exe /c "gradlew.bat runAcUnitTests"
cmd.exe /c "gradlew.bat verifyArchitectureBoundaries"
```

> 在 Windows PowerShell 中，若直接执行 `.\gradlew.bat` 得到 generic `NativeCommandFailed`，使用上面的 `cmd.exe /c` 形式。
gradlew.bat` 得到 generic `NativeCommandFailed`，使用上面的 `cmd.exe /c` 形式。
