# CLAUDE.md - Minecraft Clojure/Java Mod Rules

## 🛠 Build & Dev (Windows ./gradlew)
- **Root Strategy**: Always run from the parent directory containing `settings.gradle`.
- **LSP Sync**: Run `./gradlew :<subproject>:classes` after Java changes to refresh LSP index.
- **Fast Iteration**: `./gradlew :ac:compileClojure` or `:mcmod:compileClojure`.
- **Run Dev**: `./gradlew :forge-1.20.1:runClient`.

## 🧠 Tooling & Performance (Anti-429)
1. **LSP-First**: Use `clojure-lsp` and `java-lsp` for navigation. **STOP** using `grep` for definitions.
2. - **Surgical Reads**: When using `read_file`, **MUST** use the `pages` parameter (e.g., `"1"`, `"1-2"`). DO NOT use `offset` or `limit`. One page is approx 300 lines.
3. **Context**: Monitor `/stats`. If context >40k tokens, run `/compact` immediately.
4. **Search**: Use `rg` (ripgrep) only. Ignore `build/` and `.gradle/` via `.claudeignore`.
5. **LSP Tooling Constraint**: **NEVER** pass a directory path to the `filePath` argument in `lsp` tools. For `workspaceSymbol`, leave `filePath` empty or null. If searching within a scope, use `grep` or `rg` instead, or specify a valid `.clj` file.
6. - **Precision Reading**: When using `read_file`, **MUST** use the `pages` parameter (e.g., `"1-2"`) instead of offset/limit. 
7. - **Scale**: One page is approximately 300 lines. To read 50 lines around a definition, use the specific page number or a small range.



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
- **Remap Safety**: Do not rely on `eval`, string `Class/forName`, or cross-layer `resolve` for Minecraft/Forge symbols. Loom/Tiny Remapper remaps bytecode references, not arbitrary strings.
- **Eval Rule**: `eval` forms must not contain `net.minecraft.*` / `net.minecraftforge.*` class symbols or class-name strings. Move such logic to Java Bridge or another static bytecode path.
- **Resolve Rule**: `ac` must not `resolve` / `requiring-resolve` `cn.li.forge1201.*`. Use injected bridge vars/functions instead.
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
