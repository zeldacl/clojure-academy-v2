(ns cn.li.mcmod.spi.entity-render-registry
  "Neutral entity render namespace registry.

  Problem: SilbarnObjRenderer.java (in mc-1.20.1) hardcoded
  \"cn.li.ac.content.entities.silbarn-render\" — a static dependency
  on a business content module.

  Solution: Content modules register their render namespaces here.
  Java renderer classes read from this registry at render time."
  (:require [cn.li.mcmod.util.log :as log]))

(def ^:private render-ns-map (atom {}))

(defn register-entity-render-ns!
  "Register a Clojure render namespace for an entity hook-id.
  Called by content modules during client init.
  hook-id — keyword or string, e.g. :silbarn or \"silbarn\"
  render-ns — fully-qualified Clojure namespace string"
  [hook-id render-ns]
  (assert (string? render-ns)
          (str "render-ns must be a string, got " (type render-ns)))
  (swap! render-ns-map assoc (str hook-id) render-ns)
  (log/info "Entity render namespace registered:" hook-id "->" render-ns)
  nil)

(defn get-entity-render-ns
  "Returns the registered render namespace for a hook-id, or nil.
  Called from Java renderer classes at render time."
  [hook-id]
  (get @render-ns-map (str hook-id)))
