(ns my-mod.gui.container
  "GUI container/menu abstraction - manages server-side GUI state"
  (:require [my-mod.util.log :as log]
            [my-mod.gui.dsl :as dsl]))

;; Multimethod for version-specific container operations
(def ^:dynamic *forge-version* nil)

;; Container state management
(defonce open-containers (atom {}))

;; Container record
(defrecord Container [id gui-instance player inventory-handler])

;; Create container
(defn create-container
  "Create a new container instance"
  [container-id gui-instance player]
  (let [inventory (atom {})]
    (map->Container
      {:id container-id
       :gui-instance gui-instance
       :player player
       :inventory-handler inventory})))

;; Register open container
(defn register-container! [container-id container]
  (swap! open-containers assoc container-id container))

;; Get container
(defn get-container [container-id]
  (get @open-containers container-id))

;; Remove container
(defn unregister-container! [container-id]
  (swap! open-containers dissoc container-id))

;; Slot operations
(defn get-slot-item [container slot-index]
  (dsl/get-slot-state (:gui-instance container) slot-index))

(defn set-slot-item! [container slot-index item-stack]
  (let [gui-instance (:gui-instance container)
        old-stack (dsl/get-slot-state gui-instance slot-index)]
    (dsl/handle-slot-change gui-instance slot-index old-stack item-stack)))

(defn clear-slot! [container slot-index]
  (dsl/clear-slot-state! (:gui-instance container) slot-index))

;; Button operations
(defn handle-button-click! [container button-id]
  (dsl/handle-button-click (:gui-instance container) button-id))

;; Container validation
(defn validate-container [container player]
  (let [container-player (:player (:gui-instance container))]
    ;; Check if player matches
    (= container-player player)))

;; Quick move stack (Shift+Click)
(defn quick-move-stack
  "Handle shift-clicking items"
  [container slot-index]
  (log/info "Quick move from slot" slot-index)
  ;; TODO: Implement shift-click logic
  nil)

;; Multimethod for creating platform-specific containers
(defmulti create-platform-container
  "Create a version-specific container/menu instance"
  (fn [_container-id _gui-spec _player _world _pos] *forge-version*))

(defmethod create-platform-container :default [_ gui-spec _ _ _]
  (throw (ex-info "No container implementation for version"
                  {:version *forge-version*
                   :gui-id (:id gui-spec)})))

;; Multimethod for opening GUI
(defmulti open-gui-container
  "Open a GUI container for a player"
  (fn [_player _gui-spec _world _pos] *forge-version*))

(defmethod open-gui-container :default [_ gui-spec _ _]
  (throw (ex-info "No open-gui implementation for version"
                  {:version *forge-version*
                   :gui-id (:id gui-spec)})))
