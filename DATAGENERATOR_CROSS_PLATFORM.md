# 跨平台 DataGenerator 集成总结

## 概览

项目现在为**所有三个平台**（Forge 1.16.5、Forge 1.20.1、Fabric 1.20.1）提供完整的 DataGenerator 支持。所有平台使用**相同的核心实现**，通过中央配置自动生成所有 JSON 资源文件。

## 核心架构

### 共用模块（Platform-Agnostic）

三个独立的 Clojure 数据提供器，实现 Minecraft 的 `IDataProvider` 接口：

```
core/src/main/clojure/my_mod/datagen/
├── blockstate_provider.clj     ← 生成 blockstate JSON
├── model_provider.clj          ← 生成方块模型 JSON
└── item_model_provider.clj     ← 生成物品模型 JSON
```

**特点**:
- ✅ 零硬编码 - 使用 `modid/MOD-ID` 常量
- ✅ 纯 Clojure - 使用 `gen-class` + 反射
- ✅ 可复用 - 所有平台调用相同的实现

### 平台特定模块

#### Forge 1.16.5

```
forge-1.16.5/src/main/
├── clojure/my_mod/forge1165/datagen/
│   └── setup.clj                          ← 事件监听器
│
└── java/com/example/my_mod1165/datagen/
    └── DataGeneratorSetup.java            ← @Mod.EventBusSubscriber
```

**触发机制**:
- Forge 事件总线 → GatherDataEvent
- Java @Mod.EventBusSubscriber 注解
- 反射调用 Clojure

#### Forge 1.20.1

```
forge-1.20.1/src/main/
├── clojure/my_mod/forge1201/datagen/
│   └── setup.clj                          ← 事件监听器
│
└── java/com/example/my_mod1201/datagen/
    └── DataGeneratorSetup.java            ← @Mod.EventBusSubscriber
```

**触发机制**: 与 Forge 1.16.5 相同，API 适配 1.20.1

#### Fabric 1.20.1

```
fabric-1.20.1/src/main/
├── clojure/my_mod/fabric1201/datagen/
│   └── setup.clj                          ← 入口点实现
│
├── java/com/example/fabric1201/datagen/
│   └── DataGeneratorSetup.java            ← DataGeneratorEntrypoint
│
└── resources/
    └── fabric.mod.json                    ← fabric-datagen 入口点
```

**触发机制**:
- Fabric 加载器发现 fabric.mod.json 中的 `fabric-datagen` 入口点
- 调用 DataGeneratorEntrypoint.onInitializeDataGenerator()
- 反射调用 Clojure

## 快速开始

### 1. 编译项目

```bash
cd minecraftmod
.\gradlew.bat clean build
```

### 2. 生成 JSON（三选一）

**Forge 1.16.5**:
```bash
.\gradlew.bat :forge-1.16.5:runData
```

**Forge 1.20.1**:
```bash
.\gradlew.bat :forge-1.20.1:runData
```

**Fabric 1.20.1**:
```bash
.\gradlew.bat :fabric-1.20.1:runData
```

**或者全部生成**:
```bash
.\gradlew.bat :forge-1.16.5:runData :forge-1.20.1:runData :fabric-1.20.1:runData
```

### 3. 验证输出

查看相应平台的资源目录是否有生成的 JSON：

```
forge-1.16.5/src/main/resources/assets/my_mod/blockstates/
├── matrix.json
├── windgen_main.json
├── windgen_pillar.json
├── windgen_base.json
├── windgen_fan.json
├── solar_gen.json
├── phase_gen.json
└── reso_ore.json
```

## 统一配置（mod-id）

所有平台使用**同一个** mod-id 定义：

**中央配置**:
```clojure
; core/src/main/clojure/my_mod/config/modid.clj
(def ^:const MOD-ID
  (or (System/getenv "MOD_ID") "my_mod"))
```

### 修改 mod-id

**方法 1: 直接修改 Clojure**

编辑 [config/modid.clj](./core/src/main/clojure/my_mod/config/modid.clj)，将所有平台的 mod-id 改为相同的值。

**方法 2: 环境变量**

```bash
$env:MOD_ID="custom_mod"
.\gradlew.bat :forge-1.16.5:runData :forge-1.20.1:runData :fabric-1.20.1:runData
```

### 结果

修改后，所有三个平台的 JSON 文件都会自动使用新的 mod-id：

```json
{
  "variants": {
    "": { "model": "custom_mod:matrix" }
  }
}
```

## 工作流程对比

### Forge 1.16.5 / 1.20.1

```
.\gradlew :forge-1.16.5:runData
         ↓
Gradle 执行 `data` 运行配置
         ↓
Forge 初始化并发送 GatherDataEvent
         ↓
@Mod.EventBusSubscriber 拦截事件
         ↓
Java 反射调用 Clojure setup
         ↓
注册核心 providers
         ↓
生成 JSON 到 src/main/resources
```

### Fabric 1.20.1

```
.\gradlew :fabric-1.20.1:runData
         ↓
Gradle Loom 启动数据生成
         ↓
Fabric 加载器读取 fabric.mod.json
         ↓
发现 "fabric-datagen" 入口点
         ↓
实例化 DataGeneratorSetup
         ↓
调用 onInitializeDataGenerator()
         ↓
Java 反射调用 Clojure setup
         ↓
注册核心 providers
         ↓
生成 JSON 到 src/main/resources
```

## 添加新资源

### 添加新方块

在所有三个平台都使用的核心模块中修改：

1. **[blockstate_provider.clj](./core/src/main/clojure/my_mod/datagen/blockstate_provider.clj)**

```clojure
(def BLOCKS_TO_GENERATE
  ["matrix" ... "new_block"])
```

2. **[model_provider.clj](./core/src/main/clojure/my_mod/datagen/model_provider.clj)**

```clojure
(def BLOCK_MODELS
  {...
   "new_block"
   {:parent "block/cube_all"
    :textures {:all (str modid/MOD-ID ":blocks/new_block")}}})
```

3. **生成 JSON**

```bash
; 生成所有平台
.\gradlew.bat :forge-1.16.5:runData :forge-1.20.1:runData :fabric-1.20.1:runData
```

### 添加新物品

编辑 **[item_model_provider.clj](./core/src/main/clojure/my_mod/datagen/item_model_provider.clj)**：

```clojure
(def ITEM_MODELS
  {...
   "new_item"
   {:parent "item/generated"
    :textures {:layer0 (str modid/MOD-ID ":items/new_item")}}})
```

然后生成 JSON。

## 配置文件汇总

### Build Configuration

**forge-1.16.5/build.gradle**:
```gradle
minecraft {
  runs {
    data {
      args '--client', '--server', '--mod', 'my_mod', '--all'
      ...
    }
  }
}

task runData { ... }
```

**forge-1.20.1/build.gradle**:
- 相同配置，Gradle 版本升级到 5.1+

**fabric-1.20.1/build.gradle**:
```gradle
task runData(dependsOn: genSources) {
  group = 'fabric'
}
```

### Mod 配置

**fabric-1.20.1/src/main/resources/fabric.mod.json**:
```json
{
  "entrypoints": {
    "fabric-datagen": [
      "com.example.fabric1201.datagen.DataGeneratorSetup"
    ]
  }
}
```

## 文件清单

### 核心模块（所有平台共用）

```
core/src/main/clojure/my_mod/
├── config/
│   └── modid.clj                ← 中央 mod-id 配置
│
└── datagen/
    ├── blockstate_provider.clj  ← IDataProvider impl
    ├── model_provider.clj       ← IDataProvider impl
    └── item_model_provider.clj  ← IDataProvider impl
```

### Forge 1.16.5

```
forge-1.16.5/src/main/
├── clojure/my_mod/forge1165/datagen/setup.clj
├── java/com/example/my_mod1165/datagen/DataGeneratorSetup.java
└── (forge-1.16.5/build.gradle - 已更新)
```

### Forge 1.20.1

```
forge-1.20.1/src/main/
├── clojure/my_mod/forge1201/datagen/setup.clj
├── java/com/example/my_mod1201/datagen/DataGeneratorSetup.java
└── (forge-1.20.1/build.gradle - 已更新)
```

### Fabric 1.20.1

```
fabric-1.20.1/src/main/
├── clojure/my_mod/fabric1201/datagen/setup.clj
├── java/com/example/fabric1201/datagen/DataGeneratorSetup.java
├── resources/fabric.mod.json (已添加 fabric-datagen 入口点)
└── (fabric-1.20.1/build.gradle - 已更新)
```

### 文档

```
minecraftmod/
├── DATAGENERATOR_GUIDE_CN.md        ← Forge 使用指南
├── FABRIC_DATAGENERATOR_CN.md       ← Fabric 使用指南
└── DATAGENERATOR_IMPLEMENT.md       ← 实现细节
```

## 优势总结

✅ **单一源码主义** - 核心 DataGenerator 代码只维护一份  
✅ **跨平台一致性** - Forge 和 Fabric 生成相同的 JSON  
✅ **零配置冲突** - mod-id 在一个地方定义，所有平台自动更新  
✅ **完全自动化** - 修改配置后一个命令生成所有平台的资源  
✅ **易于维护** - 添加新资源只需修改 Clojure 映射  
✅ **纯 Clojure 实现** - 保持项目语言一致  
✅ **官方最佳实践** - 采用 Forge 和 Fabric 的推荐方式  

## 故障排除

### 通用问题

**问题**: JSON 未生成  
**解决**: 
1. `.\gradlew.bat clean build` 重新编译
2. 检查输出目录权限
3. 查看完整输出: `--info` 标志

**问题**: mod-id 不统一  
**解决**: 修改后立即重新生成所有平台

**问题**: 编译错误  
**解决**: 检查 Clojure 语法，确认数据提供器模块编译无误

### 平台特定问题

**Forge 问题**: 参考 [DATAGENERATOR_GUIDE_CN.md](./DATAGENERATOR_GUIDE_CN.md)  
**Fabric 问题**: 参考 [FABRIC_DATAGENERATOR_CN.md](./FABRIC_DATAGENERATOR_CN.md)

## 下一步

1. **编译项目**:
   ```bash
   .\gradlew.bat clean build
   ```

2. **生成所有平台的 JSON**:
   ```bash
   .\gradlew.bat :forge-1.16.5:runData :forge-1.20.1:runData :fabric-1.20.1:runData
   ```

3. **验证**:
   - 检查每个平台的 `assets/my_mod/blockstates/` 目录
   - 确保所有 JSON 文件使用正确的 mod-id

4. **开发**:
   - 添加新的方块/物品时，只需修改核心 datagen 模块
   - 每个平台自动生成相同的 JSON
   - 享受零配置冲突、全自动化的开发体验

---

**项目现已完成跨平台 DataGenerator 集成！** 🎉

所有平台（Forge 1.16.5、Forge 1.20.1、Fabric 1.20.1）现在都能用代码自动生成 JSON 资源，完全解决了 mod-id 硬编码问题。
