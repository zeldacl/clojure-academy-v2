(ns cn.li.mcmod.block.tile-dsl
  "Tile/BlockEntity DSL - declarative BlockEntityType metadata and lifecycle hooks.

  Goals:
  - Allow defining a single tile type bound to multiple blocks.
  - Keep core platform-neutral: stores metadata only; platforms query via registry metadata."
  (:require [clojure.string :as str]
            [cn.li.mcmod.block.tile-logic :as tile-logic]
            [cn.li.mcmod.util.log :as log]))

(defonce ^{:doc "Registry of tile specs.

Structure:
- :by-id {tile-id -> TileSpec}
- :block->tile-id {block-id -> tile-id}"}
  tile-registry
  (atom {:by-id {} :block->tile-id {}}))

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

(defn validate-tile-spec
  [{:keys [id impl blocks] :as spec}]
  (when-not (and (string? id) (not (str/blank? id)))
    (throw (ex-info "TileSpec must have non-empty string :id" {:spec spec})))
  (when-not (keyword? impl)
    (throw (ex-info "TileSpec :impl must be a keyword" {:id id :impl impl})))
  (when-not (and (vector? blocks) (seq blocks) (every? string? blocks))
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
    (swap! tile-registry
           (fn [{:keys [by-id block->tile-id] :as reg}]
             (when (contains? by-id id)
               (throw (ex-info "Duplicate tile-id registered" {:tile-id id})))
             (doseq [block-id blocks
                     :let [existing (get block->tile-id block-id)]
                     :when existing]
               (throw (ex-info "Block is already bound to a tile-id"
                               {:block-id block-id
                                :existing-tile-id existing
                                :new-tile-id id})))
             (-> reg
                 (assoc-in [:by-id id] tile-spec)
                 (update :block->tile-id
                         into
                         (map (fn [b] [b id]) blocks)))))
    (log/info "Registered tile" id "for blocks" blocks)
    tile-spec))

(defn get-tile
  [tile-id]
  (get-in @tile-registry [:by-id (normalize-id tile-id)]))

(defn list-tiles
  []
  (keys (:by-id @tile-registry)))

(defn get-tile-id-for-block
  [block-id]
  (get-in @tile-registry [:block->tile-id (normalize-id block-id)]))

(defmacro deftile
  "Define and register a tile spec.

  Example:
  (deftile wireless-node-tile
    :id \"wireless-node\"
    :impl :scripted
    :blocks [\"wireless-node-basic\" \"wireless-node-standard\"]
    :tile-kind :wireless-node
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
  "Register reusable tile-kind defaults in `my-mod.block.tile-logic`."
  [tile-kind & options]
  (let [options-map (apply hash-map options)]
    `(tile-logic/register-tile-kind! ~tile-kind ~options-map)))

