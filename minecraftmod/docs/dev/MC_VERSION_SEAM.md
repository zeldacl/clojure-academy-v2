# Minecraft Version Seam (`cn.li.mcver`)

Cross-version Minecraft API differences that callers must share are concentrated under
`cn.li.mcver.*` inside each **minecraft** catalog component
(`platform-src/minecraft/mc-<version>/src/main/java/cn/li/mcver/`).

Contracts are shaped for **1.21.1**. The **1.20.1** component implements the same
public surface as a downgrade (older APIs behind the same method names). Call sites
in versioned Minecraft/runtime code should prefer the seam over raw version forks
when the difference is already covered here.

Loader components (`forge-1.20.1`, `fabric-1.20.1`, `neoforge-1.21.1`) must not own
these seams. Neutral layers (`api`, `mcmod`, `ac`) must not import `cn.li.mcver` or
`net.minecraft.*`.

## Members (parity set)

`verifyVersionSeamParity` compares relative type names under each version’s
`java/cn/li/mcver` (and optional `clojure/cn/li/mcver`). Current members:

| Type | Role |
|------|------|
| `ResourceLocations` | `ResourceLocation` construction (`of`, `parse`) |
| `ItemData` | Item custom data read/write |
| `Effects` | Potion effects via `Holder<MobEffect>` |
| `BlockEntityIo` | Block-entity load / update-tag + registries handle |
| `render.ImmediateDraw` | Immediate-mode mesh begin / vertex / draw |

Do not add a seam member to one Minecraft version without adding the matching
public type (same relative path / name) to every other `kind: "minecraft"`
component.

### `ResourceLocations`

- `of(String namespace, String path)`
- `parse(String id)`

1.21.1: `ResourceLocation.fromNamespaceAndPath` / `parse`.  
1.20.1: constructors.

### `ItemData`

- `hasCustomData(ItemStack)`
- `getCustomDataCopy(ItemStack)`
- `getOrCreateCustomData(ItemStack)` — mutable copy; persist with `setCustomData`
- `setCustomData(ItemStack, CompoundTag)`
- `removeCustomData(ItemStack)`

1.21.1: `DataComponents.CUSTOM_DATA` + `CustomData`.  
1.20.1: `ItemStack` CompoundTag (`hasTag` / `getOrCreateTag` / `setTag`).

### `Effects`

- `holderOf(MobEffect)` / `holderOf(ResourceLocation)`
- `unwrap(Holder<MobEffect>)`
- `hasEffect` / `getEffect` / `addEffect` / `removeEffect` on `LivingEntity` + `Holder`

1.21.1: Holder-native registry / entity APIs.  
1.20.1: wraps bare `MobEffect` with `BuiltInRegistries.MOB_EFFECT.wrapAsHolder` and unwraps at call sites.

### `BlockEntityIo`

- `Registries` — opaque registries handle
- `NO_REGISTRIES` — sentinel for call sites that have no provider (1.20.1 unused; 1.21.1 throws if used for real IO)
- `of(HolderLookup.Provider)` — 1.21.1 only helper to wrap a real provider
- `load(BlockEntity, CompoundTag, Registries)`
- `getUpdateTag(BlockEntity, Registries)`
- `AdditionalWriter` — subclass callback when writing additional NBT

1.21.1: `HolderLookup.Provider` via `loadWithComponents` / `getUpdateTag(provider)`.  
1.20.1: ignores registries; `be.load(tag)` / `be.getUpdateTag()`.

### `render.ImmediateDraw`

- `Mode` / `Format` enums
- `begin` / `vertex` / `draw` / `texturedQuad`
- `Vertex.uv` / `color` / `endVertex`

Does not own shader/texture/blend/depth state — callers set those first.

1.21.1: `Tesselator.begin` → `BufferBuilder.buildOrThrow` → `MeshData` → `BufferUploader.drawWithShader`; `endVertex` is a no-op.  
1.20.1: `Tesselator.getBuilder()` + `endVertex` + `BufferUploader.drawWithShader(bb.end())`.

## `minecraft-base` (`cn.li.mcbase`)

Catalog component id: `minecraft-base`  
Kind: `minecraft-shared`  
Source: `platform-src/minecraft/base/src/main`

Version-agnostic Minecraft glue shared by every target that includes this component
(Forge 1.20.1, Fabric 1.20.1, NeoForge 1.21.1 today):

- Java entity specs / hook registry under `cn.li.mcbase.entity.*`
- Clojure platform op installers under `cn.li.mcbase.platform.*`
- Runtime SPIs under `cn.li.mcbase.runtime.spi.*`
- Datagen helpers under `cn.li.mcbase.datagen.*`

**Must not** reference version namespaces (`cn.li.mc1201.*`, `cn.li.mc1211.*`).
Version forks live in `cn.li.mcver.*` under each `mc-<version>` component, not in base.

## Gate: `verifyVersionSeamParity`

Registered in root `build.gradle`; included in `verifyCurrentPlatforms`.

Checks:

1. Each catalog **target** lists at most one component with `kind: "minecraft"`.
2. `minecraft-base` sources do not mention `cn.li.mc1201.` / `cn.li.mc1211.`.
3. Relative seam type names under `…/java/cn/li/mcver` (and optional Clojure seam
   tree) are identical across all `kind: "minecraft"` components.

Run:

```powershell
cmd /c .\gradlew.bat verifyVersionSeamParity
cmd /c .\gradlew.bat verifyCurrentPlatforms
```

## Adding a seam member

1. Define the public API against 1.21.1 semantics.
2. Implement under `platform-src/minecraft/mc-1.21.1/.../cn/li/mcver/`.
3. Implement the same type names under `platform-src/minecraft/mc-1.20.1/.../cn/li/mcver/`
   as a downgrade.
4. Run `verifyVersionSeamParity`.
5. Document the member in this file.
