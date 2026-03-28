# CLAUDE.md - Minecraft Clojure/Java Mod Rules

## 🛠 Build & Dev (Windows ./gradlew)
- **Root Strategy**: Always run from the parent directory containing `settings.gradle`.
- **LSP Sync**: Run `./gradlew :<subproject>:classes` after Java changes to refresh LSP index.
- **Fast Iteration**: `./gradlew :ac:compileClojure` or `:mcmod:compileClojure`.
- **Run Dev**: `./gradlew :forge-1.20.1:runClient`.

## 🧠 Tooling & Performance (Anti-429)
1. **LSP-First**: Use `clojure-lsp` and `java-lsp` for navigation. **STOP** using `grep` for definitions.
2. **Surgical Reads**: For files >100 lines, **MUST** use `limit` and `offset` (e.g., read L300-350).
3. **Context**: Monitor `/stats`. If context >40k tokens, run `/compact` immediately.
4. **Search**: Use `rg` (ripgrep) only. Ignore `build/` and `.gradle/` via `.claudeignore`.

## 🏗 Project Structure & Dependencies
- **Strict Dependency Flow**: `forge-1.20.1` → `mcmod` → `api` AND `ac` → `mcmod` → `api`.
- **The Red Line**: **`forge` and `ac` MUST NOT reference each other.** 
- **Module Roles**:
    - **`mcmod` (Pure Protocol Layer)**: 
        - **DEFINITIVE FORBIDDEN**: **DO NOT** import, reference, or generate code containing `net.minecraft.*` or any loader/library classes. 
        - **Role**: Defines cross-platform Clojure Protocols and Metadata schemas only. It serves as a pure "bridge" between `ac` (content) and `forge` (implementation).
    - **`ac` (Game Content)**: 
        - **DEFINITIVE FORBIDDEN**: Same as `mcmod`. Must only use `mcmod` protocols to interact with the world.
    - **`forge-1.20.1` (Adapter Layer)**: 
        - Implements `mcmod` protocols using actual Minecraft/Forge APIs.


## 📜 Coding Rules
- **Official-First**: **ALWAYS prioritize official Minecraft, Forge, or Fabric recommended patterns.** Use standard platform APIs and events over custom hacks or unofficial workarounds.
- **No Reflection**: All Java interop **MUST** use type hints (`^TypeName`). Set `*warn-on-reflection* true`.
- **Breaking Changes**: **DO NOT maintain compatibility with legacy code.** Prioritize clean, idiomatic, and modernized implementations over supporting deprecated patterns or old data structures.
- **Side Separation (Minecraft)**:
    - `**/client/**` = CLIENT-ONLY. Load via `side/resolve-client-fn`.
    - Common code **MUST NOT** import `net.minecraft.client.*`.
    - Java client classes **MUST** use `@OnlyIn(Dist.CLIENT)`.
- **DSL Pattern**: Content in `ac/` must be declared via `defblock`/`defitem` macros, which auto-register to `mcmod` metadata.

## ⚠️ Verification (LSP Preferred, then Bash)
- **Tooling Priority**: **ALWAYS** attempt to use `clojure-lsp` or `java-lsp` (e.g., `get-references`) to verify architecture before falling back to `rg`.
- **Architecture**: `rg "cn\.li\.forge|cn\.li\.fabric" ac/src/` (Must be empty).
- **Architecture**: `rg "cn\.li\.ac" forge-1.20.1/src/` (Must be empty).
- **Side Leakage**: `rg "net\.minecraft\.client" ac/src/` (Check against non-client folders).

## 📍 Key Logic Mapping
- **Structure Check**: `mcmod/.../block/multiblock_core.clj` & `dsl.clj`.
- **Wireless Logic**: `ac/.../wireless/network.clj`.
