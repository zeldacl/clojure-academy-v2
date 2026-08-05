# Remaining identical-but-blocked files

These files are byte-identical across versions after namespace normalization, but cannot be
promoted into minecraft-base / neoforge-shared without either:
- merging version-divergent dependencies (API forks), or
- introducing new SPI/markers and retargeting call sites.

## MC (26 files)
- block/SharedDynamicStateBlock.java
- bootstrap/init_common.clj
- bootstrap/platform_init.clj
- client/overlay/state.clj
- client/runtime/hand_effect_renderer_core.clj
- command/action_impls.clj
- command/brigadier_registry.clj
- entity/hook/effect/ScriptedEffectHook.java
- entity/hook/effect/ScriptedEffectHooks.java
- entity/hook/marker/OwnerFollowMarkerHook.java
- entity/hook/marker/ScriptedMarkerHooks.java
- entity/hook/ray/ScriptedRayHooks.java
- entity/hooks.clj
- gui/provider_bridge.clj
- gui/screen/registry.clj
- gui/slots/data_slot.clj
- gui/slots/sync.clj
- platform/runtime_ops.clj
- runtime/ItemPlayerOps.java
- runtime/adapter/block_manipulation.clj
- runtime/adapter/world_effects.clj
- runtime/entity_iterators.clj
- runtime/entity_query_core.clj
- runtime/event/item_use.clj
- runtime/player_motion_core.clj
- runtime/raycast_core.clj

## NeoForge (8 files)
- capability/ForgeCapabilityQuery.java
- integration/events/entity_attributes.clj
- integration/events/gui_open_port.clj
- integration/events/loot.clj
- setup/capability_setup.clj
- setup/capability_wiring.clj
- setup/common.clj
- setup/registry_binding.clj

False-closed traps (same-package deps that look closed to naive analyzers):
- SharedDynamicStateBlock -> AbstractDynamicStateBlock / BlockPlacementHelper (DIFF)
- Scripted*Hooks registries -> versioned Hook interfaces / entity types
- ItemPlayerOps -> ItemRegistry / ItemInventory (DIFF)
- ForgeCapabilityQuery -> CapabilityRegistry (DIFF)
