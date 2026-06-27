(ns cn.li.mcmod.gui.container-state
  "Platform-neutral container lifecycle state for GUI infrastructure.

  The original design stored 4 accumulating maps in a global atom
  (active-containers, player-containers, menu-containers, containers-by-id).
  Only `menu → clj-container` was ever read in production; the other three
  were write-only.  Since each menu is a CMenuBridge proxy—created in the same
  scope as its clj-container—the container is now stored directly on the menu
  object via the `cljContainer` field.  The atom and its maps are removed.

  Reflection is used to access the field so mcmod does not need a
  compile-time dependency on mc-1.20.1 (where CMenuBridge lives).

  Remaining functionality:
  - get-menu-container-id  (platform protocol call, not stateful)
  - owner-from-container   (used by panel.clj / wireless GUIs)
  - clear-all!             (test teardown — no-op, kept for API compat)
  - Runtime binding infra  (kept so call-with-container-state-runtime still
    works for test isolation without breaking callers.)"
  (:require [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Runtime (kept for test binding compat — no longer holds state)
;; ============================================================================

(defn create-container-state-runtime
  []
  {::runtime ::container-state-runtime})

(def ^:dynamic *container-state-runtime* nil)

(defonce ^:private installed-container-state-runtime
  (create-container-state-runtime))

(defn installed-runtime
  "Return the production container-state runtime (now just a marker)."
  []
  installed-container-state-runtime)

(defn call-with-container-state-runtime
  [runtime f]
  (binding [*container-state-runtime* (or runtime (create-container-state-runtime))]
    (f)))

;; ============================================================================
;; Menu → container lookup (reflection-based field access — no global atom)
;; ============================================================================

(def ^:private clj-container-field
  "Lazily-resolved Field for CMenuBridge.cljContainer.  Reflection avoids a
   compile-time dependency on mc-1.20.1 (where CMenuBridge lives)."
  (delay
    (try
      (let [cls (Class/forName "cn.li.mc1201.gui.CMenuBridge")]
        (.setAccessible (.getField cls "cljContainer") true))
      (catch Exception _
        nil))))

(defn get-container-for-menu
  "Return the Clojure container backing a Minecraft menu instance.
   Reads the `cljContainer` field via reflection — no global map, no memory leak."
  [menu]
  (when-let [field @clj-container-field]
    (try
      (.get field menu)
      (catch Exception _ nil))))

(defn resolve-container-for-menu
  "Resolve a Clojure container for a menu.
   (Fallback path removed since containers-by-id was write-only.)"
  [menu]
  (get-container-for-menu menu))

;; ============================================================================
;; Menu → container-id (platform protocol — not stateful)
;; ============================================================================

(defn get-menu-container-id
  "Get a Minecraft menu/container window id via platform protocol."
  [menu]
  (when menu
    (try
      (entity/menu-get-container-id menu)
      (catch Exception _ nil))))

;; ============================================================================
;; owner-from-container (used by panel.clj and wireless GUIs)
;; ============================================================================

(defn- player-key
  [player]
  (when player
    (some-> (entity/player-get-uuid player) str)))

(defn owner-from-container
  "Resolve an explicit owner map from a Clojure container."
  [container]
  (let [owner (or (:owner container)
                  (select-keys container [:server-session-id :client-session-id :player-uuid :player :owner :logical-side]))
        player (:player owner)
        player-id (or (:player-uuid owner)
                      (:player-uuid container)
                      (some-> player player-key))]
    (cond-> owner
      player (assoc :player player)
      player-id (assoc :player-uuid player-id))))

;; ============================================================================
;; No-op stubs (kept for API compatibility with existing callers)
;; ============================================================================

(defn register-active-container! [_owner _container] nil)
(defn unregister-active-container! [_owner _container] nil)

(defn register-player-container! [_owner _container] nil)
(defn unregister-player-container!
  ([_owner] nil)
  ([_owner _container] nil))

(defn register-menu-container! [menu container]
  (when-let [field @clj-container-field]
    (try (.set field menu container) (catch Exception _ nil)))
  nil)

(defn unregister-menu-container! [menu]
  (when-let [field @clj-container-field]
    (try (.set field menu nil) (catch Exception _ nil)))
  nil)

(defn register-container-by-id! [_owner _container-id _container] nil)
(defn unregister-container-by-id! [_owner _container-id] nil)

;; ============================================================================
;; Query stubs (no longer backed by atom — return empty/nil)
;; ============================================================================

(defn list-active-containers
  ([]
   [])
  ([_owner]
   []))

(defn get-player-container [_owner] nil)
(defn get-player-containers [_owner] [])
(defn get-player-container-from-active [_owner] nil)
(defn get-container-by-id [_owner _container-id] nil)

(defn container-state-snapshot
  "Return empty snapshot for tests/diagnostics.
   Production state was a memory leak; removed."
  []
  {:active-containers {}
   :player-containers {}
   :menu-containers {}
   :containers-by-id {}})

;; ============================================================================
;; Cleanup (test teardown — no state to clear)
;; ============================================================================

(defn clear-all! "No-op — state was removed. Kept for API compat." [] nil)
(defn clear-owner-containers! [_owner] nil)
(defn clear-session-containers! [_session-id] nil)
