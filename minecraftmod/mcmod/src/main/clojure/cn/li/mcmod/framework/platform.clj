(ns cn.li.mcmod.framework.platform
  "Facade API for platform adapter function maps (:platform/* sub-namespace).

   Replaces the defprotocol + ^:dynamic *runtime* + alter-var-root pattern
   with plain function maps stored in the framework atom.

   Each platform domain (e.g. :world-ops, :nbt-ops, :entity-damage) is a
   plain Clojure map of {:operation-key (fn [args] ...), ...}.

   Benefits over defprotocol + dynamic var:
     - No protocol bytecode class generation (AOT class-loading race)
     - No ThreadLocal dependency (async thread safety)
     - Function references follow the framework instance — valid on any thread"
  )

(defn install-adapter!
  "Install a platform adapter function map.

   Called once at bootstrap by platform-specific code (Forge/Fabric).
   Overwrites any existing adapter for the same key.

   Args:
     fw          — framework atom instance
     adapter-key — keyword identifying the adapter, e.g. :world-ops, :nbt-ops
     impl-map    — plain map of {:operation-key (fn [args] ...), ...}"
  [fw adapter-key impl-map]
  (swap! fw assoc-in [:platform adapter-key] impl-map)
  nil)

(defn get-adapter
  "Read a platform adapter function map.

   Returns nil if the adapter has not been installed yet.

   Args:
     fw          — framework atom instance
     adapter-key — keyword identifying the adapter"
  [fw adapter-key]
  (get-in @fw [:platform adapter-key]))

(defn adapter-installed?
  "Check if a platform adapter has been installed."
  [fw adapter-key]
  (boolean (get-in @fw [:platform adapter-key])))

(defn call-adapter
  "Look up a function from a platform adapter and call it with args.

   Returns nil if the adapter or function key is not found.
   This is the primary runtime dispatch mechanism replacing protocol method calls.

   Args:
     fw          — framework atom instance
     adapter-key — keyword identifying the adapter
     fn-key      — keyword identifying the function within the adapter
     args        — arguments to pass to the function"
  [fw adapter-key fn-key & args]
  (when-let [f (get-in @fw [:platform adapter-key fn-key])]
    (apply f args)))
