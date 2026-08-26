# GUI DSL

当前 GUI 分成两个明确层次：`.ui.edn` artifact + `presentation-core` Runtime v2 负责自定义 HUD/Screen/Container 的声明与绘制，`mcmod/gui` 纯数据 spec 只负责 Menu/Slot/容器协议；业务 GUI 由 `ac` 注册，Loader 组件只负责 Minecraft 生命周期与 screen/network glue。

## mcmod entrypoints

| Namespace | Responsibility |
|-----------|----------------|
| `cn.li.mcmod.gui.spec` | GUI spec 构造与 `register-block-gui!`。 |
| `cn.li.mcmod.gui.registry` | GUI metadata、screen factory 与 handler registry。 |
| `cn.li.mcmod.gui.handler` | 平台 GUI handler 协议。 |
| `cn.li.mcmod.gui.slot-schema` | Slot layout、quick move 与 validator 描述。 |

`mcmod` 不引用 Minecraft / Loader API。

> `cn.li.mcmod.gui.xml-parser` 已不存在（文件已删除）——`ac/src` 下不再有任何
> `guis/**/*.xml`，XML GUI 已被 Presentation Runtime（`.ui.edn` artifact，见
> [PRESENTATION_RUNTIME_NEXT_PLAN_CN.md](../02-architecture/PRESENTATION_RUNTIME_NEXT_PLAN_CN.md)）
> 取代。本文档其余部分描述仍然存在的 `mcmod/gui` spec/registry/slot-schema
> 系统；machine_container、wireless_matrix、wireless_node 用它承载 Menu/Slot 权威，
> 最终呈现统一由 `presentation-v2` 挂载对应 `ac/src/presentation/resources/academy/app/*.ui.edn`。

## ac ownership

Wireless、TechUI、Terminal 等业务 GUI 定义在 `ac` 对应 namespace 中。`ac` 通过 `cn.li.ac.gui.platform-adapter/install-into-mcmod!` 向 `mcmod` 注入容器回调与 screen factory。

## Platform ownership

- Minecraft API 适配：`platform-src/minecraft/mc-*/gui/`
- Loader glue：`platform-src/loader/{forge,fabric,neoforge}-*/`


Loader 组件不得复制业务 GUI 规则。
