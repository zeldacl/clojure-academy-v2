(ns cn.li.mcmod.events.world-lifecycle
  "Platform-neutral world lifecycle event handlers registry.

  This allows ac code to register world load/unload handlers without
  forge/fabric code knowing about specific business logic (e.g., wireless networks).

  Platform code (forge/fabric) calls dispatch-world-load/unload.
  Business code (ac) registers handlers via register-world-lifecycle-handler!")

;; ============================================================================
;; Handler Registry
;; ============================================================================

(defonce ^:private world-load-handlers (atom []))
(defonce ^:private world-unload-handlers (atom []))
(defonce ^:private world-save-handlers (atom []))

;; ============================================================================
;; Registration API (called by ac code)
;; ============================================================================

(defn register-world-lifecycle-handler!
  "Register world lifecycle handlers.

  Args:
    handler-map: Map with optional keys:
      :on-load   - (fn [world saved-data] ...) called when world loads
      :on-unload - (fn [world] ...) called when world unloads
      :on-save   - (fn [world] saved-data) called before world saves

  Example:
    (register-world-lifecycle-handler!
      {:on-load   ac.wireless.world-data/on-world-load
       :on-unload ac.wireless.world-data/on-world-unload
       :on-save   ac.wireless.world-data/on-world-save})"
  [handler-map]
  (when-let [on-load (:on-load handler-map)]
    (swap! world-load-handlers conj on-load))
  (when-let [on-unload (:on-unload handler-map)]
    (swap! world-unload-handlers conj on-unload))
  (when-let [on-save (:on-save handler-map)]
    (swap! world-save-handlers conj on-save))
  nil)

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
  (doseq [handler @world-load-handlers]
    (try
      (handler world saved-data)
      (catch Throwable t
        (println "Error in world load handler:" (.getMessage t))
        (.printStackTrace t)))))

(defn dispatch-world-unload
  "Dispatch world unload event to all registered handlers.

  Args:
    world: World instance

  Called by platform code (forge/fabric) when world unloads."
  [world]
  (doseq [handler @world-unload-handlers]
    (try
      (handler world)
      (catch Throwable t
        (println "Error in world unload handler:" (.getMessage t))
        (.printStackTrace t)))))

(defn dispatch-world-save
  "Dispatch world save event to all registered handlers.

  Args:
    world: World instance

  Returns: Map of saved data from all handlers (for persistence)

  Called by platform code (forge/fabric) before world saves."
  [world]
  (reduce
    (fn [acc handler]
      (try
        (if-let [data (handler world)]
          (conj acc data)
          acc)
        (catch Throwable t
          (println "Error in world save handler:" (.getMessage t))
          (.printStackTrace t)
          acc)))
    []
    @world-save-handlers))
