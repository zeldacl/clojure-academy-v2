(ns my-mod.platform.resource
  "Platform-agnostic resource identifier abstraction.

  Core code uses this namespace to create Minecraft resource identifiers
  (ResourceLocation / Identifier) without importing platform classes.")

(defonce ^{:dynamic true
           :doc "Platform-specific resource factory function.

                 Must be initialized by platform code before core code runs.

                 Expected signature: (fn [namespace path] -> resource-id-object)"}
  *resource-factory*
  nil)

(defn create-resource-location
  "Create a platform-specific resource identifier.

  Args:
  - namespace: resource namespace (e.g. 'my_mod')
  - path: resource path (e.g. 'textures/gui/node.png')"
  [namespace path]
  (if-let [factory *resource-factory*]
    (factory namespace path)
    (throw (ex-info "Resource factory not initialized - platform must call init-platform! first"
                    {:hint "Check platform initialization for my-mod.platform.resource/*resource-factory*"}))))

(defn factory-initialized?
  "Check if resource factory has been initialized by platform code."
  []
  (some? *resource-factory*))

;; Optional high-level injection for mcmod code that does not depend on config.modid.
;; Ac/loader sets this to (fn [namespace path] resource) so gui.components and client.resources
;; can resolve paths without requiring config.modid. namespace may be nil (use default mod id).
(def ^:dynamic *resource-location-fn* nil)

(defn invoke-resource-location
  "Call the injected resource-location function.
   [path] -> use default namespace (path is the path part).
   [namespace path] -> use given namespace and path.
   Throws if *resource-location-fn* is not set (ac must set it at init)."
  ([path]
   (if *resource-location-fn*
     (*resource-location-fn* nil path)
     (throw (ex-info "*resource-location-fn* not set - ac/loader must set my-mod.platform.resource/*resource-location-fn* at init" {:path path}))))
  ([namespace path]
   (if *resource-location-fn*
     (*resource-location-fn* namespace path)
     (throw (ex-info "*resource-location-fn* not set - ac/loader must set at init" {:namespace namespace :path path})))))
