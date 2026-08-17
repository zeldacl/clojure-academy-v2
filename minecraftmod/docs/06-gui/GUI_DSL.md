# GUI DSL

当前 GUI 声明以 `mcmod` 的纯数据 spec 为中心，业务 GUI 由 `ac` 注册，Loader 组件只负责 Minecraft 菜单、screen 与网络 glue。

## mcmod entrypoints

| Namespace | Responsibility |
|-----------|----------------|
| `cn.li.mcmod.gui.spec` | GUI spec 构造与 `register-block-gui!`。 |
| `cn.li.mcmod.gui.registry` | GUI metadata、screen factory 与 handler registry。 |
| `cn.li.mcmod.gui.handler` | 平台 GUI handler 协议。 |
| `cn.li.mcmod.gui.slot-schema` | Slot layout、quick move 与 validator 描述。 |

`mcmod` 不引用 Minecraft / Loader API。

> `cn.li.mcmod.gui.xml-parser` 已不存在（文件已删除）——`ac/src` 下不再有任何
> `guis/**/*.xml`，XML GUI 已被 Presentation Runtime（`.ui.edn` 模板，见
> [PRESENTATION_RUNTIME_NEXT_PLAN_CN.md](../02-architecture/PRESENTATION_RUNTIME_NEXT_PLAN_CN.md)）
> 取代。本文档其余部分描述的是仍然存在的 `mcmod/gui` spec/registry/slot-schema
> 系统（machine_container 等仍在用它承载 Menu/Slot 权威），未随本轮重构逐条复核，
> 如发现与代码不符请对照 `mcmod/src/main/clojure/cn/li/mcmod/gui/` 实际文件更新。

## ac ownership

Wireless、TechUI、Terminal 等业务 GUI 定义在 `ac` 对应 namespace 中。`ac` 通过 `cn.li.ac.gui.platform-adapter/install-into-mcmod!` 向 `mcmod` 注入容器回调与 screen factory。

## Platform ownership

- Minecraft API 适配：`platform-src/minecraft/mc-1.20.1/gui/`
- Forge glue：`platform-src/loader/forge/`
- Fabric glue：`platform-src/loader/fabric/`

Loader 组件不得复制业务 GUI 规则。
