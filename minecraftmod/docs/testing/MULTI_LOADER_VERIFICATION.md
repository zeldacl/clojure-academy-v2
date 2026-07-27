# Multi-loader verification

Current supported targets are declared in `platform-catalog.json`.

Required local gate:

```text
cmd /c .\gradlew.bat verifyCurrentPlatforms
```

For target-specific checks, run a single selected target:

```text
.`\\scripts\\target-gradle.ps1 forge-1.20.1`
.`\\scripts\\target-gradle.ps1 fabric-1.20.1`
```

Do not use old root module commands. Do not add a new real target only to test architecture expansion; use synthetic catalog/sourceSet/capability fixtures instead.
