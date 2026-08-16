(ns cn.li.ac.ability.service.player-runtime-commands
  "Reducer-backed player runtime command helpers for legacy projectile claims."
  (:require [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.runtime-store :as store]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn session-id
  []
  (runtime-hooks/require-player-state-session-id "player-runtime-commands"))

(defn run-for-player!
  ([player-uuid command]
   (run-for-player! player-uuid command {}))
  ([player-uuid command opts]
   (command-rt/run-command-in-session! (session-id) player-uuid command opts)))

(defn player-state
  [player-uuid]
  (skill-effects/get-player-state-in-session! (session-id) player-uuid))

(defn projectile-claims
  [player-uuid]
  (or (get-in (player-state player-uuid) [:runtime :vecmanip :projectile-claims])
      {:tick -1 :owners {}}))

(defn vec-reflection-state
  [player-uuid]
  (or (get-in (player-state player-uuid) [:runtime :vecmanip :reflection])
      {:reflecting-pairs [] :reflection-depths {}}))

(defn reset-content-runtime-for-player!
  [player-uuid]
  (run-for-player! player-uuid {:command :clear-player-projectile-claims})
  (run-for-player! player-uuid {:command :reset-vec-reflection-runtime})
  nil)

(defn reset-all-content-runtimes!
  []
  (let [sid (session-id)]
    (doseq [player-uuid (store/list-players sid)]
      (reset-content-runtime-for-player! player-uuid)))
  nil)
