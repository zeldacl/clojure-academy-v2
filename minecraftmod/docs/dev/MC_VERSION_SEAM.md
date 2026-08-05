# Minecraft Version Seam (`cn.li.mcver`)

Cross-version Minecraft API differences that callers must share are concentrated under
`cn.li.mcver.*` inside each **minecraft** catalog component
(`platform-src/minecraft/mc-<version>/src/main/java/cn/li/mcver/`).

Contracts are shaped for **26.2** (the newest supported version). The **1.21.1**
component implements the same public surface as a downgrade, and **1.20.1**
downgrades further still (older APIs behind the same method names, or — where 26.2
uses a native type unavailable on an older version, e.g. `Identifier` vs
`ResourceLocation`, or `ValueInput`/`ValueOutput` vs `CompoundTag` +
`HolderLookup.Provider` — an opaque handle wrapping the older version's native type
behind the same method names). Call sites in versioned Minecraft/runtime code should
prefer the seam over raw version forks when the difference is already covered here.

`verifyVersionSeamParity` only compares relative **type file names** under each
version's `java/cn/li/mcver` tree, not method signatures — so a member's payload
type may legitimately differ per version (see `ResourceLocations`, `Effects`,
`BlockEntityIo` below) as long as every version defines the same set of seam types.

Loader components (`forge-1.20.1`, `fabric-1.20.1`, `neoforge-1.21.1`, `neoforge-26.2`) must not own
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

26.2: `Identifier.fromNamespaceAndPath` / `Identifier.parse` (26.2 renamed
`ResourceLocation` to `Identifier`; the factory method names were kept).  
1.21.1: `ResourceLocation.fromNamespaceAndPath` / `parse`.  
1.20.1: constructors.

Return type is each version's native identifier type (`Identifier` on 26.2,
`ResourceLocation` on 1.21.1/1.20.1) — callers stay on the versioned component,
so this does not need a shared wrapper type.

### `ItemData`

- `hasCustomData(ItemStack)`
- `getCustomDataCopy(ItemStack)`
- `getOrCreateCustomData(ItemStack)` — mutable copy; persist with `setCustomData`
- `setCustomData(ItemStack, CompoundTag)`
- `removeCustomData(ItemStack)`

26.2 / 1.21.1: `DataComponents.CUSTOM_DATA` + `CustomData` (identical — 26.2 did not
touch this API).  
1.20.1: `ItemStack` CompoundTag (`hasTag` / `getOrCreateTag` / `setTag`).

### `Effects`

- `holderOf(MobEffect)` / `holderOf(ResourceLocation|Identifier)`
- `unwrap(Holder<MobEffect>)`
- `hasEffect` / `getEffect` / `addEffect` / `removeEffect` on `LivingEntity` + `Holder`

26.2: Holder-native; `Registry#getHolder` was removed from the base `Registry`
interface, so lookups go through `Registry#wrapAsHolder` (always succeeds) and
`Registry#get(Identifier)`.  
1.21.1: Holder-native registry / entity APIs (`getHolder`/`getResourceKey`).  
1.20.1: wraps bare `MobEffect` with `BuiltInRegistries.MOB_EFFECT.wrapAsHolder` and unwraps at call sites.

The `holderOf(ResourceLocation|Identifier)` overload's parameter is each version's
native identifier type.

### `BlockEntityIo`

- `Io` — opaque handle over a version's persistence payload
- `ofValueInput(...)` / `ofValueOutput(...)` — wrap the payload into an `Io`
- `load(BlockEntity, Io)`
- `getUpdateTag(BlockEntity, ...)`
- `AdditionalWriter` — subclass callback (`write(Io)`) when writing additional NBT

26.2: `Io` wraps the real `ValueInput`/`ValueOutput` (26.2 replaced
`CompoundTag + HolderLookup.Provider` with a single `ValueInput`/`ValueOutput`
parameter); unwrap with `asValueInput`/`asValueOutput`. `getUpdateTag(BlockEntity,
HolderLookup.Provider)` — untouched by the ValueInput/ValueOutput migration.  
1.21.1: `ValueInput`/`ValueOutput` do not exist yet, so `Io` wraps a
`CompoundTag` + `HolderLookup.Provider` pair instead (`asTag`/`asRegistries`);
`load` calls `loadWithComponents`. `getUpdateTag(BlockEntity, HolderLookup.Provider)`
matches 26.2 exactly (the type exists on 1.21.1).  
1.20.1: neither `ValueInput`/`ValueOutput` nor `HolderLookup.Provider` exist, so
`Io` wraps a bare `CompoundTag` (`asTag`); `load` calls `be.load(tag)`.
`getUpdateTag(BlockEntity)` drops the registries parameter entirely (no
registries concept on this path in 1.20.1 vanilla).

### `render.ImmediateDraw`

- `Mode` / `Format` enums
- `begin` / `vertex` / `draw` / `texturedQuad`
- `Vertex.uv` / `color` / `endVertex`

26.2: removed the `Tesselator`/`BufferUploader`/`VertexFormat.Mode` immediate-draw
path as part of the RenderPipeline/GpuBuffer render rewrite. The seam is
consumer-bound: the `SubmitNodeCollector` selects the `RenderType`/pipeline and
supplies its `VertexConsumer`; `draw` only closes the logical batch because
upload and graphics state belong to that pipeline.
1.21.1: `Tesselator.begin` → `BufferBuilder.buildOrThrow` → `MeshData` →
`BufferUploader.drawWithShader`; legacy callers still select shader/texture state
before entering the seam and `endVertex` is a no-op.
1.20.1: same downgrade model using `Tesselator.getBuilder()` + `endVertex` +
`BufferUploader.drawWithShader(bb.end())`.

## `minecraft-base` (`cn.li.mcbase`)

Catalog component id: `minecraft-base`  
Kind: `minecraft-shared`  
Source: `platform-src/minecraft/base/src/main`

Version-agnostic Minecraft glue shared by every target that includes this component
(Forge 1.20.1, Fabric 1.20.1, NeoForge 1.21.1, NeoForge 26.2 today):

- Java entity specs / hook registry under `cn.li.mcbase.entity.*`
- Clojure platform op installers under `cn.li.mcbase.platform.*`
- Runtime SPIs under `cn.li.mcbase.runtime.spi.*`
- Datagen helpers under `cn.li.mcbase.datagen.*`

**Must not** reference version namespaces (`cn.li.mc1201.*`, `cn.li.mc1211.*`,
`cn.li.mc262.*`).
Version forks live in `cn.li.mcver.*` under each `mc-<version>` component, not in base.

## Gate: `verifyVersionSeamParity`

Registered in root `build.gradle`; included in `verifyCurrentPlatforms`.

Checks:

1. Each catalog **target** lists at most one component with `kind: "minecraft"`.
2. Every `kind: "*-shared"` source tree is checked against catalog-derived
   Minecraft namespaces (`cn.li.mc1201.` / `cn.li.mc1211.` / `cn.li.mc262.`)
   and versioned loader namespaces.
3. Relative seam type names under `…/java/cn/li/mcver` (and optional Clojure seam
   tree) are identical across all `kind: "minecraft"` components.

Run:

```powershell
cmd /c .\gradlew.bat verifyVersionSeamParity
cmd /c .\gradlew.bat verifyCurrentPlatforms
```

## Adding a seam member

1. Define the public API against 26.2 semantics (the newest supported version).
2. Implement under `platform-src/minecraft/mc-26.2/.../cn/li/mcver/`.
3. Implement the same type name (same relative path) under
   `platform-src/minecraft/mc-1.21.1/.../cn/li/mcver/` as a downgrade — same method
   names where the native type still exists on 1.21.1; otherwise an opaque handle
   wrapping 1.21.1's available native type behind the same method names.
4. Implement the same type name under `platform-src/minecraft/mc-1.20.1/.../cn/li/mcver/`
   as a further downgrade from 1.21.1.
5. Run `verifyVersionSeamParity`.
6. Document the member in this file.
