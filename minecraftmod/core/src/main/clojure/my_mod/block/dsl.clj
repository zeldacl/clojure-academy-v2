(ns my-mod.block.dsl
  "Block DSL - Declarative block definition using Clojure macros"
  (:require [my-mod.util.log :as log]))

;; Block Registry - stores all defined blocks
(defonce block-registry (atom {}))

;; Block specifications
(defrecord BlockSpec [id material hardness resistance light-level requires-tool
                      sounds harvest-level harvest-tool friction slip-factor
                      on-right-click on-break on-place properties
                      ;; Multi-block support
                      multi-block? multi-block-size multi-block-origin
                      multi-block-positions  ; Custom positions for irregular shapes
                      multi-block-master? on-multi-block-break])

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

;; Multi-block helper functions
(defn calculate-multi-block-positions
  "Calculate all positions for a multi-block structure
   For regular shapes: size: {:width 2 :height 3 :depth 2}
   For irregular shapes: custom-positions: [{:x 0 :y 0 :z 0} {:x 1 :y 0 :z 0} ...]
   origin: {:x 0 :y 0 :z 0}
   Returns: vector of relative position maps"
  ([{:keys [width height depth]} origin]
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
  ([custom-positions origin]
   ;; For irregular multi-blocks with custom positions
   (mapv (fn [pos]
           {:x (+ (:x origin) (:x pos))
            :y (+ (:y origin) (:y pos))
            :z (+ (:z origin) (:z pos))
            :relative-x (:x pos)
            :relative-y (:y pos)
            :relative-z (:z pos)
            :is-origin? (and (= (:x pos) 0) (= (:y pos) 0) (= (:z pos) 0))})
         custom-positions)))

(defn normalize-positions
  "Normalize a list of positions to ensure one is at origin (0,0,0)
   Useful for creating irregular multi-blocks from absolute coordinates"
  [positions]
  (when (seq positions)
    (let [min-x (apply min (map :x positions))
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
  (when-not (some #(and (= (:x %) 0) (= (:y %) 0) (= (:z %) 0)) positions)
    (throw (ex-info "Multi-block positions must include origin (0,0,0)" {:positions positions})))
  (when-not (every? #(and (integer? (:x %)) (integer? (:y %)) (integer? (:z %))) positions)
    (throw (ex-info "All position coordinates must be integers" {:positions positions})))
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

(defn is-multi-block-complete?
  "Check if all parts of a multi-block structure are present
   world: world object
   master-pos: master block position (origin)
   block-spec: BlockSpec record containing multi-block configuration
   
   Returns true if all part blocks exist and are correct type"
  [world master-pos block-spec]
  (when (:multi-block? block-spec)
    (try
      ;; Get multi-block positions from spec
      (let [multi-block (:multi-block block-spec)
            positions (if (map? multi-block)
                        (:positions multi-block)  ;; New format: {:positions [...]}
                        multi-block)              ;; Old format: [...]
            
            ;; Function to calculate absolute position
            abs-pos (fn [rel-pos]
                      (let [x (+ (:x master-pos) (or (:relative-x rel-pos) (:x rel-pos) 0))
                            y (+ (:y master-pos) (or (:relative-y rel-pos) (:y rel-pos) 0))
                            z (+ (:z master-pos) (or (:relative-z rel-pos) (:z rel-pos) 0))]
                        {:x x :y y :z z}))]
        
        ;; Check origin first
        (if-not (.getBlockState world master-pos)
          false
          ;; Check all sub-block positions
          (every?
            (fn [rel-pos]
              (try
                (let [pos (abs-pos rel-pos)
                      block-state (.getBlockState world pos)]
                  (if block-state true false))
                (catch Exception e
                  (log/debug "Error checking block at" rel-pos ":" (.getMessage e))
                  false)))
            (or positions []))))
      
      (catch Exception e
        (log/error "Error checking multi-block structure:" (.getMessage e))
        false)))))

;; Create block specification
(defn create-block-spec
  "Create a block specification from options"
  [block-id options]
  (let [multi-block? (boolean (:multi-block? options))
        multi-block-size (:multi-block-size options)
        multi-block-positions (:multi-block-positions options)
        multi-block-origin (:multi-block-origin options {:x 0 :y 0 :z 0})]
    (map->BlockSpec
      {:id block-id
       :material (or (:material options) :stone)
       :hardness (or (:hardness options) default-hardness)
       :resistance (or (:resistance options) default-resistance)
       :light-level (or (:light-level options) default-light-level)
       :requires-tool (or (:requires-tool options) false)
       :sounds (or (:sounds options) :stone)
       :harvest-level (or (:harvest-level options) 0)
       :harvest-tool (or (:harvest-tool options) :pickaxe)
       :friction (or (:friction options) default-friction)
       :slip-factor (or (:slip-factor options) default-friction)
       :on-right-click (or (:on-right-click options) (fn [_] nil))
       :on-break (or (:on-break options) (fn [_] nil))
       :on-place (or (:on-place options) (fn [_] nil))
       :properties (or (:properties options) {})
       ;; Multi-block properties
       :multi-block? multi-block?
       :multi-block-size multi-block-size
       :multi-block-positions multi-block-positions
       :multi-block-origin multi-block-origin
       :multi-block-master? (or (:multi-block-master? options) false)
       :on-multi-block-break (or (:on-multi-block-break options) (fn [_] nil))})))

;; Validate block specification
(defn validate-block-spec [block-spec]
  (when-not (:id block-spec)
    (throw (ex-info "Block must have an :id" {:spec block-spec})))
  (when-not (string? (:id block-spec))
    (throw (ex-info "Block :id must be a string" {:id (:id block-spec)})))
  (when-not (get materials (:material block-spec))
    (throw (ex-info "Invalid material" {:material (:material block-spec)
                                        :valid materials})))
  ;; Validate multi-block configuration
  (when (:multi-block? block-spec)
    (let [has-size? (:multi-block-size block-spec)
          has-positions? (:multi-block-positions block-spec)]
      ;; Must have either size (regular) or positions (irregular)
      (when-not (or has-size? has-positions?)
        (throw (ex-info "Multi-block must have either :multi-block-size or :multi-block-positions"
                        {:id (:id block-spec)})))
      ;; Validate regular multi-block size
      (when has-size?
        (let [{:keys [width height depth]} (:multi-block-size block-spec)]
          (when-not (and width height depth
                         (pos? width) (pos? height) (pos? depth))
            (throw (ex-info "Invalid multi-block size, must have positive :width :height :depth"
                            {:id (:id block-spec)
                             :size (:multi-block-size block-spec)})))))
      ;; Validate irregular multi-block positions
      (when has-positions?
        (validate-multi-block-positions (:multi-block-positions block-spec)))))
  true)

;; Register block in registry
(defn register-block! [block-spec]
  (validate-block-spec block-spec)
  (log/info "Registering block:" (:id block-spec))
  (swap! block-registry assoc (:id block-spec) block-spec)
  block-spec)

;; Get block from registry
(defn get-block [block-id]
  (get @block-registry block-id))

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
            {:x 0 :y 0 :z (- i)}])))))  ; -Z

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
  (when (:multi-block? block-spec)
    (log/info "Breaking multi-block structure:" (:id block-spec))
    (when-let [handler (:on-multi-block-break block-spec)]
      (handler event-data))
    ;; Platform adapter should handle breaking all parts
    (let [{:keys [world pos]} event-data
          origin (:multi-block-origin block-spec)
          ;; Use custom positions if available, otherwise calculate from size
          positions (if-let [custom-pos (:multi-block-positions block-spec)]
                      (calculate-multi-block-positions custom-pos origin)
                      (calculate-multi-block-positions 
                        (:multi-block-size block-spec) 
                        origin))]
      {:should-break-all true
       :positions positions})))

;; Get block properties for platform creation
(defn get-block-properties
  "Get block properties map for platform-specific creation"
  [block-spec]
  {:material (:material block-spec)
   :hardness (:hardness block-spec)
   :resistance (:resistance block-spec)
   :light-level (:light-level block-spec)
   :requires-tool (:requires-tool block-spec)
   :sounds (:sounds block-spec)
   :harvest-level (:harvest-level block-spec)
   :harvest-tool (:harvest-tool block-spec)
   :friction (:friction block-spec)
   :slip-factor (:slip-factor block-spec)
   :multi-block? (:multi-block? block-spec)
   :multi-block-size (:multi-block-size block-spec)
   :multi-block-positions (:multi-block-positions block-spec)
   :multi-block-origin (:multi-block-origin block-spec)
   :multi-block-master? (:multi-block-master? block-spec)})
