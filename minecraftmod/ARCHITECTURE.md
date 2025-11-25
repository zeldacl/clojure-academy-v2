# Architecture Documentation

## Design Goals

1. **Single Codebase**: One set of core logic for multiple Forge versions
2. **Clean Separation**: Core game logic in Clojure, version-specific adapters isolated
3. **Type Safety**: Java handles Forge API interop, Clojure for business logic
4. **Maintainability**: Adding new versions requires minimal core changes

## Layer Architecture

```
┌─────────────────────────────────────────────────┐
│  Minecraft Forge 1.16.5    Forge 1.20.1         │
│  (Java Ecosystem)          (Java Ecosystem)     │
└────────────┬────────────────────┬────────────────┘
             │                    │
             ▼                    ▼
┌────────────────────┐  ┌────────────────────┐
│  MyMod1165.java    │  │  MyMod1201.java    │
│  - @Mod entry      │  │  - @Mod entry      │
│  - DeferredRegister│  │  - DeferredRegister│
│  - Event listeners │  │  - Event listeners │
└────────┬───────────┘  └─────────┬──────────┘
         │                        │
         │  Clojure.var()         │
         ▼                        ▼
┌─────────────────────────────────────────────┐
│  my-mod.forge1165.*   my-mod.forge1201.*    │
│  (Clojure Adapters)   (Clojure Adapters)   │
│  - defmethod impls    - defmethod impls    │
│  - Version-specific   - Version-specific   │
└────────────┬────────────────┬───────────────┘
             │                │
             │  multimethod   │
             ▼                ▼
┌─────────────────────────────────────────────┐
│         my-mod.core (Clojure Core)          │
│  - Game logic                               │
│  - Multimethod definitions                  │
│  - Pure data/functions                      │
└─────────────────────────────────────────────┘
```

## Multimethod Dispatch Pattern

### How It Works

1. **Core defines abstractions**:
   ```clojure
   (def ^:dynamic *forge-version* nil)
   
   (defmulti register-item 
     (fn [_item-id _item-obj] *forge-version*))
   ```

2. **Each adapter implements for its version**:
   ```clojure
   ;; In forge1165/registry.clj
   (defmethod register-item :forge-1.16.5 [id obj]
     ;; 1.16.5-specific code
     ...)
   
   ;; In forge1201/registry.clj
   (defmethod register-item :forge-1.20.1 [id obj]
     ;; 1.20.1-specific code
     ...)
   ```

3. **Adapter sets version on init**:
   ```clojure
   (defn init-from-java []
     (alter-var-root #'registry/*forge-version* 
                     (constantly :forge-1.16.5))
     (core/init))
   ```

4. **Core calls multimethod**:
   ```clojure
   (defn init []
     (registry/register-item "demo_item" ...))
   ```

At runtime, the correct implementation is invoked based on `*forge-version*`.

## Compilation & Dependency Flow

### Core Module
- **Input**: Clojure source files (`*.clj`)
- **Output**: JVM bytecode (`.class` files) via AOT compilation
- **Plugin**: `dev.clojurephant.clojure`
- **Consumers**: Both Forge modules depend on core

### Forge Modules
- **Inputs**: 
  - Java source (`MyModXXXX.java`)
  - Clojure adapters (`forge1165/*.clj`, `forge1201/*.clj`)
  - Core dependency (compiled classes + resources)
- **Output**: Single jar with:
  - Forge mod classes
  - Clojure runtime
  - Core classes
  - Adapter classes
  - Assets from core
- **Plugins**: ForgeGradle + clojurephant

## Java ↔ Clojure Interop

### Java Calls Clojure

```java
// Load namespace
IFn require = Clojure.var("clojure.core", "require");
require.invoke(Clojure.read("my-mod.forge1165.init"));

// Call function
IFn initFn = Clojure.var("my-mod.forge1165.init", "init-from-java");
initFn.invoke();
```

### Clojure Calls Java (Implicit)

Clojure can directly invoke Java constructors/methods:
```clojure
(ns my-mod.blocks
  (:import [net.minecraft.block Block]))

(defn make-block []
  (Block. properties)) ; Calls Java constructor
```

However, in this architecture:
- **Core**: No direct Java imports (stays version-agnostic)
- **Adapters**: Import version-specific Minecraft/Forge classes

## Adding a New Operation

Example: Adding item tooltip support

### 1. Define in Core
```clojure
;; core/src/main/clojure/my_mod/tooltips.clj
(ns my-mod.tooltips)

(def ^:dynamic *forge-version* nil)

(defmulti add-tooltip 
  (fn [_item _tooltip-lines] *forge-version*))
```

### 2. Implement in Adapters
```clojure
;; forge-1.16.5/.../tooltips.clj
(ns my-mod.forge1165.tooltips
  (:require [my-mod.tooltips :as tt])
  (:import [net.minecraft.util.text StringTextComponent]))

(defmethod tt/add-tooltip :forge-1.16.5 [item lines]
  ;; 1.16.5: ITextComponent API
  (StringTextComponent. (str/join "\n" lines)))

;; forge-1.20.1/.../tooltips.clj  
(ns my-mod.forge1201.tooltips
  (:require [my-mod.tooltips :as tt])
  (:import [net.minecraft.network.chat Component]))

(defmethod tt/add-tooltip :forge-1.20.1 [item lines]
  ;; 1.20.1: Component API (different package/API)
  (Component/literal (str/join "\n" lines)))
```

### 3. Call from Core Logic
```clojure
(ns my-mod.items
  (:require [my-mod.tooltips :as tt]))

(defn create-demo-item [properties]
  ;; ...
  (tt/add-tooltip item ["Line 1" "Line 2"]))
```

## Resource Management

### Shared Assets
- Location: `core/src/main/resources/assets/my_mod/`
- Content: Models, blockstates, textures, lang files
- Inclusion: Both Forge builds reference this via `sourceSets.main.resources`

### Version-Specific Resources
- Location: `forge-X.Y.Z/src/main/resources/META-INF/`
- Content: `mods.toml` (loader metadata)
- Why separate: Different loader versions require different metadata formats

## Build Process

When running `./gradlew buildAll`:

1. **Core compilation**:
   - Clojurephant compiles all core `.clj` → `.class`
   - Resources copied to build output

2. **Forge 1.16.5 compilation**:
   - Java compilation: `MyMod1165.java` → `.class`
   - Clojure compilation: `forge1165/*.clj` → `.class`
   - Dependency resolution: Pulls core classes
   - ForgeGradle: Reobfuscates classes, creates mod jar

3. **Forge 1.20.1 compilation**:
   - Same process with 1.20.1 dependencies

4. **Gather jars**:
   - `gatherJars` task copies final jars to `build/distributions/`

## Runtime Behavior

### Mod Load Sequence (1.16.5 Example)

1. Forge discovers `my_mod` via `mods.toml`
2. Instantiates `MyMod1165` (Java constructor runs)
3. Constructor:
   - Registers blocks/items via DeferredRegister
   - Loads Clojure namespaces via `Clojure.var`
   - Calls `my-mod.forge1165.init/init-from-java`
4. Clojure init:
   - Sets `*forge-version*` to `:forge-1.16.5`
   - Loads adapter namespaces (registers multimethods)
   - Calls `my-mod.core/init` (uses multimethods)
5. Game loop:
   - Events trigger Java `@SubscribeEvent` methods
   - Java calls Clojure handlers via `Clojure.var`
   - Handlers execute core logic

## Version Isolation

**Key insight**: Each jar contains its own adapter namespaces.

- `my_mod-forge-1.16.5.jar` contains:
  - `my_mod/forge1165/*.class` (1.16.5 adapters)
  - NO `my_mod/forge1201/` classes
  
- `my_mod-forge-1.20.1.jar` contains:
  - `my_mod/forge1201/*.class` (1.20.1 adapters)
  - NO `my_mod/forge1165/` classes

At runtime, only one jar is loaded, so there's no classpath conflict.

## Advantages

1. **DRY**: Game logic written once in core
2. **Type Safety**: Java handles unsafe Forge API, Clojure stays pure
3. **Flexibility**: Easy to add new versions or remove old ones
4. **Testability**: Core logic testable without Minecraft runtime
5. **Gradual Migration**: Can migrate features from one version-specific impl to core abstractions incrementally

## Limitations

1. **Clojure Overhead**: Startup cost (namespace loading, multimethod dispatch)
2. **Classpath Size**: Each jar bundles Clojure runtime (~4MB)
3. **AOT Requirement**: Clojure must be compiled ahead-of-time (no REPL in prod)
4. **API Drift**: Major Forge changes (e.g., 1.12 → 1.16) may require significant adapter rewrites

## Future Enhancements

1. **Reflection-Free Adapters**: Use protocols instead of multimethods for performance
2. **Macro-Based Registration**: Auto-generate DeferredRegister calls from Clojure data
3. **GUI Framework**: Full Menu/Screen abstraction with declarative UI in Clojure
4. **Network Layer**: Abstract packet handling across versions
5. **1.12.2 Support**: Separate composite build due to ForgeGradle 2.x incompatibility
