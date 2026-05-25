(ns cn.li.ac.entity.hook-catalog
  "AC-owned scripted entity hook-id → implementation key catalog."
  (:require [clojure.string :as str]
            [cn.li.mcmod.entity.hook-resolver :as hook-resolver]
            [cn.li.mcmod.util.log :as log]))

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

(def ^:private marker-hook->impl-key
  {"tp-marking" :owner-follow-marker
   "marker" :owner-follow-marker})

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

(defn marker-impl-key
  [hook-id]
  (some-> hook-id normalize-hook-id marker-hook->impl-key))

(defn install-resolvers!
  "Install AC scripted entity hook resolvers into the mcmod generic resolver seam."
  []
  (hook-resolver/register-resolver! :effect effect-impl-key)
  (hook-resolver/register-resolver! :ray ray-impl-key)
  (hook-resolver/register-resolver! :marker marker-impl-key)
  (log/info "Installed AC scripted entity hook resolvers")
  nil)
