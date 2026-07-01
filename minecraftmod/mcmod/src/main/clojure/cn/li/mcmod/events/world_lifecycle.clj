(ns cn.li.mcmod.events.world-lifecycle
  "Platform-neutral world lifecycle event handlers registry.

  This allows content code to register world load/unload handlers without
  forge/fabric code knowing about specific business logic.

  Platform code (forge/fabric) calls dispatch-world-load/unload.
  Content code registers handlers via register-world-lifecycle-handler!

  State stored in Framework [:service :world-lifecycle]."
  (:require [cn.li.mcmod.framework :as fw]))

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
;; Handler Registry — stored in Framework [:service :world-lifecycle]
;; ============================================================================

(defn- default-state []
  {:load []
   :unload []
   :save []
   :tick []
   :frozen? false})

(def ^:private lifecycle-path [:service :world-lifecycle])

(defn- state-snapshot []
  (if-let [fw-atom fw/*framework*]
    (or (get-in @fw-atom lifecycle-path) (default-state))
    (default-state)))

(defn- update-state! [f & args]
  (when-let [fw-atom fw/*framework*]
    (swap! fw-atom update-in lifecycle-path
           (fn [current]
             (apply f (or current (default-state)) args))))
  nil)

(defn- assert-not-frozen!
  []
  (when (:frozen? (state-snapshot))
    (throw (ex-info "World lifecycle handlers are frozen" {}))))

(defn- handler-id
  [handler-map]
  (or (:id handler-map) handler-map))

(defn- register-handler-entry!
  [phase id handler-fn]
  (update-state!
    update phase
    (fn [entries]
      (if-let [existing (first (filter #(= id (:id %)) entries))]
        (if (identical? handler-fn (:fn existing))
          entries
          (throw (ex-info "Conflicting world lifecycle handler id"
                          {:id id})))
        (conj entries {:id id :fn handler-fn})))))

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
    (register-handler-entry! :load id on-load))
  (when-let [on-unload (:on-unload handler-map)]
    (register-handler-entry! :unload id on-unload))
  (when-let [on-save (:on-save handler-map)]
    (register-handler-entry! :save id on-save))
  (when-let [on-tick (:on-tick handler-map)]
    (register-handler-entry! :tick id on-tick)))
  nil)

(defn freeze-world-lifecycle-handlers!
  "Freeze static lifecycle handler registration after bootstrap."
  []
  (update-state! assoc :frozen? true)
  nil)

(defn reset-world-lifecycle-handlers-for-test!
  "Reset lifecycle handler registry. Intended for tests/reloads only."
  []
  (when-let [fw-atom fw/*framework*]
    (swap! fw-atom assoc-in lifecycle-path (default-state)))
  nil)

(defn lifecycle-handlers-snapshot
  []
  (state-snapshot))

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
  (let [handlers (:load (state-snapshot))
        handler-ids (into #{} (map #(get % :id) handlers))
        by-id? (and (map? saved-data)
                    (some #(contains? saved-data %) handler-ids))]
    (doseq [{:keys [id] handler-fn :fn} handlers]
      (try
        (handler-fn world (if by-id? (get saved-data id) saved-data))
        (catch Throwable t
          (report-handler-error! :load t))))))

(defn dispatch-world-unload
  "Dispatch world unload event to all registered handlers.

  Args:
    world: World instance

  Called by platform code (forge/fabric) when world unloads."
  [world]
  (doseq [{handler :fn} (:unload (state-snapshot))]
    (try
      (handler world)
      (catch Throwable t
        (report-handler-error! :unload t)))))

(defn dispatch-world-save
  "Dispatch world save event to all registered handlers.

  Args:
    world: World instance

  Returns: Map of handler-id -> saved data payload (for persistence)

  Called by platform code (forge/fabric) before world saves."
  [world]
  (reduce
    (fn [acc {:keys [id] handler-fn :fn}]
      (try
        (if-let [data (handler-fn world)]
          (assoc acc id data)
          acc)
        (catch Throwable t
          (report-handler-error! :save t)
          acc)))
    {}
            (:save (state-snapshot))))

(defn dispatch-world-tick
  "Dispatch world tick event to all registered handlers.

  Args:
    world: World instance

  Called by platform code (forge/fabric) each server world tick."
  [world]
  (doseq [{handler :fn} (:tick (state-snapshot))]
    (try
      (handler world)
      (catch Throwable t
        (report-handler-error! :tick t)))))
