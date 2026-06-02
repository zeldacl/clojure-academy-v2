(ns cn.li.ac.ability.service.skill-state-commands
  "Reducer command helpers for per-context skill-state mutations.

  All writes go through command-runtime; missing session/player/context fails fast."
  (:require [cn.li.ac.ability.service.command-runtime :as command-rt]
            [cn.li.ac.ability.service.context-projection :as ctx-proj]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- require-session-id
  [session-id]
  (or session-id
      (runtime-hooks/player-state-session-id)
      (runtime-hooks/require-player-state-session-id "skill-state-commands")))

(defn- require-owner
  [owner]
  (or owner
      (runtime-hooks/current-player-state-owner)
      (throw (ex-info "skill-state command requires explicit owner or bound player-state owner"
                      {}))))

(defn- owner->session-player
  [owner]
  (let [owner-map (require-owner owner)
        session-id (or (:server-session-id owner-map)
                       (let [sid (:session-id owner-map)]
                         (when (vector? sid) (first sid)))
                       (require-session-id nil))
        player-uuid (or (:player-uuid owner-map)
                        (when (vector? (:session-id owner-map))
                          (second (:session-id owner-map))))]
    (when-not (and session-id player-uuid)
      (throw (ex-info "skill-state command owner requires session-id and player-uuid"
                      {:owner owner-map})))
    [session-id player-uuid]))

(defn context-command-ready?
  [owner ctx-id]
  (let [[session-id player-uuid] (owner->session-player owner)]
    (boolean (ctx-proj/get-store-context session-id player-uuid ctx-id))))

(defn assoc-skill-state!
  [owner ctx-id k v]
  (let [[session-id player-uuid] (owner->session-player owner)
        key-path (if (vector? k) k [k])]
    (command-rt/run-command-in-session!
     session-id
     player-uuid
     {:command :context-assoc-skill-state
      :ctx-id ctx-id
      :k key-path
      :v v})))

(defn replace-skill-state-root!
  [owner ctx-id state-map]
  (assoc-skill-state! owner ctx-id [] state-map))

(defn clear-skill-state!
  [owner ctx-id]
  (let [[session-id player-uuid] (owner->session-player owner)]
    (command-rt/run-command-in-session!
     session-id
     player-uuid
     {:command :context-clear-skill-state
      :ctx-id ctx-id})))

(defn increment-skill-state!
  [owner ctx-id k max-v]
  (let [[session-id player-uuid] (owner->session-player owner)]
    (command-rt/run-command-in-session!
     session-id
     player-uuid
     {:command :context-increment-skill-state
      :ctx-id ctx-id
      :k k
      :max max-v})))

(defn get-context-view
  "Read merged transport+store context via dispatcher when available, else store-only."
  [owner ctx-id transport-get-fn]
  (when-let [transport-ctx (transport-get-fn owner ctx-id)]
    (ctx-proj/merge-store-projection transport-ctx)))
