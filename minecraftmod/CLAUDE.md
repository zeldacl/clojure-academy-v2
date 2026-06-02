# CLAUDE.md（指针）

**Agent 与工具链约定正文（唯一来源）**：[docs/dev/AGENT_AND_TOOLING.md](docs/dev/AGENT_AND_TOOLING.md)

请在该文件中阅读构建命令、模块红线、校验方式及 Clojure 编码原则。**勿在本文件重复条文**；增删改规则只改 `AGENT_AND_TOOLING.md`。

编写或修改 `mcmod` / `ac` / 平台 Clojure 时，优先打开正文中的：

- [Clojure 设计与实现原则（强制）](docs/dev/AGENT_AND_TOOLING.md#clojure-设计与实现原则强制)（10 条）
- [P.I.C.A.S.O.（毕加索原则）](docs/dev/AGENT_AND_TOOLING.md#fp-通用原则--picaso毕加索原则)
- [S.I.D.E.（边际原则）](docs/dev/AGENT_AND_TOOLING.md#clojure-专属原则--side边际原则)

相关：`[PROJECT_LAYOUT.md](docs/01-overview/PROJECT_LAYOUT.md)`、`[TOP_LEVEL_STATE_GOVERNANCE.md](docs/dev/TOP_LEVEL_STATE_GOVERNANCE.md)`
