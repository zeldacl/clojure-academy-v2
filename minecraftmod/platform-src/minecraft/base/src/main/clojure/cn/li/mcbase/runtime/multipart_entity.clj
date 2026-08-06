(ns cn.li.mcbase.runtime.multipart-entity
  "Shared multipart entity normalization (EnderDragonPart package forks via reflection)."
  (:import [cn.li.acapi.entity MultipartEntityApi MultipartEntityApi$ParentValidator MultipartEntityPart]
           [net.minecraft.world.entity Entity]))

(defn- load-class-uninitialized
  "Resolve a class without <clinit> so unit tests can load this ns without
   Minecraft Bootstrap (EnderDragonPart → Entity → Registries)."
  [^String name]
  (try
    (Class/forName name false (clojure.lang.RT/baseLoader))
    (catch ClassNotFoundException _
      nil)
    (catch ExceptionInInitializerError _
      nil)
    (catch LinkageError _
      nil)))

(def ^:private ender-dragon-part-class
  (delay
    (or (load-class-uninitialized "net.minecraft.world.entity.boss.EnderDragonPart")
        (load-class-uninitialized "net.minecraft.world.entity.boss.enderdragon.EnderDragonPart")
        (throw (IllegalStateException. "EnderDragonPart class not found")))))

(defn- ender-dragon-part?
  [entity]
  (boolean (and entity (.isInstance ^Class @ender-dragon-part-class entity))))

(defn- ender-dragon-parent-mob
  [entity]
  (try
    (let [^Class cls (.getClass ^Object entity)
          ^java.lang.reflect.Field f (.getDeclaredField cls "parentMob")]
      (.setAccessible f true)
      (.get f entity))
    (catch Exception _
      nil)))

(def ^:private ^MultipartEntityApi$ParentValidator entity-parent-validator
  (reify MultipartEntityApi$ParentValidator
    (isValid [_ candidate]
      (instance? Entity candidate))))

(defn- valid-parent
  [entity candidate]
  (when (and (instance? Entity candidate)
             (not (identical? entity candidate)))
    candidate))

(defn- api-contract-parent
  [entity]
  (when (instance? MultipartEntityPart entity)
    (try
      (valid-parent entity
                    (.getMultipartParent ^MultipartEntityPart entity))
      (catch Exception _
        nil)
      (catch LinkageError _
        nil))))

(defn- registered-parent
  [entity]
  (valid-parent entity
                (MultipartEntityApi/resolveParent
                  entity
                  ^MultipartEntityApi$ParentValidator entity-parent-validator)))

(defn parent
  "Resolve an immediate multipart parent through vanilla, cross-loader API,
   and registered compatibility contracts, in that order."
  [entity]
  (when entity
    (or (when (ender-dragon-part? entity)
          (valid-parent entity (ender-dragon-parent-mob entity)))
        (api-contract-parent entity)
        (registered-parent entity))))

(defn multipart?
  [entity]
  (boolean (and entity (parent entity))))

(defn combat-root
  "Resolve nested multipart graphs to a stable root."
  [entity]
  (loop [^Entity current entity
         depth 0
         seen []]
    (if (or (nil? current)
            (>= depth 8)
            (some #(identical? current %) seen))
      current
      (if-let [^Entity parent-entity (parent current)]
        (recur parent-entity (inc depth) (conj seen current))
        current))))
