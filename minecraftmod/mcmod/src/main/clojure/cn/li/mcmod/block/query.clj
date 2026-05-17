(ns cn.li.mcmod.block.query
  "Block query API - functions to query registered block specifications.

  Accepts either block ids or already-resolved block specs for most helpers so
  callers can progressively migrate away from registry wrapper layers."
  (:require [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.protocol.core :as registry-core]))

(declare get-block-spec)

(defn- normalize-block-id
  [block-id]
  (cond
    (keyword? block-id) (name block-id)
    (some? block-id) (str block-id)
    :else nil))

(defn- spec-like?
  [value]
  (and (map? value)
       (contains? value :id)
       (contains? value :multi-block)))

(defn- resolve-block-spec
  [block-or-spec]
  (if (spec-like? block-or-spec)
    block-or-spec
    (get-block-spec block-or-spec)))

;; ============================================================================
;; Block Registry Query API
;; ============================================================================

;; Note: The block-registry is stored in block.dsl; this module provides
;; the query interface that other modules should use. This allows the registry
;; to be extended with different storage mechanisms in the future.

(defn- block-registry-state
  []
  (let [block-registry-var (requiring-resolve 'cn.li.mcmod.block.dsl-core/block-registry)]
    (registry-core/snapshot (var-get block-registry-var))))

(defn get-block-spec
  "Get a block specification by block-id from the registry
   block-id: string or keyword identifier
   Returns: BlockSpec record or nil if not found"
  [block-id]
  (let [id-str (normalize-block-id block-id)]
    (get (block-registry-state) id-str)))

(defn list-all-blocks
  "Get a sequence of all registered block IDs
   Returns: sequence of block-id strings"
  []
  (keys (block-registry-state)))

(defn controller-parts-block?
  "Return true when the block uses controller+parts multiblock mode."
  [block-or-spec]
  (= :controller-parts (get-in (resolve-block-spec block-or-spec)
                               [:multi-block :multiblock-mode])))

;; ============================================================================
;; Block Predicate Queries
;; ============================================================================

(defn is-controller-block?
  "Check if a block is a multi-block controller block
   block-spec: BlockSpec record to check
   Returns: true if this block is a multi-block controller"
  [block-or-spec]
  (when-let [block-spec (resolve-block-spec block-or-spec)]
    (let [multi-block (:multi-block block-spec)
          block-id (normalize-block-id (:id block-spec))
          controller-id (normalize-block-id (:controller-block-id multi-block))]
      (cond
        (= :controller-parts (:multiblock-mode multi-block))
        (and (some? controller-id)
             (= block-id controller-id))

        (:multi-block? multi-block)
        (boolean (:multi-block-master? multi-block))

        :else false))))

(defn is-part-block?
  "Check if a block is part of a multi-block structure
   block-spec: BlockSpec record to check
   Returns: true if this block is part of a multi-block"
  [block-or-spec]
  (when-let [block-spec (resolve-block-spec block-or-spec)]
    (let [multi-block (:multi-block block-spec)
          block-id (normalize-block-id (:id block-spec))
          part-id (normalize-block-id (:part-block-id multi-block))]
      (cond
        (= :controller-parts (:multiblock-mode multi-block))
        (and (some? part-id)
             (= block-id part-id))

        (:multi-block? multi-block)
        (not (boolean (:multi-block-master? multi-block)))

        :else false))))

(defn is-multi-block?
  "Check if a block is multi-block enabled
   block-spec: BlockSpec record to check
   Returns: true if block has multi-block config"
  [block-or-spec]
  (when-let [block-spec (resolve-block-spec block-or-spec)]
    (let [multi-block (:multi-block block-spec)]
      (:multi-block? multi-block))))

(defn has-tile-entity?
  "Check if a block has a tile entity (block entity)
   block-spec: BlockSpec record to check
   Returns: true if block has tile entity"
  [block-or-spec]
  (when-let [block-spec (resolve-block-spec block-or-spec)]
    (boolean (tdsl/get-tile-id-for-block (:id block-spec)))))

(defn is-light-emitter?
  "Check if a block emits light
   block-spec: BlockSpec record to check
   Returns: true if block has light level > 0"
  [block-or-spec]
  (when-let [block-spec (resolve-block-spec block-or-spec)]
    (pos? (get-in block-spec [:rendering :light-level] 0))))

;; ============================================================================
;; Block Property Accessors
;; ============================================================================

(defn get-controller-block-id
  "Get the controller block ID for a multi-block structure
   block-spec: BlockSpec record
   Returns: string block ID or nil"
  [block-or-spec]
  (when-let [block-spec (resolve-block-spec block-or-spec)]
    (get-in block-spec [:multi-block :controller-block-id])))

(defn get-part-block-id
  "Get the part block ID for a multi-block structure
   block-spec: BlockSpec record
   Returns: string block ID or nil"
  [block-or-spec]
  (when-let [block-spec (resolve-block-spec block-or-spec)]
    (get-in block-spec [:multi-block :part-block-id])))

(defn get-tile-kind
  "Get the tile entity kind for a block
   block-spec: BlockSpec record
   Returns: keyword tile kind or nil"
  [block-or-spec]
  (when-let [block-spec (resolve-block-spec block-or-spec)]
    (some-> (tdsl/get-tile-id-for-block (:id block-spec))
            tdsl/get-tile
            :tile-kind)))

(defn get-light-level
  "Get the light emission level for a block
   block-spec: BlockSpec record
   Returns: integer 0-15"
  [block-or-spec]
  (when-let [block-spec (resolve-block-spec block-or-spec)]
    (get-in block-spec [:rendering :light-level] 0)))

(defn get-hardness
  "Get the hardness value for a block
   block-spec: BlockSpec record
   Returns: float hardness value"
  [block-or-spec]
  (when-let [block-spec (resolve-block-spec block-or-spec)]
    (get-in block-spec [:physical :hardness] 1.5)))

(defn get-material
  "Get the material type for a block
   block-spec: BlockSpec record
   Returns: keyword material (:stone, :wood, etc.)"
  [block-or-spec]
  (when-let [block-spec (resolve-block-spec block-or-spec)]
    (get-in block-spec [:physical :material] :stone)))

;; ============================================================================
;; Block Structure Queries
;; ============================================================================

(defn get-structure-offsets
  "Get all relative offsets for a multi-block structure
   block-spec: BlockSpec record
   Returns: vector of {:x :y :z :relative-x :relative-y :relative-z :is-origin?} maps or nil"
  [block-or-spec]
  (when-let [block-spec (resolve-block-spec block-or-spec)]
    (let [multi-block (:multi-block block-spec)]
      (when (:multi-block? multi-block)
        (let [multiblock-router (requiring-resolve 'cn.li.mcmod.block.multiblock-router/calculate-multi-block-positions)
              origin (or (:multi-block-origin multi-block) {:x 0 :y 0 :z 0})
              positions (if-let [custom-pos (:multi-block-positions multi-block)]
                          (multiblock-router custom-pos origin)
                          (multiblock-router (:multi-block-size multi-block) origin))]
          positions)))))

(defn has-block-state-properties?
  "Check if a block has custom block state properties
   block-spec: BlockSpec record
   Returns: true if block has block-state-properties defined"
  [block-or-spec]
  (when-let [block-spec (resolve-block-spec block-or-spec)]
    (boolean (get-in block-spec [:block-state :block-state-properties]))))

(defn has-block-entity?
  "Check if a block is bound to a Tile DSL block entity.
   block-spec: BlockSpec record
   Returns: true if Tile DSL maps the block to a tile-id"
  [block-or-spec]
  (has-tile-entity? block-or-spec))
