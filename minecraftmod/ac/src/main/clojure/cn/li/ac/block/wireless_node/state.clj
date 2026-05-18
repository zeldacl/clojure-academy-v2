(ns cn.li.ac.block.wireless-node.state
  "Wireless node state schema, defaults, tier helpers, and blockstate projection."
  (:require [clojure.string :as str]
            [cn.li.ac.block.wireless-node.schema :as node-schema]
            [cn.li.ac.wireless.config :as node-config]
            [cn.li.mcmod.block.state-schema :as state-schema]
            [cn.li.mcmod.platform.be :as platform-be]))

(def node-state-schema
  (state-schema/filter-server-fields node-schema/unified-node-schema))

(def node-default-state
  (state-schema/schema->default-state node-state-schema))

(def node-scripted-load-fn
  (state-schema/schema->load-fn node-state-schema))

(def node-scripted-save-fn
  (state-schema/schema->save-fn node-state-schema))

(def block-state-properties
  (state-schema/extract-block-state-properties node-schema/blockstate-property-fields))

(def update-block-state!
  (state-schema/build-block-state-updater node-schema/blockstate-property-fields))

(defn node-types
  "Single source of truth for per-tier capability values."
  []
  (node-config/node-types))

(defn node-tier
  [state]
  (keyword (:node-type state :basic)))

(defn node-max-energy
  "Derive the max-energy for a state map from its :node-type."
  [state]
  (node-config/max-energy (node-tier state)))

(defn energy->blockstate-level
  "Transform energy value to BlockState level (0-4) for visual display."
  [e s]
  (let [max-e (double (node-max-energy s))]
    (min 4 (int (Math/round (* 4.0 (/ (double e) (max 1.0 max-e))))))))

(defn parse-node-type
  [block-id-or-kw]
  (let [s (if (keyword? block-id-or-kw) (name block-id-or-kw) (str block-id-or-kw))]
    (cond
      (str/includes? s "advanced") :advanced
      (str/includes? s "standard") :standard
      :else :basic)))

(defn node-safe-state
  "Return the BE's customState, or a fresh default state seeded with node-type."
  [be block-id]
  (or (platform-be/get-custom-state be)
      (assoc node-default-state :node-type (parse-node-type block-id))))
