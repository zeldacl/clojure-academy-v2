(ns cn.li.mc262.gui.reactive.bake-slots
  "Backend oslot type registry — dev/CI only, not on render hot path."
  (:import [cn.li.mcmod.ui.node INode]
           [net.minecraft.resources Identifier]))

(def ^:private bake-slot-specs
  {:image   {2 Identifier}
   :text    {8 clojure.lang.IPersistentMap}
   :progress {8 Identifier
              9 Identifier}})

(defn bake-asserts-enabled?
  []
  (Boolean/getBoolean "mcmod.ui.bakeAsserts"))

(defn assert-bake-slots!
  "Verify backend oslots hold expected types after bake. Dev/CI only."
  [^INode node]
  (when-let [specs (get bake-slot-specs (.getKind node))]
    (doseq [[idx expected-type] specs]
      (let [v (.getOSlot node (int idx))]
        (when (some? v)
          (when-not (instance? expected-type v)
            (throw (ex-info "Bake slot type mismatch"
                            {:kind (.getKind node)
                             :slot idx
                             :expected expected-type
                             :actual (class v)
                             :value v})))))))
  node)

(defn maybe-assert-bake-slots!
  [^INode node]
  (when (bake-asserts-enabled?)
    (assert-bake-slots! node))
  node)
