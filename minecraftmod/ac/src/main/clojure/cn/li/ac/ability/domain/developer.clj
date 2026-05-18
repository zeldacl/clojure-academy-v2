(ns cn.li.ac.ability.domain.developer
  "Pure developer-device domain constants and helpers.

  This namespace owns gameplay facts shared by ability learning, developer
  blocks, and station validation. Runtime code should depend on these pure
  helpers instead of duplicating tier order, energy pacing, or block-id mapping."
  (:require [clojure.string :as str]))

(def developer-specs
  "Developer device specifications.
  :energy    - total energy capacity
  :tps       - ticks per stim (higher = slower)
  :cps       - energy consumed per stim
  :bandwidth - learning bandwidth multiplier (reserved for non-wireless logic)"
  {:portable {:energy 10000.0 :tps 25 :cps 750.0 :bandwidth 0.3}
   :normal   {:energy 50000.0 :tps 20 :cps 700.0 :bandwidth 0.7}
   :advanced {:energy 200000.0 :tps 15 :cps 600.0 :bandwidth 1.0}})

(def developer-order
  "Developer tier order from weakest to strongest."
  [:portable :normal :advanced])

(def ^:private rank
  (zipmap developer-order (range)))

(def controller-block->developer-type
  "Controller block ids that can host station-based ability learning."
  {"developer-normal" :normal
   "developer-advanced" :advanced})

(defn developer-spec
  [developer-type]
  (get developer-specs developer-type))

(defn developer-type?
  [developer-type]
  (contains? developer-specs developer-type))

(defn normalize-block-id
  [block-id]
  (str/lower-case (name (or block-id ""))))

(defn developer-type-for-block-id
  [block-id]
  (get controller-block->developer-type (normalize-block-id block-id)))

(defn controller-block?
  [block-id]
  (contains? controller-block->developer-type (normalize-block-id block-id)))

(defn min-for-level
  "Return the minimum developer type required for a given skill level."
  [level]
  (cond
    (<= level 2) :portable
    (= level 3)  :normal
    :else        :advanced))

(defn gte?
  "True when developer-type `a` is at least as powerful as `b`."
  [a b]
  (>= (long (get rank a -1))
      (long (get rank b -1))))

(defn energy-per-tick
  "Energy consumed per tick for a development session."
  [developer-type]
  (let [{:keys [cps tps]} (developer-spec developer-type)]
    (/ cps (double tps))))
