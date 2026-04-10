(ns cn.li.ac.block.wireless-node.blockstate
  "BlockState definitions for wireless node blocks (datagen metadata).

  Generates BlockStateDefinition records for all node types (basic/standard/advanced)
  with multipart models for energy levels and connection states.

  This file contains node-specific blockstate logic extracted from blockstate_definition.clj
  to colocate it with the node block implementation."
  (:require [clojure.string :as str]))

;; ============================================================================
;; BlockState Definition Record (copied from blockstate_definition.clj)
;; ============================================================================

(defrecord BlockStateDefinition
  [;; block的minecraft registry name (不含命名空间)
   registry-name
   ;; BlockState属性定义
   properties
   ;; multipart格式的部分列表
   parts])

;; ============================================================================
;; Node BlockState Generation
;; ============================================================================

(defn- resolve-var-value
  [sym]
  (some-> (requiring-resolve sym) deref))

(defn- node-types*
  []
  (if-let [f (requiring-resolve 'cn.li.ac.block.wireless-node.config/node-types)]
    (f)
    {}))

(defn- blockstate-property-fields*
  []
  (or (resolve-var-value 'cn.li.ac.block.wireless-node.schema/blockstate-property-fields)
      []))

(defn- mod-id []
  (or (resolve-var-value 'cn.li.mcmod.config/*mod-id*)
      "my_mod"))

(defn- extract-blockstate-props
  "Extract BlockState properties from schema into a map.
   Returns: {property-keyword {:name :type :min :max :default}}"
  []
  (into {}
    (for [field (blockstate-property-fields*)
          :when (:block-state field)
          :let [bs (:block-state field)
                prop-name (:prop bs)]]
      [(keyword prop-name)
       {:name prop-name
        :type (:type bs)
        :min (:min bs)
        :max (:max bs)
        :default (:default bs)}])))

(defn get-all-node-definitions
  "Generate BlockStateDefinition records for all node types.

  Reads node-types from wireless-node/block.clj and BlockState properties
  from schema.clj to maintain single source of truth.

  Returns:
    map of block-key -> BlockStateDefinition"
  []
  (let [;; Extract BlockState properties from schema
        props (extract-blockstate-props)
        energy-min (get-in props [:energy :min])
        energy-max (get-in props [:energy :max])
        connected-type (get-in props [:connected :type])]
    (into {}
      (for [node-type (sort (keys (node-types*)))
                :let [node-type-name (name node-type)
                      block-key (keyword (str "wireless-node-" node-type-name))
                      registry-name (str "node_" node-type-name)
                      base-model (str (mod-id) ":block/" registry-name "_base")
                      energy-models (vec (for [level (range energy-min (inc energy-max))]
                                           {:condition {:energy (str level)}
                                            :models [(str (mod-id) ":block/" registry-name "_energy_" level)]}))
                      connected-model {:condition {:connected "true"}
                                       :models [(str (mod-id) ":block/" registry-name "_connected")]}]]
            [block-key
             (BlockStateDefinition.
              registry-name
              {:energy {:min energy-min :max energy-max}
               :connected {:type connected-type}}
              (vec (concat
                    [{:condition nil :models [base-model]}]
                    energy-models
                    [connected-model])))]))))

(defn get-node-blockstate-definition
  "Get BlockState definition for a specific node block.

  Parameters:
    block-key: keyword like :wireless-node-basic

  Returns:
    BlockStateDefinition or nil"
  [block-key]
  (get (get-all-node-definitions) block-key))

;; ============================================================================
;; Node Model Name Parsing
;; ============================================================================

(defn- node-model-pattern
  "Generate regex pattern for node model names based on node-types.

  Returns:
    regex pattern matching node model names like 'node_basic_base'"
  []
  (let [node-type-str (str "(" (str/join "|" (map name (sort (keys (node-types*)))) )")")
        variant-str   "(base|energy_\\d+|connected)"]
    (re-pattern (str "node_" node-type-str "_" variant-str))))

(defn- parse-node-model-name
  "Parse node model name into components.

  Parameters:
    model-name: string like 'node_basic_base' or 'node_advanced_energy_3'

  Returns:
    [node-type variant] or nil if not a node model"
  [model-name]
  (when-let [[_ node-type variant] (re-matches (node-model-pattern) model-name)]
    [node-type variant]))

(defn- variant->energy-level
  "Convert variant string to energy level for texture selection.

  Parameters:
    variant: string like 'base', 'connected', or 'energy_3'

  Returns:
    integer energy level (0-4)"
  [variant]
  (let [raw (cond
              (#{"base" "connected"} variant) 0
              (str/starts-with? variant "energy_") (Integer/parseInt (subs variant 7))
              :else 0)
        ;; Extract min/max from schema
        {:keys [min max]} (get (extract-blockstate-props) :energy)
        min-v (or min 0)
        max-v (or max 4)]
    (clojure.core/max min-v (clojure.core/min max-v raw))))

;; ============================================================================
;; Node Model Texture Configuration
;; ============================================================================

(defn get-node-model-texture-config
  "Get texture configuration for node model variants.

  This function contains node-specific texture logic:
  - Energy levels affect side texture
  - Connected state affects top texture

  Parameters:
    model-name: model name without namespace, like 'node_basic_base'

  Returns:
    {:side \"texture-path\" :vert \"texture-path\"} or nil if not a node model"
  [model-name]
  (when-let [[node-type variant] (parse-node-model-name model-name)]
    (let [energy-level (variant->energy-level variant)
          top-texture  (if (= variant "connected")
                        (str (mod-id) ":block/node_top_1")
                        (str (mod-id) ":block/node_top_0"))
          side-texture (str (mod-id) ":block/node_" node-type "_side_" energy-level)]
      {:side side-texture
       :vert top-texture})))

;; ============================================================================
;; Node Block Identification
;; ============================================================================

(defn is-node-block?
  "Check if a block is a wireless node block.

  Parameters:
    registry-name: block registry name

  Returns:
    true if this is a node block (basic/standard/advanced)"
  [registry-name]
  (boolean (re-find #"^node_(basic|standard|advanced)" registry-name)))

(defn get-node-item-model-id
  "Get item model ID for node blocks.

  Node blocks use the _base variant for their item model.

  Parameters:
    mod-id: mod ID string
    registry-name: block registry name

  Returns:
    item model ID string"
  [mod-id registry-name]
  (str mod-id ":block/" registry-name "_base"))

(comment
  ;; Usage examples
  (get-all-node-definitions)
  (get-node-blockstate-definition :wireless-node-basic)
  (get-node-model-texture-config "node_basic_base")
  (get-node-model-texture-config "node_advanced_energy_3")
  (is-node-block? "node_basic")
  (get-node-item-model-id "academycraft" "node_standard"))
