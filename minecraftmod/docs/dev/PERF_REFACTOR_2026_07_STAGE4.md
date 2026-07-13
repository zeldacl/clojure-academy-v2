# GC/CPU Perf Refactor — Stage 4 Closeout (2026-07)

Closeout for the 2026-07 GC/CPU performance refactor (stages 1-3: server tick,
client rendering, network codec + ability sync delta). This doc covers stage 4:
reflection gate audit and a profiling baseline.

## Reflection gate audit

Two independent reflection gates exist and cover different things:

- `verifyNoPlatformReflection` — regex scan of main Clojure source text for
  *explicit* reflection API usage (`Class/forName`, `clojure.lang.Reflector`,
  `.getMethod`, `.setAccessible`, etc.). Already wired into
  `verifyForgeBaseline`/`verifyFabricBaseline`.
- `checkClojure` (clojurephant, `reflection = 'fail'`) — catches *implicit*
  reflection: the Clojure compiler silently emitting reflective bytecode for
  member calls it can't statically resolve (missing type hints). This is the
  category that actually breaks at runtime after Loom remapping.

**Finding**: `checkClojure` was declared with `reflection = 'fail'` for every
module, but was never actually invoked by any baseline/PR gate for
`forge-1.20.1`/`fabric-1.20.1` (`verifyForgeBaseline`/`verifyFabricBaseline`
only ran `compileClojure`, not `checkClojure`). Confirmed empirically: a probe
file with an obviously-untyped `.length` call compiled cleanly through
`:mcmod:compileClojure` with zero warnings, but was caught immediately by
`:mcmod:checkClojure`. The `-Dclojure.compile.warn-on-reflection=true` JVM
system property some build.gradle files set for `compileClojure` does nothing
— it isn't a real Clojure-recognized property — so the `StandardOutputListener`
watching for "Reflection warning" text on that task's output was dead code.
`ac`/`mcmod` were unaffected (their `checkClojure` was already reachable via
`verifyLocalPrGate`/`verifyCurrentPlatforms`).

**Fix**:
- `verifyForgeBaseline`/`verifyFabricBaseline` (root `build.gradle`) now
  depend on `:forge-1.20.1:checkClojure` / `:fabric-1.20.1:checkClojure`.
- `gradle/platform_build_helpers.gradle`: `checkClojure`'s classpath reads
  `classes/java/main`, which `copyAcJavaClassesToPlatformOutput` /
  `copyMcmodJavaClassesToPlatformOutput` also write into without a declared
  task dependency — Gradle's task-validation flagged this once `checkClojure`
  was pulled into the graph. Added explicit `dependsOn` for both mirror tasks.
- Fixed the 6 warnings this surfaced (all pre-existing, none introduced this
  session):
  - `mc-1.20.1/gui/reactive/render.clj`: `resolve-rl` had no `^ResourceLocation`
    return hint, so its one un-hinted call site (`RenderSystem/setShaderTexture`)
    reflected.
  - `fabric-1.20.1/gui/network/shared.clj`: `c2s-channel`/`s2c-channel` defs had
    no `^ResourceLocation` tag, so both `registerGlobalReceiver` call sites
    (client + server) reflected.
  - `fabric-1.20.1/client/init.clj`: `:send-system-message!`'s `player` param
    untyped (`^Player` added); `is-glfw-key-down?` called `.getHandle` on
    `Window` — wrong method name entirely (the real accessor is `.getWindow`,
    confirmed against Forge's equivalent code), fixed by hinting `win` and
    correcting the call.
  - `fabric-1.20.1/platform/bindings.clj`: `open-player-menu!` called
    `.openHandledScreen` — a Yarn-mapping method name that doesn't exist under
    the official Mojang mappings this codebase targets. Real bug, not just a
    missing hint: would throw `NoSuchMethodException` at runtime if ever
    exercised. Forge's equivalent binding already used the correct `.openMenu`;
    Fabric's was fixed to match.

**Verified**: `:forge-1.20.1:checkClojure` and `:fabric-1.20.1:checkClojure`
both pass reflection-clean; `verifyForgeBaseline`/`verifyFabricBaseline` pass
end to end with the new dependency wiring.

**Known pre-existing gaps found but not fixed** (out of scope for this
refactor, unrelated to any file touched this session — confirmed via
`git status`):
- `verifyAotIronRules`: 7 `keyword-as-IFn` violations (`reactive_overlay.clj`,
  `skill_config.clj`, `about_reactive.clj`, `settings_reactive.clj`).
- `verifyAbilitySkillConfigCoverage`: reports a false-looking id-coverage
  mismatch (`missing: [root, info-panel, title, ...]` — these read like GUI
  widget ids, not skill ids, so the task's regex is likely scanning the wrong
  thing). Both block a full green `verifyLocalPrGate` run independent of this
  refactor.

## Profiling baseline

The plan's original target scenario (N wireless nodes + HUD + beam + OBJ
multiblock, JFR 60s) requires a live, human-driven game client — no headless
path exists for MSDF font rendering, HUD snapshot building, beam/arc effects,
or OBJ baking, since none of that runs without Minecraft's client renderer.
**That part was not run automatically; it needs a manual session.** To do it:

```
cmd /c .\gradlew.bat :forge-1.20.1:runClient
```

then, with the scene set up in-game, attach JFR to the running JVM:

```
jcmd <pid> JFR.start duration=60s filename=client_scene.jfr settings=profile
```

and inspect with `jfr print --events jdk.ObjectAllocationSample client_scene.jfr`.

### What was actually run: headless codec baseline

The Stage 3.1 binary codec is pure JDK/Clojure with no Minecraft types, so it
*can* be profiled headlessly. Added an opt-in JFR hook to
`:mcmod:runMcmodClojureTestsFast` (`-Dmcmod.test.jfr=<path>`, permanent, no
cost when unset) and ran a throwaway 3,000,000-iteration encode+decode
roundtrip benchmark against a representative nested ability-sync payload
(~737 bytes: `:ability-data`/`:resource-data`/`:cooldown-data`/`:preset-data`/
`:develop-data` plus a `:uuid` and `:msg-id`), then deleted the benchmark file.

`jfr summary` showed 98 young GC cycles across the 25-35s run; the allocation
profile (`jdk.ObjectAllocationSample`) surfaced `clojure.lang.Symbol` as the
4th-largest allocation site (1199 samples) — `write-val!`'s keyword branch was
doing `(str (symbol v))` to strip a keyword's leading colon, which allocates a
throwaway `Symbol` on every write. Replaced with `(subs (str v) 1)` (same
result, no extra allocation).

| | before | after |
|---|---|---|
| throughput | ~85,390 roundtrips/sec | ~116,743 roundtrips/sec |

+37% throughput from a single-line fix the profiling run surfaced directly —
kept as the concrete justification for doing this exercise at all rather than
skipping straight to "looks fine, ship it."

Everything else in `write-val!`'s allocation profile (`byte[]`, `String`,
`PersistentHashMap`/`MapEntry` from map reconstruction) is inherent to
producing immutable Clojure values from a byte stream and wasn't chased
further.

## Decision points from the original plan (revisited)

- `world_runtime` active/dirty set: not revisited — no server-tick profiling
  data was collected this session (needs a live world, see above).
- record-hash → long-packing upgrade (`capability_resolver.clj`): not
  revisited, same reason.
- keyword pool for the network codec: evaluated and not worth it — keywords in
  practice are short domain/skill ids, `String.getBytes`/`new String` cost
  dominates far less than the map/vector reconstruction machinery already
  measured above.
