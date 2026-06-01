# AC Refactor Namespace Migration Map

Last updated: 2026-06-01
Scope: `ac` module structural refactor (wireless / ability / registry startup chain / GUI tab)

## Principles

- Keep stable public entry namespaces where they remain current.
- Do not add transitional wrappers for deleted wireless API/domain/service/persistence stacks.
- Move cohesive responsibilities into focused namespaces.
- Preserve call contracts while reducing monolithic files.

## Wireless data layer

### `cn.li.ac.wireless.data.world`

**Lifecycle only** (no command/lookup passthrough):

- `get-world-data`, `init-world-data!`, `on-world-load` / `on-world-save` / `on-world-tick` / `on-world-unload`
- Delegates persistence to `cn.li.ac.wireless.data.persistence`

Callers:

- **Topology writes** → `cn.li.ac.wireless.api` or `cn.li.ac.wireless.service.commands`
- **Reads** → `cn.li.ac.wireless.api` or `cn.li.ac.wireless.service.queries` → `network-lookup` / `spatial-lookup`
- **Mutable commit** → `cn.li.ac.wireless.data.world-registry` (`transact!`) — internal to commands/runtime/persistence, not block/GUI

Do **not** re-add `create-network-impl!` or other `*-impl!` aliases on `data.world`.

### Wireless topology (current)

- Writes: `cn.li.ac.wireless.service.commands` → `domain.topology` + `entity-commit` + `world-registry/transact!`
- Reads: `cn.li.ac.wireless.service.queries` → `network-lookup` / `spatial-lookup`
- Removed (do not reintroduce): `topology-service`, `topology-index`, `network-membership`, `query-service`, `network-command`, `world-topology`, `*-impl!` aliases on `data.world`

### `cn.li.ac.wireless.data.network-*` modules

- `network-state`, `network-validation`, `network-runtime`, `network-energy-balance`, `node-conn` — runtime entities and tick paths only.
- There is no `cn.li.ac.wireless.data.network` compatibility namespace.

Removed obsolete modules:

- `cn.li.ac.wireless.data.world-persistence`
- `cn.li.ac.wireless.data.network-nbt`
- `cn.li.ac.wireless.data.node-conn-nbt`

## Wireless API layer

### `cn.li.ac.wireless.api`

Current canonical facade for wireless queries and commands. It delegates to:

- `cn.li.ac.wireless.core.capability-resolver` for runtime capability resolution.
- `cn.li.ac.wireless.service.commands` for topology mutations.
- `cn.li.ac.wireless.service.queries` for read paths.

Compatibility:

- Deleted split namespaces are not compatibility targets. New callers should require `cn.li.ac.wireless.api` directly.

## Wireless GUI tab layer

### `cn.li.ac.wireless.gui.tab`

Now split into:

- Role metadata/config: `cn.li.ac.wireless.gui.tab.role-config`
- View/render and widget wiring: `cn.li.ac.wireless.gui.tab.view`
- Network orchestration + public constructors: `cn.li.ac.wireless.gui.tab`

Compatibility:

- Existing constructor APIs remain in `cn.li.ac.wireless.gui.tab`:
  - `create-wireless-panel`
  - `create-wireless-panel-node`
  - `create-wireless-panel-generator`
  - `create-wireless-panel-receiver`
  - `create-embedded-developer-wireless-panel!`
  - `developer-wireless-tab-lazy-activator`

## Ability module extraction

### Utility extraction

Resource/level helper logic moved to:

- `cn.li.ac.ability.util.resource-check`
- `cn.li.ac.ability.util.level-formula`

Consumers updated:

- `cn.li.ac.ability.model.resource`
- `cn.li.ac.ability.client.keybinds`
- `cn.li.ac.ability.server.service.learning`

### Server network handlers

`cn.li.ac.ability.server.network` now delegates to focused handlers:

- `cn.li.ac.ability.server.handlers.common`
- `cn.li.ac.ability.server.handlers.level-handler`
- `cn.li.ac.ability.server.handlers.preset-handler`
- `cn.li.ac.ability.server.handlers.activation-handler`
- `cn.li.ac.ability.server.handlers.context-handler`
- `cn.li.ac.ability.server.handlers.input-handler`

## Registry startup chain SPI

Content phase orchestration support introduced:

- `cn.li.ac.registry.spi.content-phase`

`cn.li.ac.registry.content-namespaces` now uses SPI/plan-driven loading.

## Caller migration guidance

- Preferred: depend on the current stable namespace for each subsystem.
- For new code, depend on granular modules only when scoped responsibilities are required.
- Do not introduce transitional wrapper namespaces for deleted structures.
