# Remaining identical-but-blocked files

## MSDF note (checked 2026-08-06)

1.20.1 / 1.21.1 share several MSDF files (RenderTypes, MSDFAwareGlyph, setup/tick, cgui font).
`MsdfFontFace` / `MsdfFontManager` differ (1.21.1 FreeType stub). **26.2 has no MSDF pipeline.**

Cannot promote MSDF Java into `minecraft-base`: base compiles for all targets including 26.2
(ResourceLocation→Identifier, ShaderInstance/RenderType, GlyphInfo.bake forks). Options later:
- catalog component included only for Loom 1.20.1/1.21.1 targets, or
- finish 1.21.1 FreeType port + 26.2 MSDF rewrite then re-evaluate.

Already in mcbase (version-agnostic FX): MsdfTextFx, MsdfGlyphFlags, MsdfGlowAnimator.

## Batch5 / Batch6 promoted

Batch5: screen, multipart, adapters, item-use, raycast, commands, neo registry_binding.
Batch6: `bootstrap/platform_init`, `platform/runtime_ops`, NeoForge `setup/common`
(version files remain thin install + re-export).

## MC residual (intentional thin wrappers)
- gui/menu_bridge_install.clj (versioned DelegatingCMenuBridge factory)

## NeoForge residual (intentional version hub)
- setup/shared_event_install.clj
