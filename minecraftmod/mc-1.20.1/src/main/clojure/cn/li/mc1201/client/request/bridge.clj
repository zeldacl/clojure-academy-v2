(ns cn.li.mc1201.client.request.bridge
  "Provides a neutral client request dispatch helper for runtime operations."
  (:require [cn.li.mcmod.hooks.core :as power-runtime]
            [cn.li.mcmod.util.log :as log]))

(defn dispatch!
  "Dispatch an opaque content action from a client UI/request host."
  ([action-id payload]
   (dispatch! action-id payload nil))
  ([action-id payload callback]
   (power-runtime/dispatch-action! action-id {:callback callback} payload)))

(defn init! []
  (log/info "Client request bridge initialized"))
