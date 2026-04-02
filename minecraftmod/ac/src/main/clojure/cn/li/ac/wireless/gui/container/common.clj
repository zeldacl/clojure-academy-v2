(ns cn.li.ac.wireless.gui.container.common
  "Shared container utility functions for wireless GUI system."
  (:require [cn.li.mcmod.platform.be :as platform-be]
            [cn.li.mcmod.platform.position :as pos]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]))

(defn get-slot-item
  "Get item from slot by accessing tile entity inventory."
  [container slot-index]
  (let [tile (:tile-entity container)]
    (if (map? tile)
      (let [inventory-atom (:inventory tile)]
        (when inventory-atom
          (get @inventory-atom slot-index)))
      (try
        (get-in (platform-be/get-custom-state tile) [:inventory slot-index])
        (catch Exception _ nil)))))

(defn set-slot-item!
  "Set item in slot by updating tile entity inventory."
  [container slot-index item-stack]
  (let [tile (:tile-entity container)]
    (if (map? tile)
      (let [inventory-atom (:inventory tile)]
        (when inventory-atom
          (swap! inventory-atom assoc slot-index item-stack)))
      (try
        (let [state (or (platform-be/get-custom-state tile) {})
              state' (assoc-in state [:inventory slot-index] item-stack)]
          (platform-be/set-custom-state! tile state'))
        (catch Exception _ nil)))))

(defn still-valid?
  "Check if container is still valid for player based on distance."
  [container player]
  (let [tile (:tile-entity container)
        raw-pos (try (pos/position-get-block-pos tile) (catch Exception _ nil))
        max-distance 8.0]
    (and (= player (:player container))
         raw-pos
         (let [bx (double (or (try (pos/pos-x raw-pos) (catch Exception _ nil))
                              (:x raw-pos)))
               by (double (or (try (pos/pos-y raw-pos) (catch Exception _ nil))
                              (:y raw-pos)))
               bz (double (or (try (pos/pos-z raw-pos) (catch Exception _ nil))
                              (:z raw-pos)))]
           (< (entity/entity-distance-to-sqr player (+ bx 0.5) by (+ bz 0.5))
              (* max-distance max-distance))))))

(defn reset-container-atoms!
  "Reset container atoms to defaults."
  [& atom-defaults]
  (doseq [[atom-ref default-val] atom-defaults]
    (reset! atom-ref default-val))
  nil)

(defn get-slot-item-be
  "Get item from a ScriptedBlockEntity slot via customState.
  Falls back to legacy atom inventory for plain-map tiles."
  [container slot-index]
  (let [tile (:tile-entity container)]
    (if (map? tile)
      (get-slot-item container slot-index)
      (try
        (get-in (platform-be/get-custom-state tile) [:inventory slot-index])
        (catch Exception _ (get-slot-item container slot-index))))))

(defn set-slot-item-be!
  "Set item in a ScriptedBlockEntity slot via customState.
  - default-state: map used when customState is nil
  - post-write:    (fn [state]) -> state', applied after assoc-in
  Falls back to legacy atom inventory for plain-map tiles."
  [container slot-index item-stack default-state post-write]
  (let [tile (:tile-entity container)]
    (if (map? tile)
      (set-slot-item! container slot-index item-stack)
      (try
        (let [state  (or (platform-be/get-custom-state tile) default-state)
              state' (-> state
                         (assoc-in [:inventory slot-index] item-stack)
                         post-write)]
          (log/debug "set-slot-item-be! state-before=" state)
          (log/debug "set-slot-item-be! state-after=" state')
          (platform-be/set-custom-state! tile state'))
        (catch Exception e
          (log/error "set-slot-item-be! failed:" (ex-message e))
          (set-slot-item! container slot-index item-stack))))))

(defn get-tile-state
  "Get the current Clojure state map from a tile entity.
  Returns the map itself for legacy map tiles, calls getCustomState for BEs.
  Returns nil if tile is nil."
  [tile]
  (when tile
    (if (map? tile)
      tile
      (try (platform-be/get-custom-state tile) (catch Exception _ {})))))
