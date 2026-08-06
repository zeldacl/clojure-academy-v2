# Remaining identical-but-blocked files

After batch4 unlock/refactor (no legacy compat). Files are still norm-identical across
versions but blocked by version-forked dependencies (or intentionally thin wrappers).

## MC (11 files)
- bootstrap/init_common.clj
- bootstrap/platform_init.clj
- command/action_impls.clj
- command/brigadier_registry.clj
- gui/menu_bridge_install.clj (intentional thin wrapper: installs DelegatingCMenuBridge factory)
- gui/screen/registry.clj
- platform/runtime_ops.clj
- runtime/adapter/block_manipulation.clj
- runtime/adapter/world_effects.clj
- runtime/event/item_use.clj
- runtime/raycast_core.clj

Also versioned (not identical): `entity/hooks.clj` installs hook class package prefix then delegates to mcbase.

## NeoForge (3 files)
- setup/common.clj
- setup/registry_binding.clj
- setup/shared_event_install.clj (intentional version hub for Mod*/Java bridges)
