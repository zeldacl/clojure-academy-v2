(ns cn.li.mc1201.gui.registry-common
  "Shared helpers for platform GUI registry implementations."
  (:require [cn.li.mcmod.util.log :as log]))

(defn create-wrapped-container
  [create-container-fn wrap-container-fn resolve-handler-type-fn gui-id sync-or-window-id error-prefix]
  (let [clj-container (create-container-fn)]
    (if clj-container
      (wrap-container-fn sync-or-window-id (resolve-handler-type-fn gui-id) clj-container)
      (do
        (log/error error-prefix gui-id)
        nil))))