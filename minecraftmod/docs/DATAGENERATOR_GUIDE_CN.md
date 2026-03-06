# Forge DataGenerator 实现指南

## 概述

本项目使用 **Clojure 实现** 的 Forge DataGenerator，自动生成所有 JSON 资源文件。这完全解决了 mod-id 硬编码的问题。

## 架构

### 核心模块（Core）
每个内核都是纯 Clojure 实现，使用 `gen-class` 创建 `IDataProvider` 的实现：

- **`my-mod.datagen.blockstate-provider`** - 生成 blockstate JSON
  - 输出路径: `assets/{modid}/blockstates/{block-name}.json`
  - 配置块列表: `BLOCKS_TO_GENERATE`
  - 自动使用 `modid/MOD-ID` 常量

- **`my-mod.datagen.model-provider`** - 生成方块模型 JSON
  - 输出路径: `assets/{modid}/models/block/{block-name}.json`
  - 配置模型规范: `BLOCK_MODELS` 映射

- **`my-mod.datagen.item-model-provider`** - 生成物品模型 JSON
  - 输出路径: `assets/{modid}/models/item/{item-name}.json`
  - 配置物品规范: `ITEM_MODELS` 映射

### 平台模块（Platform-Specific）

#### Forge 1.20.1
- **`my-mod.forge1201.datagen.setup`** - 事件监听器
- **`com.example.my_mod1201.datagen.DataGeneratorSetup`** (Java wrapper)
  - @Mod.EventBusSubscriber 注解
  - 委托给 Clojure 实现

#### Fabric 1.20.1
- **`my-mod.fabric1201.datagen.setup`** - DataGenerator 设置
- 使用 Fabric 的 DataGeneratorEntrypoint

## 使用方法

### 1. 编译项目
确保项目编译无误：
```bash
cd minecraftmod
.\gradlew.bat clean build
```

### 2. 执行 DataGenerator

#### Forge 1.20.1
```bash
.\gradlew.bat :forge-1.20.1:runData
```

#### Fabric 1.20.1
```bash
.\gradlew.bat :fabric-1.20.1:runData
```

或使用通用的 `data` 运行配置：
```bash
.\gradlew.bat :forge-1.20.1:runData --console=plain
```

#### 输出示例
```
> Task :forge-1.20.1:runData
[my_mod] Registering BlockState DataGenerator...
[my_mod] Registering Block Model DataGenerator...
[my_mod] Registering Item Model DataGenerator...
[my_mod] DataGenerator setup complete!

Generated blockstate: assets/my_mod/blockstates/matrix.json
Generated blockstate: assets/my_mod/blockstates/windgen_main.json
...
Generated model: assets/my_mod/models/block/matrix.json
Generated model: assets/my_mod/models/block/windgen_main.json
...
Generated item model: assets/my_mod/models/item/wafer.json
Generated item model: assets/my_mod/models/item/tutorial.json
...

BUILD SUCCESSFUL
```

### 3. 验证生成的文件
生成的文件位于:
```
forge-1.20.1/src/main/resources/assets/my_mod/
├── blockstates/
│   ├── matrix.json
│   ├── windgen_main.json
│   ├── windgen_pillar.json
│   ├── windgen_base.json
│   ├── windgen_fan.json
│   ├── solar_gen.json
│   ├── phase_gen.json
│   └── reso_ore.json
├── models/
│   ├── block/
│   │   ├── matrix.json
│   │   ├── windgen_main.json
│   │   └── ...
│   └── item/
│       ├── wafer.json
│       ├── tutorial.json
│       └── ...
```

## 配置 mod-id

所有生成的文件都使用中央配置的 `modid/MOD-ID` 常量：

### 1. 修改 mod-id (仅需一处)
编辑 [core/src/main/clojure/my_mod/config/modid.clj](../core/src/main/clojure/my_mod/config/modid.clj)：

```clojure
(def ^:const MOD-ID
  "The primary mod identifier..."
  (or (System/getenv "MOD_ID") "my_mod"))  ; 改为你的 mod-id
```

### 2. 或使用环境变量
```bash
# Windows PowerShell
$env:MOD_ID="custom_mod"
.\gradlew.bat :forge-1.20.1:runData

# Linux/macOS bash
MOD_ID=custom_mod ./gradlew :forge-1.20.1:runData
```

### 3. 重新生成 JSON
```bash
.\gradlew.bat :forge-1.20.1:runData
```

所有 JSON 文件都会自动使用新的 mod-id。

## 添加新的方块/物品

### 添加新方块
1. 在 [core/src/main/clojure/my_mod/datagen/blockstate_provider.clj](../core/src/main/clojure/my_mod/datagen/blockstate_provider.clj) 中的 `BLOCKS_TO_GENERATE` 列表中添加块名称
2. 在 [core/src/main/clojure/my_mod/datagen/model_provider.clj](../core/src/main/clojure/my_mod/datagen/model_provider.clj) 中的 `BLOCK_MODELS` 映射中添加模型规范
3. 运行 `.\.gradlew.bat :forge-1.20.1:runData` 生成 JSON

### 添加新物品
1. 在 [core/src/main/clojure/my_mod/datagen/item_model_provider.clj](../core/src/main/clojure/my_mod/datagen/item_model_provider.clj) 中的 `ITEM_MODELS` 映射中添加物品规范
2. 运行 `.\.gradlew.bat :forge-1.20.1:runData` 生成 JSON

## 工作流程

```
用户修改 Clojure datagen 配置
         ↓
运行 ./gradlew :forge-1.16.5:runData
         ↓
Forge 加载 @Mod.EventBusSubscriber 事件
         ↓
Java 包装器调用 Clojure 实现
         ↓
Clojure providers 生成 JSON 到 src/main/resources
         ↓
Gradle 将 JSON 包含在构建中
```

## 故障排除

### "Failed to load Clojure DataGenerator setup"
- 确保 [forge-1.20.1/src/main/clojure/my_mod/forge1201/datagen/setup.clj](./setup.clj) 正确编译
- 检查 `build.gradle` 中的 clojure 源路径配置
- 运行 `.\gradlew.bat clean build` 重新编译

### JSON 文件未生成
- 检查输出目录权限
- 验证 `src/main/resources` 目录存在
- 查看完整的 gradle 输出: `.\gradlew.bat :forge-1.16.5:runData --info`

### mod-id 未更新
- 验证 `modid/MOD-ID` 已正确修改或设置环境变量
- 删除旧的 JSON 文件再次运行 `runData`
- 清理构建: `.\gradlew.bat clean`

## 项目结构

```
core/src/main/clojure/my_mod/
├── config/
│   └── modid.clj                    ← 中央 mod-id 配置
└── datagen/
    ├── blockstate_provider.clj      ← 生成 blockstate JSON
    ├── model_provider.clj           ← 生成方块模型 JSON
    └── item_model_provider.clj      ← 生成物品模型 JSON

forge-1.20.1/src/main/
├── clojure/my_mod/forge1201/datagen/
│   └── setup.clj                    ← Clojure 事件监听器
└── java/com/example/my_mod1201/datagen/
    └── DataGeneratorSetup.java      ← Java 包装器（@Mod.EventBusSubscriber）

fabric-1.20.1/src/main/
├── clojure/my_mod/fabric1201/datagen/
│   └── setup.clj                    ← Clojure DataGenerator 设置
└── java/com/example/my_modfabric/datagen/
    └── DataGeneratorSetup.java      ← Java 入口点
```

## 优势

✅ **零硬编码** - 所有 mod-id 通过中央配置管理  
✅ **纯 Clojure 实现** - 保持项目一致性  
✅ **完全自动化** - 修改配置后自动生成所有 JSON  
✅ **易于维护** - 添加新的方块/物品只需修改 Clojure 映射  
✅ **多平台支持** - 同一实现支持 Forge 1.20.1 和 Fabric 1.20.1  
✅ **版本无关** - JSON 格式自动适应 Minecraft 版本

## 下一步

1. 运行 `.\.gradlew.bat :forge-1.20.1:runData` 生成初始 JSON
2. 验证 `forge-1.20.1/src/main/resources/assets/my_mod/blockstates/` 中的文件
3. 根据需要修改 Clojure datagen 配置
4. 重新运行 `runData` 更新所有 JSON 文件
