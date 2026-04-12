(ns cn.li.mcmod.block.dsl
  "Block DSL - Declarative block definition using Clojure macros"
  (:require [clojure.string :as str]
            [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as platform-be]))

;; Block Registry - stores all defined blocks
(defonce block-registry (atom {}))

;; ============================================================================
;; Nested Record Structures for BlockSpec
;; ============================================================================

(defrecord PhysicalProperties
  [material hardness resistance friction slip-factor
   sounds harvest-level harvest-tool requires-tool]
  ;; Physical properties of a block: material, hardness, resistance, friction, sounds, harvest requirements.
  ;;
  ;; Fields:
  ;; - material: Material type keyword (:stone, :wood, :metal, :glass, etc.)
  ;; - hardness: How long it takes to break (higher = longer)
  ;; - resistance: Explosion resistance (higher = more resistant)
  ;; - friction: Slipperiness factor (0.6 = normal, lower = more slippery)
  ;; - slip-factor: Alternative name for friction
  ;; - sounds: Sound type keyword (:stone, :wood, :metal, :glass, etc.)
  ;; - harvest-level: Minimum tool tier required (0 = wood, 1 = stone, 2 = iron, 3 = diamond)
  ;; - harvest-tool: Tool type required (:pickaxe, :axe, :shovel, :hoe, :sword)
  ;; - requires-tool: Whether a tool is required to harvest drops
  )

(defrecord RenderingProperties
  [model-parent textures model-textures has-item-form?
   flat-item-icon? creative-tab light-level]
  ;; Rendering and visual properties of a block: models, textures, lighting, creative tab.
  ;;
  ;; Fields:
  ;; - model-parent: Parent model path for datagen (e.g., "block/cube_all")
  ;; - textures: Texture map for datagen (e.g., {:all "modid:block/texture"})
  ;; - model-textures: Additional model-specific textures
  ;; - has-item-form?: Whether this block should have a BlockItem/item model
  ;; - flat-item-icon?: Use flat 2D item icon instead of 3D block model
  ;; - creative-tab: Creative tab for block items (:misc, :building, :decorations, etc.)
  ;; - light-level: Light emission level (0-15, 0 = no light, 15 = brightest)
  )

(defrecord TileEntityConfig
  [has-block-entity? tile-kind tile-tick-fn tile-load-fn tile-save-fn]
  ;; Tile entity (block entity) configuration and lifecycle hooks.
  ;;
  ;; Fields:
  ;; - has-block-entity?: Whether this block has a tile entity
  ;; - tile-kind: Keyword identifying the tile entity type (e.g., :wireless-node)
  ;; - tile-tick-fn: Function called every tick (fn [tile-entity world pos] ...)
  ;; - tile-load-fn: Function called when loading from NBT (fn [tile-entity nbt] ...)
  ;; - tile-save-fn: Function called when saving to NBT (fn [tile-entity] nbt-map)
  )

(defrecord BlockStateConfig
  [block-state-properties]
  ;; Block state properties configuration.
  ;;
  ;; Fields:
  ;; - block-state-properties: Dynamic block state properties (e.g., {:energy IntegerProperty, :connected BooleanProperty})
  )

(defrecord EventHandlers
  [on-right-click on-break on-place on-multi-block-break]
  ;; Event handlers for block interactions.
  ;;
  ;; Fields:
  ;; - on-right-click: Handler for right-click/use (fn [event-data] ...)
  ;; - on-break: Handler for block break (fn [event-data] ...)
  ;; - on-place: Handler for block placement (fn [event-data] ...)
  ;; - on-multi-block-break: Handler for multi-block structure break (fn [event-data] ...)
  )

(defrecord MultiBlockConfig
  [multi-block? multi-block-size multi-block-positions multi-block-origin
   multi-block-rotation-center multi-block-master? multiblock-mode
   controller-block-id part-block-id pivot-xz-override
   tesr-use-raw-rotation-center? tesr-y-deg-override]
  ;; Multi-block structure configuration.
  ;;
  ;; Fields:
  ;; - multi-block?: Whether this is a multi-block structure
  ;; - multi-block-size: Regular multi-block size {:width N :height N :depth N}
  ;; - multi-block-positions: Irregular multi-block custom positions [{:x 0 :y 0 :z 0} ...]
  ;; - multi-block-origin: Origin point of the structure {:x 0 :y 0 :z 0}
  ;; - multi-block-rotation-center: Rotation center for the structure
  ;; - multi-block-master?: Whether this block is the master/controller
  ;; - multiblock-mode: Multi-block mode (:controller-parts, etc.)
  ;; - controller-block-id: ID of the controller block (for controller-parts mode)
  ;; - part-block-id: ID of the part block (for controller-parts mode)
  ;; - pivot-xz-override: Optional [dx dz] to skip legacy 2×2×2 pivot table in TESR (nil = default)
  ;; - tesr-use-raw-rotation-center?: When true, TESR uses :multi-block-rotation-center as literal
  ;;   [x y z] offsets (no direction->rotation-center remap; for irregular footprints).
  ;; - tesr-y-deg-override: When a number, TESR Y rotation uses this instead of direction-rotations.
  )

;; Block specifications
(defrecord BlockSpec
  [id registry-name properties
   physical rendering tile-entity block-state events multi-block]
  ;; Complete block specification with nested configuration groups.
  ;;
  ;; Core identity fields:
  ;; - id: Unique string identifier for this block
  ;; - registry-name: Registry name for platform registration
  ;; - properties: Additional custom properties map
  ;;
  ;; Nested configuration groups:
  ;; - physical: PhysicalProperties record
  ;; - rendering: RenderingProperties record
  ;; - tile-entity: TileEntityConfig record
  ;; - block-state: BlockStateConfig record
  ;; - events: EventHandlers record
  ;; - multi-block: MultiBlockConfig record
  )

;; Material types (version-agnostic)
(def materials
  {:stone :stone
   :wood :wood
   :metal :metal
   :glass :glass
   :dirt :dirt
   :sand :sand
   :grass :grass
   :leaves :leaves
   :water :water
   :lava :lava
   :air :air})

;; Sound types
(def sound-types
  {:stone :stone
   :wood :wood
   :metal :metal
   :glass :glass
   :grass :grass
   :sand :sand
   :gravel :gravel})

;; Tool types
(def tool-types
  {:pickaxe :pickaxe
   :axe :axe
   :shovel :shovel
   :hoe :hoe
   :sword :sword})

;; Default values
(def default-hardness 1.5)
(def default-resistance 6.0)
(def default-light-level 0)
(def default-friction 0.6)
(def default-creative-tab :misc)

;; Multi-block helper functions
(defn calculate-multi-block-positions
  "Calculate all positions for a multi-block structure
   For regular shapes: size: {:width 2 :height 3 :depth 2}
   For irregular shapes: custom-positions: [{:x 0 :y 0 :z 0} {:x 1 :y 0 :z 0} ...]
   origin: {:x 0 :y 0 :z 0}
   Returns: vector of relative position maps"
  [positions-or-spec origin]
  (if (and (map? positions-or-spec) (contains? positions-or-spec :width))
    ;; Regular shape with :width :height :depth
    (let [{:keys [width height depth]} positions-or-spec]
      (for [x (range width)
            y (range height)
            z (range depth)]
        {:x (+ (:x origin) x)
         :y (+ (:y origin) y)
         :z (+ (:z origin) z)
         :relative-x x
         :relative-y y
         :relative-z z
         :is-origin? (and (= x 0) (= y 0) (= z 0))}))
    ;; Irregular multi-blocks with custom positions
    (mapv (fn [pos]
          (let [[px py pz] (if (vector? pos)
                 [(nth pos 0 0) (nth pos 1 0) (nth pos 2 0)]
                 [(:x pos) (:y pos) (:z pos)])]
            {:x (+ (:x origin) px)
             :y (+ (:y origin) py)
             :z (+ (:z origin) pz)
             :relative-x px
             :relative-y py
             :relative-z pz
             :is-origin? (and (= px 0) (= py 0) (= pz 0))}))
          positions-or-spec)))

(defn normalize-positions
  "Normalize a list of positions to ensure one is at origin (0,0,0)
   Useful for creating irregular multi-blocks from absolute coordinates"
  [positions]
  (when (seq positions)
    (let [positions (mapv (fn [pos]
                            (if (vector? pos)
                              {:x (nth pos 0 0)
                               :y (nth pos 1 0)
                               :z (nth pos 2 0)}
                              pos))
                          positions)
          min-x (apply min (map :x positions))
          min-y (apply min (map :y positions))
          min-z (apply min (map :z positions))]
      (mapv (fn [pos]
              {:x (- (:x pos) min-x)
               :y (- (:y pos) min-y)
               :z (- (:z pos) min-z)})
            positions))))

(defn validate-multi-block-positions
  "Validate that custom multi-block positions are valid"
  [positions]
  (when (empty? positions)
    (throw (ex-info "Multi-block positions cannot be empty" {:positions positions})))
  ;; Helper to extract coordinates from either vector [x y z] or map {:x x :y y :z z}
  (let [get-coords (fn [pos]
                     (if (vector? pos)
                       [(nth pos 0 0) (nth pos 1 0) (nth pos 2 0)]
                       [(:x pos) (:y pos) (:z pos)]))]
    (when-not (some (fn [pos]
                      (let [[x y z] (get-coords pos)]
                        (and (= x 0) (= y 0) (= z 0))))
                    positions)
      (log/warn "Multi-block positions do not include origin (0,0,0), adding it automatically" positions)
      (throw (ex-info "Multi-block positions must include origin (0,0,0)" {:positions positions})))
    (when-not (every? (fn [pos]
                        (let [[x y z] (get-coords pos)]
                          (and (integer? x) (integer? y) (integer? z))))
                      positions)
      (throw (ex-info "All position coordinates must be integers" {:positions positions}))))
  true)

(defn get-multi-block-master-pos
  "Get the master block position from any part position
   part-pos: {:x 5 :y 10 :z 3}
   relative-pos: {:relative-x 1 :relative-y 2 :relative-z 1}
   Returns: {:x 4 :y 8 :z 2}"
  [part-pos relative-pos]
  {:x (- (:x part-pos) (:relative-x relative-pos))
   :y (- (:y part-pos) (:relative-y relative-pos))
   :z (- (:z part-pos) (:relative-z relative-pos))})

(defn resolve-multi-block-master-pos
  "Given a clicked block position (BlockPos) and a multi-block spec,
   try to resolve the master/origin BlockPos.

   Returns a BlockPos when resolved, or nil when not a valid part."
  [world clicked-pos block-spec]
  (let [multi-block (:multi-block block-spec)]
    (when (:multi-block? multi-block)
      (let [origin   (or (:multi-block-origin multi-block) {:x 0 :y 0 :z 0})
            positions (if-let [custom-pos (:multi-block-positions multi-block)]
                        (calculate-multi-block-positions custom-pos origin)
                        (calculate-multi-block-positions (:multi-block-size multi-block)
                                                         origin))
            part-pos-map {:x (pos/pos-x clicked-pos)
                          :y (pos/pos-y clicked-pos)
                          :z (pos/pos-z clicked-pos)}]
        (some (fn [rel-pos]
                (let [master-map (get-multi-block-master-pos part-pos-map rel-pos)
                      master-pos (pos/create-block-pos (:x master-map)
                                                       (:y master-map)
                                                       (:z master-map))]
                  (when (world/world-get-tile-entity* world master-pos)
                    master-pos)))
              positions)))))

(defn all-multi-block-positions
  "Return a sequence of all absolute BlockPos occupied by a multi-block,
   given the master/origin BlockPos and block-spec."
  [master-pos block-spec]
  (let [multi-block (:multi-block block-spec)
        origin   (or (:multi-block-origin multi-block) {:x 0 :y 0 :z 0})
        positions (if-let [custom-pos (:multi-block-positions multi-block)]
                    (calculate-multi-block-positions custom-pos origin)
                    (calculate-multi-block-positions (:multi-block-size multi-block)
                                                     origin))
        mx (pos/pos-x master-pos)
        my (pos/pos-y master-pos)
        mz (pos/pos-z master-pos)]
    (map (fn [rel-pos]
           (pos/create-block-pos (+ mx (or (:relative-x rel-pos) (:x rel-pos) 0))
                                 (+ my (or (:relative-y rel-pos) (:y rel-pos) 0))
                                 (+ mz (or (:relative-z rel-pos) (:z rel-pos) 0))))
         positions)))

(defn can-place-multi-block?
  "Check if a multi-block structure can be placed at master-pos in the world.
   Returns true if all target positions currently have no block state (treated as empty).
   Platform-specific layers can later extend this with 'replaceable' checks if needed."
  [world master-pos block-spec]
  (let [multi-block (:multi-block block-spec)]
    (if-not (:multi-block? multi-block)
      true
      (let [positions (all-multi-block-positions master-pos block-spec)]
        (every?
          (fn [p]
            (let [state (world/world-get-block-state* world p)]
              (nil? state)))
          positions)))))

(defn- dsl-block-id-str
  "Normalize block-id values from BE / specs for comparison (keyword vs string)."
  [x]
  (when (some? x)
    (if (keyword? x) (name x) (str x))))

(defn is-multi-block-complete?
  "Check if all parts of a multi-block structure are present
   world: world object
   master-pos: master block position (origin)
   block-spec: BlockSpec record containing multi-block configuration

   Returns true if all part blocks exist and are correct type"
  [world master-pos block-spec]
  (let [multi-block (:multi-block block-spec)]
    (when (:multi-block? multi-block)
      (try
        (let [origin (or (:multi-block-origin multi-block) {:x 0 :y 0 :z 0})
              positions (if-let [custom-positions (:multi-block-positions multi-block)]
                          (calculate-multi-block-positions custom-positions origin)
                          (calculate-multi-block-positions (:multi-block-size multi-block) origin))
              [mx my mz] (if (map? master-pos)
                           [(:x master-pos) (:y master-pos) (:z master-pos)]
                           [(pos/pos-x master-pos) (pos/pos-y master-pos) (pos/pos-z master-pos)])
              origin-pos (if (map? master-pos)
                           (pos/create-block-pos mx my mz)
                           master-pos)
              controller-id-str (dsl-block-id-str (:controller-block-id multi-block))
              part-id-str (dsl-block-id-str (:part-block-id multi-block))

              ;; Function to calculate absolute position
              abs-pos (fn [rel-pos]
                        (let [x (+ mx (or (:relative-x rel-pos) (:x rel-pos) 0))
                              y (+ my (or (:relative-y rel-pos) (:y rel-pos) 0))
                              z (+ mz (or (:relative-z rel-pos) (:z rel-pos) 0))]
                          (pos/create-block-pos x y z)))
              origin-state (world/world-get-block-state* world origin-pos)]

          ;; Check origin first - must be controller block
          (if-not origin-state
              (do
                (log/debug "Multi-block validation failed: origin block missing at" origin-pos)
                false)
              ;; Check if origin is controller (when controller-parts mode)
              (if (and controller-id-str part-id-str)
                (let [origin-be (world/world-get-tile-entity* world origin-pos)
                      origin-block-id-str (some-> origin-be platform-be/get-block-id dsl-block-id-str)]
                  (if (not= origin-block-id-str controller-id-str)
                    (do
                      (log/debug "Multi-block validation failed: origin is not controller. Expected:" controller-id-str "Got:" origin-block-id-str)
                      false)
                    ;; Check all other positions - must be part blocks
                    (let [result (every?
                                   (fn [rel-pos]
                                     (try
                                       (let [is-origin? (and (zero? (or (:relative-x rel-pos) (:x rel-pos) 0))
                                                             (zero? (or (:relative-y rel-pos) (:y rel-pos) 0))
                                                             (zero? (or (:relative-z rel-pos) (:z rel-pos) 0)))]
                                         (if is-origin?
                                           true  ; Already checked origin
                                           (let [pos (abs-pos rel-pos)
                                                 block-state (world/world-get-block-state* world pos)]
                                             (if-not block-state
                                               (do
                                                 (log/debug "Multi-block validation failed: missing block at" pos "rel-pos" rel-pos)
                                                 false)
                                               ;; Verify it's a part block
                                               (let [be (world/world-get-tile-entity* world pos)
                                                     bid-str (some-> be platform-be/get-block-id dsl-block-id-str)]
                                                 (if (not= bid-str part-id-str)
                                                   (do
                                                     (log/debug "Multi-block validation failed: wrong block type at" pos ". Expected:" part-id-str "Got:" bid-str)
                                                     false)
                                                   true))))))
                                       (catch Exception e
                                         (log/debug "Error checking block at" rel-pos ":"(ex-message e))
                                         false)))
                                   (or positions []))]
                      (when result
                        (log/debug "Multi-block validation passed for structure at" origin-pos))
                      result)))
                ;; No controller-parts mode, just check blocks exist
                (let [result (every?
                               (fn [rel-pos]
                                 (try
                                   (let [pos (abs-pos rel-pos)
                                         block-state (world/world-get-block-state* world pos)]
                                     (when-not block-state
                                       (log/debug "Multi-block validation failed: missing block at" pos "rel-pos" rel-pos))
                                     (if block-state true false))
                                   (catch Exception e
                                     (log/debug "Error checking block at" rel-pos ":"(ex-message e))
                                     false)))
                               (or positions []))]
                  (when result
                    (log/debug "Multi-block validation passed for structure at" origin-pos))
                  result))))

        (catch Exception e
          (log/error "Error checking multi-block structure:"(ex-message e))
          false)))))

;; Create block specification
(defn create-block-spec
  "Create a block specification from options.

  Accepts both nested and flat syntax for backward compatibility during migration:
  - Nested: {:physical {:material :stone :hardness 5.0} ...}
  - Flat: {:material :stone :hardness 5.0 ...}

  Nested syntax takes precedence over flat syntax."
  [block-id options]
  (let [;; Extract nested groups (new syntax)
        physical-opts (:physical options)
        rendering-opts (:rendering options)
        tile-entity-opts (:tile-entity options)
        block-state-opts (:block-state options)
        events-opts (:events options)
        multi-block-opts (:multi-block options)

        ;; Handle multi-block shorthand syntax
        multi-block-config (or multi-block-opts (:multi-block options))
        shorthand-positions (when (map? multi-block-config)
                              (:positions multi-block-config))
        processed-shorthand-positions (when shorthand-positions
                                        (let [positions (mapv (fn [pos]
                                                                (if (vector? pos)
                                                                  {:x (nth pos 0 0)
                                                                   :y (nth pos 1 0)
                                                                   :z (nth pos 2 0)}
                                                                  pos))
                                                              shorthand-positions)]
                                          (if (some #(and (= (:x %) 0)
                                                          (= (:y %) 0)
                                                          (= (:z %) 0))
                                                    positions)
                                            positions
                                            (into [{:x 0 :y 0 :z 0}] positions))))

        ;; Build nested records
        physical (map->PhysicalProperties
                   {:material (or (:material physical-opts) (:material options) :stone)
                    :hardness (or (:hardness physical-opts) (:hardness options) default-hardness)
                    :resistance (or (:resistance physical-opts) (:resistance options) default-resistance)
                    :friction (or (:friction physical-opts) (:friction options) default-friction)
                    :slip-factor (or (:slip-factor physical-opts) (:slip-factor options) default-friction)
                    :sounds (or (:sounds physical-opts) (:sounds options) :stone)
                    :harvest-level (or (:harvest-level physical-opts) (:harvest-level options) 0)
                    :harvest-tool (or (:harvest-tool physical-opts) (:harvest-tool options) :pickaxe)
                    :requires-tool (or (:requires-tool physical-opts) (:requires-tool options) false)})

        rendering (map->RenderingProperties
                    {:model-parent (or (:model-parent rendering-opts) (:model-parent options))
                     :textures (or (:textures rendering-opts) (:textures options))
                     :model-textures (or (:model-textures rendering-opts) (:model-textures options))
                     :has-item-form? (if (contains? rendering-opts :has-item-form?)
                                       (:has-item-form? rendering-opts)
                                       (not= false (:has-item-form options)))
                     :flat-item-icon? (boolean (or (:flat-item-icon? rendering-opts) (:flat-item-icon? options)))
                     :creative-tab (or (:creative-tab rendering-opts) (:creative-tab options) default-creative-tab)
                     :light-level (or (:light-level rendering-opts) (:light-level options) default-light-level)})

        tile-entity (map->TileEntityConfig
                      {:has-block-entity? (boolean (or (:has-block-entity? tile-entity-opts) (:has-block-entity? options)))
                       :tile-kind (or (:tile-kind tile-entity-opts) (:tile-kind options))
                       :tile-tick-fn (or (:tile-tick-fn tile-entity-opts) (:tile-tick-fn options))
                       :tile-load-fn (or (:tile-load-fn tile-entity-opts) (:tile-load-fn options))
                       :tile-save-fn (or (:tile-save-fn tile-entity-opts) (:tile-save-fn options))})

        block-state (map->BlockStateConfig
                      {:block-state-properties (or (:block-state-properties block-state-opts)
                                                   (:block-state-properties options))})

        events (map->EventHandlers
                 {:on-right-click (or (:on-right-click events-opts) (:on-right-click options) (fn [_] nil))
                  :on-break (or (:on-break events-opts) (:on-break options) (fn [_] nil))
                  :on-place (or (:on-place events-opts) (:on-place options) (fn [_] nil))
                  :on-multi-block-break (or (:on-multi-block-break events-opts) (:on-multi-block-break options) (fn [_] nil))})

        ;; Multi-block configuration
        multi-block? (boolean (or (:multi-block? multi-block-opts)
                                  (:multi-block? options)
                                  multi-block-config))
        multi-block-size (or (:size multi-block-opts)
                             (:multi-block-size multi-block-opts)
                             (:multi-block-size options)
                             (when (map? multi-block-config)
                               (:size multi-block-config)))
        multi-block-positions (or (:positions multi-block-opts)
                                  (:multi-block-positions multi-block-opts)
                                  (:multi-block-positions options)
                                  processed-shorthand-positions)
        multi-block-origin (or (:origin multi-block-opts)
                               (:multi-block-origin multi-block-opts)
                               (:multi-block-origin options)
                               (when (map? multi-block-config)
                                 (:origin multi-block-config))
                               {:x 0 :y 0 :z 0})
        multi-block-rotation-center (or (:rotation-center multi-block-opts)
                                        (:multi-block-rotation-center multi-block-opts)
                                        (:multi-block-rotation-center options)
                                        (when (map? multi-block-config)
                                          (:rotation-center multi-block-config)))
        pivot-xz-override (or (:pivot-xz-override multi-block-opts)
                              (:pivot-xz-override options)
                              (when (map? multi-block-config)
                                (:pivot-xz-override multi-block-config)))
        tesr-use-raw-rotation-center?
        (boolean (or (:tesr-use-raw-rotation-center? multi-block-opts)
                     (:tesr-use-raw-rotation-center? options)
                     (when (map? multi-block-config)
                       (:tesr-use-raw-rotation-center? multi-block-config))))
        tesr-y-deg-override (or (:tesr-y-deg-override multi-block-opts)
                              (:tesr-y-deg-override options)
                              (when (map? multi-block-config)
                                (:tesr-y-deg-override multi-block-config)))

        multi-block (map->MultiBlockConfig
                      {:multi-block? multi-block?
                       :multi-block-size multi-block-size
                       :multi-block-positions multi-block-positions
                       :multi-block-origin multi-block-origin
                       :multi-block-rotation-center multi-block-rotation-center
                       :multi-block-master? (or (:multi-block-master? multi-block-opts)
                                                (:multi-block-master? options)
                                                false)
                       :multiblock-mode (or (:multiblock-mode multi-block-opts) (:multiblock-mode options))
                       :controller-block-id (or (:controller-block-id multi-block-opts) (:controller-block-id options))
                       :part-block-id (or (:part-block-id multi-block-opts) (:part-block-id options))
                       :pivot-xz-override pivot-xz-override
                       :tesr-use-raw-rotation-center? tesr-use-raw-rotation-center?
                       :tesr-y-deg-override tesr-y-deg-override})]

    (map->BlockSpec
      {:id block-id
       :registry-name (:registry-name options)
       :properties (or (:properties options) {})
       :physical physical
       :rendering rendering
       :tile-entity tile-entity
       :block-state block-state
       :events events
       :multi-block multi-block})))

;; Validate block specification
(defn validate-block-spec [block-spec]
  (when-not (:id block-spec)
    (throw (ex-info "Block must have an :id" {:spec block-spec})))
  (when-not (string? (:id block-spec))
    (throw (ex-info "Block :id must be a string" {:id (:id block-spec)})))
  (when (and (:registry-name block-spec)
             (or (not (string? (:registry-name block-spec)))
                 (str/blank? (:registry-name block-spec))))
    (throw (ex-info "Block :registry-name must be a non-empty string when provided"
                    {:registry-name (:registry-name block-spec)
                     :id (:id block-spec)})))

  ;; Validate physical properties
  (let [physical (:physical block-spec)]
    (when-not (get materials (:material physical))
      (throw (ex-info "Invalid material" {:material (:material physical)
                                          :valid materials
                                          :id (:id block-spec)}))))

  ;; Validate rendering properties
  (let [rendering (:rendering block-spec)]
    (when (and (:model-parent rendering)
               (or (not (string? (:model-parent rendering)))
                   (str/blank? (:model-parent rendering))))
      (throw (ex-info "Block :model-parent must be a non-empty string when provided"
                      {:model-parent (:model-parent rendering)
                       :id (:id block-spec)})))

    (when (and (:textures rendering)
               (not (map? (:textures rendering))))
      (throw (ex-info "Block :textures must be a map when provided"
                      {:textures (:textures rendering)
                       :id (:id block-spec)})))

    (when (and (:model-textures rendering)
               (not (map? (:model-textures rendering))))
      (throw (ex-info "Block :model-textures must be a map when provided"
                      {:model-textures (:model-textures rendering)
                       :id (:id block-spec)})))

    (when (map? (:model-textures rendering))
      (doseq [[model-name texture-path] (:model-textures rendering)]
        (when-not (or (string? model-name) (keyword? model-name))
          (throw (ex-info "Block :model-textures keys must be string/keyword"
                          {:invalid-key model-name
                           :id (:id block-spec)})))
        (when-not (and (string? texture-path) (not (str/blank? texture-path)))
          (throw (ex-info "Block :model-textures values must be non-empty texture path strings"
                          {:invalid-value texture-path
                           :model-name model-name
                           :id (:id block-spec)}))))))

  ;; Validate tile entity config
  (let [tile-entity (:tile-entity block-spec)]
    (when (and (:tile-tick-fn tile-entity)
               (not (or (fn? (:tile-tick-fn tile-entity))
                        (symbol? (:tile-tick-fn tile-entity))
                        (var? (:tile-tick-fn tile-entity)))))
      (throw (ex-info "Block :tile-tick-fn must be a function or a symbol/var referencing one when provided"
                      {:id (:id block-spec)})))
    (when (and (:tile-load-fn tile-entity)
               (not (or (fn? (:tile-load-fn tile-entity))
                        (symbol? (:tile-load-fn tile-entity))
                        (var? (:tile-load-fn tile-entity)))))
      (throw (ex-info "Block :tile-load-fn must be a function or a symbol/var referencing one when provided"
                      {:id (:id block-spec)})))
    (when (and (:tile-save-fn tile-entity)
               (not (or (fn? (:tile-save-fn tile-entity))
                        (symbol? (:tile-save-fn tile-entity))
                        (var? (:tile-save-fn tile-entity)))))
      (throw (ex-info "Block :tile-save-fn must be a function or a symbol/var referencing one when provided"
                      {:id (:id block-spec)})))
    (when (and (:tile-kind tile-entity)
               (not (keyword? (:tile-kind tile-entity))))
      (throw (ex-info "Block :tile-kind must be a keyword when provided"
                      {:id (:id block-spec)
                       :tile-kind (:tile-kind tile-entity)}))))

  ;; Validate multi-block configuration
  (let [multi-block (:multi-block block-spec)]
    (when (:multi-block? multi-block)
      (let [has-size? (:multi-block-size multi-block)
            has-positions? (:multi-block-positions multi-block)]
        ;; Must have either size (regular) or positions (irregular)
        (when-not (or has-size? has-positions?)
          (throw (ex-info "Multi-block must have either :multi-block-size or :multi-block-positions"
                          {:id (:id block-spec)})))
        ;; Validate regular multi-block size
        (when has-size?
          (let [{:keys [width height depth]} (:multi-block-size multi-block)]
            (when-not (and width height depth
                           (pos? width) (pos? height) (pos? depth))
              (throw (ex-info "Invalid multi-block size, must have positive :width :height :depth"
                              {:id (:id block-spec)
                               :size (:multi-block-size multi-block)})))))
        ;; Validate irregular multi-block positions
        (when has-positions?
          (validate-multi-block-positions (:multi-block-positions multi-block))))))
  true)

;; Register block in registry
(defn register-block! [block-spec]
  (validate-block-spec block-spec)
  (log/info "Registering block:" (:id block-spec))
  (swap! block-registry assoc (:id block-spec) block-spec)
  block-spec)

;; Get block from registry
(defn get-block [block-id]
  (let [id-str (if (keyword? block-id) (name block-id) block-id)]
    (get @block-registry id-str)))

;; List all registered blocks
(defn list-blocks []
  (keys @block-registry))

;; Main macro: defblock
(defmacro defblock
  "Define a block with declarative syntax
  
  Example:
  (defblock my-block
    :material :stone
    :hardness 3.0
    :resistance 10.0
    :light-level 15
    :requires-tool true
    :harvest-tool :pickaxe
    :harvest-level 2
    :on-right-click (fn [data] (println \"Clicked!\")))"
  [block-name & options]
  (let [block-id (name block-name)
        options-map (apply hash-map options)]
    `(def ~block-name
       (register-block!
         (create-block-spec ~block-id ~options-map)))))

;; Define a controller+part multi-block with shared options.
;;
;; Example:
;; (defmultiblock wireless-matrix
;;   :multi-block {:positions [[0 0 1] [1 0 1] ...]}
;;   :common {:physical {:material :stone :hardness 3.0}}
;;   :controller {:registry-name "matrix" :events {:on-place ...}}
;;   :part {:registry-name "matrix_part" :rendering {:has-item-form false}})
(defmacro defmultiblock
  [base-name & options]
  (let [base-sym (if (and (seq? base-name)
                          (= 'quote (first base-name)))
                   (second base-name)
                   base-name)
        options-map (apply hash-map options)
        multi-block (:multi-block options-map)
        common-opts (or (:common options-map) {})
        raw-controller-opts (or (:controller options-map) {})
        raw-part-opts (or (:part options-map) {})
        controller-name (or (:controller-name options-map) base-sym)
        part-name (or (:part-name options-map)
                      (symbol (str (name base-sym) "-part")))
        controller-id (name controller-name)
        part-id (name part-name)
        ;; Deep merge function for nested maps
        deep-merge (fn deep-merge [& maps]
                     (apply merge-with
                            (fn [x y]
                              (if (and (map? x) (map? y))
                                (deep-merge x y)
                                y))
                            maps))
        merged-controller-opts (deep-merge common-opts
                                           raw-controller-opts
                                           {:multi-block multi-block
                                            :multiblock-mode :controller-parts
                                            :controller-block-id controller-id
                                            :part-block-id part-id})
        merged-part-opts (deep-merge common-opts
                                     {:rendering {:has-item-form? false}}
                                     raw-part-opts
                                     {:multiblock-mode :controller-parts
                                      :controller-block-id controller-id
                                      :part-block-id part-id})]
    `(do
       (defblock ~controller-name ~@(vec (mapcat identity merged-controller-opts)))
       (defblock ~part-name ~@(vec (mapcat identity merged-part-opts))))))

(defmacro defcontroller-multiblock
  [base-name & options]
  `(defmultiblock ~base-name ~@options))

;; Helper: create ore block preset
(defn ore-preset
  "Create an ore block preset with common properties"
  [harvest-level]
  {:material :stone
   :hardness 3.0
   :resistance 3.0
   :requires-tool true
   :harvest-tool :pickaxe
   :harvest-level harvest-level
   :sounds :stone})

;; Helper: create wood block preset
(defn wood-preset
  "Create a wood block preset with common properties"
  []
  {:material :wood
   :hardness 2.0
   :resistance 3.0
   :requires-tool false
   :harvest-tool :axe
   :harvest-level 0
   :sounds :wood})

;; Helper: create metal block preset
(defn metal-preset
  "Create a metal block preset with common properties"
  [harvest-level]
  {:material :metal
   :hardness 5.0
   :resistance 6.0
   :requires-tool true
   :harvest-tool :pickaxe
   :harvest-level harvest-level
   :sounds :metal})

;; Helper: create glass block preset
(defn glass-preset
  "Create a glass block preset with common properties"
  []
  {:material :glass
   :hardness 0.3
   :resistance 0.3
   :requires-tool false
   :sounds :glass})

;; Helper: create light-emitting block
(defn light-block-preset
  "Create a light-emitting block preset"
  [light-level]
  {:material :glass
   :hardness 1.0
   :resistance 1.0
   :light-level light-level
   :sounds :glass})

;; Helper: create multi-block preset
(defn multi-block-preset
  "Create a regular multi-block preset
   size: {:width 2 :height 3 :depth 2}
   Example: (multi-block-preset {:width 3 :height 4 :depth 3})"
  [size & additional-options]
  (merge
    {:multi-block? true
     :multi-block-size size
     :multi-block-origin {:x 0 :y 0 :z 0}
     :material :metal
     :hardness 5.0
     :resistance 10.0
     :requires-tool true
     :harvest-tool :pickaxe}
    (apply merge additional-options)))

;; Helper: create irregular multi-block preset
(defn irregular-multi-block-preset
  "Create an irregular multi-block preset with custom positions
   positions: [{:x 0 :y 0 :z 0} {:x 1 :y 0 :z 0} {:x 0 :y 1 :z 0} ...]
   Example: (irregular-multi-block-preset [{:x 0 :y 0 :z 0} {:x 1 :y 1 :z 0}])"
  [positions & additional-options]
  (merge
    {:multi-block? true
     :multi-block-positions (normalize-positions positions)
     :multi-block-origin {:x 0 :y 0 :z 0}
     :material :metal
     :hardness 5.0
     :resistance 10.0
     :requires-tool true
     :harvest-tool :pickaxe}
    (apply merge additional-options)))

;; Helper: create shape-based irregular multi-blocks
(defn create-cross-shape
  "Create a cross/plus shape multi-block positions (center + 4 arms)
   arm-length: length of each arm from center
   Returns: vector of positions"
  [arm-length]
  (vec (concat
         [{:x 0 :y 0 :z 0}]  ; center
         (for [i (range 1 (inc arm-length))]
           [{:x i :y 0 :z 0}   ; +X
            {:x (- i) :y 0 :z 0}  ; -X
            {:x 0 :y 0 :z i}   ; +Z
            {:x 0 :y 0 :z (- i)}]))))  ; -Z

(defn create-l-shape
  "Create an L-shape multi-block positions
   width: width of L
   height: height of L
   Returns: vector of positions"
  [width height]
  (vec (concat
         (for [x (range width)]
           {:x x :y 0 :z 0})
         (for [z (range 1 height)]
           {:x 0 :y 0 :z z}))))

(defn create-t-shape
  "Create a T-shape multi-block positions
   width: width of T (horizontal bar)
   height: height of T (vertical stem)
   Returns: vector of positions"
  [width height]
  (vec (concat
         ;; Horizontal bar
         (for [x (range (- (quot width 2)) (inc (quot width 2)))]
           {:x x :y 0 :z 0})
         ;; Vertical stem (excluding overlap at origin)
         (for [z (range 1 height)]
           {:x 0 :y 0 :z z}))))

(defn create-pyramid-shape
  "Create a pyramid-shaped multi-block positions
   base-size: size of the base (square)
   height: height of pyramid
   Returns: vector of positions"
  [base-size height]
  (vec (for [y (range height)
             :let [layer-size (max 1 (- base-size y))
                   offset (quot y 2)]
             x (range (- offset) (- layer-size offset))
             z (range (- offset) (- layer-size offset))]
         {:x x :y y :z z})))

(defn create-hollow-cube
  "Create a hollow cube multi-block positions (only walls, no interior)
   size: edge length of cube
   Returns: vector of positions"
  [size]
  (vec (for [x (range size)
             y (range size)
             z (range size)
             :when (or (= x 0) (= x (dec size))
                       (= y 0) (= y (dec size))
                       (= z 0) (= z (dec size)))]
         {:x x :y y :z z})))

;; Helper: combine presets
(defn merge-presets
  "Merge multiple presets with options"
  [& preset-and-options]
  (apply merge preset-and-options))

;; Multimethod for version-specific block creation
(def ^:dynamic *forge-version* nil)

(defmulti create-platform-block
  "Create a version-specific block instance"
  (fn [_block-spec] *forge-version*))

(defmethod create-platform-block :default [block-spec]
  (throw (ex-info "No block implementation for version"
                  {:version *forge-version*
                   :block-id (:id block-spec)})))

;; Block interaction handlers
(defn handle-right-click
  "Handle right-click on a block"
  [block-spec event-data]
  (when-let [handler (:on-right-click block-spec)]
    (handler event-data)))

(defn handle-break
  "Handle block break"
  [block-spec event-data]
  (when-let [handler (:on-break block-spec)]
    (handler event-data)))

(defn handle-place
  "Handle block placement"
  [block-spec event-data]
  (when-let [handler (:on-place block-spec)]
    (handler event-data)))

(defn handle-multi-block-break
  "Handle multi-block structure break
   Should break all parts of the multi-block"
  [block-spec event-data]
  (let [multi-block (:multi-block block-spec)
        events (:events block-spec)]
    (when (:multi-block? multi-block)
      (log/info "Breaking multi-block structure:" (:id block-spec))
      (when-let [handler (:on-multi-block-break events)]
        (handler event-data))
      ;; Platform adapter should handle breaking all parts
      (let [origin (or (:multi-block-origin multi-block) {:x 0 :y 0 :z 0})
            ;; Use custom positions if available, otherwise calculate from size
            positions (if-let [custom-pos (:multi-block-positions multi-block)]
                        (calculate-multi-block-positions custom-pos origin)
                        (calculate-multi-block-positions
                          (:multi-block-size multi-block)
                          origin))]
        {:should-break-all true
         :positions positions}))))

;; Get block properties for platform creation
(defn get-block-properties
  "Get block properties map for platform-specific creation"
  [block-spec]
  (let [physical (:physical block-spec)
        multi-block (:multi-block block-spec)]
    {:material (:material physical)
     :hardness (:hardness physical)
     :resistance (:resistance physical)
     :light-level (get-in block-spec [:rendering :light-level])
     :requires-tool (:requires-tool physical)
     :sounds (:sounds physical)
     :harvest-level (:harvest-level physical)
     :harvest-tool (:harvest-tool physical)
     :friction (:friction physical)
     :slip-factor (:slip-factor physical)
     :multi-block? (:multi-block? multi-block)
     :multi-block-size (:multi-block-size multi-block)
     :multi-block-positions (:multi-block-positions multi-block)
     :multi-block-origin (:multi-block-origin multi-block)
     :multi-block-rotation-center (:multi-block-rotation-center multi-block)
     :multi-block-master? (:multi-block-master? multi-block)}))
