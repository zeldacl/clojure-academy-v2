# Remaining identical-but-blocked files

## MSDF note (checked 2026-08-06)

1.20.1 / 1.21.1 share several MSDF files (RenderTypes, MSDFAwareGlyph, setup/tick, cgui font).
`MsdfFontFace` / `MsdfFontManager` differ (1.21.1 FreeType stub). **26.2 has no MSDF pipeline.**

Cannot promote MSDF Java into `minecraft-base`: base compiles for all targets including 26.2
(ResourceLocation→Identifier, ShaderInstance/RenderType, GlyphInfo.bake forks). Options later:
- catalog component included only for Loom 1.20.1/1.21.1 targets, or
- finish 1.21.1 FreeType port + 26.2 MSDF rewrite then re-evaluate.

Already in mcbase (version-agnostic FX): MsdfTextFx, MsdfGlyphFlags, MsdfGlowAnimator.

## MC (identical across three versions, still blocked)
- bootstrap/platform_init.clj
- command/action_impls.clj
- command/brigadier_registry.clj
- gui/menu_bridge_install.clj (intentional thin wrapper)
- gui/screen/registry.clj
- platform/runtime_ops.clj
- runtime/adapter/block_manipulation.clj
- runtime/adapter/world_effects.clj
- runtime/event/item_use.clj
- runtime/raycast_core.clj

Promoted this round: `bootstrap/init_common.clj` → mcbase.

## NeoForge (3 files)
- setup/common.clj
- setup/registry_binding.clj
- setup/shared_event_install.clj (intentional version hub)
