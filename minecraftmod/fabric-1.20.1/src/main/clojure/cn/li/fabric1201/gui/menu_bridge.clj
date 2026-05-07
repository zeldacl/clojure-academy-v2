(ns cn.li.fabric1201.gui.menu-bridge
  "Fabric 1.20.1 menu bridge placeholder.

  The original implementation used `gen-class` against Minecraft menu types,
  which is currently too eager during Clojure compilation and triggers loader
  bootstrap failures. Keep a compile-safe facade here until the runtime bridge
  is reintroduced in a Java-backed or proxy-backed form."
  (:require [cn.li.mcmod.util.log :as log]))

(defn create-menu-bridge [window-id menu-type clj-container]
  (log/warn "Fabric menu bridge is not implemented yet"
            {:window-id window-id
             :menu-type (some-> menu-type class .getName)
             :container-keys (when (map? clj-container) (keys clj-container))})
  nil)

(defn create-screen-handler-bridge [window-id menu-type clj-container]
  (create-menu-bridge window-id menu-type clj-container))
