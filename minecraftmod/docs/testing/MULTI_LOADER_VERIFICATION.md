# Multi-loader verification

Current supported targets are declared in `platform-catalog.json`.

Required local gate:

```text
cmd /c .\gradlew.bat verifyCurrentPlatforms
```

For target-specific checks, run a single selected target:

```text
cmd /c .\gradlew.bat :platform:compileJava :platform:compileClojure "-PplatformTarget=forge-1.20.1"
cmd /c .\gradlew.bat :platform:compileJava :platform:compileClojure "-PplatformTarget=fabric-1.20.1"
```

Do not use old root module commands. Do not add a new real target only to test architecture expansion; use synthetic catalog/sourceSet/capability fixtures instead.
