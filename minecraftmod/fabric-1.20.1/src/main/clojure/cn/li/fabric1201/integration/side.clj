(ns cn.li.fabric1201.integration.side
  "Runtime side detection for Fabric 1.20.1 (stub).

  This namespace provides side detection utilities for Fabric mods.
  Note: Full implementation depends on Fabric API availability."
  (:require [cn.li.mcmod.util.log :as log]))

;; Fabric side detection stub
;; In practice, check FabricLoader.getInstance().getEnvironmentType()

(defn client-side?
  "Returns true if running on physical client.
  Note: This is a stub - actual implementation depends on Fabric API."
  []
  false)

(defn server-side?
  "Returns true if running on dedicated server.
  Note: This is a stub."
  []
  true)

(defn require-client-ns
  "Safely require client-only namespace."
  [ns-sym]
  nil)

(defn resolve-client-fn
  "Safely resolve client-only function."
  [ns-sym fn-name]
  nil)

(defn call-client-fn
  "Safely call client-only function."
  [ns-sym fn-name & args]
  nil)

(defmacro when-client [& body]
  nil)

(defmacro when-server [& body]
  `(do ~@body))
