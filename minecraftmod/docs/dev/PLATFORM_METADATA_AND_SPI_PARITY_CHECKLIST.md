# Platform Metadata & SPI Parity Checklist (Forge 1.20.1 vs Fabric 1.20.1)

Date: 2026-05-09  
Scope: planned gap category **metadata resources + ServiceLoader SPI parity**

## 1) Metadata files in scope

- Fabric: `platform-src/loader/fabric/src/main/resources/fabric.mod.json`
- Forge: `platform-src/loader/forge/src/main/resources/META-INF/mods.toml`
- SPI service descriptors:
  - `platform-src/loader/fabric/src/main/resources/META-INF/services/cn.li.mcmod.platform.spi.platform target bootstrap`
  - `platform-src/loader/forge/src/main/resources/META-INF/services/cn.li.mcmod.platform.spi.platform target bootstrap`
- Pack metadata:
  - `platform-src/loader/forge/src/main/resources/pack.mcmeta`
  - `ac/src/main/resources/data/my_mod/pack.mcmeta` (data pack metadata)

## 2) Parity matrix

| Area | Forge | Fabric | Status | Notes |
|---|---|---|---|---|
| Mod ID | `my_mod` | `my_mod` | ✅ aligned | core identity matches |
| Entry bootstrap | Forge Mod class + SPI | Fabric main/client/datagen + SPI | ✅ semantic parity | loader mechanism differs by design |
| SPI bootstrap descriptor | `cn.li.forge1201.platform.spi.Forge1201platform target bootstrap` | `cn.li.fabric1201.platform.spi.Fabric1201platform target bootstrap` | ✅ aligned | both expose same SPI contract |
| Java requirement | implicit via Forge toolchain (17+) | explicit `java >=17` | ✅ acceptable | no functional mismatch observed |
| Version source | `mods.toml` hardcoded `1.0.0` | `fabric.mod.json` now expands `${mod_version}` during `processResources` | 🟨 partial | Fabric side now deterministic; Forge side still static literal |
| License | `All rights reserved` | `MIT` | ❌ mismatch | legal metadata inconsistency; must reconcile |
| Author/contact fields | `Clojure Academy` + minimal | author now expands `${mod_authors}`; contact still example URLs | 🟨 partial | removed author placeholder drift; contact URLs still TODO |
| pack.mcmeta (resource pack) | present in Forge resources | not found under Fabric main resources | ⚠ check required | Fabric may rely on generated resources path; explicit policy needed |
| Language assets in main resources | no Forge lang in main resources | Fabric has `assets/my_mod/lang/en_us.json` | ⚠ strategy divergence | both loaders generate lang in `src/generated/resources`; clarify single source policy |

## 3) Immediate action items

1. **Unify legal and author metadata**
   - Decide authoritative values for license/authors/contact and apply to both `mods.toml` and `fabric.mod.json`.

2. **Unify version strategy**
   - ✅ Fabric side done: `fabric.mod.json` uses `${mod_version}` and `platform-src/loader/fabric/build.gradle` expands it.
   - TODO: either keep Forge hardcoded by policy, or add active Forge `processResources` expansion and then use placeholders in `mods.toml`.

3. **Document pack metadata policy**
   - Decide whether Fabric must have an explicit `pack.mcmeta` under platform resources or only generated assets path.

4. **Define lang source-of-truth**
   - Enforce one strategy: generated lang only (recommended), with optional minimal fallback in main resources if needed.

## 4) Keep platform-private boundaries (intentional non-equality)

- Loader dependency declarations (`fabricloader`/`fabric-api` vs `forge` dependency range) remain platform-specific.
- Entrypoint declaration format remains loader-specific (Fabric JSON entrypoints vs Forge Mod metadata/event system).

## 5) Verification hooks for this checklist

After metadata normalization:
- `:platform:processResources :platform:jar`
- `:platform:processResources :platform:jar`
- `verifyCurrentPlatforms`

Optional follow-up:
- Add a lightweight task/script to parse both metadata files and fail CI on contradictory legal/version fields.

## 6) Execution evidence (2026-05-09)

- ✅ `:platform:processResources :platform:processResources` passed.
- ✅ `verifyCurrentPlatforms` passed after metadata updates.
- ✅ Verified generated Fabric metadata now has concrete values in `platform-src/loader/fabric/build/resources/main/fabric.mod.json`:
   - `"version": "1.0.0"`
   - `"name": "my_mod (Fabric)"`
   - `"authors": ["Clojure Academy"]`

## 7) Related datagen parity evidence (2026-05-09)

- ✅ `verifyForgeFabricDatagenParity` now passes as a stable comparison gate for already-generated shared outputs.
- Current enforced scope:
   - `data/my_mod/recipes`
   - `assets/my_mod/blockstates`
   - `assets/my_mod/lang`
- The gate intentionally avoids Forge datagen task graph coupling and does not yet include item/block model parity.
