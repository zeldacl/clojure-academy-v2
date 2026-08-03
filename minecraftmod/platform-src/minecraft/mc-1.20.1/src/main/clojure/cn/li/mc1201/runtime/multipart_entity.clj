(ns cn.li.mc1201.runtime.multipart-entity
  "Shared multipart entity normalization."
  (:import [net.minecraft.world.entity Entity]
           [net.minecraft.world.entity.boss EnderDragonPart]))

(defn parent
  [entity loader-parent-fn]
  (try
    (let [candidate (or (when (instance? EnderDragonPart entity)
                          (.-parentMob ^EnderDragonPart entity))
                        (when loader-parent-fn
                          (loader-parent-fn entity)))]
      (when (instance? Entity candidate)
        candidate))
    (catch Exception _
      nil)))

(defn multipart?
  [entity loader-parent-fn]
  (boolean (and entity (parent entity loader-parent-fn))))

(defn combat-root
  "Resolve nested multipart graphs to a stable root."
  [entity loader-parent-fn]
  (loop [^Entity current entity
         depth 0
         seen []]
    (if (or (nil? current)
            (>= depth 8)
            (some #(identical? current %) seen))
      current
      (if-let [^Entity parent-entity (parent current loader-parent-fn)]
        (recur parent-entity (inc depth) (conj seen current))
        current))))
