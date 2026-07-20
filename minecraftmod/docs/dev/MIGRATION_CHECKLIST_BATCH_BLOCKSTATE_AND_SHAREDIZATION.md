# Migration Checklist (Batch: Fabric blockstate parity + sharedization)

## Scope
- Goal: achieve Forge/Fabric functional parity for Fabric datagen blockstates/models/items while moving duplicated logic to `mc-1.20.1`.
- Validation baseline used in this batch: `:platform:compileClojure`, `:platform:runDatagen`, `verifyArchitectureBoundaries`, `verifyCurrentPlatforms`.

## Migrated to `mc-1.20.1` (shared now)

### New shared helpers introduced
1. `platform-src/minecraft/version/mc-1201/src/main/clojure/cn/li/mc1201/datagen/blockstate_support.clj`
   - Reason: blockstate/model JSON assembly logic is loader-agnostic and should not be duplicated in Forge/Fabric providers.
   - Risk: low (pure data-shaping helpers).

2. `platform-src/minecraft/version/mc-1201/src/main/clojure/cn/li/mc1201/gui/network/packet.clj`
   - Reason: EDN payload encode/decode behavior was duplicated between Forge/Fabric network bridges.
   - Change: shared `encode-payload*`/`decode-payload*` APIs for string+bytes.
   - Risk: medium (packet compatibility); covered by platform verification.

### Existing shared modules adopted by both loaders in this batch
- GUI bridge/common flow (now organized by domain subpackage):
   - `cn.li.mc1201.gui.menu.proxy`
   - `cn.li.mc1201.gui.menu.container`
   - `cn.li.mc1201.gui.provider.common`
   - `cn.li.mc1201.gui.provider.dispatcher`
   - `cn.li.mc1201.gui.registry.common`
   - `cn.li.mc1201.gui.registry.open`
   - `cn.li.mc1201.gui.screen.registry`
   - `cn.li.mc1201.gui.screen.impl`
   - `cn.li.mc1201.gui.slots.common`
   - `cn.li.mc1201.gui.slots.sync`
   - `cn.li.mc1201.gui.slots.tabbed`
   - `cn.li.mc1201.gui.init.checks`
   - `cn.li.mc1201.gui.init.orchestrator`
- Datagen common:
  - `cn.li.mc1201.datagen.resource-location`
  - `cn.li.mc1201.datagen.gson-util`
  - `cn.li.mc1201.datagen.item-registry`
  - `cn.li.mc1201.datagen.item-model-patterns`
  - `cn.li.mc1201.datagen.metadata-resolver`
  - `cn.li.mc1201.datagen.recipe-patterns`
  - `cn.li.mc1201.datagen.lang-data`
  - `cn.li.mc1201.datagen.json-util`
  - `cn.li.mc1201.datagen.DataGeneratorInterop` (Java)
- Integration/common event dispatch:
  - `cn.li.mc1201.integration.event-handlers`
- Java shared base classes used by platform wrappers:
  - `cn.li.mc1201.block.AbstractDynamicStateBlock`
  - `cn.li.mc1201.block.entity.BlockEntityRegistry`
  - `cn.li.mc1201.effect.ScriptedMobEffect`
  - `cn.li.mc1201.item.NbtBarItem`
  - `cn.li.mc1201.item.ScriptedItem`
  - `cn.li.mc1201.trigger.ModCustomTrigger`
  - `cn.li.mc1201.worldgen.PhaseLiquidPoolFeature`
  - `cn.li.mc1201.client.GuiGraphicsHelper`
  - `cn.li.mc1201.client.render.ModRenderTypes`

Reason for all above: logic is either pure algorithm/data-conversion or shared lifecycle/adapter glue that should remain single-source across loaders.

## Fabric parity completion in this batch
1. `platform-src/loader/fabric/src/main/clojure/cn/li/fabric1201/datagen/provider_factory.clj`
   - Provides Fabric provider factory glue for blockstate + block model + block-item model generation from shared blockstate definitions.
2. `platform-src/loader/fabric/src/main/clojure/cn/li/fabric1201/datagen/setup.clj`
   - Registers blockstate provider with existing lang/item/advancement/recipe providers.
3. `platform-src/loader/fabric/src/generated/resources/assets/my_mod/{blockstates,models}`
   - Datagen output now contains Fabric blockstate/model artifacts for AC blocks (including multipart/stateful models).

Reason: this was a parity gap; Forge already generated these assets.

## Kept platform-private (not moved)
1. Loader registration/event wiring entrypoints in `forge1201` and `fabric1201` namespaces/classes.
   - Reason: depends on Loader APIs and lifecycle semantics.
2. Platform-specific network transport adapters and registration calls.
   - Reason: channel/event registration differs by loader.
3. Datagen pack registration hooks (`GatherDataEvent` vs Fabric datagen entrypoint).
   - Reason: launcher/toolchain API differs.

## Verification status
- ✅ `:platform:compileClojure` passed.
- ✅ `:platform:runDatagen` passed (blockstate provider executed).
- ✅ `verifyArchitectureBoundaries` passed.
- ✅ `verifyCurrentPlatforms` passed.
- ✅ Hook checks in combined run passed (`verifyFabricHookManifest`, `verifyForgeHookCoverage`, `verifyPlatformNoBusinessHookIds`).

## Additional diagnostics (2026-05-09)
- ✅ `:platform:compileJava --stacktrace` passed (no reproducible Java blocker).
- ✅ `:platform:compileJava --stacktrace --info` passed on rerun.
- Note: earlier isolated `compileJava --info` non-zero report was transient and is not reproducible after rerun.
- ✅ Gate bundle rerun passed: `verifyForgeHookCoverage verifyPlatformHookCoverage verifyPlatformNoBusinessHookIds verifyCurrentPlatforms`.

## Follow-up candidates
1. Add automated Forge↔Fabric datagen artifact diff task for blockstates/models to make parity drift visible in CI.
2. Continue remaining file-by-file matrix batches from session plan until full repository checklist is complete.

## Next-batch progress (metadata + SPI, 2026-05-09)
- Added `docs/dev/PLATFORM_METADATA_AND_SPI_PARITY_CHECKLIST.md` to close the explicit audit gap for metadata/SPI file coverage.
- Implemented Fabric metadata normalization in this batch:
   - `fabric.mod.json` now uses `${mod_version}` / `${mod_name}` / `${mod_authors}` placeholders.
   - `platform-src/loader/fabric/build.gradle` now expands `fabric.mod.json` placeholders during `processResources`.
- Verified with gates:
   - `:platform:processResources :platform:processResources` ✅
   - `verifyCurrentPlatforms` ✅
- Important constraint captured: active Forge build currently does not expand `mods.toml` placeholders, so Forge metadata remains static literals for now (tracked in the new checklist as TODO).

## Datagen parity batch (2026-05-09)
- Added root task `verifyForgeFabricDatagenParity` for the stable shared generated-resource subsets.
- Final gate scope is intentionally limited to:
  - `data/my_mod/recipes`
  - `assets/my_mod/blockstates`
  - `assets/my_mod/lang`
- The gate now compares only already-generated outputs and passes without invoking Forge datagen / `checkClojure`.
- Item and block model parity still needs a separate follow-up batch because those generated assets remain structurally divergent between loaders.
