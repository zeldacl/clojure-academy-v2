(ns my-mod.block.tile-logic
  "Registry and dispatch for scripted block entity logic.

  Supports both block-id direct registration and reusable tile-kind registration.
  ScriptedBlockEntity calls into this namespace for tick/load/save lifecycle hooks."
  (:require [my-mod.util.log :as log]))

(defonce tile-logic-registry (atom {}))
(defonce tile-kind-registry (atom {}))

(defn register-tile-kind!
  "Register reusable tile logic by tile-kind keyword.

  cfg uses normalized keys:
  - :tick-fn (fn [level pos state be] ...)
  - :read-nbt-fn (fn [tag] ...)
  - :write-nbt-fn (fn [be tag] ...)"
  [tile-kind cfg]
  (when-not (keyword? tile-kind)
    (throw (ex-info "register-tile-kind!: tile-kind must be keyword"
                    {:tile-kind tile-kind})))
  (swap! tile-kind-registry assoc tile-kind cfg)
  (log/info "Registered tile-kind logic" tile-kind)
  nil)

(defn- normalize-cfg
  [cfg]
  (cond-> cfg
    (contains? cfg :tile-tick-fn) (assoc :tick-fn (:tile-tick-fn cfg))
    (contains? cfg :tile-load-fn) (assoc :read-nbt-fn (:tile-load-fn cfg))
    (contains? cfg :tile-save-fn) (assoc :write-nbt-fn (:tile-save-fn cfg))))

(defn- merge-with-kind
  "Merge kind-cfg with normalized. Nil values in normalized do not override
  kind-cfg (so tile-kind defaults are used when tile spec omits hooks)."
  [kind-cfg normalized]
  (if (empty? kind-cfg)
    normalized
    (merge kind-cfg
           (into {} (remove (fn [[_ v]] (nil? v)) normalized)))))

(defn register-tile-logic!
  "Register tile lifecycle logic for a block-id.

  cfg supports keys:
  - :tile-kind (keyword, optional) to reuse kind defaults
  - :tick-fn / :read-nbt-fn / :write-nbt-fn (optional overrides)
  Legacy aliases :tile-tick-fn/:tile-load-fn/:tile-save-fn are also accepted.
  Nil hook values in cfg do not override tile-kind defaults."
  [block-id cfg]
  (let [normalized (normalize-cfg cfg)
        tile-kind (:tile-kind normalized)
        kind-cfg (when tile-kind (get @tile-kind-registry tile-kind))
        merged (merge-with-kind (or kind-cfg {}) normalized)]
    (if (or (:tick-fn merged) (:read-nbt-fn merged) (:write-nbt-fn merged))
      (do
        (swap! tile-logic-registry assoc block-id merged)
        (log/info "Registered tile logic for block" block-id "with tile-kind" tile-kind))
      (do
        (log/warn "register-tile-logic!: no lifecycle hooks resolved, skipping registration"
                  {:block-id block-id
                   :tile-kind tile-kind
                   :cfg cfg
                   :kind-cfg kind-cfg})
        nil))))

(defn get-tile-logic [block-id]
  (get @tile-logic-registry block-id))

(defn invoke-tick
  "Called from ScriptedBlockEntity.serverTick(level, pos, state, be).
  Invokes the registered :tick-fn for block-id with (level pos state be)."
  [block-id level pos state be]
  (when-let [cfg (get-tile-logic block-id)]
    (when-let [tick-fn (:tick-fn cfg)]
      (try
        (tick-fn level pos state be)
        (catch Exception e
          (log/error "Tile tick error" block-id (.getMessage e)))))))

(defn read-nbt
  "Called from ScriptedBlockEntity.load(tag). Returns a data map for the BE to apply (setFromData).
  tag is the platform NBT compound (INBTCompound)."
  [block-id tag]
  (if-let [cfg (get-tile-logic block-id)]
    (if-let [read-fn (:read-nbt-fn cfg)]
      (try
        (read-fn tag)
        (catch Exception e
          (log/error "Tile read-nbt error" block-id (.getMessage e))
          {}))
      {})
    {}))

(defn write-nbt
  "Called from ScriptedBlockEntity.saveAdditional(tag). Writes BE state to tag.
  be has getEnergy(), getBatteryStack(), etc.; tag is INBTCompound."
  [block-id be tag]
  (when-let [cfg (get-tile-logic block-id)]
    (when-let [write-fn (:write-nbt-fn cfg)]
      (try
        (write-fn be tag)
        (catch Exception e
          (log/error "Tile write-nbt error" block-id (.getMessage e)))))))
