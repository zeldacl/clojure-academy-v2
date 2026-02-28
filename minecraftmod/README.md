# Clojure Minecraft Mod Framework

一个使用 Clojure 实现的多模组加载器 Minecraft Mod 框架，支持 **Forge 1.20.1** 和 **Fabric 1.20.1**。

## 项目特性

- ✅ **多版本支持**：Forge 1.20.1、Fabric 1.20.1
- ✅ **单一代码库**：核心逻辑只写一次，通过 multimethod 分发版本特定实现
- ✅ **纯 Clojure 实现**：99% 代码使用 Clojure，只有最小的 Java 桥接
- ✅ **声明式 GUI DSL**：使用 Clojure 宏定义 GUI，无需编写繁琐的 Java 代码
- ✅ **声明式 Block DSL**：简洁的方块定义语法，支持预设和交互处理器
- ✅ **声明式 Item DSL**：快速定义物品，支持工具、食物、护甲等多种类型
- ✅ **类型安全**：Clojure 与 Java API 无缝互操作
- ✅ **易于扩展**：添加新版本只需实现对应的 multimethod
- ✅ **REPL 友好**：支持交互式开发和热重载

Notes:
- All platforms build with Java 17.
- Core logic is in Clojure, with per-version Java adapters handling Forge/Fabric APIs.

## Build (Windows PowerShell)

```powershell
# Compile core module
./gradlew.bat :core:compileClojure

# Compile individual platforms (from platform directory)
cd forge-1.20.1
../gradlew.bat compileClojure
cd ../fabric-1.20.1
../gradlew.bat compileClojure

# Or compile all from root
cd ..
./gradlew.bat compileClojure
```

## Run dev clients
```powershell
# Forge 1.20.1 client
./gradlew :forge-1.20.1:runClient

# Fabric 1.20.1 client
./gradlew :fabric-1.20.1:runClient
```

## 快速开始：使用 DSL

### GUI DSL

```clojure
(ns my-mod.my-gui
  (:require [my-mod.gui.dsl :as dsl]))

(defonce my-slots (atom {}))

;; 声明式定义 GUI
(dsl/defgui my-custom-gui
  :title "My GUI"
  :width 176
  :height 166
  :slots [{:index 0 
           :x 80 
           :y 35
           :on-change (dsl/slot-change-handler my-slots 0)}]
  :buttons [{:id 0 
             :x 100 
             :y 60 
             :text "Clear"
             :on-click (dsl/clear-slot-handler my-slots 0)}]
  :labels [{:x 8 :y 6 :text "My GUI"}])
```

### Block DSL

```clojure
(ns my-mod.my-blocks
  (:require [my-mod.block.dsl :as bdsl]))

;; 声明式定义方块
(bdsl/defblock my-custom-block
  :material :stone
  :hardness 3.0
  :resistance 10.0
  :light-level 15
  :requires-tool true
  :harvest-tool :pickaxe
  :on-right-click (fn [data] (println "Clicked!")))
```

### Item DSL

```clojure
(ns my-mod.my-items
  (:require [my-mod.item.dsl :as idsl]))

;; 声明式定义物品
(idsl/defitem my-custom-item
  :max-stack-size 16
  :creative-tab :tools
  :durability 500
  :rarity :rare
  :on-use (fn [data] (println "Used!")))

;; 使用预设快速创建
(def my-sword
  (idsl/merge-presets
    (idsl/tool-preset :diamond 1561 8.0 -2.4)
    (idsl/rare-item-preset :epic)
    {:on-use (fn [data] (println "Special attack!"))}))
```

## What's included
- **core**: Pure Clojure namespaces with multimethod-based abstractions
  - `my-mod.core`: Init hook and game logic
  - `my-mod.registry`: Multimethod registry abstraction
  - `my-mod.blocks/items`: Factory functions and definitions
  - `my-mod.gui.api/core`: GUI abstractions and handlers
  - `my-mod.gui.dsl`: 🎨 声明式 GUI DSL 系统
  - `my-mod.gui.renderer`: 跨版本渲染抽象
  - `my-mod.gui.container`: 容器/菜单管理
  - `my-mod.gui.network`: 网络通信抽象
  - `my-mod.gui.demo`: 示例 GUI（demo、crafting、furnace、storage）
  - `my-mod.block.dsl`: 🎨 声明式 Block DSL 系统
  - `my-mod.block.demo`: 示例方块（16+ 种不同类型）
  - `my-mod.item.dsl`: 🎨 声明式 Item DSL 系统
  - `my-mod.item.demo`: 示例物品（19+ 种不同类型）
  - Shared assets (models, blockstates using vanilla textures)
  
- **forge-1.20.1**: Java @Mod entry + Clojure adapters
  - `MyMod1201.java`: Minimal @Mod bridge (20 lines)
  - `my-mod.forge1201.mod`: Complete Clojure mod implementation
  - `my-mod.forge1201.*`: Multimethod implementations for 1.20.1 API

- **fabric-1.20.1**: Fabric ModInitializer + Clojure adapters
  - `MyModFabric.java`: Minimal ModInitializer bridge (17 lines)
  - `my-mod.fabric1201.mod`: Complete Clojure mod implementation
  - `my-mod.fabric1201.*`: Multimethod implementations for Fabric API

## 文档

- [架构文档](ARCHITECTURE.md) - 系统设计和 multimethod 分发机制
- [构建指南](BUILD.md) - 编译和打包说明
- [GUI 演示](GUI_DEMO_CN.md) - GUI 功能说明
- [GUI DSL 指南](GUI_DSL_GUIDE_CN.md) - 🎨 **声明式 GUI 开发完整教程**
- [Block DSL 指南](BLOCK_DSL_GUIDE_CN.md) - 🎨 **声明式方块定义完整教程**
- [Item DSL 指南](ITEM_DSL_GUIDE_CN.md) - 🎨 **声明式物品定义完整教程**
- [Fabric 支持](FABRIC_SUPPORT_CN.md) - Fabric 模组加载器适配说明
- [迁移报告](MIGRATION_REPORT_CN.md) - Java 到 Clojure 迁移记录
- [项目总结](SUMMARY_CN.md) - 整体架构和实现总结

## Architecture

The framework uses Clojure multimethods for version dispatch:
1. Core defines abstract operations (register-item, register-block, open-gui)
2. Each Forge/Fabric adapter implements these multimethods keyed by version (`:forge-1.20.1`, `:fabric-1.20.1`)
3. Java @Mod classes handle Forge lifecycle and call Clojure hooks
4. At runtime, the correct implementation is selected based on the loaded version

## Next steps
- Add GUI/menu abstraction and per-version implementations
- Wire right-click-block to open your GUI via NetworkHooks (1.16.5+) / MenuType