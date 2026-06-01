# CLAUDE.md（指针）

本仓库中 **Agent 与工具链约定的正文** 已迁至：

**[docs/dev/AGENT_AND_TOOLING.md](docs/dev/AGENT_AND_TOOLING.md)**

请从该文件阅读构建命令、模块红线与校验方式。

## Clojure 设计与实现原则（强制）

编写或修改 `mcmod` / `ac` / 平台 Clojure 代码时遵守：

1. **函数式风格**：优先纯函数与不可变数据；副作用集中在显式边界（命名或模块上可识别），避免可变共享状态与命令式流程控制。
2. **Clojure 最佳实践**：遵循项目既有命名与抽象（`defprotocol`、数据驱动 DSL、threading macro 等）；不引入与 idiomatic Clojure 相悖的 Java 式层次或样板。
3. **最精简代码**：在满足可读性的前提下删冗余；不为“将来可能”增加分支、配置或抽象。
4. **单一最佳设计**：同一问题只保留一套方案；禁止新旧架构并存、双路径注册或“过渡层”长期留存。
5. **无薄封装残留**：删除仅转发一层的 wrapper、镜像模块与无增量的 indirection；逻辑应落在正确层级的一次实现上。
6. **不兼容旧代码与存档**：重构时不保留旧 API、迁移分支或读档兼容；直接改数据格式与注册路径，由调用方与资源一并更新。
7. **禁止冗余代码**：不得保留死代码、重复实现、未使用的 def/var 与仅为占位的大段注释块；发现即删或合并。
8. **相似模式须抽象**：同一文件或邻近模块内重复出现的结构（注册、校验、网络编解码、GUI 槽位等）应提取为共享函数或宏，禁止复制粘贴多份近似逻辑。
9. **目录与文件须合规**：新建或移动源码前对照 [docs/01-overview/PROJECT_LAYOUT.md](docs/01-overview/PROJECT_LAYOUT.md) 与模块既有子域划分（如 `ac/terminal`、`ac/wireless`）；命名空间路径与目录一一对应，禁止在根目录或随意子包下堆放“临时”文件。
10. **慎用顶层全局变量**：非必要不得新增命名空间级 `def` / `defonce` / `^:private def`（含可变 atom 与“方便缓存”的单例）；优先参数传递、显式 owner（world/player/session 等）或不可变注册表。若确须保留，须在代码旁注明**无法编译**或**无法实现功能**的具体原因；可变顶层状态另见 [docs/dev/TOP_LEVEL_STATE_GOVERNANCE.md](docs/dev/TOP_LEVEL_STATE_GOVERNANCE.md)，新增须跑 `auditTopLevelMutableState` 并更新白名单（如适用）。
