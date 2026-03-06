# Forge 1.20.1 和 Fabric 1.20.1 实现说明

## Forge 1.20.1 实现

由于 Forge 1.16.5 到 1.20.1 的 API 变化主要是命名和包结构调整，核心逻辑相同：

**主要变化：**
1. `Container` → `AbstractContainerMenu`
2. `ContainerType` → `MenuType`
3. `INamedContainerProvider` → `MenuProvider`
4. `NetworkHooks.openGui()` → `NetworkHooks.openScreen()`
5. 包名从 `net.minecraft.inventory.container` 改为 `net.minecraft.world.inventory`

**实现方式：**
- 复制 `forge-1.16.5/gui/` 下的三个文件
- 修改导入包名和类名
- 调整 API 调用方式

## Fabric 1.20.1 实现

Fabric 使用不同的 API 体系：

**主要差异：**
1. 使用 `ScreenHandler` 替代 `Container`
2. 使用 `ScreenHandlerType` 注册
3. 使用 `NamedScreenHandlerFactory` 替代 `INamedContainerProvider`
4. 使用 `ExtendedScreenHandlerFactory` 传递额外数据
5. 使用 `ServerPlayerEntity.openHandledScreen()` 打开 GUI

**实现方式：**
- 创建 `ScreenHandler` 包装器
- 实现 `NamedScreenHandlerFactory`
- 使用 `ScreenHandlerRegistry.registerSimple()` 注册
- 客户端使用 `HandledScreens.register()`

## 实际部署建议

由于这是一个 Clojure-first 项目，建议：

1. **优先实现一个平台** (如 Forge 1.16.5)
2. **测试核心功能**
3. **根据需要扩展到其他平台**

当前已完成的 Forge 1.16.5 实现提供了完整的模板，可以根据需要快速适配其他版本。

## 文件清单

**Forge 1.16.5 (已完成):**
- `forge-1.16.5/gui/bridge.clj` (150 行)
- `forge-1.16.5/gui/registry_impl.clj` (130 行)
- `forge-1.16.5/gui/screen_impl.clj` (100 行)

**Forge 1.20.1 (待实现):**
- 基于 1.16.5 修改 API 调用
- 主要是包名和类名调整

**Fabric 1.20.1 (待实现):**
- 需要重新实现，API 完全不同
- 约 400 行代码
