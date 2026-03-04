# BlockState DataProvider 架构升级

## 概述

完成了Forge 1.20.1 DataProvider的架构升级，实现了**定义与实现分离**的设计模式，为未来支持多个Forge版本奠定基础。

## 架构设计

### 三层结构

```
┌─────────────────────────────────────────────────────┐
│  平台实现层 (Platform-specific)                      │
├─────────────────────────────────────────────────────┤
│  forge-1.20.1/datagen/                             │
│  ├── blockstate_provider.clj (Forge DataProvider)  │
│  ├── model_provider.clj (Forge DataProvider)       │
│  └── setup.clj (配置)                              │
└─────────────────────────────────────────────────────┘
                        ↑
                   调用、依赖
                        ↓
┌─────────────────────────────────────────────────────┐
│  定义层 (Platform-independent)                      │
├─────────────────────────────────────────────────────┤
│  core/block/blockstate_definition.clj               │
│  ├── BlockStateDefinition (数据结构)               │
│  ├── SIMPLE_BLOCKS (定义)                          │
│  ├── NODE_BLOCKS (定义)                            │
│  └── 查询接口 (get-block-state-definition 等)      │
└─────────────────────────────────────────────────────┘
                        ↑
                依赖 (零依赖)
                        ↓
┌─────────────────────────────────────────────────────┐
│  共享依赖 (Shared)                                  │
├─────────────────────────────────────────────────────┤
│  - Clojure 1.11.1                                   │
│  - clojure.data.json                                │
└─────────────────────────────────────────────────────┘
```

## 关键组件

### 1. 定义层 (core/block/blockstate_definition.clj)

**职责**：定义所有block的BlockState结构

**核心数据结构**：
```clojure
(defrecord BlockStatePart
  [condition     ; 应用条件或nil
   models])      ; model列表

(defrecord BlockStateDefinition
  [registry-name ; block的registry名称
   properties    ; BlockState属性定义
   parts])       ; BlockStatePart列表
```

**定义示例**：
```clojure
:node-basic 
(BlockStateDefinition.
 "node_basic"
 {:energy {:min 0 :max 4}
  :connected {:type :boolean}}
 [{:condition nil :models [...]}
  {:condition {:energy "0"} :models [...]}
  ...])
```

**查询接口**：
- `get-block-state-definition(block-key)` - 获取单个定义
- `get-all-definitions()` - 获取所有定义
- `is-multipart-block?(definition)` - 判断是否multipart
- `get-definitions-for-platform(platform)` - 按平台筛选

### 2. Forge实现层

#### blockstate_provider.clj
- **作用**：生成blockstate JSON文件
- **依赖**：blockstate-definition (core中的定义)
- **过程**：
  1. 获取所有block定义
  2. 调用`blockstate-definition->json`转换
  3. 写入assets/my_mod/blockstates/*.json

#### model_provider.clj
- **作用**：生成model JSON文件
- **依赖**：blockstate-definition (从定义中提取model)
- **过程**：
  1. 从blockstate定义中提取所有引用的models
  2. 为每个model生成JSON
  3. 写入assets/my_mod/models/block/*.json

## 优势

### 1. 代码复用
- 新增Fabric 1.20.1支持时，**直接复用** blockstate_definition.clj
- 只需写fabric版的DataProvider实现
- 避免Java/Clojure中的重复定义

### 2. 单一真实来源 (Single Source of Truth)
- BlockState结构定义只在一个地方
- 修改定义自动影响所有平台

### 3. 类型安全
- 使用Clojure record明确数据结构
- 避免手动拼接JSON的错误

### 4. 易于维护
- 定义与实现清晰分离
- 每个文件职责单一
- 易于理解和修改

### 5. 扩展性
```
添加新平台时的工作量：
旧方案: 手动复制整个datagen目录 + 修改API调用
新方案: 创建fabric版blockstate_provider.clj使用相同的定义
      + fabric版model_provider.clj使用相同的定义
```

## 使用示例

### 添加新Forge版本 (如forge-1.21.1)

1. 复制目录：
```bash
forge-1.20.1/src/main/clojure/my_mod/forge1201/
  → forge-1.21.1/src/main/clojure/my_mod/forge1211/
```

2. 修改命名空间和package

3. **不需要修改blockstate定义** - 所有forge版本共享core中的定义

### 添加新block

只需在 `core/block/blockstate_definition.clj` 中添加：

```clojure
(def MY_BLOCKS
  {:my-new-block
   (BlockStateDefinition.
    "my_new_block"
    {}
    [{:condition nil :models ["my_mod:block/my_new_block"]}])})
```

Forge DataProvider自动生成对应的JSON。

## 信息流

```
(1) BlockState定义
    core/blockstate_definition.clj
         ↓
    ┌────┴────┬────────────────┐
    ↓         ↓                ↓
(2) blockstate_provider  model_provider  ...
    ↓                         ↓
(3) blockstates/*.json   models/block/*.json
```

## 迁移说明

### 旧方案 vs 新方案

| 方面 | 旧方案 | 新方案 |
|------|--------|--------|
| BlockState定义 | 分散在各DataProvider中 | 集中在core/blockstate_definition.clj |
| 新平台支持 | 完整复制datagen目录 | 复用定义，只写实现 |
| 修改blockstate | 需改多个地方 | 只改core定义 |
| 代码复用 | 低 | 高 |

### 何时使用

- **core/blockstate_definition.clj**：
  - ✅ 所有block的结构定义
  - ✅ BlockState属性说明
  - ❌ Minecraft/Forge特定API调用

- **forge-1.20.1/datagen/*.clj**：
  - ✅ Forge DataProvider实现
  - ✅ JSON文件生成逻辑
  - ❌ 通用block结构定义

## 后续优化方向

1. **Java BlockStateProvider**：当前使用Clojure gen-class实现，可考虑用Java重写以获得更好的IDE支持

2. **Model配置**：目前model-name->json使用简化逻辑，可扩展支持复杂的model变体

3. **平台适配器**：创建通用的datagen模板，减少新平台支持的代码量

4. **验证层**：添加blockstate定义的验证，确保所有引用的models存在

## 文件清单

### Core层
- `core/src/main/clojure/my_mod/block/blockstate_definition.clj` (新建)
- `core/src/main/clojure/my_mod/datagen/` (旧的可备份或删除)

### Forge-1.20.1层
- `forge-1.20.1/src/main/clojure/my_mod/forge1201/datagen/blockstate_provider.clj` (升级)
- `forge-1.20.1/src/main/clojure/my_mod/forge1201/datagen/model_provider.clj` (升级)
- `forge-1.20.1/src/main/java/my_mod/datagen/BlockStateGen.java` (新建, 参考框架)

## 下一步

- [ ] 测试runData任务是否能正确生成JSON
- [ ] 添加Fabric 1.20.1 DataProvider实现
- [ ] 创建文档说明如何添加新block
- [ ] 考虑将blockstate定义与block注册联系起来
