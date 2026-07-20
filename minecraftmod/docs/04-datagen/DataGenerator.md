# DataGenerator

DataGen 随 `:platform` target 运行。目标由 `-PplatformTarget=<target-id>` 显式选择，配置来自 `platform-targets.json`。

## Source ownership

- 共享 Minecraft DataGen 逻辑：`platform-src/minecraft/version/mc-1201/src/main/clojure/cn/li/mc1201/datagen/`
- Forge provider glue：`platform-src/loader/forge/src/main/clojure/cn/li/forge1201/datagen/`
- Fabric provider glue：`platform-src/loader/fabric/src/main/clojure/cn/li/fabric1201/datagen/`
- Loader Java entrypoints：对应 `platform-src/loader/<loader>/src/main/java/`

## Commands

- Forge：`cmd /c .\gradlew.bat :platform:runData "-PplatformTarget=forge-1.20.1"`
- Fabric：`cmd /c .\gradlew.bat :platform:runDatagen "-PplatformTarget=fabric-1.20.1"`

## Output

生成内容写入 `platform-target/build/generated/datagen/<target-id>/`。同一 parity group 的比较由 CI matrix 或外部脚本读取 build 输出完成，不把 DataGen 结果写回 `platform-src`。
