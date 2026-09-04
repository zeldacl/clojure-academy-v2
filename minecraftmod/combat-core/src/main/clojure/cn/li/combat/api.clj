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
  (:require [cn.li.combat.final-damage :as damage]
            [cn.li.combat.platform :as platform]
            [cn.li.combat.beam-settlement :as beam-settlement]
            [cn.li.combat.vocabulary :as vocabulary]
            [cn.li.combat.kernels :as kernels]
            [cn.li.combat.run :as run]
            [cn.li.combat.lib :as lib]
            [cn.li.combat.player :as player]))

;; ---- damage ----
(defn resolve-damage [reactions raw-event] (damage/resolve-event reactions raw-event))
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
