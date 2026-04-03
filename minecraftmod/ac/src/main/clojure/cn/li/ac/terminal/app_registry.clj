(ns cn.li.ac.terminal.app-registry
  "Simple atom-based registry for terminal applications.

  Apps are data maps with:
  - :id - Keyword identifier
  - :name - Display name
  - :icon - Texture path
  - :description - Short description
  - :gui-fn - Symbol pointing to GUI function
  - :available-fn - Optional predicate (fn [player] boolean)
  - :category - Optional category keyword"
  (:require [cn.li.mcmod.util.log :as log]))

;; ============================================================================
;; Registry
;; ============================================================================

(defonce app-registry
  (atom {}))

;; ============================================================================
;; Registration
;; ============================================================================

(defn register-app!
  "Register an app in the registry.

  App spec must include:
  - :id (keyword) - Unique identifier
  - :name (string) - Display name
  - :icon (string) - Texture path
  - :gui-fn (symbol) - Function to open GUI

  Optional fields:
  - :description (string) - Short description
  - :available-fn (fn) - Predicate to check availability
  - :category (keyword) - App category"
  [app-spec]
  (let [app-id (:id app-spec)]
    (when-not app-id
      (throw (ex-info "App must have an :id" {:spec app-spec})))
    (when-not (keyword? app-id)
      (throw (ex-info "App :id must be a keyword" {:id app-id})))
    (when-not (:name app-spec)
      (throw (ex-info "App must have a :name" {:id app-id})))
    (when-not (:icon app-spec)
      (throw (ex-info "App must have an :icon" {:id app-id})))
    (when-not (:gui-fn app-spec)
      (throw (ex-info "App must have a :gui-fn" {:id app-id})))

    (log/info "Registering terminal app:" app-id)
    (swap! app-registry assoc app-id app-spec)
    app-spec))

(defn unregister-app!
  "Unregister an app from the registry."
  [app-id]
  (log/info "Unregistering terminal app:" app-id)
  (swap! app-registry dissoc app-id))

;; ============================================================================
;; Access
;; ============================================================================

(defn get-app
  "Get an app by ID."
  [app-id]
  (get @app-registry app-id))

(defn list-all-apps
  "List all registered apps."
  []
  (vals @app-registry))

(defn list-app-ids
  "List all registered app IDs."
  []
  (keys @app-registry))

(defn app-count
  "Get the number of registered apps."
  []
  (count @app-registry))

;; ============================================================================
;; Filtering
;; ============================================================================

(defn list-available-apps
  "List apps available to a player (filtered by :available-fn)."
  [player]
  (filter (fn [app]
            (if-let [available-fn (:available-fn app)]
              (try
                (available-fn player)
                (catch Exception e
                  (log/error "Error checking app availability:" (:id app) (ex-message e))
                  false))
              true))
          (list-all-apps)))

(defn list-apps-by-category
  "List all apps in a specific category."
  [category]
  (filter #(= category (:category %))
          (list-all-apps)))

;; ============================================================================
;; App Launching
;; ============================================================================

(defn launch-app
  "Launch an app by calling its :gui-fn.
  Returns true if successful, false otherwise."
  [app-id player]
  (if-let [app (get-app app-id)]
    (try
      (let [gui-fn-sym (:gui-fn app)
            gui-fn (requiring-resolve gui-fn-sym)]
        (if gui-fn
          (do
            (log/info "Launching app:" app-id "for player:" player)
            (gui-fn player)
            true)
          (do
            (log/error "App GUI function not found:" gui-fn-sym)
            false)))
      (catch Exception e
        (log/error "Error launching app:" app-id (ex-message e))
        false))
    (do
      (log/error "App not found:" app-id)
      false)))

;; ============================================================================
;; Utilities
;; ============================================================================

(defn clear-registry!
  "Clear all registered apps. Used for testing."
  []
  (log/warn "Clearing terminal app registry")
  (reset! app-registry {}))

(defn get-registry-snapshot
  "Get a snapshot of the current registry state."
  []
  @app-registry)
