(ns cn.li.mc1201.gui.provider.common
  "Shared helper functions for GUI provider bridges."
  (:import [net.minecraft.world.entity.player Player]))

(defn tile->pos
  [tile-entity ^Player player]
  (cond
    (nil? tile-entity)
    (.blockPosition player)

    (map? tile-entity)
    (or (:pos tile-entity) (.blockPosition player))

    :else
    (try
      (clojure.lang.Reflector/invokeInstanceMethod tile-entity "getBlockPos" (object-array []))
      (catch Exception _
        (.blockPosition player)))))
