(ns cn.li.forge1201.ability.raycast
  "Forge implementation of IRaycast protocol."
  (:require [cn.li.mcmod.platform.raycast :as prc]
            [cn.li.mcmod.util.log :as log])
  (:import [java.util UUID]))

(set! *warn-on-reflection* true)

(defn- load-class-no-init ^Class [class-name]
  (Class/forName class-name false (.getContextClassLoader (Thread/currentThread))))

(defn- new-instance
  [class-name & args]
  (clojure.lang.Reflector/invokeConstructor (load-class-no-init class-name) (to-array args)))

(defn- invoke-static-no-init
  [class-name method-name & args]
  (clojure.lang.Reflector/invokeStaticMethod (load-class-no-init class-name) method-name (to-array args)))

(defn- enum-constant [class-name enum-name]
  (java.lang.Enum/valueOf (load-class-no-init class-name) enum-name))

(def entity-class (delay (load-class-no-init "net.minecraft.world.entity.Entity")))
(def living-entity-class (delay (load-class-no-init "net.minecraft.world.entity.LivingEntity")))

(defn- get-server []
  (invoke-static-no-init "net.minecraftforge.server.ServerLifecycleHooks" "getCurrentServer"))

(defn- get-player-by-uuid [uuid-str]
  (try
    (when-let [server (get-server)]
      (let [uuid (UUID/fromString uuid-str)]
        (.getPlayerList server)
        (.getPlayer (.getPlayerList server) uuid)))
    (catch Exception e
      (log/warn "Failed to get player by UUID:" uuid-str (ex-message e))
      nil)))

(defn- raycast-blocks-impl [_world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
  (try
    (when-let [server (get-server)]
      (when-let [level (.overworld server)]
        (let [start (new-instance "net.minecraft.world.phys.Vec3" start-x start-y start-z)
              end (new-instance "net.minecraft.world.phys.Vec3"
                                (+ start-x (* dir-x max-distance))
                                (+ start-y (* dir-y max-distance))
                                (+ start-z (* dir-z max-distance)))
              clip-context (new-instance "net.minecraft.world.level.ClipContext"
                                         start end
                                         (enum-constant "net.minecraft.world.level.ClipContext$Block" "OUTLINE")
                                         (enum-constant "net.minecraft.world.level.ClipContext$Fluid" "NONE")
                                         nil)
              result (.clip level clip-context)]
          (when (= (.getType result) (enum-constant "net.minecraft.world.phys.HitResult$Type" "BLOCK"))
            (let [pos (.getBlockPos result)
                  block-state (.getBlockState level pos)
                  block (.getBlock block-state)
                  hit-vec (.getLocation result)
                  distance (.distanceTo start hit-vec)]
              {:x (.getX pos)
               :y (.getY pos)
               :z (.getZ pos)
               :block-id (str (.getDescriptionId block))
               :face (keyword (str (.getDirection result)))
               :distance distance})))))
    (catch Exception e
      (log/warn "Failed to raycast blocks:" (ex-message e))
      nil)))

(defn- raycast-entities-impl [_world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
  (try
    (when-let [server (get-server)]
      (when-let [level (.overworld server)]
        (let [start (new-instance "net.minecraft.world.phys.Vec3" start-x start-y start-z)
              end (new-instance "net.minecraft.world.phys.Vec3"
                                (+ start-x (* dir-x max-distance))
                                (+ start-y (* dir-y max-distance))
                                (+ start-z (* dir-z max-distance)))
              aabb (new-instance "net.minecraft.world.phys.AABB"
                                 (min start-x (+ start-x (* dir-x max-distance)))
                                 (min start-y (+ start-y (* dir-y max-distance)))
                                 (min start-z (+ start-z (* dir-z max-distance)))
                                 (max start-x (+ start-x (* dir-x max-distance)))
                                 (max start-y (+ start-y (* dir-y max-distance)))
                                 (max start-z (+ start-z (* dir-z max-distance))))
              entities (.getEntitiesOfClass level @living-entity-class (.inflate aabb 2.0))
              hits (atom [])]
          (doseq [entity entities]
            (let [entity-aabb (.getBoundingBox entity)
                  optional-hit (.clip entity-aabb start end)]
              (when (.isPresent optional-hit)
                (let [hit-vec (.get optional-hit)
                      distance (.distanceTo start hit-vec)
                      pos (.position entity)]
                  (swap! hits conj {:uuid (str (.getUUID entity))
                                    :x (.x pos)
                                    :y (.y pos)
                                    :z (.z pos)
                                    :type (str (.getDescriptionId (.getType entity)))
                                    :distance distance})))))
          (when (seq @hits)
            (first (sort-by :distance @hits))))))
    (catch Exception e
      (log/warn "Failed to raycast entities:" (ex-message e))
      nil)))

(defn- raycast-combined-impl [world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
  (let [block-hit (raycast-blocks-impl world-id start-x start-y start-z dir-x dir-y dir-z max-distance)
        entity-hit (raycast-entities-impl world-id start-x start-y start-z dir-x dir-y dir-z max-distance)]
    (cond
      (and block-hit entity-hit)
      (if (< (:distance block-hit) (:distance entity-hit))
        (assoc block-hit :hit-type :block)
        (assoc entity-hit :hit-type :entity))

      block-hit
      (assoc block-hit :hit-type :block)

      entity-hit
      (assoc entity-hit :hit-type :entity)

      :else
      nil)))

(defn- get-player-look-vector-impl [player-uuid]
  (try
    (when-let [player (get-player-by-uuid player-uuid)]
      (let [look-vec (.getLookAngle player)]
        {:x (.x look-vec)
         :y (.y look-vec)
         :z (.z look-vec)}))
    (catch Exception e
      (log/warn "Failed to get player look vector:" (ex-message e))
      nil)))

(defn- raycast-from-player-impl [player-uuid max-distance living-only?]
  (try
    (when-let [player (get-player-by-uuid player-uuid)]
      (let [eye-pos (.getEyePosition player)
            look-vec (.getLookAngle player)
            start-x (.x eye-pos)
            start-y (.y eye-pos)
            start-z (.z eye-pos)
            dir-x (.x look-vec)
            dir-y (.y look-vec)
            dir-z (.z look-vec)
            level (.serverLevel player)
            start (new-instance "net.minecraft.world.phys.Vec3" start-x start-y start-z)
            end (new-instance "net.minecraft.world.phys.Vec3"
                              (+ start-x (* dir-x max-distance))
                              (+ start-y (* dir-y max-distance))
                              (+ start-z (* dir-z max-distance)))
            aabb (new-instance "net.minecraft.world.phys.AABB"
                               (min start-x (+ start-x (* dir-x max-distance)))
                               (min start-y (+ start-y (* dir-y max-distance)))
                               (min start-z (+ start-z (* dir-z max-distance)))
                               (max start-x (+ start-x (* dir-x max-distance)))
                               (max start-y (+ start-y (* dir-y max-distance)))
                               (max start-z (+ start-z (* dir-z max-distance))))
            entities (if living-only?
                      (.getEntitiesOfClass level @living-entity-class (.inflate aabb 2.0))
                      (.getEntitiesOfClass level @entity-class (.inflate aabb 2.0)))
            hits (atom [])]
        (doseq [entity entities]
          (when-not (= entity player)  ; Don't hit self
            (let [entity-aabb (.getBoundingBox entity)
                  optional-hit (.clip entity-aabb start end)]
              (when (.isPresent optional-hit)
                (let [hit-vec (.get optional-hit)
                      distance (.distanceTo start hit-vec)
                      pos (.position entity)]
                  (swap! hits conj {:entity-id (str (.getUUID entity))
                                    :x (.x pos)
                                    :y (.y pos)
                                    :z (.z pos)
                                    :distance distance}))))))
        (when (seq @hits)
          (first (sort-by :distance @hits)))))
    (catch Exception e
      (log/warn "Failed to raycast from player:" (ex-message e))
      nil)))

(defn forge-raycast []
  (reify prc/IRaycast
    (raycast-blocks [_ world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
      (raycast-blocks-impl world-id start-x start-y start-z dir-x dir-y dir-z max-distance))
    (raycast-entities [_ world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
      (raycast-entities-impl world-id start-x start-y start-z dir-x dir-y dir-z max-distance))
    (raycast-combined [_ world-id start-x start-y start-z dir-x dir-y dir-z max-distance]
      (raycast-combined-impl world-id start-x start-y start-z dir-x dir-y dir-z max-distance))
    (get-player-look-vector [_ player-uuid]
      (get-player-look-vector-impl player-uuid))
    (raycast-from-player [_ player-uuid max-distance living-only?]
      (raycast-from-player-impl player-uuid max-distance living-only?))))

(defn install-raycast! []
  (alter-var-root #'prc/*raycast*
                  (constantly (forge-raycast)))
  (log/info "Forge raycast installed"))
