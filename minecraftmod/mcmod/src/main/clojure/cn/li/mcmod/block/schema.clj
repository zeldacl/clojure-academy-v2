(ns cn.li.mcmod.block.schema
  "Block schema - Record definitions and constants for block specifications")

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

;; ============================================================================
;; Material types (version-agnostic)
;; ============================================================================

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

;; ============================================================================
;; Default values for block properties
;; ============================================================================

(def default-hardness 1.5)
(def default-resistance 6.0)
(def default-light-level 0)
(def default-friction 0.6)
(def default-creative-tab :misc)
