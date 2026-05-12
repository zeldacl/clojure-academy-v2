(ns cn.li.ac.gui.platform-adapter.dispatcher-api
  "Dispatcher-oriented operations exposed to platform GUI bridges."
  (:require [cn.li.ac.wireless.gui.container.dispatcher :as dispatcher]
            [cn.li.mcmod.gui.metadata :as metadata]))

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

(def get-container-type dispatcher/get-container-type)

(defn get-gui-id-for-container
  "Resolve GUI ID from a container using business-layer metadata."
  [container]
  (let [container-type (dispatcher/get-container-type container)]
    (when (not= container-type :unknown)
      (metadata/get-gui-id-for-type container-type))))
