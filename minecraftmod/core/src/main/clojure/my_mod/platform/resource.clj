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
