# 构建与 AOT 策略

## Purpose

说明当前平台模块（尤其 Forge）中与 Clojure AOT、remap、shadow、sourceSets 注入相关的设计背景、风险点与推荐演进方向。

## 当前现状

`platform-src/loader/forge/build.gradle` 当前承担了大量平台构建特殊逻辑，包括但不限于：

- 把 `ac` / `mcmod` / `api` 的源码与资源注入到自身 `sourceSets`。
- 对 Clojure 命名空间执行 `aotAll()`。
- 对输出的 class 做 `stripClojureLVT` 处理，以规避 remap 阶段兼容性问题。
- 把 `build/clojure/main` 中的 class 复制到 `build/classes/java/main` 供 Loom / Forge 运行时查找。
- 提供 bisect 任务辅助定位 `compileClojure` / `checkClojure` 失败命名空间。

Fabric 当前也采用了相似的 sourceSets 注入方式，但默认未进入根构建基线。

## 风险

1. **构建规则复制成本高**：新增 `future loader` / 新版本时容易复制大量 build logic。
2. **模块边界在构建层被拉近**：平台模块感知共享源码的物理目录结构。
3. **remap / AOT 知识集中在单个模块脚本里**：不利于多人维护。
4. **文档化不足**：后续维护者难以判断哪些逻辑是“必须”，哪些只是历史 workaround。

## 当前建议

### 短期

- 保持现有 `forge target` 构建可运行，不急于推翻。
- 优先把现有约定写成文档和模板。
- 对新增平台模块复用同一套模式，但将差异项显式化。

### 中期

将以下逻辑抽到 `buildSrc` 或统一 Gradle convention/plugin：

- 共享 sourceSets 注入规则
- `clojure { builds { main { aotAll() ... }}}`
- `stripClojureLVT`
- `copyClojureClassesToJavaOutput`
- 运行任务前置依赖配置
- 平台 compile/check bisect 辅助逻辑

### 长期

评估是否能从“平台模块物理注入共享源码”逐步演进为：

- 标准工程依赖
- 统一 AOT 输出协议
- 更清晰的 classpath / remap 责任边界

## 对新增平台模块的要求

新增 `forge-*` / `future-loader target` / `fabric-*` 模块时，必须回答以下问题：

1. 是否复用统一 build convention？
2. 该平台需要哪些 sourceSets 注入？
3. AOT 输出落在哪个目录？
4. remap 依赖哪个打包产物？
5. datagen / client / server 运行任务如何拿到 Clojure AOT 输出？
6. 是否需要平台特有的字节码兼容处理？
