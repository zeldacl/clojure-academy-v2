(ns cn.li.mc1201.client.player-state-core
  "CLIENT-ONLY shared helpers for reading local player state from Minecraft."
  (:import [net.minecraft.client Minecraft]
           [net.minecraft.core.registries BuiltInRegistries]))

(defn local-player-item-id
  "Returns the registry ID string of the item in the local player's main hand,
  or nil if no item is held."
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (let [stack (.getMainHandItem player)]
        (when (and stack (not (.isEmpty stack)))
          (when-let [key (.getKey BuiltInRegistries/ITEM (.getItem stack))]
            (str (.getNamespace key) ":" (.getPath key))))))))

(defn local-player-pos
  "Returns {:x :y :z} for the local player position, or nil if not in-game."
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      {:x (.getX player) :y (.getY player) :z (.getZ player)})))

(defn local-player-eye-pos
  "Returns {:x :y :z} for the local player eye position (y offset +1.62), or nil."
  []
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      {:x (.getX player) :y (+ (.getY player) 1.62) :z (.getZ player)})))

(defn local-player-look-end
  "Returns {:x :y :z} for the point [distance] blocks ahead of the player's eye,
  along their look direction. Returns nil if not in-game."
  [distance]
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (let [look (.getLookAngle player)
            eye  (local-player-eye-pos)]
        {:x (+ (:x eye) (* (.x look) distance))
         :y (+ (:y eye) (* (.y look) distance))
         :z (+ (:z eye) (* (.z look) distance))}))))
