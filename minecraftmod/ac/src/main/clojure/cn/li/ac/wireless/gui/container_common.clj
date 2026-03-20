(ns cn.li.ac.wireless.gui.container-common
  "Shared helpers for wireless GUI containers"
  (:require [cn.li.ac.wireless.gui.container-helpers :as helpers]
            [cn.li.mcmod.platform.be :as platform-be]))

(defn get-slot-item
  "Get item from slot"
  [container slot-index]
  (helpers/get-slot-item container slot-index))

(defn set-slot-item!
  "Set item in slot"
  [container slot-index item-stack]
  (helpers/set-slot-item! container slot-index item-stack))

(defn still-valid?
  "Check if container is still valid for player"
  [container player]
  (helpers/still-valid? container player))

(defn reset-container-atoms!
  "Reset container atoms to defaults"
  [& pairs]
  (apply helpers/reset-container-atoms! pairs))

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
          (platform-be/set-custom-state! tile state'))
        (catch Exception _
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
