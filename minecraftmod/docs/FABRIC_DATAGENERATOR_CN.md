# Fabric 1.20.1 DataGenerator 集成指南

## 概述

本项目为 Fabric 1.20.1 平台也添加了完整的 DataGenerator 支持。使用方法与 Forge 相同，但触发机制不同。

## 架构

### 核心模块（与 Forge 共用）
- `my-mod.datagen.blockstate-provider`
- `my-mod.datagen.model-provider`
- `my-mod.datagen.item-model-provider`

### Fabric 特定模块

**Clojure 入口**:
- [fabric-1.20.1/src/main/clojure/my_mod/fabric1201/datagen/setup.clj](./fabric-1.20.1/src/main/clojure/my_mod/fabric1201/datagen/setup.clj)
  - 提供 `register-data-generators!` 函数
  - 注册所有核心 providers

**Java 包装器**:
- [fabric-1.20.1/src/main/java/com/example/fabric1201/datagen/DataGeneratorSetup.java](./fabric-1.20.1/src/main/java/com/example/fabric1201/datagen/DataGeneratorSetup.java)
  - 实现 `DataGeneratorEntrypoint` 接口
  - Fabric 在数据生成时自动调用

**配置文件更新**:
- [fabric-1.20.1/src/main/resources/fabric.mod.json](./fabric-1.20.1/src/main/resources/fabric.mod.json)
  - 添加 `"fabric-datagen"` 入口点
  - Fabric 加载器自动发现并调用

## 使用方法

### 生成 JSON 文件

对于 Fabric，使用 `gradle runData` 任务（Gradle Loom 提供的）：

```bash
# 在项目根目录运行
.\gradlew.bat :fabric-1.20.1:runData
```

或使用 Fabric Loom 的原生任务：

```bash
.\gradlew.bat :fabric-1.20.1:genSources
```

### 预期输出

成功运行后会看到：

```
> Task :fabric-1.20.1:runData
[my_mod] Initializing Fabric DataGenerator...
[my_mod] Registering Fabric DataGenerators...
[my_mod] Registering BlockState DataGenerator...
[my_mod] Registering Block Model DataGenerator...
[my_mod] Registering Item Model DataGenerator...
[my_mod] Fabric DataGenerator setup complete!

Generated blockstate: assets/my_mod/blockstates/matrix.json
Generated blockstate: assets/my_mod/blockstates/windgen_main.json
...
```

### 输出位置

生成的 JSON 文件位于：

```
fabric-1.20.1/src/main/resources/assets/my_mod/
├── blockstates/
│   ├── matrix.json
│   ├── windgen_main.json
│   └── ...
├── models/
│   ├── block/
│   │   ├── matrix.json
│   │   └── ...
│   └── item/
│       ├── wafer.json
│       └── ...
```

## 配置 mod-id

与 Forge 相同，所有生成的文件都使用中央配置的 `modid/MOD-ID` 常量：

### 修改方法

**方法 1: 直接修改 Clojure**

编辑 [core/src/main/clojure/my_mod/config/modid.clj](../core/src/main/clojure/my_mod/config/modid.clj)：

```clojure
(def ^:const MOD-ID "your_mod_id")
```

**方法 2: 环境变量**

```bash
# Windows PowerShell
$env:MOD_ID="your_mod_id"
.\gradlew.bat :fabric-1.20.1:runData

# Linux/macOS bash
MOD_ID=your_mod_id ./gradlew :fabric-1.20.1:runData
```

### 重新生成

修改 mod-id 后，重新运行：

```bash
.\gradlew.bat :fabric-1.20.1:runData
```

所有 JSON 文件会自动使用新的 mod-id。

## 与 Forge 的区别

| 特性 | Forge | Fabric |
|------|-------|--------|
| 事件系统 | @Mod.EventBusSubscriber | DataGeneratorEntrypoint |
| 入口点配置 | 各平台单独定义 | fabric.mod.json |
| 触发任务 | `:forge-X.X.X:runData` | `:fabric-1.20.1:runData` |
| 工作目录 | `run/data/` | `fabric-datagen` |
| 调用时机 | MOD 总线事件 | 数据生成初始化 |

## 工作流程

```
用户修改 Clojure datagen 配置
         ↓
运行 ./gradlew :fabric-1.20.1:runData
         ↓
Gradle Loom 启动数据生成
         ↓
Fabric 加载器发现 fabric-datagen 入口点
         ↓
DataGeneratorSetup.onInitializeDataGenerator() 被调用
         ↓
Java 包装器通过反射调用 Clojure 实现
         ↓
Clojure providers 生成 JSON
         ↓
Gradle 收集所有生成的资源
```

## 添加新资源

与 Forge 完全相同：

1. 编辑对应的 Clojure datagen 模块
2. 在配置中添加新的方块/物品条目
3. 运行 `.\gradlew.bat :fabric-1.20.1:runData`
4. JSON 自动生成到 `src/main/resources`

## 故障排除

### DataGenerator 入口点未被调用

**症状**: 运行 `runData` 时没有看到 `[my_mod]` 的输出信息

**解决**:
1. 确认 [fabric.mod.json](./fabric-1.20.1/src/main/resources/fabric.mod.json) 中包含 `"fabric-datagen"` 入口点
2. 检查 Java 包装器类名是否正确: `com.example.fabric1201.datagen.DataGeneratorSetup`
3. 清理构建: `.\gradlew.bat clean`
4. 重新编译: `.\gradlew.bat :fabric-1.20.1:build`

### "Method not found" 异常

**症状**: 看到 "Method not found - trying alternative approach"

**解决**:
- Clojure 的函数名 `register-data-generators!` 被转换为 `register_data_generators_BANG_`
- 检查是否使用了错误的方法名
- 查看输出中的"Available methods"列表

### JSON 文件未生成

**症状**: `src/main/resources` 下没有新的 JSON 文件

**解决**:
1. 验证 `src/main/resources` 目录存在且有写权限
2. 查看完整输出: `.\gradlew.bat :fabric-1.20.1:runData --info`
3. 检查是否有编译错误
4. 尝试 `clean` + `runData`: `.\gradlew.bat clean :fabric-1.20.1:runData`

### 与 Forge mod-id 不一致

**症状**: Fabric 生成的 JSON 使用了错误的 mod-id

**解决**:
1. 确认在 [config/modid.clj](../core/src/main/clojure/my_mod/config/modid.clj) 中修改了 `MOD-ID`
2. 这个文件被核心模块使用，影响所有平台
3. 无需分别为 Fabric 和 Forge 配置不同的 mod-id

## 项目结构

```
fabric-1.20.1/src/main/
├── clojure/my_mod/fabric1201/datagen/
│   └── setup.clj                (Clojure 入口)
│
├── java/com/example/fabric1201/datagen/
│   └── DataGeneratorSetup.java  (Java 包装器)
│
└── resources/
    └── fabric.mod.json          (入口点配置)
```

## 高级用法

### 手动调用 DataGenerator

如果需要手动调用数据生成（例如自定义脚本），可以：

```clojure
(require '[my-mod.fabric1201.datagen.setup :as dg])

;; 创建 provider 实例
(def providers (dg/create-providers generator exfile-helper))

;; 手动执行
(doseq [provider providers]
  (.run provider cache))
```

### 扩展现有 Providers

如果需要添加自定义的 DataGenerator：

1. 创建新模块: `my-mod.datagen.custom-provider`
2. 在 `register-data-generators!` 中注册:
   ```clojure
   (.addProvider generator (custom-provider/->CustomProvider generator exfile-helper))
   ```

## 已知限制

- Fabric DataGenerator 仅在 `runData` / `genSources` 任务时运行，不会在正常游戏运行时执行
- 与 Forge DataGenerator 不同，Fabric 没有条件依赖或存在检查（ExistingFileHelper 通常为 null）

## 下一步

1. **对所有平台运行数据生成**:
   ```bash
   .\gradlew.bat :forge-1.16.5:runData :forge-1.20.1:runData :fabric-1.20.1:runData
   ```

2. **验证所有输出**:
   - 检查 `forge-1.16.5/src/main/resources/assets/my_mod/blockstates/`
   - 检查 `forge-1.20.1/src/main/resources/assets/my_mod/blockstates/`
   - 检查 `fabric-1.20.1/src/main/resources/assets/my_mod/blockstates/`

3. **统一资源**:
   - 所有平台生成的 JSON 内容相同（只使用中央 mod-id 配置）
   - 可以选择在构建中共用资源文件，或分别维护

## 参考

- [Fabric Datagen API](https://fabricmc.net/wiki/documentation:datagen)
- [Fabric Loom 文档](https://github.com/FabricMC/fabric-loom)
- [本地 DataGenerator 指南](./DATAGENERATOR_GUIDE_CN.md)
