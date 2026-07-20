# `^:dynamic` binding audit (P0 keep-list)

Produced for the top-level-state refactor (see
[TOP_LEVEL_STATE_GOVERNANCE.md](TOP_LEVEL_STATE_GOVERNANCE.md)). Cross-references
every `^:dynamic` top-level def found by `auditTopLevelMutableState`'s
report-only "dynamic" category (130 findings across
`mc-1.20.1`/`forge target`/`fabric target`/`mcmod`/`ac`, `main` sources only)
against every real `(binding [...])` call site in the same source trees
(`rg -n "\(binding\s*\["`, 19 call sites found).

**Method**: a `^:dynamic` var only needs to stay dynamic if something actually
`binding`s it. `alter-var-root` does not require `:dynamic` metadata — it works
on any Var. So a `^:dynamic` var with zero binding sites anywhere in the repo
is pure decoration: safe to convert to a plain `def` + `install-root!`
(`cn.li.mcmod.runtime.install`) with no behavior change.

## KEEP — real `binding` usage found (12 vars)

| var | file | bound at | binder |
|---|---|---|---|
| `*sync-scheduler-runtime*` | `platform-src/minecraft/version/mc-1201/.../runtime/sync_core.clj` | `sync_core.clj:42` | `with-sync-scheduler-runtime` macro |
| `*server-context-runtime*` | `platform-src/loader/fabric/.../adapter/server_context.clj` | `server_context.clj:27,32` | `with-server-context-runtime` macro + `call-with-server-context-runtime` |
| `*session-cleanup-runtime*` | `platform-src/minecraft/version/mc-1201/.../client/session_cleanup.clj` | `session_cleanup.clj:37` | `with-session-cleanup-runtime` macro |
| `*script-render-runtime*` | `platform-src/minecraft/version/mc-1201/.../client/render/script_render_runtime.clj` | `script_render_runtime.clj:37,42` | macro + `call-with-script-render-runtime` |
| `*script-render-executor-runtime*` | `platform-src/minecraft/version/mc-1201/.../client/render/script_render_executor.clj` | `script_render_executor.clj:36,41` | macro + `call-with-script-render-executor-runtime` |
| `*owner*` | `ac/.../terminal/client/runtime.clj` | `runtime.clj:103` | `with-owner` macro |
| `*reflection-chain-id*` | `ac/.../content/ability/vecmanip/vec_reflection.clj` | `vec_reflection.clj:409` | inline `binding` around chain dispatch |
| `*get-player-uuid-fn*` | `ac/.../ability/client/keybinds.clj` | `client_effect_hooks.clj:36` | inline `binding` (cross-namespace) |
| ~~`*cat-engine-render-runtime*`~~ | `ac/.../block/cat_engine/render.clj` | — | **P4: migrated.** `create-cache-runtime`/`with-bound-runtime` removed; cache now lives at Framework `[:service :render-cache :rotor-cache]` via `render_runtime.clj`'s `render-cache-atom`. `render_runtime_state_test.clj`'s only real test didn't exercise this cache at all — the 3-var `with-bound-runtime` fixture was vestigial and was deleted rather than replaced. |
| ~~`*wind-gen-render-runtime*`~~ | `ac/.../block/wind_gen/render.clj` | — | **P4: migrated**, same as above (`:fan-rot-cache`). |
| ~~`*wireless-matrix-render-runtime*`~~ | `ac/.../block/wireless_matrix/render.clj` | — | **P4: migrated**, same as above (`:last-shield-hw-state`). |

Note: `create-cache-runtime` in the same file backs these three vars only —
it is a *different* helper from `lazy-resource` (below), which never needs
`^:dynamic` in the first place.

## P3 addendum — 2 more KEEP cases found (test-tree binding was under-scanned)

The original scan above under-counted `binding` sites in `test` trees. P3 re-grepped
every `^:dynamic` var individually before converting and found 2 more real KEEP cases:

| var | file | bound at | binder |
|---|---|---|---|
| `*translate-fn*` | `mcmod/.../i18n.clj` | `mcmod/src/test/clojure/cn/li/mcmod/i18n_test.clj:7,11,15` | inline `binding` (test-only) |
| `*show-all?*` | `ac/.../tutorial/registry.clj` | none in source — public debug flag intended for ad-hoc REPL `binding`/`alter-var-root` toggling | manual |

Both left untouched by P3. `*mod-id*` (`mcmod/.../config.clj`) and `*forge-version*`
(`mcmod/.../item/dsl.clj`) were re-verified to have zero `binding` usage anywhere
(only `alter-var-root`/`with-redefs`, which work on non-dynamic vars) — both were
converted (KILL) as originally classified.

## `lazy-resource` helper — dynamic-ness never required

`ac/.../block/machine/render_runtime.clj`'s `lazy-resource` fn (used by the
OBJ-model/texture holders in `wind_gen`, `developer`, `solar_gen`,
`cat_engine`, `phase_gen`, etc. render.clj files) does `var-get`/
`alter-var-root` on a plain Var — it was never dynamic-binding-dependent.
Any `^:dynamic` var backing a `lazy-resource` thunk only needed `:dynamic`
metadata by copy-paste habit, not by requirement. All such vars (the bulk of
the ~25 render-model dynamic vars the P4 plan targets) fall into the KILL
list below unless individually cross-referenced above.

## KILL — zero binding usage anywhere (118 of 130 dynamic findings)

Every other `^:dynamic` finding in the report-only section (see
`build/reports/top-level-state/audit.md` → "Report-only: dynamic vars /
JVM primitives / bare defonce") has no matching `(binding [...])` call site
in `main` or `test` sources. These are pure `alter-var-root`-driven SPI
holders, init-guard booleans, and registry pointers — safe to convert to a
plain `def` + `cn.li.mcmod.runtime.install/install-root!` with zero behavior
change. This is the primary work item for P1 (platform layer) and P4
(ac render layer).

## Separately discovered: pre-existing bug, out of P0 scope

`cn.li.ac.ability.service.context-dispatcher/*context-owner*`
(`context_dispatcher.clj:25`) is a **plain `defn`** (already migrated to
Framework `[:service :client-ctx :context-owner]`, per its own comment at
line 23-24), **not** a `^:dynamic` Var. Yet it is passed to `binding` at:

- `ac/.../ability/service/context_manager.clj:112`
- `ac/.../ability/service/context_state.clj:54`
- `ac/src/test/clojure/cn/li/ac/content/ability/vecmanip/vec_reflection_test.clj:180,208`
- `ac/src/test/clojure/cn/li/ac/content/ability/teleporter/shift_teleport_test.clj:223,290`

`binding` on a non-`:dynamic` Var throws
`IllegalStateException: Can't dynamically bind non-dynamic var` at the point
the binding form executes. This is a live runtime bug unrelated to the
top-level-state refactor (it looks like `*context-owner*` was converted from
a `^:dynamic` var to a Framework-backed fn, and these five binding call sites
were missed). Flagged here for a separate fix — not touched by this P0 change
set.
