# Framework State Architecture

## Overview

The `cn.li.mcmod.framework` namespace provides the **single source of truth** for all
system-level state in the Clojure Academy Minecraft mod. It replaces ~85 scattered
`defonce`/`delay`/`^:dynamic` singletons with one Framework atom.

## Core Concept

```
*framework* → Framework atom
├─ :registry/* → static content registries (frozen after init, lock-free HAMT reads)
├─ :service/*  → runtime dynamic services
└─ :platform/* → platform adapter function maps
```

**Created once** per logical side (client/server) at mod entry point.
**Bound** to `^:dynamic *framework*` for the entire lifecycle.

## Sub-namespaces

### `:registry/*` — Static Content Registries

Populated during content init, **frozen** afterwards. All reads are lock-free
HAMT pointer chases — safe for concurrent access from any thread with zero
synchronization overhead.

| Key | Content | Filled During |
|-----|---------|---------------|
| `:registry/blocks` | block-id → BlockSpec | Phase 4 content registration |
| `:registry/items` | item-id → ItemSpec | Phase 4 |
| `:registry/entities` | entity-id → EntitySpec | Phase 4 |
| `:registry/particles` | particle-id → ParticleSpec | Phase 4 |
| `:registry/fluids` | fluid-id → FluidSpec | Phase 4 |
| `:registry/effects` | effect-id → MobEffect spec | Phase 4 |
| `:registry/sounds` | sound-id → SoundEvent spec | Phase 4 |
| `:registry/loot` | loot-table-id → LootTable spec | Phase 4 |
| `:registry/configs` | config-key → ConfigSpec | Phase 2 |
| `:registry/guis` | gui-id → GUISpec | Phase 4 |
| `:registry/slots` | slot-id → SlotSpec | Phase 4 |
| `:registry/tiles` | tile-id → TileSpec | Phase 3 |
| `:registry/hooks` | hook-key → [handler-fns] | Phase 2 |
| `:registry/commands` | command-name → CommandSpec | Phase 2 |
| `:registry/energy` | energy-type-id → EnergyTypeSpec | Phase 4 |
| `:registry/providers` | provider-id → ProviderSpec | Phase 2 |
| `:registry/keybinds` | keybind-id → KeybindSpec | client init |
| `:registry/messages` | msg-id → MessageSpec | Phase 2 |
| `:registry/content` | multi-domain content descriptors | Phase 2 |
| `:registry/categories` | category-id → CategorySpec | Phase 2 |
| `:registry/skills` | skill-id → SkillSpec | Phase 2 |

### `:service/*` — Runtime Dynamic Services

Read/write during gameplay via Facade API guard functions.

| Key | Content |
|-----|---------|
| `:service/lifecycle` | content init callbacks, runtime activation fns, datagen hooks |
| `:service/ability-runtime` | AC ability runtime container |
| `:service/ability-events` | event subscriber registry |
| `:service/ability-lifecycle` | lifecycle handler registry |
| `:service/wireless-worlds` | per-world wireless network topology |
| `:service/container-state` | GUI menu → container mappings |
| `:service/tutorial-events` | tutorial handler fns |
| `:service/network-server` | server network handlers |
| `:service/network-client` | client network handlers |
| `:service/world-lifecycle` | world load/unload/save/tick handlers |
| `:service/world-save-cache` | pending world-save payloads |
| `:service/gui-handler` | GUI handler instance |
| `:service/particle-queue` | per-session particle effect queue |

### `:platform/*` — Platform Adapter Function Maps

Installed once at bootstrap by platform-specific code (Forge/Fabric).
Read-only thereafter — function references follow the Framework instance,
valid on any thread (no ThreadLocal dependency).

| Key | Content |
|-----|---------|
| `:platform/world-ops` | world access operations |
| `:platform/entity-damage` | entity damage operations |
| `:platform/world-effects` | world effect operations |
| `:platform/player-feedback` | player feedback operations |
| `:platform/gui-open` | GUI open function |
| ... | (additional adapters added incrementally) |

## Facade API

Direct `swap!`/`reset!` on the Framework atom is **forbidden** in business code.
All access goes through guard functions:

```clojure
;; Reading (any thread, any time)
(cn.li.mcmod.framework.registry/get-spec fw :blocks id)

;; Writing (init phase only, rejected after freeze)
(cn.li.mcmod.framework.registry/register! fw :blocks id spec)

;; Service access
(cn.li.mcmod.framework.service/get-service fw :lifecycle)
(cn.li.mcmod.framework.service/update-service! fw :container-state ...)

;; Platform adapter access
(cn.li.mcmod.framework.platform/get-adapter fw :world-ops)
(cn.li.mcmod.framework.platform/call-adapter fw :world-ops :get-tile-entity world pos)
```

## Thread Safety

### Iron Rule A: Capture atom before crossing async boundary

```clojure
;; ❌ ThreadLocal loss on async thread
(future (get-in @*framework* [:registry :blocks id]))

;; ✅ Capture atom reference in closure
(let [fw *framework*]
  (future (get-in @fw [:registry :blocks id])))
```

### Iron Rule B: Freeze registries after content init

```clojure
(cn.li.mcmod.framework.registry/freeze-all! fw)
```

After freezing, all `:registry/*` reads are lock-free HAMT lookups — zero CAS,
zero contention, nanosecond throughput on concurrent threads.

### Multiplayer CAS Contention Prevention

Per-player mutable state (cooldowns, charge levels, combo buffers) must NEVER
go into the Framework atom. Use per-player atoms (`ConcurrentHashMap<String, Atom>`
in `AtomAbilityStore`) or Player NBT/Capability. See Iron Rule 12.

## Data Ownership Boundaries

```
Framework :registry/*  ← static content specs (immutable after init)
Framework :platform/*  ← platform adapter fns (immutable after bootstrap)
Framework :service/*   ← runtime services (low-frequency reads/writes)

Player NBT/Capability  ← per-player state (CD, CP, charge, context-registry)
World SavedData        ← per-world state (wireless network topology)
BlockEntity NBT        ← per-BE state (energy, inventory)
Closure factories      ← per-client-session state (particle queue, overlay)
```

## Migration Status

- **Framework atom**: ✅ Created and wired into Forge/Fabric entry points
- **Phase 1** (lifecycle, hooks): ✅ Migrated to Framework
- **Phase 2** (DSL registries): ✅ 12 files migrated
- **Phase 3** (GUI, command): ✅ 6 files migrated
- **Phase 4** (platform SPI): ~ 2 files proven, remainder using legacy dynamic vars (AOT-safe)
- **Phase 5** (AC registries): ~ 2 files proven, remainder require coordinated caller update
- **Phase 6** (client session): ⏭️ Already AOT-safe (delay pattern), deferred
- **Phase 7a** (wireless): ✅ Migrated to Framework per-world storage
- **Phase 7b** (player data): ✅ CAS contention fixed (ConcurrentHashMap per-player atoms)
