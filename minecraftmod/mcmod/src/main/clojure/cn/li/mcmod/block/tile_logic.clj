(ns cn.li.mcmod.block.tile-logic
  "Registry and dispatch for scripted block entity logic.

  Supports both block-id direct registration and reusable tile-kind registration.
  ScriptedBlockEntity calls into this namespace for tick/load/save/capability/container hooks."
  (:require [cn.li.mcmod.util.log :as log]))

(defonce tile-logic-registry (atom {}))
(defonce tile-kind-registry (atom {}))

;; capability-registry: tile-id → {cap-key → handler-factory-fn}
;; handler-factory-fn is (fn [be side] handler-or-nil)
(defonce capability-registry (atom {}))

;; container-registry: tile-id → container-fns-map
;; container-fns-map keys:
;;   :get-size                  (fn [be] int)
;;   :get-item                  (fn [be slot] ItemStack-or-nil)
;;   :set-item!                 (fn [be slot item] nil)
;;   :remove-item               (fn [be slot amount] ItemStack-or-nil)
;;   :remove-item-no-update     (fn [be slot] ItemStack-or-nil)
;;   :clear!                    (fn [be] nil)
;;   :still-valid?              (fn [be player] bool)  ; optional, defaults true
;;   :slots-for-face            (fn [be face] int[])   ; optional
;;   :can-place-through-face?   (fn [be slot item face] bool)  ; optional
;;   :can-take-through-face?    (fn [be slot item face] bool)  ; optional
(defonce container-registry (atom {}))

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
          (log/error "Tile tick error" block-id(ex-message e)))))))

(defn read-nbt
  "Called from ScriptedBlockEntity.load(tag). Returns a data map for the BE to apply (setFromData).
  tag is the platform NBT compound (INBTCompound)."
  [block-id tag]
  (if-let [cfg (get-tile-logic block-id)]
    (if-let [read-fn (:read-nbt-fn cfg)]
      (try
        (read-fn tag)
        (catch Exception e
          (log/error "Tile read-nbt error" block-id(ex-message e))
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
          (log/error "Tile write-nbt error" block-id(ex-message e)))))))

;; ============================================================================
;; Capability registry
;; ============================================================================

(defn register-tile-capability!
  "Register a capability key for a tile-id.
  The actual handler-factory-fn is looked up from platform/capability-type-registry.
  Must be called after declare-capability! has been called for the key."
  [tile-id cap-key]
  (swap! capability-registry update tile-id assoc cap-key cap-key)
  (log/info "Registered capability" cap-key "for tile" tile-id)
  nil)

(defn get-capability
  "Called from ScriptedBlockEntity.getCapability().
  Returns a handler object for (tile-id, cap-key, be, side), or nil.
  Looks up handler-factory-fn from platform/capability-type-registry."
  [tile-id cap-key be side]
  (when (get-in @capability-registry [tile-id cap-key])
    (try
    (let [get-factory (requiring-resolve 'cn.li.mcmod.platform.capability/get-handler-factory)
      factory     (when get-factory (get-factory cap-key))]
      (when factory (factory be side)))
      (catch Exception e
        (log/error "get-capability error" tile-id cap-key(ex-message e))
        nil))))

;; ============================================================================
;; Container registry
;; ============================================================================

(defn register-container!
  "Register container functions map for a tile-id.
  fns-map keys: :get-size :get-item :set-item! :remove-item
                :remove-item-no-update :clear! :still-valid?
                :slots-for-face :can-place-through-face? :can-take-through-face?"
  [tile-id fns-map]
  (swap! container-registry assoc tile-id fns-map)
  (log/info "Registered container for tile" tile-id)
  nil)

(defn- container-fns [tile-id] (get @container-registry tile-id))

(defn container-size [tile-id be]
  (if-let [f (:get-size (container-fns tile-id))] (int (f be)) 0))

(defn container-get-item [tile-id be slot]
  (when-let [f (:get-item (container-fns tile-id))] (f be slot)))

(defn container-set-item [tile-id be slot item]
  (when-let [f (:set-item! (container-fns tile-id))] (f be slot item)))

(defn container-remove-item [tile-id be slot amount]
  (when-let [f (:remove-item (container-fns tile-id))] (f be slot amount)))

(defn container-remove-item-no-update [tile-id be slot]
  (when-let [f (:remove-item-no-update (container-fns tile-id))] (f be slot)))

(defn container-clear [tile-id be]
  (when-let [f (:clear! (container-fns tile-id))] (f be)))

(defn container-still-valid [tile-id be player]
  (if-let [f (:still-valid? (container-fns tile-id))] (f be player) true))

(defn container-slots-for-face [tile-id be face]
  (when-let [f (:slots-for-face (container-fns tile-id))] (f be face)))

(defn container-can-place-through-face [tile-id be slot item face]
  (boolean (when-let [f (:can-place-through-face? (container-fns tile-id))] (f be slot item face))))

(defn container-can-take-through-face [tile-id be slot item face]
  (boolean (when-let [f (:can-take-through-face? (container-fns tile-id))] (f be slot item face))))
