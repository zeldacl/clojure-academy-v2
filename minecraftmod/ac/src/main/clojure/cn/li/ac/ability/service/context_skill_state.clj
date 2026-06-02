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

(defn assoc-skill-state!
  [ctx-id k v]
  (ctx-cmd/assoc-skill-state! (current-owner) ctx-id k v))

(defn update-skill-state-root!
  [ctx-id f & args]
  (let [ctx-data (or (get-context ctx-id) {})
        current (or (:skill-state ctx-data) {})
        next-state (apply f current args)]
    (ctx-cmd/replace-skill-state-root! (current-owner) ctx-id next-state)))

(defn clear-skill-state!
  [ctx-id]
  (ctx-cmd/clear-skill-state! (current-owner) ctx-id))
