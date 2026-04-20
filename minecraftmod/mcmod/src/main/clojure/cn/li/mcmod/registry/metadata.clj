(ns cn.li.mcmod.registry.metadata
  "Registry metadata system - single source of truth for block/item registration.
  
  This module provides metadata-driven registration, ensuring platform code
  does not contain hardcoded game-specific block/item names. All registration
  information is dynamically retrieved from the DSL systems.
  
  Architecture:
  - Platform code queries this module for what to register
  - This module queries DSL systems for available blocks/items
  - Game content lives in ac (and DSL registries in mcmod); platform code stays generic"
  (:require [clojure.string :as str]
            [cn.li.mcmod.block.dsl :as bdsl]
            [cn.li.mcmod.block.tile-dsl :as tdsl]
            [cn.li.mcmod.gui.dsl :as gui-dsl]
            [cn.li.mcmod.fluid.dsl :as fdsl]
            [cn.li.mcmod.effect.dsl :as edsl]
            [cn.li.mcmod.particle.dsl :as pdsl]
            [cn.li.mcmod.loot.dsl :as ldsl]
            [cn.li.mcmod.sound.dsl :as sdsl]
            [cn.li.mcmod.item.dsl :as idsl]))

;; Block Registration Metadata
;; ============================================================

(defn get-all-block-ids
  "Returns a sequence of all registered block IDs from the block DSL.
  
  Platform code should iterate over this list to register all blocks,
  without knowing specific block names.
  
  Returns:
    Sequence of block ID strings (e.g., [\"demo-block\" \"copper-ore\" ...])"
  []
  (bdsl/list-blocks))

(declare get-block-spec)

(defn get-block-registry-name
  "Returns the Minecraft registry name for a block ID.
  
  Uses explicit :registry-name override from block spec when provided,
  otherwise converts DSL block ID (kebab-case) to Minecraft registry format
  (snake_case).
  
  Args:
    block-id: String - DSL block identifier (e.g., \"demo-block\")
  
  Returns:
    String - Registry name (e.g., \"demo_block\")"
  [block-id]
  (let [block-spec (get-block-spec block-id)
        explicit-name (:registry-name block-spec)]
    (if (and (string? explicit-name) (not (str/blank? explicit-name)))
      explicit-name
      (str/replace block-id #"-" "_"))))

(defn get-block-spec
  "Retrieves the full block specification from the DSL.
  
  Args:
    block-id: String - DSL block identifier
  
  Returns:
    Map - Block specification with all properties"
  [block-id]
  (bdsl/get-block block-id))

(defn controller-parts-block?
  "Return true when the block uses DSL controller+parts mode."
  [block-id]
  (= :controller-parts (get-in (get-block-spec block-id) [:multi-block :multiblock-mode])))

(defn- norm-block-id-for-compare
  "Match BE / DSL ids whether stored as keyword or string (defrecord vs runtime)."
  [x]
  (when (some? x)
    (if (keyword? x) (name x) (str x))))

(defn is-controller-block?
  "Return true when block-id is the controller block in controller+parts mode."
  [block-id]
  (let [spec (get-block-spec block-id)
        multi-block (:multi-block spec)
        cid (:controller-block-id multi-block)]
    (and (= :controller-parts (:multiblock-mode multi-block))
         (some? cid)
         (= (norm-block-id-for-compare block-id) (norm-block-id-for-compare cid)))))

(defn is-part-block?
  "Return true when block-id is the part block in controller+parts mode."
  [block-id]
  (let [spec (get-block-spec block-id)
        multi-block (:multi-block spec)
        pid (:part-block-id multi-block)]
    (and (= :controller-parts (:multiblock-mode multi-block))
         (some? pid)
         (= (norm-block-id-for-compare block-id) (norm-block-id-for-compare pid)))))

(defn get-controller-block-id
  "Get controller block-id for a block participating in controller+parts mode."
  [block-id]
  (get-in (get-block-spec block-id) [:multi-block :controller-block-id]))

(defn get-part-block-id
  "Get part block-id for a block participating in controller+parts mode."
  [block-id]
  (get-in (get-block-spec block-id) [:multi-block :part-block-id]))

(defn get-structure-offsets
  "Get normalized relative offsets (including origin) for a controller block.
  Returns a vector of maps with :relative-x/:relative-y/:relative-z."
  [controller-block-id]
  (when-let [spec (get-block-spec controller-block-id)]
    (let [multi-block (:multi-block spec)
          origin (or (:multi-block-origin multi-block) {:x 0 :y 0 :z 0})]
      (if-let [custom-pos (:multi-block-positions multi-block)]
        (bdsl/calculate-multi-block-positions custom-pos origin)
        (bdsl/calculate-multi-block-positions (:multi-block-size multi-block) origin)))))

(defn has-block-state-properties?
  "Checks if a block has dynamic block state properties.

  Args:
    block-id: String - DSL block identifier

  Returns:
    Boolean - true if block has block-state-properties defined"
  [block-id]
  (let [block-spec (get-block-spec block-id)]
    (some? (get-in block-spec [:block-state :block-state-properties]))))

(defn has-block-entity?
  "Checks if a block uses the generic scripted BlockEntity (ScriptedEntityBlock/ScriptedBlockEntity).

  Args:
    block-id: String - DSL block identifier

  Returns:
    Boolean - true if block has :has-block-entity? in its spec"
  [block-id]
  (let [block-spec (get-block-spec block-id)]
    (boolean (or (get-in block-spec [:tile-entity :has-block-entity?])
                 (some? (tdsl/get-tile-id-for-block block-id))))))

(defn get-scripted-block-ids
  "Returns block IDs that use the generic scripted BlockEntity.
  Platform uses this to register ScriptedEntityBlock and BlockEntityType per block-id."
  []
  (filter has-block-entity? (get-all-block-ids)))

(defn get-tile-kind
  "Returns optional :tile-kind metadata for a block.

  Prefers Tile DSL (`deftile`) when the block is bound to a tile-id."
  [block-id]
  (if-let [tile-id (tdsl/get-tile-id-for-block block-id)]
    (some-> (tdsl/get-tile tile-id) :tile-kind)
    (some-> (get-block-spec block-id) :tile-entity :tile-kind)))

(defn get-scripted-tile-hooks
  "Returns optional scripted tile lifecycle hooks for a block.
  Result keys: :tick-fn :read-nbt-fn :write-nbt-fn."
  [block-id]
  (if-let [tile-id (tdsl/get-tile-id-for-block block-id)]
    (let [spec (tdsl/get-tile tile-id)]
      {:tick-fn (:tick-fn spec)
       :read-nbt-fn (:read-nbt-fn spec)
       :write-nbt-fn (:write-nbt-fn spec)})
    (let [spec (get-block-spec block-id)
          tile-entity (:tile-entity spec)]
      {:tick-fn (:tile-tick-fn tile-entity)
       :read-nbt-fn (:tile-load-fn tile-entity)
       :write-nbt-fn (:tile-save-fn tile-entity)})))

;; Tile/BlockEntity Registration Metadata (Tile DSL + legacy block spec compatibility)
;; ============================================================

(defn get-all-tile-ids
  "Return all registered tile ids.

  Includes:
  - explicit tile ids from Tile DSL
  - implicit per-block tile ids for legacy blocks with :has-block-entity? true"
  []
  (let [explicit (set (tdsl/list-tiles))
        implicit (->> (get-all-block-ids)
                      (filter (fn [block-id]
                                (boolean (get-in (get-block-spec block-id) [:tile-entity :has-block-entity?]))))
                      set)]
    (seq (into explicit implicit))))

(defn get-block-tile-id
  "Get tile-id for a block-id, if the block has a BlockEntity.

  Returns:
  - tile-id string (explicit Tile DSL mapping if present, else block-id for legacy)
  - nil if block has no BlockEntity"
  [block-id]
  (or (tdsl/get-tile-id-for-block block-id)
      (when (boolean (get-in (get-block-spec block-id) [:tile-entity :has-block-entity?]))
        (str block-id))))

(defn get-tile-spec
  "Get TileSpec for a tile-id.

  For implicit legacy tiles, returns a synthetic spec map."
  [tile-id]
  (if-let [spec (tdsl/get-tile tile-id)]
    spec
    (when-let [block-spec (get-block-spec tile-id)]
      (let [tile-entity (:tile-entity block-spec)]
        (when (boolean (:has-block-entity? tile-entity))
          {:id tile-id
           :impl :scripted
           :blocks [tile-id]
           :tile-kind (:tile-kind tile-entity)
           :tick-fn (:tile-tick-fn tile-entity)
           :read-nbt-fn (:tile-load-fn tile-entity)
           :write-nbt-fn (:tile-save-fn tile-entity)})))))

(defn get-tile-block-ids
  "Get block-ids bound to a tile-id."
  [tile-id]
  (when-let [spec (get-tile-spec tile-id)]
    (:blocks spec)))

(defn get-tile-registry-name
  "Get the Minecraft registry name for a tile-id (BlockEntityType registry path).

  Rules:
  - explicit TileSpec: :registry-name override if present else kebab->snake
  - implicit legacy tile-id==block-id: uses block registry name to preserve stability"
  [tile-id]
  (if-let [spec (tdsl/get-tile tile-id)]
    (if-let [explicit (:registry-name spec)]
      explicit
      (str/replace tile-id #"-" "_"))
    ;; implicit legacy
    (get-block-registry-name tile-id)))

;; GUI metadata (core single source of truth)
;; ============================================================

(defn get-all-gui-ids
  "Return all registered platform GUI ids (ints)."
  []
  (gui-dsl/get-all-gui-ids))

(defn get-gui-spec
  "Get a GUI spec by gui-id (int)."
  [gui-id]
  (gui-dsl/get-gui-by-gui-id gui-id))

(defn get-gui-registry-name
  [gui-id]
  (gui-dsl/get-registry-name gui-id))

(defn get-gui-screen-factory-fn-kw
  [gui-id]
  (gui-dsl/get-screen-factory-fn-kw gui-id))

(defn get-gui-slot-layout
  [gui-id]
  (gui-dsl/get-slot-layout gui-id))

(defn get-gui-slot-range
  [gui-id section]
  (gui-dsl/get-slot-range gui-id section))

(defn get-block-state-properties
  "Returns the block state properties definition for a block.

  Args:
    block-id: String - DSL block identifier

  Returns:
    Map - Block state properties (e.g., {:energy {...} :connected {...}}), or nil"
  [block-id]
  (let [block-spec (get-block-spec block-id)]
    (get-in block-spec [:block-state :block-state-properties])))

;; Item Registration Metadata
;; ============================================================

(defn get-all-item-ids
  "Returns a sequence of all registered item IDs from the item DSL.

  Platform code should iterate over this list to register all items,
  without knowing specific item names.

  Returns:
    Sequence of item ID strings (e.g., [\"demo-item\" \"copper-ingot\" ...])"
  []
  (idsl/list-items))

(defn get-item-registry-name
  "Returns the Minecraft registry name for an item ID.

  Converts DSL item ID (kebab-case) to Minecraft registry format (snake_case).

  Args:
    item-id: String - DSL item identifier (e.g., \"demo-item\")

  Returns:
    String - Registry name (e.g., \"demo_item\")"
  [item-id]
  (clojure.string/replace item-id #"-" "_"))

(defn get-item-spec
  "Retrieves the full item specification from the DSL.

  Args:
    item-id: String - DSL item identifier

  Returns:
    Map - Item specification with all properties"
  [item-id]
  (idsl/get-item item-id))

;; Effect Registration Metadata
;; ============================================================

(defn get-all-effect-ids
  "Returns all registered custom effect IDs from the effect DSL."
  []
  (edsl/list-effects))

(defn get-effect-spec
  "Retrieves full effect specification from the DSL."
  [effect-id]
  (edsl/get-effect effect-id))

(defn get-effect-registry-name
  "Returns the Minecraft registry name for an effect ID."
  [effect-id]
  (or (:registry-name (get-effect-spec effect-id))
      (str/replace (str effect-id) #"-" "_")))

;; Particle Registration Metadata
;; ============================================================

(defn get-all-particle-ids
  "Returns all registered particle IDs from particle DSL."
  []
  (pdsl/list-particles))

(defn get-particle-spec
  "Retrieves full particle specification from DSL."
  [particle-id]
  (pdsl/get-particle particle-id))

(defn get-particle-registry-name
  "Returns the Minecraft registry name for a particle ID."
  [particle-id]
  (or (:registry-name (get-particle-spec particle-id))
      (str/replace (str particle-id) #"-" "_")))

;; Sound Registration Metadata
;; ============================================================

(defn get-all-sound-ids
  "Returns a sequence of all registered sound IDs from the sound DSL."
  []
  (sdsl/list-sounds))

(defn get-sound-registry-name
  "Returns the Minecraft registry name for a sound ID.

  Uses explicit :registry-name override when provided.

  Args:
    sound-id: String - DSL sound identifier

  Returns:
    String - Registry name"
  [sound-id]
  (let [sound-spec (sdsl/get-sound sound-id)
        explicit-name (:registry-name sound-spec)]
    (if (and (string? explicit-name) (not (str/blank? explicit-name)))
      explicit-name
      sound-id)))

(defn get-sound-spec
  "Retrieves the full sound specification from the DSL.

  Args:
    sound-id: String - DSL sound identifier

  Returns:
    Map - Sound specification with all properties"
  [sound-id]
  (sdsl/get-sound sound-id))

;; Loot Injection Metadata
;; ============================================================

(defn get-all-loot-injection-ids
  "Returns all registered loot injection IDs from the loot DSL."
  []
  (ldsl/list-loot-injections))

(defn get-loot-injection-spec
  "Returns loot injection specification by injection ID."
  [injection-id]
  (ldsl/get-loot-injection injection-id))

(defn get-loot-injections-for-table
  "Returns all loot injection specs matching target loot table ID."
  [target-table]
  (ldsl/get-loot-injections-for-table target-table))

;; BlockItem Registration Metadata
;; ============================================================

(defn should-create-block-item?
  "Determines if a block should have a corresponding BlockItem.

  By default, all blocks get BlockItems for placement in the world.
  This can be customized based on block properties if needed.

  Args:
    block-id: String - DSL block identifier

  Returns:
    Boolean - true if BlockItem should be created"
  [block-id]
  (let [block-spec (get-block-spec block-id)]
    (not= false (get-in block-spec [:rendering :has-item-form?]))))

;; Fluid Registration Metadata
;; ============================================================

(defn get-all-fluid-ids
  "Returns a sequence of all registered fluid IDs from the fluid DSL."
  []
  (fdsl/list-fluids))

(defn get-fluid-spec
  "Retrieves the full fluid specification from the fluid DSL."
  [fluid-id]
  (fdsl/get-fluid fluid-id))

(defn get-fluid-registry-name
  "Returns Minecraft registry name for a fluid ID."
  [fluid-id]
  (or (some-> (get-fluid-spec fluid-id) :registry-name)
      (str/replace (str fluid-id) #"-" "_")))

(defn get-fluid-block-id
  "Returns associated block-id for a fluid."
  [fluid-id]
  (some-> (get-fluid-spec fluid-id) :block :block-id))

(defn get-fluid-id-for-block
  "Returns fluid-id associated with block-id, or nil."
  [block-id]
  (some (fn [fluid-id]
          (when (= (str block-id) (get-fluid-block-id fluid-id))
            fluid-id))
        (get-all-fluid-ids)))

(defn fluid-block?
  "Returns true when a block-id is produced by the fluid DSL."
  [block-id]
  (boolean
    (some (fn [fluid-id]
            (= (str block-id) (get-fluid-block-id fluid-id)))
          (get-all-fluid-ids))))

;; Creative Tab Metadata
;; ============================================================

(defn get-item-creative-tab
  "Returns the creative tab for an item.

  Args:
    item-id: String - DSL item identifier

  Returns:
    Keyword - Creative tab (e.g., :misc, :tools, :combat)"
  [item-id]
  (let [item-spec (get-item-spec item-id)]
    (or (:creative-tab item-spec) :misc)))

(defn get-block-creative-tab
  "Returns the creative tab for a block (used for block items).

  Args:
    block-id: String - DSL block identifier

  Returns:
    Keyword - Creative tab (e.g., :misc, :building-blocks)"
  [block-id]
  (let [block-spec (get-block-spec block-id)]
    (or (get-in block-spec [:rendering :creative-tab]) :misc)))

(defn get-all-creative-tab-entries
  "Returns all items and block-items that should be added to creative tabs.

  Platform code should use this to populate creative mode tabs without
  knowing specific item/block names.

  Returns:
    Sequence of maps with:
      :type - :item or :block-item
      :id - registry ID (string)
      :tab - creative tab keyword
      :registry-name - Minecraft registry name

  Example:
    [{:type :item :id \"demo-item\" :tab :misc :registry-name \"demo_item\"}
     {:type :block-item :id \"demo-block\" :tab :building-blocks :registry-name \"demo_block\"}]"
  []
  (let [;; Get all standalone items - fetch spec once per item
        item-entries (for [item-id (get-all-item-ids)
                           :let [spec (idsl/get-item item-id)]]
                       {:type :item
                        :id item-id
                        :tab (:creative-tab spec)
                        :registry-name (or (:registry-name spec)
                                          (str/replace item-id #"-" "_"))})

        ;; Get all block items - fetch spec once per block
        block-item-entries (for [block-id (get-all-block-ids)
                                 :let [spec (bdsl/get-block block-id)
                                       rendering (:rendering spec)]
                                 :when (:has-item-form? rendering)]
                             {:type :block-item
                              :id block-id
                              :tab (:creative-tab rendering)
                              :registry-name (or (:registry-name spec)
                                                (str/replace block-id #"-" "_"))})]

    ;; Combine and return all entries
    (concat item-entries block-item-entries)))

;; Initialization
;; ============================================================

(defn init-registry-metadata!
  "Initialize registry metadata system.
  
  Called during mod initialization to ensure DSL systems are ready.
  Platform code should call this before attempting registration."
  []
  ;; DSL systems are initialized when their namespaces are loaded
  ;; This function exists for future initialization needs
  nil)
