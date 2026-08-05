(ns cn.li.mc262.runtime.multipart-entity
  "Shared multipart entity normalization."
  (:import [cn.li.acapi.entity MultipartEntityApi MultipartEntityApi$ParentValidator MultipartEntityPart]
           [net.minecraft.world.entity Entity]
           [net.minecraft.world.entity.boss.enderdragon EnderDragonPart]))

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
    (or (when (instance? EnderDragonPart entity)
          (valid-parent entity (.-parentMob ^EnderDragonPart entity)))
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
