(ns cn.li.mcmod.block.tile-logic
  "Registry and dispatch for scripted block entity logic.

  Supports tile-id lifecycle registration and reusable tile-kind registration.
  ScriptedBlockEntity calls into this namespace for tick/load/save/capability/container hooks."
  (:require [cn.li.mcmod.platform.capability :as platform-cap]
            [cn.li.mcmod.platform.runtime :as prt]
            [cn.li.mcmod.protocol.core :as registry-core]
            [cn.li.mcmod.util.log :as log]))

(def ^:private ^:dynamic *tile-logic-registry-state* {})
(def ^:private ^:dynamic *tile-kind-registry-state* {})

(defonce tile-logic-registry (registry-core/var-root-registry #'*tile-logic-registry-state*))
(defonce tile-kind-registry (registry-core/var-root-registry #'*tile-kind-registry-state*))

;; capability-registry: tile-id → {cap-key → handler-factory-fn}
;; handler-factory-fn is (fn [be side] handler-or-nil)
(def ^:private ^:dynamic *capability-registry-state* {})
(defonce capability-registry (registry-core/var-root-registry #'*capability-registry-state*))

;; Resolve capability factory lazily (without top-level delay state)
(def ^:private capability-factory-lock
  (Object.))

(def ^:private ^:dynamic *capability-get-factory*
  nil)

(defn install-capability-get-factory!
  [factory-fn label]
  (prt/install-impl! #'*capability-get-factory* factory-fn (or label "capability-get-factory"))
  nil)

(defn- capability-get-factory
  []
  (or *capability-get-factory*
      (locking capability-factory-lock
        (or *capability-get-factory*
            platform-cap/get-handler-factory))))

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
(def ^:private ^:dynamic *container-registry-state* {})
(defonce container-registry (registry-core/var-root-registry #'*container-registry-state*))

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
  (registry-core/swap-state! tile-kind-registry #(assoc % tile-kind cfg))
  (log/info "Registered tile-kind logic" tile-kind)
  nil)

(defn- merge-with-kind
  "Merge kind-cfg with normalized. Nil values in normalized do not override
  kind-cfg (so tile-kind defaults are used when tile spec omits hooks)."
  [kind-cfg normalized]
  ;; 筑起安全防线：强制在入口核对类型，万一前面写错了，立刻精准报错拦截，绝不隐藏！
  {:pre [(map? kind-cfg)
         (or (map? normalized) (nil? normalized))]}

  (cond
    (empty? kind-cfg) normalized
    (nil? normalized) kind-cfg

    :else
    ;; 确认类型百分之百正确后，再使用高性能的 reduce-kv 运转
    (reduce-kv (fn [m k v]
                 (if (nil? v) m (assoc m k v)))
               kind-cfg
               normalized)))


(defn register-tile-logic!
  "Register tile lifecycle logic for a tile-id.

  cfg supports keys:
  - :tile-kind (keyword, optional) to reuse kind defaults
  - :tick-fn / :read-nbt-fn / :write-nbt-fn (optional overrides)
  Nil hook values in cfg do not override tile-kind defaults."
  [tile-id cfg]
  (let [normalized cfg
        tile-kind (:tile-kind normalized)
        kind-cfg (when tile-kind (registry-core/lookup tile-kind-registry tile-kind))
        merged (merge-with-kind (or kind-cfg {}) normalized)]
    (if (or (:tick-fn merged) (:read-nbt-fn merged) (:write-nbt-fn merged))
      (do
        (registry-core/swap-state! tile-logic-registry #(assoc % tile-id merged))
        (log/info "Registered tile logic for tile" tile-id "with tile-kind" tile-kind))
      (do
        (log/warn "register-tile-logic!: no lifecycle hooks resolved, skipping registration"
                  {:tile-id tile-id
                   :tile-kind tile-kind
                   :cfg cfg
                   :kind-cfg kind-cfg})
        nil))))

(defn get-tile-logic [tile-id]
  (registry-core/lookup tile-logic-registry tile-id))

(defn invoke-tick
  "Called from ScriptedBlockEntity.serverTick(level, pos, state, be).
  Invokes the registered :tick-fn for tile-id with (level pos state be)."
  [tile-id level pos state be]
  (when-let [cfg (get-tile-logic tile-id)]
    (when-let [tick-fn (:tick-fn cfg)]
      (try
        (tick-fn level pos state be)
        (catch Exception e
          (log/error "Tile tick error" tile-id (ex-message e)))))))

(defn read-nbt
  "Called from ScriptedBlockEntity.load(tag). Returns the custom-state map for the BE.

  `read-nbt-fn` must return a Clojure map (multiblock BER and client rendering assume map?).
  tag is the platform NBT compound (INBTCompound)."
  [tile-id tag]
  (if-let [cfg (get-tile-logic tile-id)]
    (if-let [read-fn (:read-nbt-fn cfg)]
      (try
        (read-fn tag)
        (catch Exception e
          (log/error "Tile read-nbt error" tile-id (ex-message e))
          {}))
      {})
    {}))

(defn write-nbt
  "Called from ScriptedBlockEntity.saveAdditional(tag). Writes BE state to tag.
  tag is INBTCompound."
  [tile-id be tag]
  (when-let [cfg (get-tile-logic tile-id)]
    (when-let [write-fn (:write-nbt-fn cfg)]
      (try
        (write-fn be tag)
        (catch Exception e
          (log/error "Tile write-nbt error" tile-id (ex-message e)))))))

;; ============================================================================
;; Capability registry
;; ============================================================================

(defn register-tile-capability!
  "Register a capability key for a tile-id.
  The actual handler-factory-fn is looked up from platform/capability-type-registry.
  Must be called after declare-capability! has been called for the key."
  [tile-id cap-key]
  (registry-core/swap-state! capability-registry
                             #(update % tile-id (fnil assoc {}) cap-key cap-key))
  (log/info "Registered capability" cap-key "for tile" tile-id)
  nil)

(defn get-capability
  "Called from ScriptedBlockEntity.getCapability().
  Returns a handler object for (tile-id, cap-key, be, side), or nil.
  Looks up handler-factory-fn from platform/capability-type-registry."
  [tile-id cap-key be side]
  (let [k (if (keyword? cap-key) cap-key (keyword cap-key))]
    (when (get-in (registry-core/snapshot capability-registry) [tile-id k])
      (try
        (when-let [get-factory (capability-get-factory)]
          (when-let [factory (get-factory k)]
            (factory be side)))
        (catch Exception e
          (log/error "get-capability error" tile-id k (ex-message e))
          nil)))))

;; ============================================================================
;; Container registry
;; ============================================================================

(defn register-container!
  "Register container functions map for a tile-id.
  fns-map keys: :get-size :get-item :set-item! :remove-item
                :remove-item-no-update :clear! :still-valid?
                :slots-for-face :can-place-through-face? :can-take-through-face?"
  [tile-id fns-map]
  (registry-core/swap-state! container-registry #(assoc % tile-id fns-map))
  (log/info "Registered container for tile" tile-id)
  nil)

(defn- container-fns [tile-id] (registry-core/lookup container-registry tile-id))

(defn- invoke-container-fn
  "Invoke a container function by key, applying args. Returns nil if function not found."
  [tile-id fn-key & args]
  (when-let [f (get (container-fns tile-id) fn-key)]
    (apply f args)))

(defn container-size [tile-id be]
  (or (invoke-container-fn tile-id :get-size be) 0))

(defn container-get-item [tile-id be slot]
  (invoke-container-fn tile-id :get-item be slot))

(defn container-set-item [tile-id be slot item]
  (invoke-container-fn tile-id :set-item! be slot item))

(defn container-remove-item [tile-id be slot amount]
  (invoke-container-fn tile-id :remove-item be slot amount))

(defn container-remove-item-no-update [tile-id be slot]
  (invoke-container-fn tile-id :remove-item-no-update be slot))

(defn container-clear [tile-id be]
  (invoke-container-fn tile-id :clear! be))

(defn container-still-valid [tile-id be player]
  (if-let [result (invoke-container-fn tile-id :still-valid? be player)]
    result
    true))

(defn container-slots-for-face [tile-id be face]
  (invoke-container-fn tile-id :slots-for-face be face))

(defn container-can-place-through-face [tile-id be slot item face]
  (boolean (invoke-container-fn tile-id :can-place-through-face? be slot item face)))

(defn container-can-take-through-face [tile-id be slot item face]
  (boolean (invoke-container-fn tile-id :can-take-through-face? be slot item face)))
