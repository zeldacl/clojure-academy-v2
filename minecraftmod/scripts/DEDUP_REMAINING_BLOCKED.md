# Remaining identical-but-blocked files

## MSDF note (checked 2026-08-06)

1.20.1 / 1.21.1 share several MSDF files (RenderTypes, MSDFAwareGlyph, setup/tick, cgui font).
`MsdfFontFace` / `MsdfFontManager` differ (1.21.1 FreeType stub). **26.2 has no MSDF pipeline.**

Cannot promote MSDF Java into `minecraft-base`: base compiles for all targets including 26.2
(ResourceLocation→Identifier, ShaderInstance/RenderType, GlyphInfo.bake forks). Options later:
- catalog component included only for Loom 1.20.1/1.21.1 targets, or
- finish 1.21.1 FreeType port + 26.2 MSDF rewrite then re-evaluate.

Already in mcbase (version-agnostic FX): MsdfTextFx, MsdfGlyphFlags, MsdfGlowAnimator.

## Promoted through batch7

- `RegistryDispatch` / `TextureSizeAccess` → `cn.li.mcver`
- `client/i18n`, `gui/reactive/bake-slots` → mcbase (+ version install for id class)
- NeoForge `runtime/item-handler` → neoforgebase

## Promoted through batch8

- `McAccess` → `cn.li.mcver` (unlocks shared interop / damage / liquid client-side)
- `runtime/{interop,damage-interception,lifecycle,network}-core` → mcbase (+ thin version re-exports)
- `datagen/{lang,worldgen}-provider-shell` → mcbase (+ thin version re-exports)
- `ScriptedLiquidBlock`, `PositionalLoopSoundInstance` → mcbase
- `RegistryLookups` → `cn.li.mcver`; `DamageSourceAccess` → mcbase
- Entity hooks → mcbase via `IScriptedEffectEntity` / `IScriptedRayEntity`:
  `OwnerOffsetEffectHook`, `OwnerFollowRayHook`, `Noop{Effect,Ray,Marker}Hook`
- batch9: `accessor-registry`, `gui/registry/common`, `datagen/resource-location`,
  `blockstate-properties`, `entity-damage-core` + adapter, `key-scheme-provider-core`
  (`McAccess.windowHandle`)
- batch10: `NbtAccess` / `ItemUseResults` / `EntityClasses` → `cn.li.mcver`;
  `McAccess.gameTime` + `clientPartialTick`; `IScriptedOwnedEntity.getOwnerUuid`;
  promote `event-{helpers-core,handlers}`, `brigadier-util`, `blockstate-provider-shell`,
  `gui/reactive/clock`, `runtime/{native-nbt,nbt-core,item-callback,item-handler-core}`
  → mcbase (+ thin version re-exports)
- batch11: `TeleportAccess` / `RegistryValues` / `AdvancementJson` → `cn.li.mcver`;
  promote `named-position-store-core`, `teleportation-core`, `potion-effects-core`,
  `datagen/{item-registry,advancement-provider-{core,shell}}`, `brigadier-tree` → mcbase;
  `overlay-host-core` + versioned draw-tape shells
- batch12: `WorldOps` / `Ingredients` / `McAccess.closeScreen`;
  `IScriptedBlockBodyEntity`; promote Java `ItemInventory`/`ItemRegistry`/`BlockRegistry`;
  `entity-motion-core`, `metadata-resolver`, `session-cleanup-core` (+ walk-speed hook shell)
- batch13: `BlockEntityRegistry`, `KeyMappingAccess`;
  `datagen/setup-common`, `client/texture-registry`, `vanilla-input-control-core`

## Blocked: ScriptedRenderShapes (intentional)

26.2 maps `entityblock-animated` → `RenderShape.INVISIBLE` (no ENTITYBLOCK_ANIMATED).
Do not unify with 1.20.1/1.21.1.

## Blocked: terminal-render (checked 2026-08-06)

`gui/reactive/terminal_render.clj` **cannot** go into `minecraft-base`:

- 1.20.1 / 1.21.1: `GuiGraphics` + `PoseStack` + `RenderSystem.setProjectionMatrix` (near-identical; 1201 aligned to `ResourceLocations/of`).
- 26.2: `GuiGraphicsExtractor` + CPU plane project + `Matrix3x2f` affine fit + `GuiGraphicsHelper/blitAdditive`; cursor/window APIs also differ (`Window.handle` vs `.getWindow`).

Shared bridge keys only (`:terminal-apply-perspective!` etc.). Keep versioned.

## MC residual (intentional thin wrappers / install shells)
- gui/menu_bridge_install.clj
- bootstrap/platform_init.clj, platform/runtime_ops.clj, entity/hooks.clj
- runtime/raycast_ops_install.clj, ender_dragon_parts_install.clj
- versioned thin re-exports for batch8 cores/shells (call-site compatibility)
- gui/reactive/terminal_render.clj (26.2 pipeline fork)

## NeoForge residual (intentional version hubs)
- setup/shared_event_install.clj
- setup/common.clj (thin install + re-export)
