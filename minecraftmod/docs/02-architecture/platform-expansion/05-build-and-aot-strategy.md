# Build and AOT strategy

All platform builds use the single `:platform` project and a selected target.

Examples:

- `cmd /c .\gradlew.bat :platform:compileJava :platform:compileClojure "-PplatformTarget=forge-1.20.1"`
- `cmd /c .\gradlew.bat :platform:compileJava :platform:compileClojure "-PplatformTarget=fabric-1.20.1"`
- `cmd /c .\gradlew.bat verifyCurrentPlatforms`

AOT manifests are owned by source components. When files move between components, update the manifest in the same change.

Datagen output goes under `platform-target/build/generated/datagen/<target-id>/` with a hash manifest. Parity comparisons belong in matrix/CI, not in duplicated source directories.
