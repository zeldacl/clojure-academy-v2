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

(defn register-item-action!
  "Map an item id (e.g. \"ac:coin\") to an action keyword (e.g. :railgun-coin-throw)."
  [item-id action-keyword]
  (swap! item-action-registry assoc item-id action-keyword)
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
  (swap! action-handlers assoc action-keyword handler-fn)
  nil)

(defn on-item-action!
  "Dispatch `action` to its registered handler.  Returns nil if no handler."
  [action player-uuid payload]
  (when-let [handler (get @action-handlers action)]
    (try
      (handler player-uuid payload)
      (catch Exception e
        (log/warn "Item action handler" action "failed:" (ex-message e))
        nil))))

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
    (swap! item-entity-spawns assoc item-id entity-spec)
    nil)

  (defn get-item-entity-spawn
    "Return the entity-spawn spec for `item-id`, or nil."
    [item-id]
    (get @item-entity-spawns item-id))
