(ns cn.li.ac.ability.service.context-skill-state
  "Shared skill-state read/write helpers for skill implementations.

  All writes delegate to skill-state-commands (reducer path only)."
  (:require [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.skill-state-commands :as ctx-cmd]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn current-owner
  []
  (or ctx/*context-owner*
      (runtime-hooks/current-player-state-owner)))

(defn get-context
  ([ctx-id]
   (get-context (current-owner) ctx-id))
  ([owner ctx-id]
   (if owner
     (ctx/get-context owner ctx-id)
     (ctx/get-context ctx-id))))

(declare update-skill-state-root!)

(defn assoc-skill-state!
  [ctx-id k v]
  (let [path (if (vector? k) k [k])]
    (update-skill-state-root! ctx-id update :skill-state #(assoc-in (or % {}) path v))))

(defn update-skill-state-root!
  [ctx-id f & args]
  (let [ctx-data (or (get-context ctx-id) {})
        next-ctx (apply f ctx-data args)
        next-state (cond
                     (and (map? next-ctx) (contains? next-ctx :skill-state))
                     (or (:skill-state next-ctx) {})
                     (map? next-ctx)
                     next-ctx
                     :else
                     {})]
    (ctx-cmd/replace-skill-state-root! (current-owner) ctx-id next-state)))

(defn replace-skill-state-root!
  [ctx-id state-map]
  (update-skill-state-root! ctx-id assoc :skill-state state-map))

(defn clear-skill-state!
  [ctx-id]
  (update-skill-state-root! ctx-id dissoc :skill-state))
