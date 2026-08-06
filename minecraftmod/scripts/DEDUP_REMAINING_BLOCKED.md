# Remaining identical-but-blocked files

After batch3 unlock/refactor (no legacy compat). Files are still norm-identical across
versions but blocked by version-forked dependencies.

## MC (16 files)
- bootstrap/init_common.clj
- bootstrap/platform_init.clj
- client/overlay/state.clj
- client/runtime/hand_effect_renderer_core.clj
- command/action_impls.clj
- command/brigadier_registry.clj
- entity/hooks.clj
- gui/provider_bridge.clj
- gui/screen/registry.clj
- gui/slots/data_slot.clj
- gui/slots/sync.clj
- platform/runtime_ops.clj
- runtime/adapter/block_manipulation.clj
- runtime/adapter/world_effects.clj
- runtime/event/item_use.clj
- runtime/raycast_core.clj

## NeoForge (6 files)
- integration/events/loot.clj
- setup/capability_setup.clj
- setup/capability_wiring.clj
- setup/common.clj
- setup/registry_binding.clj
- setup/shared_event_install.clj
