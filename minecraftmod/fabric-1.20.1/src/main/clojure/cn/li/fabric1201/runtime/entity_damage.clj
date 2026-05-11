(ns cn.li.fabric1201.runtime.entity-damage
  "Fabric implementation of IEntityDamage protocol."
  (:require [cn.li.fabric1201.runtime.server-context :as server-context]
            [cn.li.mc1201.runtime.entity-damage-core :as core]
            [cn.li.mc1201.runtime.entity-query-core :as query-core]
            [cn.li.mcmod.platform.entity-damage :as ped]
            [cn.li.mcmod.platform.power-runtime :as power-runtime]
            [cn.li.mcmod.util.log :as log]))

(defn- get-server []
  (server-context/get-server))

(defn- class-noinit [^String class-name]
  (Class/forName class-name false (.getContextClassLoader (Thread/currentThread))))

(defonce ^:private living-entity-class
  (delay (class-noinit "net.minecraft.world.entity.LivingEntity")))

(defonce ^:private aabb-class
  (delay (class-noinit "net.minecraft.world.phys.AABB")))

(defn- living-entity? [entity]
  (instance? @living-entity-class entity))

(defn- make-aabb [min-x min-y min-z max-x max-y max-z]
  (.newInstance (.getConstructor @aabb-class (into-array Class [Double/TYPE Double/TYPE Double/TYPE Double/TYPE Double/TYPE Double/TYPE]))
                (object-array [(double min-x) (double min-y) (double min-z)
                               (double max-x) (double max-y) (double max-z)])))

(defn- resolve-level [world-id]
  (query-core/resolve-level (get-server) world-id))

(defn- apply-direct-damage-impl! [world-id entity-uuid damage source-type]
  (try
    (when-let [level (resolve-level world-id)]
      (when-let [entity (query-core/get-entity-by-uuid level entity-uuid)]
        (when (living-entity? entity)
          (let [living entity
                dmg-source (core/resolve-damage-source level source-type)]
            (.hurt living dmg-source (float damage))
            true))))
    (catch Exception e
      (log/warn "[fabric] Failed to apply direct damage:" (ex-message e))
      false)))

(defn- apply-aoe-damage-impl! [world-id x y z radius damage source-type falloff?]
  (try
    (when-let [level (resolve-level world-id)]
      (let [origin-pos {:x x :y y :z z}
        aabb (make-aabb (- x radius) (- y radius) (- z radius)
                (+ x radius) (+ y radius) (+ z radius))
        entities (.getEntitiesOfClass level @living-entity-class aabb)
          dmg-source (core/resolve-damage-source level source-type)
            damaged (atom [])]
        (doseq [entity entities]
          (let [target-pos (core/entity-pos-map entity)
                actual-damage (core/compute-aoe-damage
                                origin-pos target-pos radius damage falloff?)]
            (when (> actual-damage 0.0)
              (.hurt entity dmg-source (float actual-damage))
              (swap! damaged conj (str (.getUUID entity))))))
        @damaged))
    (catch Exception e
      (log/warn "[fabric] Failed to apply AOE damage:" (ex-message e))
      [])))

(defn- find-reflection-target [level current-entity max-radius]
  (let [current-pos (core/entity-pos-map current-entity)
      aabb (make-aabb (- (:x current-pos) max-radius)
          (- (:y current-pos) max-radius)
          (- (:z current-pos) max-radius)
          (+ (:x current-pos) max-radius)
          (+ (:y current-pos) max-radius)
          (+ (:z current-pos) max-radius))
      entities (.getEntitiesOfClass level @living-entity-class aabb)
        candidates (mapv core/candidate-map entities)
        target-uuid (core/select-reflection-target-uuid
                      (str (.getUUID current-entity))
                      current-pos
                      candidates
                      max-radius)]
    (when target-uuid
      (query-core/get-entity-by-uuid level target-uuid))))

(defn- apply-reflection-damage-impl! [world-id entity-uuid damage source-type reflection-count max-reflections]
  (try
    (when-let [level (resolve-level world-id)]
      (when-let [entity (query-core/get-entity-by-uuid level entity-uuid)]
        (when (living-entity? entity)
          (let [living entity
                dmg-source (core/resolve-damage-source level source-type)
                search-radius (double (power-runtime/get-reflection-search-radius))
                hit-entities (atom [(str (.getUUID living))])]
            (.hurt living dmg-source (float damage))
            (loop [current-entity living
                   current-damage damage
                   reflection-num reflection-count]
              (when (< reflection-num max-reflections)
                (when-let [next-entity (find-reflection-target level current-entity search-radius)]
                  (let [reflected-damage (core/compute-reflected-damage current-damage)]
                    (.hurt next-entity dmg-source (float reflected-damage))
                    (swap! hit-entities conj (str (.getUUID next-entity)))
                    (recur next-entity reflected-damage (inc reflection-num))))))
            @hit-entities))))
    (catch Exception e
      (log/warn "[fabric] Failed to apply reflection damage:" (ex-message e))
      [])))

(defn fabric-entity-damage []
  (reify ped/IEntityDamage
    (apply-direct-damage! [_ world-id entity-uuid damage source-type]
      (apply-direct-damage-impl! world-id entity-uuid damage source-type))
    (apply-aoe-damage! [_ world-id x y z radius damage source-type falloff?]
      (apply-aoe-damage-impl! world-id x y z radius damage source-type falloff?))
    (apply-reflection-damage! [_ world-id entity-uuid damage source-type reflection-count max-reflections]
      (apply-reflection-damage-impl! world-id entity-uuid damage source-type reflection-count max-reflections))))

(defn install-entity-damage! []
  (server-context/install-server-context!)
  (alter-var-root #'ped/*entity-damage*
                  (constantly (fabric-entity-damage)))
  (log/info "Fabric entity damage installed"))
