(ns cn.li.mcmod.entity.hook-catalog
  "Shared mapping from business hook ids to platform-neutral implementation keys.

  Platform adapters should resolve business hook ids through this catalog, then
  map implementation keys to platform classes/functions locally."
  (:require [clojure.string :as str]))

(def ^:private effect-hook->impl-key
  {"intensify-arcs" :intensify-arcs
   "diamond-shield" :owner-offset
   "md-shield" :owner-offset
   "surround-arc" :owner-offset
   "generic-arc" :generic-arc
   "md-ball" :md-ball
   "ripple-mark" :noop
   "blood-splash" :noop
   "coin-throwing" :coin-throwing})

(def ^:private ray-hook->impl-key
  {"mine-ray-basic" :owner-follow
   "mine-ray-expert" :owner-follow
   "mine-ray-luck" :owner-follow
   "md-ray" :owner-follow
   "md-ray-small" :owner-follow
   "md-ray-barrage" :owner-follow
   "barrage-ray-pre" :owner-follow
   "railgun-fx" :owner-follow})

(defn normalize-hook-id
  [hook-id]
  (cond
    (keyword? hook-id) (name hook-id)
    (string? hook-id) (when-not (str/blank? hook-id) hook-id)
    :else nil))

(defn effect-impl-key
  [hook-id]
  (some-> hook-id normalize-hook-id effect-hook->impl-key))

(defn ray-impl-key
  [hook-id]
  (some-> hook-id normalize-hook-id ray-hook->impl-key))
