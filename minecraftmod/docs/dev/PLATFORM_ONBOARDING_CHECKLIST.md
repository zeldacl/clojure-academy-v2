# Platform Target Onboarding Checklist

Use this checklist when adding or changing a platform target.

## Catalog

- [ ] Add or update one explicit entry in `platform-targets.json`.
- [ ] Declare loader, Minecraft version, Java version, source components, test components, capabilities, dependencies and artifact metadata.
- [ ] Do not infer behavior from target id string parsing.

## Source placement

- [ ] Shared platform logic goes to `platform-src/common/`.
- [ ] Minecraft API logic goes to `platform-src/minecraft/base/` or `platform-src/minecraft/version/<version>/`.
- [ ] Loader lifecycle and metadata go to `platform-src/loader/<loader>/`.

## Verification

- [ ] `.\gradlew.bat verifyCurrentPlatforms`
- [ ] `.\gradlew.bat :platform:compileJava :platform:compileClojure "-PplatformTarget=<target-id>"`
- [ ] DataGen manifest generated when the target supports datagen.
- [ ] Capability owners are unique.

## Documentation

- [ ] Update current docs only.
- [ ] Do not add archive, status snapshot, migration report or dual-track compatibility documentation.
