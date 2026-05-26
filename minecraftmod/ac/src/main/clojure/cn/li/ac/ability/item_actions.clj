(ns cn.li.ac.ability.item-actions
  "Registry-based item-action dispatch.

  Content skills register item-action handlers at load time.
  Platform code queries this registry to resolve and execute item actions.

  No Minecraft imports."
  (:require [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Item → action resolution
;; ============================================================================

(defonce ^:private item-action-registry
  ;; item-id (String) → action keyword
  (atom {}))

(defonce ^:private item-action-registries-frozen? (atom false))

(declare action-handlers item-entity-spawns)

(defn- assert-registries-open!
  []
  (when @item-action-registries-frozen?
    (throw (ex-info "Item action registries are frozen" {}))))

(defn item-action-registries-snapshot
  []
  {:item-actions @item-action-registry
   :action-handlers @action-handlers
   :item-entity-spawns @item-entity-spawns
   :frozen? @item-action-registries-frozen?})

(defn reset-item-action-registries-for-test!
  ([]
   (reset-item-action-registries-for-test! {}))
  ([{:keys [item-actions action-handlers item-entity-spawns frozen?]
     :or {item-actions {} action-handlers {} item-entity-spawns {} frozen? false}}]
   (reset! item-action-registry item-actions)
   (reset! cn.li.ac.ability.item-actions/action-handlers action-handlers)
   (reset! cn.li.ac.ability.item-actions/item-entity-spawns item-entity-spawns)
   (reset! item-action-registries-frozen? frozen?)
   nil))

(defn freeze-item-action-registries!
  []
  (reset! item-action-registries-frozen? true)
  nil)

(defn register-item-action!
  "Map an item id (e.g. \"ac:coin\") to an action keyword (e.g. :railgun-coin-throw)."
  [item-id action-keyword]
  (if-let [existing (get @item-action-registry item-id)]
    (when-not (= existing action-keyword)
      (throw (ex-info "Conflicting item action id"
                      {:item-id item-id :existing existing :new action-keyword})))
    (do
      (assert-registries-open!)
      (swap! item-action-registry assoc item-id action-keyword)))
  nil)

(defn resolve-item-action
  "Return the action keyword for `item-id`, or nil."
  [item-id]
  (get @item-action-registry item-id))

;; ============================================================================
;; Action → handler dispatch
;; ============================================================================

(defonce ^:private action-handlers
  ;; action-keyword → (fn [player-uuid payload])
  (atom {}))

(defn register-action-handler!
  "Register a handler fn for an action keyword."
  [action-keyword handler-fn]
  (when-not (contains? @action-handlers action-keyword)
    (assert-registries-open!)
    (swap! action-handlers assoc action-keyword handler-fn))
  nil)

;; ============================================================================
;; Item → entity-spawn spec
;; ============================================================================

(defonce ^:private item-entity-spawns
  ;; item-id (String) → {:entity-id String :speed double}
  (atom {}))

(defn register-item-entity-spawn!
  "Register a scripted-effect entity to spawn when `item-id` is used.
  `entity-spec` is a map with :entity-id (String) and optional :speed (double)."
  [item-id entity-spec]
  (if-let [existing (get @item-entity-spawns item-id)]
    (when-not (= existing entity-spec)
      (throw (ex-info "Conflicting item entity spawn id"
                      {:item-id item-id :existing existing :new entity-spec})))
    (do
      (assert-registries-open!)
      (swap! item-entity-spawns assoc item-id entity-spec)))
  nil)

(defn get-item-entity-spawn
  "Return the entity-spawn spec for `item-id`, or nil."
  [item-id]
  (get @item-entity-spawns item-id))

(defn on-item-action!
  "Dispatch `action` to its registered handler.  Returns nil if no handler."
  [action player-uuid payload]
  (when-let [handler (get @action-handlers action)]
    (try
      (handler player-uuid payload)
      (catch Exception e
        (log/warn "Item action handler" action "failed:" (ex-message e))
        nil))))

(defn reset-item-action-registries!
  "Clear all item-action registries. For REPL and clojure.test isolation only."
  []
  (reset-item-action-registries-for-test!))
