# 平台目标�?Fabric 维护级别

当前平台架构不再�?Gradle 子工程表�?Loader / Minecraft 版本。所有平台构建都通过单一 `:platform` 工程执行，目标由 `platform-catalog.json` 声明�?
## 当前目标

| target id | Loader | Minecraft | 维护级别 |
|-----------|--------|-----------|----------|
| `forge-1.20.1` | Forge | 1.20.1 | 主线支持 |
| `fabric-1.20.1` | Fabric | 1.20.1 | 维护支持 |

Fabric 维护支持的含义：

- 保持 catalog、sourceSet、compile、metadata、AOT、datagen entrypoint 方向可维护；
- 不承诺功能与 Forge 完全同步�?- 不通过复制 Forge 代码补齐差异�?- 差异必须落在 `platform-src/loader/fabric/` �?catalog capabilities 中�?
## 组件职责

- Minecraft 版本差异：`platform-src/minecraft/mc-1.20.1/`
- Loader 差异：`platform-src/loader/forge/`、`platform-src/loader/fabric/`
- 通用平台逻辑：`platform-src/common/`
- 构建目标输出：`platform-target/build/`

## 禁止�?
- 不新�?`forge-1.20.1/`、`fabric-1.20.1/`、`mc-1.20.1/` 这类根目录�?- 不使用平�?SPI、ServiceLoader bootstrap、task alias �?pass-through namespace�?- 不用 target id 字符串解�?loader/version；行为只能来�?`platform-catalog.json` 的显式字段�?- 不新增真�?NeoForge / Minecraft 26.1 支持，除非先完成新的 catalog 目标设计与验证�?