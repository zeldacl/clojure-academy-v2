(ns cn.li.forge1201.runtime.multipart-entity
  "Forge multipart API registration adapter."
  (:import [net.minecraftforge.entity PartEntity]))

(defn parent
  [entity]
  (when (instance? PartEntity entity)
    (.getParent ^PartEntity entity)))
