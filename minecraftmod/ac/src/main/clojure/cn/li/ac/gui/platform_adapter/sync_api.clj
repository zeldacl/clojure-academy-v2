(ns cn.li.ac.gui.platform-adapter.sync-api
  "GUI block-state client payload routing.

  Server broadcast contract lives in `cn.li.mcmod.gui.sync-api` and is consumed
  directly by platform modules."
  (:require [cn.li.mcmod.gui.registry :as gui-registry]
            [cn.li.mcmod.util.log :as log]))

(defn apply-gui-sync-payload!
  "Apply GUI sync payload on client by routing to correct business handler."
  [payload]
  (let [gui-id (:gui-id payload)
        apply-fn (when (integer? gui-id)
                   (gui-registry/get-payload-sync-apply-fn gui-id))]
    (if apply-fn
      (apply-fn payload)
      (log/debug "Unknown gui-id in sync payload:" gui-id))))
