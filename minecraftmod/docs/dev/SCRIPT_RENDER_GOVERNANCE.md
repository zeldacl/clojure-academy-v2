# Script Render Governance

Script rendering code crosses `ac`, `mcmod`, Minecraft-version components and Loader glue. Keep ownership explicit.

## Compile gates

```powershell
.\gradlew.bat :ac:compileClojure :mcmod:compileClojure
.\gradlew.bat :platform:compileClojure "-PplatformTarget=forge-1.20.1"
.\gradlew.bat :platform:compileClojure "-PplatformTarget=fabric-1.20.1"
.\gradlew.bat verifyCurrentPlatforms
```

## Rules

- Data model and render intent live in `ac` / `mcmod`.
- Minecraft render implementation lives in `platform-src/minecraft/version/mc-1201/`.
- Loader lifecycle and registration live in `platform-src/loader/<loader>/`.
- Do not add direct `ac` imports to Loader components.
