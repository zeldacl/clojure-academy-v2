# Build and AOT strategy

All platform builds use the single `:platform` project and a selected target.

Examples:

- `cmd /c .\gradlew.bat :platform:compileJava :platform:compileClojure "-PplatformTarget=forge-1.20.1"`
- `cmd /c .\gradlew.bat :platform:compileJava :platform:compileClojure "-PplatformTarget=fabric-1.20.1"`
- `cmd /c .\gradlew.bat verifyCurrentPlatforms`

AOT manifests are owned by source components. When files move between components, update the manifest in the same change.

Datagen output goes under `platform-target/build/generated/datagen/<target-id>/` with `META-INF/academy-datagen-hashes.json`. `runData` / `runDatagen` finalize by writing the manifest, and `compareDatagenParityManifests` compares targets in the same `datagenParityGroup` after matrix jobs collect or preserve those outputs. Parity never belongs in duplicated source directories.
