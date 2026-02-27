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
  (let [inventory @(:inventory (:tile-entity container))]
    (get inventory slot-index)))

(defn set-slot-item!
  "Set item in slot by updating tile entity inventory
  
  Args:
  - container: Container instance with :tile-entity key
  - slot-index: Integer slot index
  - item-stack: ItemStack to place in slot
  
  Returns: Updated inventory map"
  [container slot-index item-stack]
  (let [tile (:tile-entity container)
        inventory-atom (:inventory tile)]
    (swap! inventory-atom assoc slot-index item-stack)))

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
  (let [tile (:tile-entity container)
        world (:world tile)
        pos (:pos tile)
        max-distance 8.0]
    ;; Check if player is still close enough
    (and (= player (:player container))
         (< (.distanceSq (.getPos player) pos) (* max-distance max-distance)))))

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
