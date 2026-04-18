(ns cn.li.ac.ability.server.dispatch
  "Bridge from skill spec maps to runtime callbacks.

  If a skill spec has :pattern, we interpret input events using
  cn.li.ac.ability.server.patterns and the spec's :actions/:fx."
  (:require [cn.li.ac.ability.server.patterns :as patterns]
            [cn.li.mcmod.util.log :as log]))

(defn can-handle?
  [spec]
  (and (keyword? (:pattern spec))
       (some? (patterns/handlers spec))))

(defn dispatch!
  "Dispatch a context-runtime callback key (e.g. :on-key-down) through the
  pattern engine. Returns true if handled."
  [spec cb-key evt]
  (when (can-handle? spec)
    (when-let [hs (patterns/handlers spec)]
      (when-let [h (get hs cb-key)]
        (try
          (h spec (assoc evt :skill-id (:id spec)))
          true
          (catch Exception e
            (log/warn "Skill runtime dispatch failed" (:id spec) cb-key (ex-message e))
            true))))))

