# Build and AOT strategy

All platform builds use the single `:platform` project and a selected target.

Examples:

- `.`\\scripts\\target-gradle.ps1 forge-1.20.1``
- `.`\\scripts\\target-gradle.ps1 fabric-1.20.1``
- `cmd /c .\gradlew.bat verifyCurrentPlatforms`

AOT is exhaustive for each selected source component. The build derives the namespace set directly from its Clojure source roots, so moves and renames need no separate list update.

Datagen output goes under `platform/build/targets/<target-id>/platform/generated/datagen/<target-id>/` with `META-INF/academy-datagen-hashes.json`. `runData` / `runDatagen` finalize by writing the manifest, and `compareDatagenParityManifests` compares targets in the same `datagenParityGroup` after matrix jobs collect or preserve those outputs. Parity never belongs in duplicated source directories.
