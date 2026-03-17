(ns my-mod.wireless.gui.container-helpers
  "Shared container utility functions for wireless GUI system
  
  Provides common slot access, lifecycle, and validation helpers used by
  both node_container and matrix_container to eliminate code duplication.")

;; ============================================================================
;; Slot Access Helpers
;; ============================================================================

(defn get-slot-item
  "Get item from slot by accessing tile entity inventory
  
  Args:
  - container: Container instance with :tile-entity key
  - slot-index: Integer slot index
  
  Returns: ItemStack or nil"
  [container slot-index]
  (let [tile (:tile-entity container)]
    (if (map? tile)
      (let [inventory-atom (:inventory tile)]
        (when inventory-atom
          (get @inventory-atom slot-index)))
      (try
        (get-in (.getCustomState tile) [:inventory slot-index])
        (catch Exception _ nil)))))

(defn set-slot-item!
  "Set item in slot by updating tile entity inventory
  
  Args:
  - container: Container instance with :tile-entity key
  - slot-index: Integer slot index
  - item-stack: ItemStack to place in slot
  
  Returns: Updated inventory map"
  [container slot-index item-stack]
  (let [tile (:tile-entity container)]
    (if (map? tile)
      (let [inventory-atom (:inventory tile)]
        (when inventory-atom
          (swap! inventory-atom assoc slot-index item-stack)))
      (try
        (let [state (or (.getCustomState tile) {})
              state' (assoc-in state [:inventory slot-index] item-stack)]
          (.setCustomState tile state'))
        (catch Exception _ nil)))))

;; ============================================================================
;; Container Lifecycle
;; ============================================================================

(defn still-valid?
  "Check if container is still valid for player based on distance
  
  Args:
  - container: Container instance with :tile-entity and :player keys
  - player: Player instance
  
  Returns: boolean - true if player is within 8 blocks of tile"
  [container player]
  (let [tile         (:tile-entity container)
        ;; Support both legacy map tiles (with :pos) and ScriptedBlockEntity/BlockEntity
        raw-pos     (or (:pos tile)
                        (try (.getBlockPos tile) (catch Exception _ nil))
                        (try (.getPos tile) (catch Exception _ nil)))
        max-distance 8.0]
    (and (= player (:player container))
         raw-pos
         (let [bx (double (or (try (.getX raw-pos) (catch Exception _ nil))
                              (:x raw-pos)))
               by (double (or (try (.getY raw-pos) (catch Exception _ nil))
                              (:y raw-pos)))
               bz (double (or (try (.getZ raw-pos) (catch Exception _ nil))
                              (:z raw-pos)))]
           ;; MC 1.20.1: Entity.distanceToSqr(x,y,z) replaces old distanceSq/getPos
           (< (.distanceToSqr player
                               (+ bx 0.5) by (+ bz 0.5))
              (* max-distance max-distance))))))

(defn reset-container-atoms!
  "Reset container atoms to default values on close
  
  Args:
  - atom-defaults: Sequence of [atom default-value] pairs
  
  Returns: nil
  
  Example:
    (reset-container-atoms! 
      [(:energy container) 0]
      [(:max-energy container) 0]
      [(:is-online container) false])"
  [& atom-defaults]
  (doseq [[atom-ref default-val] atom-defaults]
    (reset! atom-ref default-val))
  nil)
