# Client / Server 边界规则（平台扩展版）

## Purpose

为新增 Loader / 新版本模块明确客户端与服务端代码的边界规则，避免 `net.minecraft.client.*` 或等价 client-only API 泄漏到共享层或 dedicated server 路径。

## 基本规则

1. `ac` / `mcmod` 不得依赖 client-only API。
2. 平台模块中的 client-only 逻辑必须集中在明确的 client namespace / package 中。
3. common/main 初始化与 client 初始化必须分离。
4. dedicated server 可加载的路径上不能出现 client 类静态初始化。

## 目录建议

- `cn.li.<platform>.client.*`：客户端渲染、screen、粒子、音效等。
- `cn.li.<platform>.gui.*`：若包含 client-only screen 注册，应进一步区分 common/server/client init。
- `cn.li.<platform>.side`：物理侧检测与 client-only 解析辅助。

## 入口规则

### Forge / future loader

- 主 mod 初始化不应直接硬依赖 client 类。
- client 生命周期中再通过 side-checked 路径装载 client-only 逻辑。

### Fabric

- `ClientModInitializer` 作为 client 专用入口。
- `ModInitializer` 不得装载 client-only 类。

## 代码审查检查项

- 是否在共享层或 common path 中出现 `net.minecraft.client.*` / Loader client API。
- client screen / renderer / FX 是否只在 client entry 或 client-only namespace 中装载。
- dedicated server 启动路径上是否仍有 client 类静态引用。

## 建议的自动化校验

- 文本扫描共享层中的 client-only import。
- 对已启用平台执行 dedicated server 烟雾启动。
- 将现有 `CLIENT_SERVER_SEPARATION.md` 中的经验持续纳入平台模板与验证矩阵。
