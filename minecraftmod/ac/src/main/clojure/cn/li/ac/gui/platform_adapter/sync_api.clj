(ns cn.li.ac.gui.platform-adapter.sync-api
  "Unified GUI sync transport registration and payload routing."
  (:require [cn.li.mcmod.gui.registry :as gui-registry]
            [cn.li.mcmod.util.log :as log]))

(def ^:private platform-broadcast-lock
  (Object.))

(def ^:private ^:dynamic *platform-broadcast-fn*
  nil)

(defn register-gui-sync-impl!
  "Register a unified GUI state broadcast implementation for the current platform."
  [broadcast-fn]
  (locking platform-broadcast-lock
    (alter-var-root #'*platform-broadcast-fn* (constantly broadcast-fn)))
  (log/info "Registered unified GUI sync implementation"))

(defn get-platform-broadcast-fn
  "Get the registered platform broadcast function"
  []
  (var-get #'*platform-broadcast-fn*))

(defn apply-gui-sync-payload!
  "Apply GUI sync payload on client by routing to correct business handler."
  [payload]
  (let [gui-id (:gui-id payload)
        apply-fn (when (integer? gui-id)
         (gui-registry/get-payload-sync-apply-fn gui-id))]
    (if apply-fn
      (apply-fn payload)
      (log/debug "Unknown gui-id in sync payload:" gui-id))))
