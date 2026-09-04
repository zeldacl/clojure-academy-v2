(ns cn.li.combat.api
  "The public surface of combat-core. Content modules (ac, and future bc/cc)
   and ability-runtime should require only this namespace, never combat-
   core's internal namespaces directly -- delegation only, no logic here.

   Sized from combat-core's actual external consumers, not speculative
   coverage.

   The old graph compiler/engine facade (compile-program/create-engine/
   execute!/capability-matrix, delegating to final-compiler/final-engine)
   was removed once it had zero real callers left: cn.li.ac.ability.
   final-catalog-service was the last one, and it stopped compiling
   registrations through the old engine once nothing executed the result
   any more (see that namespace's own docstring) -- final_engine.clj/
   final_compiler.clj themselves are deleted, not just unreferenced here."
  (:require [cn.li.combat.damage :as damage]
            [cn.li.combat.platform :as platform]
            [cn.li.combat.beam-settlement :as beam-settlement]
            [cn.li.combat.vocabulary :as vocabulary]
            [cn.li.combat.kernels :as kernels]
            [cn.li.combat.dsl-vocabulary :as dsl-vocabulary]
            [cn.li.combat.run :as run]
            [cn.li.combat.lib :as lib]
            [cn.li.combat.player :as player]))

;; ---- damage ----
;; Cut over from cn.li.combat.final-damage (deleted) to cn.li.combat.damage:
;; same aggregation arithmetic, ported verbatim (see that namespace's own
;; docstring) -- only the dispatch layer changed, an O(n) linear scan over
;; every registered policy per damage event replaced by an O(k) mark-type+
;; priority indexed lookup. resolve-damage still takes a flat policies list
;; (combat_runtime.clj's own call site is unchanged) and builds the index
;; fresh per call, matching combat_runtime.clj's own final-damage-policies-v2
;; "not a per-frame hot path, don't add cached mutable state for this"
;; reasoning -- a real cross-event cache is a separate, later optimization
;; if profiling ever shows this matters.
(defn resolve-damage [reactions raw-event] (damage/resolve-event (damage/build-index reactions) raw-event))
(defn materialize-vfx [descriptor event] (damage/materialize-vfx descriptor event))
;; install-boundary! (mcmod begin/complete SPI hookup) is not included here:
;; it has zero real callers anywhere in the repo today (unlike the other
;; functions above, all confirmed against a real ac call site).

;; ---- platform capabilities ----
(defn install! [opts] (platform/install! opts))
(defn damage! [request] (platform/damage! request))

;; ---- delayed/continued execution ----
(defn settle! [payload] (beam-settlement/settle! payload))
(defn schedule-action! [schedule! request] (beam-settlement/schedule-action! schedule! request))

;; ---- vocabulary (for a content module's own schema-export/editor tooling) ----
(def descriptor-specs vocabulary/descriptor-specs)
(def kernel-descriptors kernels/kernel-specs)

;; ---- surface-DSL vocabulary + fn library (for the node editor's palette,
;; ability-runtime/editor/palette.clj -- these are the :vocab/:fns/
;; category-for the editor's "skill mode" is configured with; see the
;; node-editor plan's §2.2 injection table) ----
(def skill-vocab dsl-vocabulary/nodes)
(def skill-vocab-category-for dsl-vocabulary/category-for)
;; capability-type is a FUNCTION (?budget/*, ?cooldown/* etc are typed by
;; NAMESPACE, not enumerated one name at a time -- see run.clj's own
;; docstring), the exact :capabilities value cn.li.node.compile's env
;; expects for the editor's check.clj to compile-check a skill doc the
;; same way real dispatch does.
(def skill-capability-type run/capability-type)

;; ---- S8 cutover: the new node-core engine (cn.li.combat.run) is now the
;; live production dispatch path for real player actions -- see
;; cn.li.combat.run's own namespace docstring and NODE_LANGUAGE.md §0.
;; cn.li.ac.ability.skills-catalog and cn.li.ability.engine-v2 are this
;; facade's only real callers, matching the same "content modules touch
;; only this namespace" convention the functions above already establish
;; for the old engine. ----
(def skill-lib-fns lib/fns)
(defn compile-skill-doc!
  ([text] (run/compile-doc! text skill-lib-fns))
  ([text fns] (run/compile-doc! text fns)))
(defn compile-skill-program [ir host] (run/compile-program ir host))
(defn dispatch-skill! [program entry input] (run/dispatch! program entry input))

;; ---- S7: player-composed spells (cn.li.combat.player) ----
(defn compile-and-admit-player-spell
  "glyphs, complexity-cap -> {:ok true :complexity n :ir ir} or {:ok false
   :reject reason ...} -- see cn.li.combat.player/compile-and-admit's own
   docstring. The only path a content module's server-side network
   handler should use to turn a player's raw glyph submission into
   something dispatchable."
  [glyphs complexity-cap]
  (player/compile-and-admit glyphs complexity-cap))

(defn player-glyph-catalog
  "-> [{:glyph :kind :effects :cost :admissible?} ...] for every known
   glyph -- see cn.li.combat.player/glyph-catalog's own docstring. The
   player spell composer's palette should filter on :admissible?, which
   reads the identical allowlist compile-and-admit-player-spell's own
   admit check enforces."
  []
  (player/glyph-catalog))
