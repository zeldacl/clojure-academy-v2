(ns cn.li.mc1201.gui.bridge
  "Shared GUI bridge facade helpers for platform wrappers.")

(defn create-menu-provider
  [provider-create-fn gui-id tile-entity]
  (provider-create-fn gui-id tile-entity))

(defn create-extended-menu-provider
  [provider-create-extended-fn gui-id tile-entity]
  (provider-create-extended-fn gui-id tile-entity))

(defn create-extended-factory
  [provider-create-extended-fn gui-id tile-entity]
  (create-extended-menu-provider provider-create-extended-fn gui-id tile-entity))

(defn create-screen-handler-factory
  [provider-create-fn gui-id tile-entity]
  (create-menu-provider provider-create-fn gui-id tile-entity))

(defn create-extended-screen-handler-factory
  [provider-create-extended-fn gui-id tile-entity]
  (create-extended-menu-provider provider-create-extended-fn gui-id tile-entity))

(defn wrap-clojure-container
  [menu-create-fn sync-or-window-id handler-or-menu-type clj-container]
  (menu-create-fn sync-or-window-id handler-or-menu-type clj-container))