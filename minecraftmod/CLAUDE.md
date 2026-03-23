# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

All builds use the Gradle wrapper on Windows (use `./gradlew` in bash):

```bash
# Build all active modules
./gradlew buildAll

# Build individual platform
./gradlew :forge-1.20.1:build

# Compile Clojure only (faster iteration)
./gradlew :ac:compileClojure
./gradlew :mcmod:compileClojure
./gradlew :forge-1.20.1:compileClojure

# Run dev client
./gradlew :forge-1.20.1:runClient

# Clean
./gradlew clean
```

Output jars go to `build/distributions/` (via `gatherJars` task) and per-module `build/libs/`.

**Note:** Fabric is currently commented out in `settings.gradle` — only `mcmod`, `ac`, and `forge-1.20.1` are active subprojects.

**Java requirement:** Java 17+ for all active modules.

## Project Structure

```
minecraftmod/
├── api/            # (java) (namespace: cn/li/acapi) The api for other mod
├── mcmod/          # (java+clojure) (namespace: cn/li/mcmod) Platform-agnostic base: block/item/GUI DSLs, metadata systems, protocols
├── ac/             # (clojure) (namespace: cn/li/ac) Game content: wireless energy system, blocks, items, GUIs, renderers
├── forge-1.20.1/   # (java+clojure) (namespace: cn/li/forge1201) Forge 1.20.1 platform adapter (Java @Mod entry + Clojure adapters)
└── fabric-1.20.1/  # (java+clojure) (namespace: cn/li/fabric1201) Fabric 1.20.1 adapter (currently disabled in settings.gradle)
```

Module dependency chain1: `forge-1.20.1` → `mcmod`→ `api`
Module dependency chain2: `ac` → `mcmod`→ `api`

## Architecture

### Layer Model

1. **Java entry points** (`MyMod1201.java`) — minimal `@Mod` classes that call into Clojure via `Clojure.var()`.
2. **Platform adapters** (`forge-1.20.1/`, `fabric-1.20.1/`) — generic Clojure code that queries metadata for all registration and event dispatch. **Zero hardcoded game content names here.**
3. **Metadata systems** (`mcmod/registry/metadata.clj`, `events/metadata.clj`, `gui/metadata.clj`) — single source of truth populated by DSL macros.
4. **DSL systems** (`mcmod/block/dsl.clj`, `item/dsl.clj`, `gui/dsl.clj`) — macros (`defblock`, `defitem`, `defgui`) that define content and auto-register into metadata.
5. **Game content** (`ac/`) — uses DSLs to declare all blocks, items, GUIs, and wireless logic.

### Key Patterns

- **Metadata-driven registration**: Platform adapters iterate `(registry-metadata/get-all-block-ids)` etc. — adding new content requires zero platform code changes.
- **Protocol-based platform abstraction**: `cn.li.platform.item/IItemStack` is implemented by both Forge and Fabric `platform_impl.clj` — use protocol functions, not platform-specific Java calls, in shared code.
- **Multimethod dispatch**: Version-specific behavior is dispatched via multimethods keyed on platform version (`:forge-1.20.1`, `:fabric-1.20.1`).
- **Wireless/multiblock system**: `ac/wireless/network.clj` manages SSID-based energy networks; `ac/block/wireless_node.clj` and `wireless_matrix.clj` use multiblock routing via `mcmod/block/multiblock_core.clj`.

### Key Namespaces in `ac/`

- `cn.li.ac.wireless.network` — `WirelessNet` record, network lifecycle
- `cn.li.ac.wireless.gui.*` — GUI network handlers, matrix/node container and XML layout
- `cn.li.ac.block.wireless_node`, `wireless_matrix`, `solar_gen` — block definitions
- `cn.li.ac.client.render.*` — client-side block entity renderers
- `cn.li.ac.energy.*` — energy operations and item energy abstraction

### GUI System

GUIs are defined in XML-style Clojure data (`*_gui_xml.clj`), parsed by `mcmod/gui/xml_parser.clj`, with sync handled by `sync_helpers.clj`. Network handlers (`*_network_handler.clj`) bridge server↔client GUI state. Platform-specific menu/screen implementations live in `forge-1.20.1/gui/`.

## Coding Rules

- **No reflection in Clojure**: All Java interop must use type hints to avoid reflection. Never use untyped Java method calls — always add `^TypeName` hints to eliminate reflection warnings. Use `(set! *warn-on-reflection* true)` to detect violations.

### Client/Server Code Separation

The mod produces a single JAR that runs on both client and dedicated server. Strict separation rules prevent client-only code from loading on servers:

#### Architecture Constraints

1. **Dependency chain**: `platform (forge/fabric)` → `mcmod` → `ac` (one-way only, no reverse dependencies)
2. **ac/ and mcmod/ modules**: MUST NEVER directly import Minecraft classes (`net.minecraft.*`)
3. **ac/ and platform modules**: MUST NOT directly reference each other — all interactions go through `mcmod` as intermediary
4. **Client-only Minecraft classes** (`net.minecraft.client.*`): Can ONLY be imported in platform implementation CLIENT sublayer

#### Directory Convention

- `*/client/*` directories = CLIENT-ONLY code (must be loaded via side-checked `requiring-resolve`)
- `*/` (root) = COMMON code (safe for both client and server)

Examples:
- `mcmod/src/main/clojure/cn/li/mcmod/client/` - CLIENT-ONLY
- `ac/src/main/clojure/cn/li/ac/client/render/` - CLIENT-ONLY
- `forge-1.20.1/src/main/clojure/cn/li/forge1201/client/` - CLIENT-ONLY
- `forge-1.20.1/src/main/java/cn/li/forge1201/client/` - CLIENT-ONLY (must use `@OnlyIn`)

#### Code Organization Rules

1. **Common code must never import client classes**:
   ```clojure
   ;; ❌ BAD - Direct client import in common code
   (ns cn.li.mcmod.block.logic
     (:import [net.minecraft.client Minecraft]))

   ;; ✅ GOOD - Use protocol or lazy resolution
   (ns cn.li.mcmod.block.logic)
   (when-let [get-client (requiring-resolve 'cn.li.mcmod.client.accessor/get-minecraft)]
     (get-client))
   ```

2. **Client code must be loaded via side-checked `requiring-resolve`**:
   ```clojure
   ;; In platform layer (forge-1.20.1/mod.clj)
   (require '[cn.li.forge1201.side :as side])

   (when (side/client-side?)
     (when-let [init-client! (side/resolve-client-fn 'cn.li.forge1201.client.init 'init-client)]
       (init-client!)))
   ```

3. **Java client classes must use `@OnlyIn(Dist.CLIENT)`**:
   ```java
   import net.minecraftforge.api.distmarker.Dist;
   import net.minecraftforge.api.distmarker.OnlyIn;

   @OnlyIn(Dist.CLIENT)
   public class ClientProxy {
       // Client-only methods
   }
   ```

4. **Client namespaces must document their side requirement**:
   ```clojure
   (ns cn.li.forge1201.client.init
     "CLIENT-ONLY: Must be loaded via side-checked requiring-resolve.
     This namespace contains client-side initialization code.")
   ```

#### Side Detection System

Each platform adapter provides runtime side detection:

- **Forge**: `cn.li.forge1201.side/client-side?`, `server-side?`, `resolve-client-fn`
- **Fabric**: `cn.li.fabric1201.side/client-side?`, `server-side?`, `resolve-client-fn`

Use `resolve-client-fn` to safely load client-only functions:
```clojure
(when-let [init! (side/resolve-client-fn 'cn.li.forge1201.client.init 'init-client)]
  (init!))
```

#### Verification Commands

Before committing, verify architecture compliance:

```bash
# Check ac/ has no Minecraft imports
grep -r "import.*minecraft" ac/src/main/clojure/

# Check ac/ has no platform imports
grep -r "cn\.li\.forge\|cn\.li\.fabric" ac/src/main/clojure/

# Check mcmod/ has no Minecraft imports
grep -r "import.*minecraft" mcmod/src/main/clojure/

# Check mcmod/ has no platform imports
grep -r "cn\.li\.forge\|cn\.li\.fabric" mcmod/src/main/clojure/

# Check platform isolates client imports
grep -r "net\.minecraft\.client" forge-1.20.1/src/main/clojure/ | grep -v "/client/"

# Check Java client classes have @OnlyIn
find forge-1.20.1/src/main/java -name "*.java" -exec grep -l "net.minecraft.client" {} \; | xargs grep -L "@OnlyIn"
```

All commands should return no matches (or only acceptable exceptions like comments).

## Troubleshooting

- **Clojure compilation errors**: Check `require` chains — missing namespace will fail the whole compile. Run `./gradlew :forge-1.20.1:compileClojure --info` for detail.
- **Gradle daemon issues**: `./gradlew --stop && ./gradlew clean`
- **GUI not opening**: Check that the `defgui` registration ran before the platform adapter's `register-all-guis!` call during mod init.


## Compact Instructions

When compressing, preserve in priority order:

1. Architecture decisions (NEVER summarize)
2. Modified files and their key changes
3. Current verification status (pass/fail)
4. Open TODOs and rollback notes
5. Tool outputs (can delete, keep pass/fail only)
