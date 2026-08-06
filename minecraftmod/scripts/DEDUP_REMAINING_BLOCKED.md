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

## MC residual (intentional thin wrappers / install shells)
- gui/menu_bridge_install.clj
- bootstrap/platform_init.clj, platform/runtime_ops.clj, entity/hooks.clj
- runtime/raycast_ops_install.clj, ender_dragon_parts_install.clj

## NeoForge residual (intentional version hubs)
- setup/shared_event_install.clj
- setup/common.clj (thin install + re-export)
