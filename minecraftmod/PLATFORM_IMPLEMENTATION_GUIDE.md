# Platform Implementation Guide

## Overview

This guide documents the current platform-specific implementation patterns used across all three supported platforms: Forge 1.16.5, Forge 1.20.1, and Fabric 1.20.1.

**Key Design Principle**: Platform code contains **zero hardcoded game content names**. All game content is discovered dynamically through metadata systems.

## Platform Structure

Each platform follows an identical architectural pattern with platform-specific APIs:

```
platform-name/
├── src/main/
│   ├── java/
│   │   └── com/example/my_modXXXX/
│   │       └── MyModXXXX.java          # Entry point
│   └── clojure/my_mod/platformXXXX/
│       ├── mod.clj                     # Main initialization
│       ├── bridge.clj                  # Container/Menu bridge
│       ├── events.clj                  # Event handlers
│       ├── registry_impl.clj           # MenuType registration
│       └── init.clj                    # Initialization utilities
└── src/main/resources/
    └── META-INF/
        └── mods.toml / fabric.mod.json # Loader metadata
```

## Core Implementation Files

### 1. `mod.clj` - Main Module

**Purpose**: Entry point for Clojure initialization, handles dynamic registration of all content.

**Key Responsibilities**:
- ✅ Dynamic block registration (zero hardcoded names)
- ✅ Dynamic item registration (zero hardcoded names)
- ✅ BlockItem creation for all blocks
- ✅ Storage of registered objects in atoms
- ❌ NO game-specific imports (no `block-demo`, `item-demo`)
- ❌ NO hardcoded content variables (no `demo-block`, `copper-ore`)

**Standard Implementation Pattern**:

```clojure
(ns my-mod.platformXXXX.mod
  (:require [my-mod.block.dsl :as bdsl]
            [my-mod.item.dsl :as idsl]
            [my-mod.registry.metadata :as registry-metadata]
            [my-mod.util.log :as log]))

;; Storage for dynamically registered content
(defonce registered-blocks (atom {}))
(defonce registered-items (atom {}))

;; Generic block registration (queries metadata)
(defn register-all-blocks! []
  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (let [registry-name (registry-metadata/get-block-registry-name block-id)
          block-spec (registry-metadata/get-block-spec block-id)]
      ;; Create platform-specific Block object
      ;; Register with platform registry
      ;; Store in registered-blocks atom
      )))

;; Generic item registration (queries metadata)
(defn register-all-items! []
  ;; Register standalone items
  (doseq [item-id (registry-metadata/get-all-item-ids)]
    (let [registry-name (registry-metadata/get-item-registry-name item-id)]
      ;; Create platform-specific Item object
      ;; Register with platform registry
      ;; Store in registered-items atom
      ))
  
  ;; Register BlockItems for all blocks
  (doseq [block-id (registry-metadata/get-all-block-ids)]
    (when (registry-metadata/should-create-block-item? block-id)
      ;; Create BlockItem from registered block
      ;; Register with platform registry
      )))

;; Generic query functions (replace get-demo-* functions)
(defn get-registered-block [block-id]
  (get @registered-blocks block-id))

(defn get-registered-item [item-id]
  (get @registered-items item-id))
```

**Platform-Specific Differences**:

| Aspect | Forge 1.16.5 | Forge 1.20.1 | Fabric 1.20.1 |
|--------|--------------|--------------|---------------|
| Registry API | `DeferredRegister` | `DeferredRegister` | `BuiltInRegistries` |
| Block Properties | `AbstractBlock$Properties/of Material` | `BlockBehaviour$Properties/copy` | `BlockBehaviour$Properties/copy` |
| Item Properties | `Item$Properties` with `.tab()` | `Item$Properties` | `Item$Properties` with `.stacksTo()` |
| Registration | `.register()` on DeferredRegister | `.register()` on DeferredRegister | `Registry/register()` |

### 2. `events.clj` - Event Handlers

**Purpose**: Handle player interaction events and dispatch to game logic.

**Key Responsibilities**:
- ✅ Generic event handling (works for any block)
- ✅ Dynamic block identification via metadata
- ✅ Handler lookup via event metadata
- ❌ NO hardcoded block name checks (no `"demo_block"`)
- ❌ NO game module dependencies (no `my-mod.defs`)

**Standard Implementation Pattern**:

```clojure
(ns my-mod.platformXXXX.events
  (:require [my-mod.core :as core]
            [my-mod.util.log :as log]
            [my-mod.events.metadata :as event-metadata]))

(defn handle-right-click [event-data]
  (let [{:keys [x y z player world block]} event-data
        block-name (str block)
        ;; Dynamic identification (converts Minecraft name to DSL ID)
        block-id (event-metadata/identify-block-from-full-name block-name)]
    
    (log/info "Right-click event at (" x "," y "," z ") block:" block-name)
    
    ;; Generic dispatch (checks metadata for handler)
    (when (and block-id 
               (event-metadata/has-event-handler? block-id :on-right-click))
      (log/info "Block has registered handler, dispatching...")
      (core/on-block-right-click (assoc event-data :block-id block-id)))))
```

**Block Name Identification**:

The `identify-block-from-full-name` function handles various Minecraft block name formats:
- `"Block{my_mod:demo_block}"` → `"demo-block"`
- `"my_mod:demo_block"` → `"demo-block"`
- `"demo_block"` → `"demo-block"`

**Platform-Specific Differences**:

| Aspect | Forge 1.16.5 | Forge 1.20.1 | Fabric 1.20.1 |
|--------|--------------|--------------|---------------|
| Event Type | `PlayerInteractEvent$RightClickBlock` | `PlayerInteractEvent$RightClickBlock` | `UseBlockCallback` |
| World Access | `.getWorld(evt)` | `.getLevel(evt)` | Parameter `world` |
| Player Access | `.getPlayer(evt)` | `.getEntity(evt)` | Parameter `player` |
| Position | `.getPos(evt)` | `.getPos(evt)` | `.getBlockPos(hit-result)` |

### 3. `bridge.clj` - Container/Menu Bridge

**Purpose**: Wraps platform-specific container/menu classes to work with core GUI system.

**Key Responsibilities**:
- ✅ Generic container implementation (works for any GUI)
- ✅ Delegates to dispatcher protocol (no `instance?` checks)
- ✅ Uses GUI metadata for display names
- ❌ NO GUI-specific classes (no `NodeContainer`, `MatrixContainer`)
- ❌ NO hardcoded GUI logic

**Standard Implementation Pattern**:

```clojure
(ns my-mod.platformXXXX.bridge
  (:require [my-mod.gui.dispatcher :as dispatcher]
            [my-mod.gui.metadata :as gui-metadata]
            [my-mod.gui.slot-manager :as slot-manager]))

;; Generic container (works for all GUIs)
(defn -tick [this]
  (dispatcher/safe-tick! (-getClojureContainer this)))

(defn -stillValid [this player]
  (dispatcher/safe-validate (-getClojureContainer this) player))

(defn -broadcastChanges [this]
  (dispatcher/safe-sync! (-getClojureContainer this)))

(defn -quickMoveStack [this player slot-index]
  (slot-manager/execute-quick-move-platform
    (-getClojureContainer this) player slot-index))

(defn -getDisplayName [this]
  ;; Uses metadata instead of hardcoded strings
  (gui-metadata/get-display-name (.getGuiId this)))
```

**Platform-Specific Differences**:

| Aspect | Forge 1.16.5 | Forge 1.20.1 | Fabric 1.20.1 |
|--------|--------------|--------------|---------------|
| Base Class | `AbstractContainerMenu` | `AbstractContainerMenu` | `ScreenHandler` |
| Bridge Name | `ForgeMenuBridge` | `ForgeMenuBridge` | `FabricScreenHandlerBridge` |
| Quick Move | `quickMoveStack` | `quickMoveStack` | `transferSlot` |
| Validation | `stillValid` | `stillValid` | `canUse` |

### 4. `registry_impl.clj` - GUI Registry

**Purpose**: Register MenuTypes/ScreenHandlerTypes for all GUIs.

**Key Responsibilities**:
- ✅ Dynamic MenuType registration (zero hardcoded GUI names)
- ✅ Uses GUI metadata for configuration
- ✅ Map-based storage (not individual variables)
- ❌ NO GUI-specific variables (no `node-menu-type`, `matrix-menu-type`)
- ❌ NO hardcoded GUI IDs

**Standard Implementation Pattern**:

```clojure
(ns my-mod.platformXXXX.registry-impl
  (:require [my-mod.gui.metadata :as gui-metadata]))

;; Generic storage (replaces gui-specific variables)
(defonce gui-menu-types (atom {}))

(defn get-menu-type [gui-id]
  (get @gui-menu-types gui-id))

;; Dynamic registration loop
(defn register-menu-types! []
  (doseq [gui-id (gui-metadata/get-all-gui-ids)]
    (let [menu-type (create-menu-type gui-id)]
      (swap! gui-menu-types assoc gui-id menu-type)
      (register-with-platform menu-type))))

;; Generic menu type creation
(defn create-menu-type [gui-id]
  (let [registry-name (gui-metadata/get-registry-name gui-id)]
    ;; Create platform-specific MenuType using metadata
    ))
```

**Platform-Specific Differences**:

| Aspect | Forge 1.16.5 | Forge 1.20.1 | Fabric 1.20.1 |
|--------|--------------|--------------|---------------|
| Type Name | `MenuType` | `MenuType` | `ScreenHandlerType` |
| Creation | `IForgeMenuType.create()` | `IForgeMenuType.create()` | `new ScreenHandlerType<>()` |
| Registration | `DeferredRegister.register()` | `DeferredRegister.register()` | `Registry.register()` |

## Metadata Systems

### Registry Metadata (`registry/metadata.clj`)

**Location**: `core/src/main/clojure/my_mod/registry/metadata.clj`

**Purpose**: Provides registration information for all blocks and items.

**Key Functions**:
```clojure
(get-all-block-ids)              ; → ["demo-block", "copper-ore", ...]
(get-all-item-ids)               ; → ["demo-item", "copper-ingot", ...]
(get-block-registry-name "demo-block") ; → "demo_block"
(get-block-spec "demo-block")    ; → {:material :stone, :hardness 1.5, ...}
(should-create-block-item? "demo-block") ; → true
```

**How Platform Uses It**:
```clojure
;; Platform code never knows "demo-block" exists
(doseq [block-id (registry-metadata/get-all-block-ids)]
  (register-block block-id))
```

### Event Metadata (`events/metadata.clj`)

**Location**: `core/src/main/clojure/my_mod/events/metadata.clj`

**Purpose**: Maps blocks to their event handlers.

**Key Functions**:
```clojure
(identify-block-from-full-name "Block{my_mod:demo_block}") ; → "demo-block"
(has-event-handler? "demo-block" :on-right-click) ; → true
(get-block-event-handler "demo-block" :on-right-click) ; → fn
```

**Auto-Sync from DSL**:
```clojure
;; Called during initialization
(sync-handlers-from-dsl!)
;; Reads :on-right-click from all defblock definitions
;; Automatically registers handlers
```

**How Platform Uses It**:
```clojure
;; Platform code never checks for "demo_block" string
(let [block-id (event-metadata/identify-block-from-full-name block-name)]
  (when (event-metadata/has-event-handler? block-id :on-right-click)
    (dispatch-to-handler block-id event-data)))
```

### GUI Metadata (`gui/metadata.clj`)

**Location**: `core/src/main/clojure/my_mod/gui/metadata.clj`

**Purpose**: Provides configuration for all GUIs.

**Key Functions**:
```clojure
(get-all-gui-ids)                ; → [1 2 3 ...]
(get-display-name 1)             ; → "Demo GUI"
(get-registry-name 1)            ; → "demo_gui"
(get-slot-layout 1)              ; → [{:type :input, :x 10, ...}, ...]
```

**How Platform Uses It**:
```clojure
;; Dynamic MenuType creation for all GUIs
(doseq [gui-id (gui-metadata/get-all-gui-ids)]
  (create-and-register-menu-type gui-id))

;; Display name from metadata
(defn -getDisplayName [this]
  (gui-metadata/get-display-name (.getGuiId this)))
```

## Adding New Content (Zero Platform Changes)

### Adding a New Block

**Game Content** (`block/demo.clj`):
```clojure
(bdsl/defblock ruby-ore
  :material :stone
  :hardness 3.0
  :harvest-level 2
  :on-right-click (fn [ctx] (log/info "Ruby ore clicked!")))
```

**Platform Code Changes**: **NONE** ✅

The block is automatically:
1. Discovered via `registry-metadata/get-all-block-ids`
2. Registered in all three platforms
3. Gets a BlockItem created
4. Right-click handler registered via `event-metadata/sync-handlers-from-dsl!`

### Adding a New GUI

**Game Content** (`gui/demo.clj`):
```clojure
(gui-dsl/defgui enchanting-table-gui
  :id 3
  :title "Enchanting Table"
  :registry-name "enchanting_table"
  :slots [{:type :input :x 20 :y 40}
          {:type :output :x 120 :y 40}])
```

**Platform Code Changes**: **NONE** ✅

The GUI is automatically:
1. Discovered via `gui-metadata/get-all-gui-ids`
2. MenuType/ScreenHandlerType created in all platforms
3. Display name, slots, etc. pulled from metadata

### Adding a New Item

**Game Content** (`item/demo.clj`):
```clojure
(idsl/defitem emerald-shard
  :max-stack-size 16
  :creative-tab :materials
  :rarity :rare)
```

**Platform Code Changes**: **NONE** ✅

The item is automatically:
1. Discovered via `registry-metadata/get-all-item-ids`
2. Registered in all three platforms

## Verification Commands

To verify platform code contains no game concepts:

```bash
# Check for hardcoded block names
grep -r "demo_block\|demo-block-id\|copper-ore" forge-1.16.5/src/main/clojure/
grep -r "demo_block\|demo-block-id\|copper-ore" forge-1.20.1/src/main/clojure/
grep -r "demo_block\|demo-block-id\|copper-ore" fabric-1.20.1/src/main/clojure/

# All should return: No matches found ✅

# Check for game module imports
grep -r "block-demo\|item-demo\|my-mod.defs" forge-1.16.5/src/main/clojure/
grep -r "block-demo\|item-demo\|my-mod.defs" forge-1.20.1/src/main/clojure/
grep -r "block-demo\|item-demo\|my-mod.defs" fabric-1.20.1/src/main/clojure/

# All should return: No matches found ✅

# Verify metadata usage
grep -r "registry-metadata\|event-metadata\|gui-metadata" forge-1.16.5/src/main/clojure/
grep -r "registry-metadata\|event-metadata\|gui-metadata" forge-1.20.1/src/main/clojure/
grep -r "registry-metadata\|event-metadata\|gui-metadata" fabric-1.20.1/src/main/clojure/

# All should return: Multiple matches ✅
```

## Best Practices

### ✅ DO

1. **Query metadata** for all content discovery
2. **Use generic loops** (`doseq` over metadata results)
3. **Store in Maps/atoms** (not individual variables)
4. **Name functions generically** (`get-registered-block` not `get-demo-block`)
5. **Import metadata systems** (`registry-metadata`, `event-metadata`, etc.)
6. **Use dispatcher patterns** (protocols, not `instance?` checks)

### ❌ DON'T

1. **Hardcode content names** (no `"demo_block"`, `"copper-ore"`)
2. **Import game modules** (no `block-demo`, `item-demo`, `my-mod.defs`)
3. **Create content-specific variables** (no `demo-block`, `node-menu-type`)
4. **Use content-specific functions** (no `get-demo-block`, `open-node-gui`)
5. **Check content types** (no `instance? NodeContainer`)
6. **Hardcode GUI logic** (no `when (= gui-id 1)`)

## Summary

The current platform implementation achieves **100% platform neutrality** through:

1. **Metadata Systems** - Single source of truth for all content
2. **Dynamic Discovery** - Platform code queries, never hardcodes
3. **Generic Patterns** - Identical code across all platforms
4. **Zero Game Coupling** - Platform code has no game module dependencies

**Result**: Adding new content requires **zero platform code changes** ✅
