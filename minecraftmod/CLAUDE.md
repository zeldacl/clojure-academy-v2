# CLAUDE.md（指针）

**Agent 与工具链约定正文（唯一来源）**：[docs/dev/AGENT_AND_TOOLING.md](docs/dev/AGENT_AND_TOOLING.md)

请在该文件中阅读构建命令、模块红线、校验方式及 Clojure 编码原则。**勿在本文件重复条文**；增删改规则只改 `AGENT_AND_TOOLING.md`。

编写或修改 `mcmod` / `ac` / 平台 Clojure 时，优先打开正文中的：

- [Architecture rules](docs/dev/AGENT_AND_TOOLING.md#architecture-rules)（模块边界、依赖方向、target 目录约束）
- [Required gate](docs/dev/AGENT_AND_TOOLING.md#required-gate)（`verifyCurrentPlatforms` 聚合的强制校验清单）

> 曾经指向 `AGENT_AND_TOOLING.md` 内「Clojure 设计与实现原则（10 条）」「P.I.C.A.S.O.」「S.I.D.E.」三个小节的链接已失效——`AGENT_AND_TOOLING.md` 正文中从未写入过这三节内容。在有人补写正文前，不要引用这三个名字；上面两条是当前正文里实际存在、可核实的章节。

相关：`[PROJECT_LAYOUT.md](docs/01-overview/PROJECT_LAYOUT.md)`、`[TOP_LEVEL_STATE_GOVERNANCE.md](docs/dev/TOP_LEVEL_STATE_GOVERNANCE.md)`
