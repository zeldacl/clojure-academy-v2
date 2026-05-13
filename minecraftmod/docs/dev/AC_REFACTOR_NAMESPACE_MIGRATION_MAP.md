# AC Refactor Namespace Migration Map

Last updated: 2026-05-13
Scope: `ac` module structural refactor (wireless / ability / registry startup chain / GUI tab)

## Principles

- Keep old public entry namespaces available as compatibility facades when practical.
- Move cohesive responsibilities into focused namespaces.
- Preserve call contracts while reducing monolithic files.

## Wireless data layer

### `cn.li.ac.wireless.data.world`

Now delegates internally to:

- `cn.li.ac.wireless.data.world-registry`
- `cn.li.ac.wireless.data.spatial-lookup`
- `cn.li.ac.wireless.data.network-lookup`

Compatibility:

- Existing callers can continue to require `cn.li.ac.wireless.data.world`.

### `cn.li.ac.wireless.data.network`

Now delegates internally to:

- `cn.li.ac.wireless.data.network-state`
- `cn.li.ac.wireless.data.network-membership`
- `cn.li.ac.wireless.data.network-validation`
- `cn.li.ac.wireless.data.network-energy-balance`

Compatibility:

- Existing callers can continue to require `cn.li.ac.wireless.data.network`.

Removed obsolete modules:

- `cn.li.ac.wireless.data.world-persistence`
- `cn.li.ac.wireless.data.network-nbt`
- `cn.li.ac.wireless.data.node-conn-nbt`

## Wireless API layer

### `cn.li.ac.wireless.api`

Now organized by responsibility:

- Query: `cn.li.ac.wireless.api-query`
- Command: `cn.li.ac.wireless.api-command`
- Lifecycle: `cn.li.ac.wireless.api-lifecycle`

Compatibility:

- `cn.li.ac.wireless.api` remains the stable facade.

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

- Preferred: keep requiring original facade namespaces unless you need internal granularity.
- For new code, depend on granular modules only when scoped responsibilities are required.
- Do not bypass compatibility facades from external modules unless there is a measured reason.
