(ns cn.li.ac.gui.platform-adapter.sync-bootstrap
  "Client-side registration for block GUI state push handling."
  (:require [cn.li.ac.gui.platform-adapter.sync-api :as sync-api]
            [cn.li.mcmod.network.client :as net-client]))

(def ^:private client-handler-installed? (atom false))

(defn register-client-push-handler!
  "Register the global push handler that routes block GUI sync to business apply fns."
  []
  (when (compare-and-set! client-handler-installed? false true)
    (net-client/register-push-handler! sync-api/BLOCK-GUI-STATE-MSG-ID
      sync-api/apply-gui-sync-payload!))
  nil)
