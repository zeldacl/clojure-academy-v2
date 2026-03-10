# Clojure Minecraft Mod Framework - Workspace Instructions

## Project Overview

This is a **multi-loader Minecraft mod framework** written primarily in **Clojure** (99%), supporting both **Forge 1.20.1** and **Fabric 1.20.1** from a single codebase. The architecture uses declarative DSLs for blocks, items, GUIs, tiles, and NBT serialization with a metadata-driven platform layer.

**Key Features:**
- Single codebase for multiple mod loaders (Forge/Fabric)
- Declarative DSLs for game content (blocks, items, GUIs)
- Metadata-driven architecture (platform code contains zero hardcoded game content)
- REPL-friendly interactive development
- Functional, immutable state management

## Quick Start

### Prerequisites
- **Java 17+** (required for both Forge and Fabric 1.20.1)
- Gradle wrapper included (no separate installation needed)
- Active REPL connection via Calva for interactive development

### Build Commands (PowerShell)

```powershell
# Build all modules from root
.\gradlew clean buildAll

# Build individual platforms
.\gradlew :forge-1.20.1:build
.\gradlew :fabric-1.20.1:build

# Compile Clojure only (faster iteration)
.\gradlew :mcmod:compileClojure  # Core foundation
.\gradlew :ac:compileClojure     # Game content

# Run development clients
.\gradlew :forge-1.20.1:runClient
.\gradlew :fabric-1.20.1:runClient
```

**Build outputs:**
- Platform JARs: `{platform}/build/libs/*.jar`
- Combined distributions: `build/distributions/*.jar`

### Testing In-Game
```powershell
# In Minecraft chat after launching runClient:
/give @p my_mod:demo_block
/give @p my_mod:wireless_node_basic
```

## Architecture Principles

### 1. **Metadata-Driven Platform Neutrality**

Platform code (forge-1.20.1, fabric-1.20.1) **never hardcodes game content**. All registration is driven by querying metadata systems.

**Bad (violates principle):**
```clojure
;; ❌ Platform code hardcoding specific blocks
(register-block "demo_block" ...)
(when (.contains block-name "wireless_node") ...)
```

**Good (follows principle):**
```clojure
;; ✅ Platform queries metadata dynamically
(doseq [block-id (registry-metadata/get-all-block-ids)]
  (register-block block-id))

(when-let [handler (event-metadata/get-event-handler block-id :on-right-click)]
  (handler event-data))
```

### 2. **Layer Separation**

```
┌─────────────────────────────────────────────────────────┐
│  Platform Modules (forge-1.20.1, fabric-1.20.1)        │
│  - Java entrypoints (@Mod, ModInitializer)             │
│  - Platform adapters (registry.clj, events.clj)        │
│  - Queries metadata, contains NO game logic            │
└────────────────────┬────────────────────────────────────┘
                     │ queries
┌────────────────────▼────────────────────────────────────┐
│  Metadata Systems (mcmod)                               │
│  - registry/metadata.clj (what to register)            │
│  - events/metadata.clj (event handler routing)         │
│  - block/tile_dsl.clj (tile entity specs)              │
└────────────────────┬────────────────────────────────────┘
                     │ populated by
┌────────────────────▼────────────────────────────────────┐
│  DSL Systems (mcmod)                                    │
│  - block/dsl.clj (defblock macro)                      │
│  - item/dsl.clj (defitem macro)                        │
│  - gui/dsl.clj (defgui macro)                          │
│  - block/tile_dsl.clj (deftile macro)                  │
│  - nbt/dsl.clj (defnbt macro)                          │
└────────────────────┬────────────────────────────────────┘
                     │ used by
┌────────────────────▼────────────────────────────────────┐
│  Game Content (ac)                                      │
│  - block/*.clj (wireless nodes, demo blocks)           │
│  - item/*.clj (materials, tools)                       │
│  - gui/*.clj (GUI definitions and controllers)         │
│  - All game-specific logic lives here ONLY             │
└─────────────────────────────────────────────────────────┘
```

### 3. **Module Responsibilities**

| Module | Purpose | Can Import | Cannot Import |
|--------|---------|------------|---------------|
| **mcmod** | Platform-neutral foundation (DSLs, abstractions) | Clojure stdlib, data libs | Minecraft APIs, Forge/Fabric |
| **ac** | Game content and logic | mcmod, Clojure libs | Minecraft APIs directly |
| **forge-1.20.1** | Forge platform adapter | mcmod, ac, Forge APIs | Fabric APIs |
| **fabric-1.20.1** | Fabric platform adapter | mcmod, ac, Fabric APIs | Forge APIs |

**File locations:**
- Core code: `mcmod/src/main/clojure/my_mod/`
- Game code: `ac/src/main/clojure/my_mod/`
- Platform adapters: `{platform}/src/main/clojure/my_mod/{forge1201|fabric1201}/`
- Java entrypoints: `{platform}/src/main/java/`

## DSL Patterns

### Block Definition

Use `defblock` from `my-mod.block.dsl` to declaratively define blocks:

```clojure
(ns my-mod.block.my-blocks
  (:require [my-mod.block.dsl :as bdsl]))

(bdsl/defblock my-custom-block
  :registry-name "my_custom_block"     ; Required: asset/registry ID
  :material :metal                      ; Physical properties
  :hardness 3.0
  :resistance 10.0
  :light-level 15
  :requires-tool true
  :harvest-tool :pickaxe
  :harvest-level 2
  :sounds :metal
  :on-right-click handle-click-fn      ; Event hooks (must be resolvable symbols)
  :on-place handle-place-fn
  :on-break handle-break-fn
  :block-state-properties props)       ; Optional: dynamic visual state
```

**Block State Properties** (for visual state like energy level, connected):
```clojure
(def block-state-properties
  [{:name "energy" :type :int :values [0 1 2 3 4]}
   {:name "connected" :type :boolean}])
```

### Item Definition

Use `defitem` from `my-mod.item.dsl`:

```clojure
(ns my-mod.item.my-items
  (:require [my-mod.item.dsl :as idsl]))

(idsl/defitem my-custom-item
  :id "my_custom_item"           ; Required: registry ID
  :max-stack-size 16             ; Default 64, must be 1 for durability
  :creative-tab :tools
  :durability 500                ; Optional: makes item damageable
  :on-use use-handler-fn
  :properties {:tooltip ["Line 1" "Line 2"]
               :display-name "Custom Item"})
```

### Tile Entity (BlockEntity) Definition

Use `deftile` from `my-mod.block.tile-dsl`. One tile can serve **multiple block variants**:

```clojure
(ns my-mod.block.my-tiles
  (:require [my-mod.block.tile-dsl :as tdsl]))

(tdsl/deftile my-custom-tile
  :id "my_tile"                              ; Tile entity type ID
  :impl :scripted                            ; Use :scripted for tick/NBT logic
  :blocks ["block_variant_1"                 ; Block IDs using this tile
           "block_variant_2"
           "block_variant_3"]
  :tick-fn my-tick-fn                        ; Called every game tick
  :read-nbt-fn my-load-fn                    ; Deserialize from NBT
  :write-nbt-fn my-save-fn)                  ; Serialize to NBT
```

**Tile tick function signature:**
```clojure
(defn my-tick-fn
  [be level pos state]
  ;; be = BlockEntity instance
  ;; level = Level (world)
  ;; pos = BlockPos
  ;; state = BlockState
  ;; Mutate state via (.setCustomState be new-state-map)
  )
```

### NBT Serialization

Use `defnbt` to auto-generate read/write functions:

```clojure
(ns my-mod.block.my-nbt
  (:require [my-mod.nbt.dsl :as ndsl]))

(ndsl/defnbt my-data
  [:energy "energy" :double]
  [:name "name" :string]
  [:inventory "inventory" :inventory :atom? true]
  [:enabled "enabled" :boolean :default true])

;; Auto-generates:
;; - write-my-data-to-nbt [compound data]
;; - read-my-data-from-nbt [compound]
```

**Supported types:** `:double`, `:float`, `:long`, `:int`, `:boolean`, `:string`, `:keyword`, `:inventory`, `:atom`

**Options:**
- `:atom? true` — Field is an atom, dereference before write
- `:default value` — Default value if NBT key missing
- `:transform-write fn` — Transform before writing
- `:transform-read fn` — Transform after reading

### GUI Definition

Use `defgui` from `my-mod.gui.dsl`:

```clojure
(ns my-mod.gui.my-guis
  (:require [my-mod.gui.dsl :as gui]))

(gui/defgui my-custom-gui
  :gui-id 5                                  ; Unique integer ID for platform registration
  :display-name "My Custom GUI"
  :gui-type :custom
  :registry-name "my_custom_gui"
  :slot-layout slot-layout-spec              ; Define inventory slots
  :container-predicate container-check-fn    ; Validate GUI can open
  :container-fn create-container-fn          ; Factory for Container
  :screen-fn create-screen-fn                ; Factory for client Screen
  :tick-fn tick-fn                           ; Called every tick when open
  :sync-get get-sync-data-fn                 ; Gather data to sync to client
  :sync-apply apply-sync-data-fn)            ; Apply synced data
```

**Slot layout example:**
```clojure
(def slot-layout
  {:tile-slots [[0 3]]        ; Tile entity slots 0-3 (inclusive)
   :player-inventory [[9 44]] ; Player inventory slots 9-44
   :player-hotbar [[0 8]]})   ; Player hotbar slots 0-8
```

## Common Patterns

### 1. **Pure Functional State Updates**

Tile entity state should be updated via pure functions, not mutation:

```clojure
(defn tick-charge [state level pos]
  (let [current-energy (:energy state)
        new-energy (+ current-energy 10.0)]
    (assoc state :energy new-energy)))  ; Return new state map

(defn my-tick-fn [be level pos state]
  (let [current-state (.getCustomState be)
        new-state (-> current-state
                      (tick-charge level pos)
                      (tick-discharge level pos))]
    (.setCustomState be new-state)))
```

### 2. **Event Handler Signatures**

Event handlers receive a context map:

```clojure
(defn handle-block-click
  [{:keys [world pos player block-state sneaking hand]}]
  ;; Return map for GUI opening:
  {:gui-id 0
   :player player
   :world world
   :pos pos}
  ;; Or return nil for no action
  )
```

### 3. **Registry Name Conversions**

Always use metadata functions for consistency:

```clojure
;; ✅ Correct
(registry-metadata/get-block-registry-name block-id)  ; "my_block" -> "my_mod:my_block"

;; ❌ Wrong
(str "my_mod:" block-id)  ; Inconsistent with metadata
```

### 4. **Metadata Query Pattern**

Platform code should always check metadata before dispatching:

```clojure
;; In platform event listener
(when (event-metadata/has-event-handler? block-id :on-right-click)
  (let [handler (event-metadata/get-event-handler block-id :on-right-click)
        event-data {:world level :pos pos :player player}]
    (handler event-data)))
```

### 5. **Block State Schema**

For blocks with dynamic visual state (energy, connections), use state schema:

```clojure
(require '[my-mod.block.state-schema :as ss])

(def my-schema
  [{:key :energy
    :nbt-key "energy"
    :nbt-type :double
    :block-state-property "energy"
    :block-state-type :int
    :block-state-transformer #(int (/ % 1000))}])  ; Map energy to 0-4 visual state

;; Auto-generates:
(def load-fn (ss/schema->load-fn my-schema))
(def save-fn (ss/schema->save-fn my-schema))
(def update-visual (ss/schema->block-state-updater my-schema))
```

## Clojure Conventions

### Code Style

- **Naming:** `kebab-case` for functions/vars, `PascalCase` for protocols/records
- **Pure functions:** Mark impure functions with `!` suffix (e.g., `save-data!`)
- **Threading:** Use `->` and `->>` for data transformations
- **Destructuring:** Destructure in parameter lists when possible

```clojure
;; ✅ Good
(defn process-node
  [{:node/keys [energy name password]
    :keys [world pos]}]
  (when (>= energy 100.0)
    (transmit-signal! world pos)))
```

### Inline `def` for REPL Debugging

Use inline `def` to capture intermediate values during REPL development:

```clojure
(defn process-data [input]
  (def input input)  ; Capture for REPL inspection
  (let [processed (transform input)]
    (def processed processed)  ; Capture intermediate result
    (finalize processed)))
```

### Alignment for Bracket Balancing

**Critical:** Always align multi-line elements vertically. Misalignment breaks bracket balancing:

```clojure
;; ❌ Wrong - misaligned
(select-keys m [:key-a
                :key-b
               :key-c])

;; ✅ Correct - aligned
(select-keys m [:key-a
                :key-b
                :key-c])
```

### REPL-First Development

1. **Develop in REPL first** before editing files
2. **Never edit without REPL** — if REPL unavailable, stop and notify user
3. **Reload after edits:** `(require 'my.namespace :reload)`
4. **Use return values** over `println` for debugging

## Common Pitfalls

### 1. **Hardcoding Game Content in Platform Code**

**Problem:** Platform code contains specific block/item names
**Solution:** Query metadata systems instead

```clojure
;; ❌ Wrong
(when (= block-id "wireless_node_basic")
  (do-something))

;; ✅ Correct
(when (registry-metadata/has-block-entity? block-id)
  (do-something))
```

### 2. **Loading Platform Implementations Late**

**Problem:** Core code tries to use protocols before platform extends them
**Solution:** Load `platform_impl.clj` first in platform `mod.clj` init

```clojure
;; In forge-1.20.1/src/main/clojure/my_mod/forge1201/mod.clj
(ns my-mod.forge1201.mod)

(require 'my-mod.forge1201.platform-impl)  ;; ✅ Load FIRST
(require 'my-mod.registry.metadata)
```

### 3. **Stack Size vs Durability Conflict**

**Problem:** Item has both `:max-stack-size > 1` and `:durability`
**Solution:** Durable items MUST have `:max-stack-size 1`

```clojure
;; ❌ Wrong - contradictory
(idsl/defitem broken-tool
  :max-stack-size 16
  :durability 500)

;; ✅ Correct
(idsl/defitem working-tool
  :max-stack-size 1
  :durability 500)
```

### 4. **Block State Properties vs Persistent State**

**Problem:** Treating BlockState properties as persistent storage
**Solution:** BlockState properties are VISUAL ONLY, derived from tile entity state

```clojure
;; Block state properties are visual projections
;; Persistent data lives in tile entity customState map
;; Use state-schema to sync: customState -> BlockState properties
```

### 5. **Forgetting to Sync Event Handlers**

**Problem:** `defblock` defines `:on-right-click` but metadata system doesn't know
**Solution:** Platform `mod.clj` must call sync function at startup

```clojure
;; In platform mod.clj initialization
(event-metadata/sync-handlers-from-registry!)
```

### 6. **Client/Server Separation**

**Problem:** Calling client-only APIs from server code crashes dedicated servers
**Solution:** Keep rendering, keybinds, screens in client-only namespaces

```clojure
;; my-mod.client.render/* — Client only
;; my-mod.block.logic/* — Shared (careful with imports)
;; my-mod.gui.container/* — Shared (server needs Container)
;; my-mod.gui.screen/* — Client only (Screen is client-only)
```

### 7. **Registry Name Stability**

**Problem:** Changing `:registry-name` breaks existing worlds
**Solution:** Registry names are stable API—never change casually

```clojure
;; ❌ Wrong - breaking change
(bdsl/defblock my-block
  :registry-name "new_name"  ; Was "old_name"
  ...)

;; ✅ Correct - stable names
(bdsl/defblock my-block
  :registry-name "my_block"  ; Never change
  ...)
```

## Key Files Reference

### Core DSLs (mcmod)
- [mcmod/src/main/clojure/my_mod/block/dsl.clj](mcmod/src/main/clojure/my_mod/block/dsl.clj) — Block DSL
- [mcmod/src/main/clojure/my_mod/item/dsl.clj](mcmod/src/main/clojure/my_mod/item/dsl.clj) — Item DSL
- [mcmod/src/main/clojure/my_mod/gui/dsl.clj](mcmod/src/main/clojure/my_mod/gui/dsl.clj) — GUI DSL
- [mcmod/src/main/clojure/my_mod/block/tile_dsl.clj](mcmod/src/main/clojure/my_mod/block/tile_dsl.clj) — Tile DSL
- [mcmod/src/main/clojure/my_mod/nbt/dsl.clj](mcmod/src/main/clojure/my_mod/nbt/dsl.clj) — NBT DSL

### Metadata Systems (mcmod)
- [mcmod/src/main/clojure/my_mod/registry/metadata.clj](mcmod/src/main/clojure/my_mod/registry/metadata.clj) — Registry metadata
- [mcmod/src/main/clojure/my_mod/events/metadata.clj](mcmod/src/main/clojure/my_mod/events/metadata.clj) — Event routing
- [mcmod/src/main/clojure/my_mod/block/state_schema.clj](mcmod/src/main/clojure/my_mod/block/state_schema.clj) — State schema

### Example Content (ac)
- [ac/src/main/clojure/my_mod/block/wireless_node.clj](ac/src/main/clojure/my_mod/block/wireless_node.clj) — Advanced block example
- [ac/src/main/clojure/my_mod/gui/definitions.clj](ac/src/main/clojure/my_mod/gui/definitions.clj) — GUI definitions
- [ac/src/main/clojure/my_mod/item/mat_core.clj](ac/src/main/clojure/my_mod/item/mat_core.clj) — Item examples

### Platform Adapters
- [forge-1.20.1/src/main/clojure/my_mod/forge1201/mod.clj](forge-1.20.1/src/main/clojure/my_mod/forge1201/mod.clj) — Forge entry
- [fabric-1.20.1/src/main/clojure/my_mod/fabric1201/mod.clj](fabric-1.20.1/src/main/clojure/my_mod/fabric1201/mod.clj) — Fabric entry
- `{platform}/src/main/clojure/my_mod/{forge1201|fabric1201}/registry.clj` — Platform registry
- `{platform}/src/main/clojure/my_mod/{forge1201|fabric1201}/events.clj` — Platform events

### Documentation
- [ARCHITECTURE.md](ARCHITECTURE.md) — Architecture overview
- [BUILD.md](BUILD.md) — Build instructions
- [docs/03-dsl/](docs/03-dsl/) — DSL guides (Chinese)
- [docs/02-architecture/](docs/02-architecture/) — Architecture deep-dives

## Adding New Features

### New Block Type

1. Create file in `ac/src/main/clojure/my_mod/block/`
2. Define with `defblock`:
   ```clojure
   (bdsl/defblock my-new-block
     :registry-name "my_new_block"
     :material :stone
     :hardness 3.0
     ...)
   ```
3. If needs persistent data, create `deftile`
4. If needs GUI, create `defgui`
5. Assets: `ac/src/main/resources/assets/my_mod/models/block/my_new_block.json`
6. **Platform code requires NO changes** — metadata discovery is automatic

### New Platform Support (e.g., Forge 1.19.2)

1. Create subproject directory `forge-1.19.2/`
2. Add to `settings.gradle`: `include 'forge-1.19.2'`
3. Copy and adapt `build.gradle` from existing platform
4. Create adapter namespaces (mod.clj, registry.clj, events.clj)
5. Implement platform-specific protocols
6. Test with `.\gradlew :forge-1.19.2:runClient`

### New DSL Type

1. Create in `mcmod/src/main/clojure/my_mod/{domain}/dsl.clj`
2. Define registry atom and spec record
3. Create macro that populates registry
4. Add metadata query functions
5. Update platform adapters to query new metadata

## Performance Considerations

- **Tile entity ticks:** Limit per-tick work, use ticker modulo for periodic tasks
  ```clojure
  (when (zero? (mod ticker 20))  ; Every second
    (expensive-operation))
  ```
- **Transients:** Use for building large collections in loops
- **Type hints:** Add `^long`, `^double` in hot numeric loops
- **Lazy sequences:** Don't hold head in loops (prevents GC)
- **Caching:** Cache expensive computations (e.g., network topology)

## Troubleshooting

### "Unsupported class file major version" Error
- Java version mismatch. This project requires **Java 17+**
- Check: `java -version`
- Set `JAVA_HOME` to JDK 17+ installation

### Clojure Compilation Errors
```powershell
.\gradlew :mcmod:compileClojure --info  # Detailed logs
```
- Check all `require` dependencies exist
- Verify namespace matches file path (my-mod.block.demo → my_mod/block/demo.clj)

### REPL Unavailable
- **Never edit without REPL** — stop and ask user to reconnect
- Check Calva connection status in VS Code
- Restart REPL if needed

### Platform Not Loading Content
- Check platform `mod.clj` loads `platform_impl` first
- Verify metadata sync called: `(registry-metadata/sync-all!)`
- Check event handler sync: `(event-metadata/sync-handlers-from-registry!)`

### GUI Not Opening
- Verify `:gui-id` is unique across all GUIs
- Check `:container-predicate` returns true
- Confirm slot-layout indices don't overlap

## Development Workflow

1. **Start REPL** via Calva (Connect to Jack-in)
2. **Write code in REPL** first to validate logic
3. **Move to files** after validation
4. **Reload namespace** after file edits
5. **Test in-game** via `runClient`
6. **Build** when ready for distribution

**Typical iteration cycle:**
```clojure
;; 1. Develop in REPL
(def test-data {:energy 100.0 :name "Node"})
(defn process [data] ...)

;; 2. Move to file (e.g., my_tile.clj)

;; 3. Reload
(require 'my-mod.block.my-tile :reload)

;; 4. Test in-game or via unit tests
```

## Testing

- **Unit tests:** `{module}/src/test/clojure/`
- **Run tests:** `.\gradlew test`
- **In-game testing:** Use `/give` commands and creative mode
- **REPL testing:** Prefer unit tests over println debugging

---

**Remember:** This framework prioritizes declarative clarity, functional purity, and metadata-driven platform neutrality. Always prefer querying metadata over hardcoding, and pure functions over mutation.
