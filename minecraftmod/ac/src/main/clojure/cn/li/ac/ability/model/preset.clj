(ns cn.li.ac.ability.model.preset
  "Pure-data functions for PresetData.

  4 presets × 4 key slots. Each slot holds a controllable pair [cat-id ctrl-id]
  or nil. Sparse encoding: only non-nil entries stored.

  Map schema:
    {:active-preset  int       ; 0–3
     :slots         {[preset-idx key-idx] [cat-id ctrl-id]}}"
  (:require [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Constructors
;; ============================================================================

(defn new-preset-data []
  {:active-preset 0
   :slots         {}})

;; ============================================================================
;; Active Preset
;; ============================================================================

(defn get-active-preset [d]
  (:active-preset (or d (new-preset-data))))

(defn set-active-preset [d idx]
  (when-not (and (>= idx 0) (<= idx 3))
    (throw (IllegalArgumentException. "set-active-preset: idx must be 0-3")))
  (assoc (or d (new-preset-data)) :active-preset idx))

;; ============================================================================
;; Slot Access
;; ============================================================================

(defn- as-keyword
  [x]
  (cond
    (keyword? x) x
    (string? x) (keyword x)
    (symbol? x) (keyword (name x))
    :else x))

(defn normalize-controllable
  "Normalize a stored controllable to [cat ctrl] keywords.
   Accepts vector or list (sync/NBT roundtrips sometimes yield lists)."
  [pair]
  (when (and (sequential? pair) (= 2 (count pair)))
    [(as-keyword (first pair)) (as-keyword (second pair))]))

(defn normalize-slots
  "Rekey :slots so [preset key] lookups survive Long/int / string keys and
   normalize controllable pairs to keyword vectors."
  [slots]
  (into {}
        (keep (fn [[k v]]
                (let [nk (when (and (sequential? k) (= 2 (count k)))
                           [(int (first k)) (int (second k))])
                      nv (normalize-controllable v)]
                  (when (and nk nv) [nk nv])))
              (or slots {}))))

(defn- slots-map
  [d]
  (normalize-slots (:slots (or d (new-preset-data)) {})))

(defn get-slot [d preset-idx key-idx]
  (get (slots-map d) [(int preset-idx) (int key-idx)]))

(defn set-slot [d preset-idx key-idx controllable]
  (let [d (or d (new-preset-data))
        pair (normalize-controllable controllable)]
    (if (nil? pair)
      (update d :slots dissoc [(int preset-idx) (int key-idx)])
      (assoc-in d [:slots [(int preset-idx) (int key-idx)]] pair))))

(defn clear-slots
  "Remove all preset slot bindings while preserving the active preset index."
  [d]
  (assoc (or d (new-preset-data)) :slots {}))

(defn get-active-slots
  "Return vec of 4 controllables (or nil) for the active preset."
  [d]
  (let [d (or d (new-preset-data))
        p (int (or (:active-preset d) 0))
        slots (slots-map d)]
    (mapv #(get slots [p %]) (range 4))))

;; ============================================================================
;; Serialization
;; ============================================================================

(defn preset-data->vec
  "Serialize to vector of [preset-idx key-idx cat-id ctrl-id]."
  [d]
  (mapv (fn [[[pi ki] [cat ctrl]]]
          [pi ki (name cat) (name ctrl)])
        (slots-map d)))

(defn vec->preset-data
  "Deserialize. active-preset defaults to 0."
  ([coll] (vec->preset-data coll 0))
  ([coll active-preset]
   {:active-preset (int active-preset)
    :slots (->> coll
                (reduce (fn [acc [pi ki cat ctrl]]
                          (assoc acc [(int pi) (int ki)]
                                 [(keyword cat) (keyword ctrl)]))
                        {}))}))
