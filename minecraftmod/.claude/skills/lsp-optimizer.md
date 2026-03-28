---
name: lsp-navigator
description: 混合语言项目语义导航规范
---
# 核心准则
1. **定位优先**：寻找 `defn` 或 Java 类定义时，必须优先调用 `clojure-lsp` 或 `java-lsp` 的定义查询工具。
2. **片段读取**：通过 LSP 获取行号范围（Range）后，必须使用 `read_file` 的 `offset` 和 `limit` 参数，严禁读取超过 100 行的文件全文。
3. **混合同步**：若在 Project A 中找不到 Project B 的类，提示用户运行 `./gradlew :project-b:classes` 以刷新索引。
