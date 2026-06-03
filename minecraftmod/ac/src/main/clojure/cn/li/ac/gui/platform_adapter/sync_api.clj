(ns cn.li.ac.gui.platform-adapter.sync-api
  "GUI block-state sync dispatch (server broadcast + client payload routing).

  Server broadcast uses the same platform-version multimethod pattern as
  `mcmod.network.client/send-request`: each loader registers a `defmethod`;
  missing dispatch fails fast via `:default`."
  (:require [cn.li.mcmod.gui.registry :as gui-registry]
            [cn.li.mcmod.platform.dispatch :as platform-dispatch]
            [cn.li.mcmod.util.log :as log]))

(def BLOCK-GUI-STATE-MSG-ID
  "ac/gui-block-state-sync")

(def ^:private err-type ::gui-broadcast-unavailable)

(defmulti broadcast-gui-state!*
  (fn [_world _pos _sync-data]
    (platform-dispatch/current-platform-version)))

(defmethod broadcast-gui-state!* :default
  [_world _pos _sync-data]
  (throw (ex-info "No GUI block-state broadcast for platform"
                  {:type err-type
                   :platform (platform-dispatch/current-platform-version)
                   :registered (vec (keys (methods broadcast-gui-state!*)))})))

(defn assert-gui-broadcast-dispatch!
  "Fail fast when loader forgot to register `broadcast-gui-state!*` for this platform.
  Call from loader GUI network bootstrap after the defmethod namespace is loaded."
  [platform-key]
  (when-not (get (methods broadcast-gui-state!*) platform-key)
    (throw (ex-info "GUI block-state broadcast defmethod not registered"
                    {:type err-type
                     :platform platform-key
                     :registered (vec (keys (methods broadcast-gui-state!*)))})))
  nil)

(defn apply-gui-sync-payload!
  "Apply GUI sync payload on client by routing to correct business handler."
  [payload]
  (let [gui-id (:gui-id payload)
        apply-fn (when (integer? gui-id)
                   (gui-registry/get-payload-sync-apply-fn gui-id))]
    (if apply-fn
      (apply-fn payload)
      (log/debug "Unknown gui-id in sync payload:" gui-id))))
