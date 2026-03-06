# Fabric 支持文档

## 概述

本项目现已支持 **Fabric 1.20.1** 模组加载器，与现有的 Forge 1.16.5 和 Forge 1.20.1 共享同一套核心逻辑。

## 架构设计

### 模块结构

```
minecraftmod/
├── core/                    # 共享核心逻辑（Clojure）
├── forge-1.16.5/           # Forge 1.16.5 适配器
├── forge-1.20.1/           # Forge 1.20.1 适配器
└── fabric-1.20.1/          # Fabric 1.20.1 适配器 (新增)
    ├── src/main/
    │   ├── java/com/example/my_mod1201/
    │   │   └── MyModFabric.java        # ModInitializer 入口
    │   ├── clojure/my_mod/fabric1201/
    │   │   ├── init.clj                # 版本初始化
    │   │   ├── mod.clj                 # Mod 主逻辑
    │   │   ├── registry.clj            # 注册系统实现
    │   │   ├── events.clj              # 事件处理
    │   │   └── gui/impl.clj            # GUI 实现
    │   └── resources/
    │       └── fabric.mod.json         # Fabric 元数据
    └── build.gradle                     # Fabric Loom 构建配置
```

### 关键差异：Forge vs Fabric

| 特性 | Forge | Fabric |
|-----|-------|--------|
| **注册系统** | DeferredRegister | Registry.register |
| **事件系统** | MinecraftForge.EVENT_BUS | Fabric API Callbacks |
| **入口点** | @Mod 注解 | ModInitializer 接口 |
| **构建工具** | ForgeGradle | Fabric Loom |
| **依赖包含** | 自动 | 需要 `include` 配置 |

## Fabric 实现要点

### 1. 注册系统 (registry.clj)

```clojure
;; Fabric 使用 Registry.register 直接注册
(defmethod registry/register-block :fabric-1.20.1
  [_ block-id block-instance]
  (Registry/register 
    BuiltInRegistries/BLOCK
    (ResourceLocation. "my_mod" block-id)
    block-instance))
```

**与 Forge 的区别**：
- Forge: DeferredRegister + RegistryObject（延迟注册）
- Fabric: 直接注册到 BuiltInRegistries（立即注册）

### 2. 事件系统 (events.clj)

```clojure
;; Fabric 使用回调接口注册事件
(defn register-events []
  (.register UseBlockCallback/EVENT
    (reify UseBlockCallback
      (interact [_ player world hand hit-result]
        (handle-use-block player world hand hit-result)))))
```

**与 Forge 的区别**：
- Forge: EventBusSubscriber + @SubscribeEvent 注解
- Fabric: 回调接口 (Callback) + .register 方法

### 3. Mod 入口 (MyModFabric.java)

```java
public class MyModFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("my-mod.fabric1201.mod"));
        
        IFn modInit = Clojure.var("my-mod.fabric1201.mod", "mod-init");
        modInit.invoke();
    }
}
```

**与 Forge 的区别**：
- Forge: @Mod 注解自动触发构造函数
- Fabric: 实现 ModInitializer 接口，手动调用初始化

### 4. 依赖打包 (build.gradle)

```gradle
dependencies {
  // Clojure 必须使用 include 才会打包到 mod jar 中
  implementation "org.clojure:clojure:1.11.1"
  include "org.clojure:clojure:1.11.1"
  include "org.clojure:spec.alpha:0.3.218"
  include "org.clojure:core.specs.alpha:0.2.62"
}
```

**与 Forge 的区别**：
- Forge: 依赖自动包含在 mod jar 中
- Fabric: 需要显式使用 `include` 指令

## 构建与测试

### 构建 Fabric Mod

```powershell
# 构建 Fabric 版本
.\gradlew :fabric-1.20.1:build

# 生成的 jar 位置
fabric-1.20.1\build\libs\fabric-1.20.1-1.0.0-fabric-1.20.1.jar
```

### 运行测试

```powershell
# 启动 Fabric 客户端
.\gradlew :fabric-1.20.1:runClient

# 启动 Fabric 服务器
.\gradlew :fabric-1.20.1:runServer
```

### 游戏内测试

1. 进入游戏后执行：
   ```
   /give @p my_mod:demo_item
   /give @p my_mod:demo_block
   ```

2. 放置 demo_block 并右键点击

3. 查看日志确认事件触发：
   ```
   [INFO] Fabric 1.20.1 Right-click event at (x,y,z) block: demo_block
   [INFO] Demo block detected! Triggering GUI open logic...
   ```

## 多模组加载器支持总览

### 当前支持的版本

| 加载器 | Minecraft 版本 | Java 版本 | 状态 |
|-------|---------------|----------|------|
| Forge | 1.16.5 | 8 | ✅ 完成 |
| Forge | 1.20.1 | 17 | ✅ 完成 |
| Fabric | 1.20.1 | 17 | ✅ 完成 |

### 版本调度机制

所有版本通过 multimethod 共享核心逻辑：

```clojure
;; core/registry.clj 定义抽象
(defmulti register-block (fn [_] *forge-version*))

;; forge1165/registry.clj 实现
(defmethod register-block :forge-1.16.5 [block-id block] ...)

;; forge1201/registry.clj 实现
(defmethod register-block :forge-1.20.1 [block-id block] ...)

;; fabric1201/registry.clj 实现
(defmethod register-block :fabric-1.20.1 [block-id block] ...)
```

每个适配器在初始化时设置 `*forge-version*`：
- Forge 1.16.5: `:forge-1.16.5`
- Forge 1.20.1: `:forge-1.20.1`
- Fabric 1.20.1: `:fabric-1.20.1`

## 扩展到更多版本

### 添加 Fabric 1.16.5

1. 创建 `fabric-1.16.5/` 模块
2. 复制 `fabric-1.20.1/` 的结构
3. 调整 API 差异（主要是包名变化）
4. 在 `init.clj` 中设置 `*forge-version*` 为 `:fabric-1.16.5`
5. 在核心模块添加 `:fabric-1.16.5` 的 defmethod 实现

### 添加 Quilt 支持

Quilt 兼容 Fabric，只需：
1. 将 `fabric.mod.json` 改为 `quilt.mod.json`
2. 依赖改为 `org.quiltmc:quilt-loader`
3. 其他代码保持不变

## 常见问题

### Q: 为什么 Fabric 也用 `*forge-version*` 变量名？

A: 这是历史遗留命名，实际上它是"模组加载器版本"的意思。为了保持兼容性，没有重命名。可以理解为 `*loader-version*`。

### Q: Fabric 构建失败怎么办？

A: 检查以下几点：
1. Fabric Loom 版本是否支持 Gradle 7+
2. `fabric.mod.json` 格式是否正确
3. Clojure 依赖是否使用了 `include`
4. Java 版本是否为 17

### Q: 如何在 Fabric 中实现 GUI？

A: Fabric GUI 实现与 Forge 类似，但使用：
- `ExtendedScreenHandlerFactory` 代替 Forge 的 `MenuProvider`
- `ScreenHandler` 代替 Forge 的 `AbstractContainerMenu`
- `HandledScreen` 代替 Forge 的 `AbstractContainerScreen`

### Q: 性能如何？

A: Clojure 代码经过 AOT 编译后性能与 Java 相当。multimethod 分发的开销可忽略不计。

## 总结

通过 multimethod 抽象 + 版本适配器模式，我们实现了：

✅ **单一代码库**：核心逻辑只写一次  
✅ **多加载器支持**：Forge + Fabric 同时支持  
✅ **类型安全**：Clojure 与 Java 无缝互操作  
✅ **易于扩展**：添加新版本只需新增适配器  

项目现已成为真正的 **多模组加载器框架**，同时支持 Minecraft 社区两大主流生态系统。
