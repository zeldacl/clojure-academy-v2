(ns cn.li.ac.gui.platform-adapter.dispatcher-api
  "Dispatcher-oriented operations exposed to platform GUI bridges."
  (:require [cn.li.ac.wireless.gui.container.dispatcher :as dispatcher]
            [cn.li.mcmod.gui.registry :as gui-registry]))

(def safe-tick! dispatcher/safe-tick!)
(def safe-validate dispatcher/safe-validate)
(def safe-sync! dispatcher/safe-sync!)
(def safe-handle-button-click! dispatcher/safe-handle-button-click!)
(def safe-handle-text-input! dispatcher/safe-handle-text-input!)
(def safe-close! dispatcher/safe-close!)

(def slot-count dispatcher/slot-count)
(def slot-get-item dispatcher/slot-get-item)
(def slot-set-item! dispatcher/slot-set-item!)
(def slot-can-place? dispatcher/slot-can-place?)
(def slot-changed! dispatcher/slot-changed!)

(defn- player-inventory-start-for
  [container]
  (if-let [gui-id (some-> container dispatcher/get-container-type gui-registry/get-gui-id-for-type)]
    (let [[main-start _] (or (gui-registry/get-slot-range gui-id :player-main) [0 -1])
          [hotbar-start _] (or (gui-registry/get-slot-range gui-id :player-hotbar) [0 -1])
          starts (filter #(and (integer? %) (<= 0 %)) [main-start hotbar-start])]
      (if (seq starts) (apply min starts) 0))
    0))

(defn execute-quick-move-forge
  [_menu container slot-index _slot _stack]
  (let [player-inventory-start (player-inventory-start-for container)]
    (dispatcher/safe-execute-quick-move container slot-index player-inventory-start)))

(def get-container-type dispatcher/get-container-type)

(defn get-gui-id-for-container
  [container]
  (let [container-type (dispatcher/get-container-type container)]
    (when (not= container-type :unknown)
      (gui-registry/get-gui-id-for-type container-type))))
