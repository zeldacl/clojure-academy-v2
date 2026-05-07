# 版本演进策略

## Purpose

约束 Loader 与 Minecraft 版本扩展时的模块命名、目录组织和差异放置方式，避免版本判断散落到共享层。

## 模块命名建议

推荐按 `loader-mcVersion` 命名：

- `forge-1.20.1`
- `forge-1.21.x`
- `neoforge-1.20.1`
- `neoforge-1.21.x`
- `fabric-1.20.1`
- `fabric-1.21.x`

## 差异放置规则

### 应放在版本模块内

- Loader 入口类
- 版本特有 API 适配
- registry / event / network / GUI / datagen / config 桥接
- 客户端初始化差异
- 构建依赖与运行任务差异

### 不应回流到共享层

- `if version == ...` 形式的业务逻辑
- 对 Minecraft 原生类的直接调用
- Loader 专有概念泄漏到 `ac` / `mcmod`

## 版本族策略

建议按“平台族”看待版本升级：

- Forge 家族：保留一组相对对称的 `forge-*` 模块。
- NeoForge 家族：以 Forge 平台模板为主要参照。
- Fabric 家族：保持自身三入口（main/client/datagen）结构，但命名规范与共享契约保持一致。

## 升级路径建议

### 仅升级 Minecraft 小版本

若 Loader API 与共享契约变化不大：

- 新建对应 `loader-newVersion` 模块。
- 复制上一个版本的平台模块骨架。
- 只修改平台 API 适配、依赖版本、构建参数。

### 切换 Loader（例如 Forge → NeoForge）

- 共享层不动。
- 以最近的 Forge 模块为模板，替换 Loader API 接线。
- 对 network / event / config / datagen 做重点校验。

## 文档同步要求

每增加一个正式受支持的平台或版本，必须同步更新：

- `docs/README.md`
- 本目录相关文档
- `docs/dev/ADD_NEW_LOADER_OR_VERSION.md`
- `docs/testing/MULTI_LOADER_VERIFICATION.md`
- 根构建或 CI 中的验证矩阵说明
