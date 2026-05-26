(ns cn.li.mcmod.events.world-lifecycle
  "Platform-neutral world lifecycle event handlers registry.

  This allows content code to register world load/unload handlers without
  forge/fabric code knowing about specific business logic.

  Platform code (forge/fabric) calls dispatch-world-load/unload.
  Content code registers handlers via register-world-lifecycle-handler!")

(defn- report-handler-error!
  "Log a handler failure to `*err*` (wraps a bare `Writer` in `PrintWriter` for tests)."
  [phase ^Throwable t]
  (let [^java.io.Writer err *err*
        ^java.io.PrintWriter pw (if (instance? java.io.PrintWriter err)
                                    ^java.io.PrintWriter err
                                    (java.io.PrintWriter. err))]
    (.println pw (str "Error in world " (name phase) " handler: " (ex-message t)))
    (.printStackTrace t pw)))

;; ============================================================================
;; Handler Registry
;; ============================================================================

(defonce ^:private world-load-handlers (atom []))
(defonce ^:private world-unload-handlers (atom []))
(defonce ^:private world-save-handlers (atom []))
(defonce ^:private world-tick-handlers (atom []))
(defonce ^:private world-lifecycle-frozen? (atom false))

(defn- assert-not-frozen!
  []
  (when @world-lifecycle-frozen?
    (throw (ex-info "World lifecycle handlers are frozen" {}))))

(defn- handler-id
  [handler-map]
  (or (:id handler-map) handler-map))

(defn- register-handler-entry!
  [handlers-atom id handler-fn]
  (swap! handlers-atom
         (fn [entries]
           (if-let [existing (first (filter #(= id (:id %)) entries))]
             (if (identical? handler-fn (:fn existing))
               entries
               (throw (ex-info "Conflicting world lifecycle handler id"
                               {:id id})))
             (conj entries {:id id :fn handler-fn}))))
  nil)

;; ============================================================================
;; Registration API (called by content code)
;; ============================================================================

(defn register-world-lifecycle-handler!
  "Register world lifecycle handlers.

  Args:
    handler-map: Map with optional keys:
      :on-load   - (fn [world saved-data] ...) called when world loads
      :on-unload - (fn [world] ...) called when world unloads
      :on-save   - (fn [world] saved-data) called before world saves
      :on-tick   - (fn [world] ...) called each world tick (server side)

  Example:
    (register-world-lifecycle-handler!
      {:on-load   my.content.world-data/on-world-load
       :on-unload my.content.world-data/on-world-unload
       :on-save   my.content.world-data/on-world-save})"
  [handler-map]
  (assert-not-frozen!)
  (let [id (handler-id handler-map)]
  (when-let [on-load (:on-load handler-map)]
    (register-handler-entry! world-load-handlers id on-load))
  (when-let [on-unload (:on-unload handler-map)]
    (register-handler-entry! world-unload-handlers id on-unload))
  (when-let [on-save (:on-save handler-map)]
    (register-handler-entry! world-save-handlers id on-save))
  (when-let [on-tick (:on-tick handler-map)]
    (register-handler-entry! world-tick-handlers id on-tick)))
  nil)

(defn freeze-world-lifecycle-handlers!
  "Freeze static lifecycle handler registration after bootstrap."
  []
  (reset! world-lifecycle-frozen? true)
  nil)

(defn reset-world-lifecycle-handlers-for-test!
  "Reset lifecycle handler registry. Intended for tests/reloads only."
  []
  (reset! world-load-handlers [])
  (reset! world-unload-handlers [])
  (reset! world-save-handlers [])
  (reset! world-tick-handlers [])
  (reset! world-lifecycle-frozen? false)
  nil)

(defn lifecycle-handlers-snapshot
  []
  {:load @world-load-handlers
   :unload @world-unload-handlers
   :save @world-save-handlers
   :tick @world-tick-handlers
   :frozen? @world-lifecycle-frozen?})

;; ============================================================================
;; Dispatch API (called by platform code)
;; ============================================================================

(defn dispatch-world-load
  "Dispatch world load event to all registered handlers.

  Args:
    world: World instance
    saved-data: Saved data from previous session (may be nil)

  Called by platform code (forge/fabric) when world loads."
  [world saved-data]
  (doseq [{handler :fn} @world-load-handlers]
    (try
      (handler world saved-data)
      (catch Throwable t
        (report-handler-error! :load t)))))

(defn dispatch-world-unload
  "Dispatch world unload event to all registered handlers.

  Args:
    world: World instance

  Called by platform code (forge/fabric) when world unloads."
  [world]
  (doseq [{handler :fn} @world-unload-handlers]
    (try
      (handler world)
      (catch Throwable t
        (report-handler-error! :unload t)))))

(defn dispatch-world-save
  "Dispatch world save event to all registered handlers.

  Args:
    world: World instance

  Returns: Map of saved data from all handlers (for persistence)

  Called by platform code (forge/fabric) before world saves."
  [world]
  (reduce
    (fn [acc {handler :fn}]
      (try
        (if-let [data (handler world)]
          (conj acc data)
          acc)
        (catch Throwable t
          (report-handler-error! :save t)
          acc)))
    []
    @world-save-handlers))

(defn dispatch-world-tick
  "Dispatch world tick event to all registered handlers.

  Args:
    world: World instance

  Called by platform code (forge/fabric) each server world tick."
  [world]
  (doseq [{handler :fn} @world-tick-handlers]
    (try
      (handler world)
      (catch Throwable t
        (report-handler-error! :tick t)))))
