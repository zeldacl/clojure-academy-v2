(ns cn.li.mcmod.gui.sync-api
  "Deprecated custom S2C block GUI sync API.
  
  The project no longer supports platform-level GUI push broadcasts."
  (:require [cn.li.mcmod.platform.dispatch :as platform-dispatch]))

(defn broadcast-gui-state!*
  [_world _pos _sync-data]
  (throw (ex-info "Custom block GUI S2C sync has been removed"
                  {:type ::gui-broadcast-removed
                   :platform (platform-dispatch/current-platform-version)})))