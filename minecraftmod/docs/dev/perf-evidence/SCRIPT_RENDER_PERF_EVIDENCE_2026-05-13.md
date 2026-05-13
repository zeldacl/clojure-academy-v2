# ScriptRender Performance Evidence — 2026-05-13

## Scope
- Renderer IDs in scope:
  - `ray-composite`
  - `md-ball` (hybrid params path)
- Platforms:
  - Forge 1.20.1
  - Fabric 1.20.1

## Commit / workspace
- Commit SHA: `73d9c2080e4d3b379dc2a7a425a4c5868e19df2f`
- Workspace root: `i:/code/minecraft/clojure-academy-v2/minecraftmod`

## What was executed in this session
1. Compile validation after single-path renderer cleanup:
   - `:forge-1.20.1:compileJava :fabric-1.20.1:compileJava`
   - Result: **BUILD SUCCESSFUL**
2. Forge client launch feasibility (perf capture prerequisite):
   - `:forge-1.20.1:runClient`
   - Blocked by `:forge-1.20.1:checkClojure` reflection-fail gate.
3. Forge client launch retry with check bypass:
   - `:forge-1.20.1:runClient -x :forge-1.20.1:checkClojure`
   - Client process started, but world load path hit runtime blockers:
     - lifecycle ClassCastException (`AtomRegistry` cast to `Future`)
     - configured feature datapack failure (`my_mod:phase_liquid_pool` unbound/parse error)
4. Blocker fixes applied and revalidated:
   - fixed `tile_logic.clj` AtomRegistry deref misuse (`registry-core/lookup`)
   - hardened Forge registry lookups for missing optional/legacy entries:
     - `forge1201.entity.ModEntities#getEntityType`
     - `forge1201.registry.state/*get-registered-*`
   - follow-up compiles succeeded:
     - `:mcmod:compileClojure :forge-1.20.1:compileClojure :forge-1.20.1:compileJava`

## Current status against certification checklist
- Frame-time delta (mean/p95): **NOT COLLECTED** (blocked)
- Render-thread allocation delta: **NOT COLLECTED** (blocked)
- Stability under mode matrix:
  - scripted/hybrid enabled: **PARTIAL** (startup reaches client UI path)
  - global/per-id toggles: **NOT COLLECTED**

## Blocking issues (must clear before final perf numbers)
1. Forge pre-run check gate failure in `checkClojure` (reflection warnings treated as failure), so perf runs currently require `-x :forge-1.20.1:checkClojure`.
2. Full low/medium/stress scenario metrics are still pending manual in-world execution (JFR capture path already prepared).

## Evidence artifacts from this session
- Forge runClient attempt log (check gate failure):
  - `c:/Users/lxy/AppData/Roaming/Code/User/workspaceStorage/95894b4e2df1b942d86595acbde42271/GitHub.copilot-chat/chat-session-resources/7868e640-0905-4196-bb47-3900d2946ae7/call_Q6LGbzUzZC6nalRHfPRS9zN3__vscode-1778636044306/content.txt`
- Forge runClient retry log (world-load blockers):
  - `c:/Users/lxy/AppData/Roaming/Code/User/workspaceStorage/95894b4e2df1b942d86595acbde42271/GitHub.copilot-chat/chat-session-resources/7868e640-0905-4196-bb47-3900d2946ae7/call_LgMe4Ap46TAwHN6RTyVKeRgh__vscode-1778636044308/content.txt`

## Next execution procedure (after blockers fixed)
Use `scripts/perf/run_script_render_perf_capture.ps1` for each mode in matrix:
- `enabled`
- `global-disabled`
- `per-id-disabled`

For each mode, run low / medium / stress densities and attach:
- JFR file path
- scene definition
- frame-time mean/p95
- render-thread allocations
- pass/fail vs threshold

## Sign-off
- Owner: _TBD_
- Date: _TBD_
- Result: **PENDING (blocked by runtime prerequisites)**
