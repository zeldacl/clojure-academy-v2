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
  (:require [cn.li.mcmod.util.log :as log]
            [cn.li.ac.terminal.app-spi :as app-spi]))

;; ============================================================================
;; Registry
;; ============================================================================

(defn default-app-registry-runtime-state
  []
  {:apps {}
   :frozen? false})

(defn create-app-registry-runtime
  ([] (create-app-registry-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-app-registry-runtime-state))}}]
   {::runtime ::app-registry-runtime
    :state* state*}))

(defonce ^:private installed-app-registry-runtime
  (create-app-registry-runtime))

(def ^:dynamic *app-registry-runtime*
  installed-app-registry-runtime)

(defn call-with-app-registry-runtime
  [runtime f]
  (when-not (and (map? runtime)
                 (= ::app-registry-runtime (::runtime runtime))
                 (some? (:state* runtime)))
    (throw (ex-info "Expected app registry runtime" {:runtime runtime})))
  (binding [*app-registry-runtime* runtime]
    (f)))

(defn- current-app-registry-runtime
  []
  *app-registry-runtime*)

(defn- app-registry-state-atom
  []
  (:state* (current-app-registry-runtime)))

(defn- app-registry-state-snapshot
  []
  @(app-registry-state-atom))

(defn- update-app-registry-state!
  [f & args]
  (apply swap! (app-registry-state-atom) f args))

(defn- assert-app-registry-open!
  []
  (when (:frozen? (app-registry-state-snapshot))
    (throw (ex-info "Terminal app registry is frozen" {}))))

(defn app-registry-snapshot
  []
  (app-registry-state-snapshot))

(defn reset-app-registry-for-test!
  ([] (reset-app-registry-for-test! {}))
  ([{:keys [apps frozen?]
     :or {apps {} frozen? false}}]
   (reset! (app-registry-state-atom)
           {:apps apps
            :frozen? frozen?})
   nil))

(defn freeze-app-registry!
  []
  (update-app-registry-state! assoc :frozen? true)
  nil)

(defn- normalize-app
  [app-spec]
  (app-spi/normalize-app-spec app-spec))

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
  (let [app-spec (normalize-app app-spec)
        app-id (:id app-spec)]
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

    (if-let [existing (get (:apps (app-registry-state-snapshot)) app-id)]
      existing
      (do
        (assert-app-registry-open!)
        (log/info "Registering terminal app:" app-id)
        (update-app-registry-state! assoc-in [:apps app-id] app-spec)
        app-spec))))

(defn unregister-app!
  "Unregister an app from the registry."
  [app-id]
  (assert-app-registry-open!)
  (log/info "Unregistering terminal app:" app-id)
  (update-app-registry-state! update :apps dissoc app-id))

;; ============================================================================
;; Access
;; ============================================================================

(defn get-app
  "Get an app by ID."
  [app-id]
  (get (:apps (app-registry-state-snapshot)) app-id))

(defn list-all-apps
  "List all registered apps."
  []
  (vals (:apps (app-registry-state-snapshot))))

(defn list-app-ids
  "List all registered app IDs."
  []
  (keys (:apps (app-registry-state-snapshot))))

(defn app-count
  "Get the number of registered apps."
  []
  (count (:apps (app-registry-state-snapshot))))

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
            gui-fn (app-spi/app-launcher gui-fn-sym)]
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
  (reset-app-registry-for-test!))

(defn get-registry-snapshot
  "Get a snapshot of the current registry state."
  []
  (app-registry-state-snapshot))
