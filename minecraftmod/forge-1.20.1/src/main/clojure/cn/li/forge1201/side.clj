(ns cn.li.forge1201.side
  "Runtime side detection for Forge 1.20.1.

  Provides functions to detect whether code is running on physical client
  or dedicated server, and safely resolve client-only functions."
  (:require [cn.li.mcmod.util.log :as log])
  (:import [net.minecraftforge.api.distmarker Dist]
           [net.minecraftforge.fml.loading FMLEnvironment]))

(defn client-side?
  "Returns true if running on physical client (integrated or dedicated client).
  False on dedicated server."
  []
  (= FMLEnvironment/dist Dist/CLIENT))

(defn server-side?
  "Returns true if running on dedicated server.
  False on client (including integrated server)."
  []
  (= FMLEnvironment/dist Dist/DEDICATED_SERVER))

(defn require-client-ns
  "Safely require a client-only namespace. Returns the namespace or nil if on server.

  Args:
    ns-sym: Symbol - Namespace to require (e.g., 'cn.li.forge1201.client.init)

  Returns:
    The namespace symbol if successfully loaded on client, nil otherwise."
  [ns-sym]
  (when (client-side?)
    (try
      (require ns-sym)
      ns-sym
      (catch Exception e
        (log/error "Failed to load client namespace" ns-sym e)
        nil))))

(defn resolve-client-fn
  "Safely resolve a client-only function. Returns the var or nil if on server.

  Args:
    ns-sym: Symbol - Namespace containing the function
    fn-name: Symbol or String - Function name

  Returns:
    The resolved var if on client, nil if on server or if resolution fails.

  Example:
    (when-let [init! (resolve-client-fn 'cn.li.forge1201.client.init 'init-client)]
      (init!))"
  [ns-sym fn-name]
  (when (client-side?)
    (try
      (requiring-resolve (symbol (str ns-sym) (str fn-name)))
      (catch Exception e
        (log/error "Failed to resolve client function" ns-sym fn-name e)
        nil))))
