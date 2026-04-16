(ns cn.li.ac.ability.skill-runtime
  "Bridge from skill spec maps to runtime callbacks.

  If a skill spec has :pattern, we interpret input events using
  cn.li.ac.ability.patterns and the spec's :actions/:fx."
  (:require [cn.li.ac.ability.patterns :as patterns]
            [cn.li.mcmod.util.log :as log]))

(def ^:private cb->pattern-key
  {:on-key-down  :on-key-down
   :on-key-tick  :on-key-tick
   :on-key-up    :on-key-up
   :on-key-abort :on-key-abort})

(defn can-handle?
  [spec]
  (keyword? (:pattern spec)))

(defn dispatch!
  "Dispatch a context-runtime callback key (e.g. :on-key-down) through the
  pattern engine. Returns true if handled."
  [spec cb-key evt]
  (when (can-handle? spec)
    (when-let [hs (patterns/handlers spec)]
      (when-let [h (get hs (get cb->pattern-key cb-key cb-key))]
        (try
          (h spec (assoc evt :skill-id (:id spec)))
          true
          (catch Exception e
            (log/warn "Skill runtime dispatch failed" (:id spec) cb-key (ex-message e))
            true))))))

