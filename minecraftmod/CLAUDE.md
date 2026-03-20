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
├── mcmod/          # Platform-agnostic base: block/item/GUI DSLs, metadata systems, protocols
├── ac/             # Game content: wireless energy system, blocks, items, GUIs, renderers
├── forge-1.20.1/   # Forge 1.20.1 platform adapter (Java @Mod entry + Clojure adapters)
└── fabric-1.20.1/  # Fabric 1.20.1 adapter (currently disabled in settings.gradle)
```

Module dependency chain: `forge-1.20.1` → `ac` → `mcmod`

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

## Troubleshooting

- **Clojure compilation errors**: Check `require` chains — missing namespace will fail the whole compile. Run `./gradlew :forge-1.20.1:compileClojure --info` for detail.
- **Gradle daemon issues**: `./gradlew --stop && ./gradlew clean`
- **GUI not opening**: Check that the `defgui` registration ran before the platform adapter's `register-all-guis!` call during mod init.
