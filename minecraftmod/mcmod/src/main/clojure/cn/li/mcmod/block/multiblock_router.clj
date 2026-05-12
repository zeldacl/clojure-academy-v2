(ns cn.li.mcmod.block.multiblock-router
  "Block multiblock routing - Pure functions for multiblock structure calculations and verification"
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.world :as world]
            [cn.li.mcmod.platform.be :as platform-be]))

;; ============================================================================
;; Multiblock Position Calculation
;; ============================================================================

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

;; ============================================================================
;; Multiblock Master Position Resolution
;; ============================================================================

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

;; ============================================================================
;; Multiblock Position Enumeration
;; ============================================================================

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

;; ============================================================================
;; Multiblock Placement Validation
;; ============================================================================

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

;; ============================================================================
;; Multiblock Completion Verification
;; ============================================================================

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
