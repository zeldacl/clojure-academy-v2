(ns cn.li.mc1211.client.player-state-core
  "CLIENT-ONLY shared helpers for reading local player state from Minecraft."
  (:import [net.minecraft.client Minecraft]
           [net.minecraft.core.registries BuiltInRegistries]
           [net.minecraft.world.level ClipContext ClipContext$Block ClipContext$Fluid]
           [net.minecraft.world.phys BlockHitResult HitResult$Type Vec3]
           [net.minecraft.world.phys.shapes CollisionContext]))

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

(defn local-player-block-aim
  "Where the local player is aiming: the precise block-hit point within
  [distance], or the look end when nothing is hit. Returns nil if not in-game.

  The same trace the server runs for aim-following skills — OUTLINE blocks,
  no fluids, no entity shape context — but evaluated locally, so a client
  effect can follow the crosshair instead of lagging a server tick plus
  network latency behind it. Uses the render-interpolated eye and view vector
  so it stays smooth between ticks."
  [distance]
  (when-let [^Minecraft mc (Minecraft/getInstance)]
    (when-let [player (.player mc)]
      (when-let [level (.level mc)]
        (let [^Vec3 eye (.getEyePosition player (float 1.0))
              ^Vec3 look (.getViewVector player (float 1.0))
              ^Vec3 end (.add eye (.scale look (double distance)))
              ;; nil is ambiguous (Entity vs CollisionContext); match Raycast.
              ^BlockHitResult hit (.clip level
                                         (ClipContext. eye end
                                                       ClipContext$Block/OUTLINE
                                                       ClipContext$Fluid/NONE
                                                       (CollisionContext/empty)))
              ^Vec3 point (if (and hit (= (.getType hit) HitResult$Type/BLOCK))
                            (.getLocation hit)
                            end)]
          {:x (.x point) :y (.y point) :z (.z point)})))))
