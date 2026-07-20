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
- Hash manifest only：`cmd /c .\gradlew.bat :platform:generateDatagenHashManifest "-PplatformTarget=<target-id>"`
- Parity compare：`cmd /c .\gradlew.bat compareDatagenParityManifests`

## Output

生成内容写入 `platform-target/build/generated/datagen/<target-id>/`。每个 target 的 hash manifest 位于 `platform-target/build/generated/datagen/<target-id>/META-INF/academy-datagen-hashes.json`。

同一 `datagenParityGroup` 的比较由 CI matrix 先分别生成各 target 输出，再运行 `compareDatagenParityManifests` 读取 manifest。DataGen 结果不写回 `platform-src`，也不提交生成残留。
