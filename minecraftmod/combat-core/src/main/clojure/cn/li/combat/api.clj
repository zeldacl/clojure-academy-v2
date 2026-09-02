(ns cn.li.combat.api
  "The public surface of combat-core. Content modules (ac, and future bc/cc)
   and ability-runtime should require only this namespace, never combat-
   core's internal namespaces directly -- delegation only, no logic here.

   Sized from combat-core's actual external consumers (ac's core/init.clj,
   final_catalog_service.clj, final_runtime.clj, combat_runtime.clj,
   server_hooks.clj; ability-runtime's combat.clj), not speculative
   coverage. Replaces four requiring-resolve call sites in ac that existed
   only because this facade didn't."
  (:require [cn.li.combat.final-engine :as engine]
            [cn.li.combat.final-compiler :as compiler]
            [cn.li.combat.final-damage :as damage]
            [cn.li.combat.platform :as platform]
            [cn.li.combat.beam-settlement :as beam-settlement]
            [cn.li.combat.vocabulary :as vocabulary]
            [cn.li.combat.kernels :as kernels]))

;; ---- final graph compiler + engine ----
(defn compile-program [environment program] (compiler/compile-program environment program))
(defn create-engine [opts] (engine/create-engine opts))
(defn execute! [engine compiled frame] (engine/execute! engine compiled frame))
(def capability-matrix engine/capability-matrix)

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
