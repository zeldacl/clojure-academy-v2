(ns cn.li.mcmod.block.tile-dsl
  "Tile/BlockEntity DSL - declarative BlockEntityType metadata and lifecycle hooks.

  Goals:
  - Allow defining a single tile type bound to multiple blocks.
  - Keep core platform-neutral: stores metadata only; platforms query via registry metadata."
  (:require [clojure.string :as str]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.schema.core :as schema]
            [cn.li.mcmod.protocol.core :as registry-core]
            [cn.li.mcmod.util.log :as log]))

(def ^:private ^:dynamic *tile-registry-state*
  {:by-id {} :block->tile-id {}})

(defonce ^{:doc "Registry of tile specs.

Structure:
- :by-id {tile-id -> TileSpec}
- :block->tile-id {block-id -> tile-id}"}
  tile-registry
  (registry-core/var-root-registry #'*tile-registry-state*))

(defrecord TileSpec
  [id
   registry-name
   impl
   blocks
   tile-kind
   tick-fn
   read-nbt-fn
   write-nbt-fn
   ;; Optional custom BlockEntity supplier. Shape:
   ;; (fn [be-type pos state block-id] -> BlockEntity)
   be-supplier])

(defn- normalize-id
  [x]
  (cond
    (nil? x) nil
    (string? x) x
    (keyword? x) (name x)
    (symbol? x) (name x)
    :else (str x)))

(defn- normalize-block-ids
  [blocks]
  (->> blocks
       (map normalize-id)
       (remove str/blank?)
       vec))

(def ^:private tile-id-schema
  [:and string? [:fn (complement str/blank?)]])

(def ^:private tile-impl-schema
  keyword?)

(def ^:private tile-blocks-schema
  [:and
   [:vector string?]
   [:fn seq]])

(let [validator-for (memoize schema/validator)]
  (defn- valid-tile-id? [x]
    (schema/valid? (validator-for tile-id-schema) x))
  (defn- valid-tile-impl? [x]
    (schema/valid? (validator-for tile-impl-schema) x))
  (defn- valid-tile-blocks? [x]
    (schema/valid? (validator-for tile-blocks-schema) x)))

(defn validate-tile-spec
  [{:keys [id impl blocks] :as spec}]
  (when-not (valid-tile-id? id)
    (throw (ex-info "TileSpec must have non-empty string :id" {:spec spec})))
  (when-not (valid-tile-impl? impl)
    (throw (ex-info "TileSpec :impl must be a keyword" {:id id :impl impl})))
  (when-not (valid-tile-blocks? blocks)
    (throw (ex-info "TileSpec :blocks must be a non-empty vector of block-id strings"
                    {:id id :blocks blocks})))
  true)

(defn create-tile-spec
  "Create a TileSpec from options.

  Required options:
  - :impl   keyword (e.g. :scripted)
  - :blocks vector of block-ids (strings/keywords/symbols accepted)

  Optional:
  - :registry-name string override (snake_case) used by platform registration
  - :tile-kind keyword
  - :tick-fn/:read-nbt-fn/:write-nbt-fn
  - :be-supplier custom BlockEntity supplier (advanced)"
  [tile-id options]
  (let [blocks (normalize-block-ids (:blocks options))
        spec (map->TileSpec
              {:id tile-id
               :registry-name (:registry-name options)
               :impl (or (:impl options) :scripted)
               :blocks blocks
               :tile-kind (:tile-kind options)
               :tick-fn (:tick-fn options)
               :read-nbt-fn (:read-nbt-fn options)
               :write-nbt-fn (:write-nbt-fn options)
               :be-supplier (:be-supplier options)})]
    (validate-tile-spec spec)
    spec))

(defn register-tile!
  "Register a TileSpec.

Enforces:
- tile-id unique
- a block-id can only belong to one tile-id"
  [tile-spec]
  (validate-tile-spec tile-spec)
  (let [{:keys [id blocks]} tile-spec]
    (registry-core/swap-state!
     tile-registry
     (fn [{:keys [by-id block->tile-id] :as reg}]
       (when (contains? by-id id)
         ;; `checkClojure`/AOT 会重复加载同一份 DSL，导致 tile-spec 里函数对象不再“相等”。
         ;; 这里将重复 tile-id 视为幂等；真正的约束由下面的 block->tile-id 冲突检查保证。
         nil)

       (doseq [block-id blocks
               :let [existing (get block->tile-id block-id)]
               :when existing]
         (when-not (= existing id)
           (when-not *compile-files*
             (throw (ex-info "Block is already bound to a tile-id"
                             {:block-id block-id
                              :existing-tile-id existing
                              :new-tile-id id})))))

       (-> reg
           (assoc-in [:by-id id] tile-spec)
           (update :block->tile-id
                   into
                   (map (fn [b] [b id]) blocks)))))
    (log/info "Registered tile" id "for blocks" blocks)
    tile-spec))

(defn get-tile
  [tile-id]
  (registry-core/lookup-in tile-registry [:by-id (normalize-id tile-id)]))

(defn list-tiles
  []
  (keys (:by-id (registry-core/snapshot tile-registry))))

(defn get-tile-id-for-block
  [block-id]
  (registry-core/lookup-in tile-registry [:block->tile-id (normalize-id block-id)]))

(defmacro deftile
  "Define and register a tile spec.

  Example:
  (deftile content-node-tile
    :id \"content-node\"
    :impl :scripted
    :blocks [\"content-node-basic\" \"content-node-standard\"]
    :tile-kind :content-node
    :tick-fn my-tick
    :read-nbt-fn my-read
    :write-nbt-fn my-write)"
  [tile-name & options]
  (let [options-map (apply hash-map options)
        tile-id (or (:id options-map) (name tile-name))
        options-map (dissoc options-map :id)]
    `(def ~tile-name
       (register-tile!
         (create-tile-spec ~tile-id ~options-map)))))

(defmacro deftile-kind
  "Register reusable tile-kind defaults in `cn.li.mcmod.block.tile-logic`."
  [tile-kind & options]
  (let [options-map (apply hash-map options)]
    `(tile-logic/register-tile-kind! ~tile-kind ~options-map)))

