(ns cn.li.presentation.core.runtime-v2
  "Single-threaded retained Presentation Runtime v2.

   This namespace intentionally has no dependency on presentation-compiler or
   Minecraft. It owns mount state, host geometry, input routing, reducer
   commits, and effect ordering; rendering is supplied by later renderer code."
  (:require [cn.li.presentation.core.artifact :as artifact])
  (:import [cn.li.presentation.core HostGeometry MountHandle]))

(defrecord UiRuntime [state owner-thread])

(defn- owner-thread! [^UiRuntime runtime]
  (when-not (identical? (:owner-thread runtime) (Thread/currentThread))
    (throw (IllegalStateException.
            "Presentation Runtime must be accessed from its owner thread"))))

(defn create-runtime
  ([] (create-runtime {}))
  ([{:keys [owner-thread]}]
   (->UiRuntime (volatile! {:next-id 1
                          :mounts {}
                          :resource-epoch 0})
              (or owner-thread (Thread/currentThread)))))

(defn- runtime-state [^UiRuntime runtime] @(:state runtime))

(defn mount!
  [^UiRuntime runtime {:keys [host view-id artifact state reduce run-effect! close!]
                     :or {state {}
                          reduce (fn [state _action _payload]
                                   {:state state :effects [] :event-result :pass})}}]
  (owner-thread! runtime)
  (let [id (:next-id (runtime-state runtime))
        handle (MountHandle. (long id))
        artifact (or artifact (artifact/load-view view-id))
        instance {:handle handle
                  :host host
                  :view-id view-id
                  :artifact artifact
                  :view-state state
                  :reduce reduce
                  :run-effect! (or run-effect! (fn [_] nil))
                  :close! (or close! (fn [_] nil))
                  :geometry (HostGeometry/identity 0 0)
                  :dirty #{:structure :layout :paint :semantics}}]
    (vswap! (:state runtime)
            (fn [snapshot]
              (-> snapshot
                  (update :next-id inc)
                  (assoc-in [:mounts handle] instance))))
    handle))

(defn- instance! [^UiRuntime runtime mount]
  (or (get-in (runtime-state runtime) [:mounts mount])
      (throw (ex-info "unknown Presentation mount" {:mount mount}))))

(defn present!
  "Replace a mount's complete view state and mark dependent phases dirty." 
  [^UiRuntime runtime mount next-state]
  (owner-thread! runtime)
  (instance! runtime mount)
  (vswap! (:state runtime)
          (fn [snapshot]
            (-> snapshot
                (assoc-in [:mounts mount :view-state] next-state)
                (update-in [:mounts mount :dirty]
                           into #{:layout :paint :semantics}))))
  next-state)

(defn update-view! [^UiRuntime runtime mount f & args]
  (let [current (:view-state (instance! runtime mount))]
    (present! runtime mount (apply f current args))))

(defn update-host! [^UiRuntime runtime mount ^HostGeometry geometry]
  (owner-thread! runtime)
  (instance! runtime mount)
  (vswap! (:state runtime)
          (fn [snapshot]
            (-> snapshot
                (assoc-in [:mounts mount :geometry] geometry)
                (update-in [:mounts mount :dirty]
                           into #{:layout :paint :semantics}))))
  geometry)

(defn dispatch!
  "Run one already-routed action through the pure reducer, then effects." 
  [^UiRuntime runtime mount {:keys [action payload]}]
  (owner-thread! runtime)
  (let [instance (instance! runtime mount)
        response ((:reduce instance) (:view-state instance) action payload)
        next-state (if (contains? response :state)
                     (:state response)
                     (:view-state instance))
        effects (or (:effects response) [])
        result (or (:event-result response) :pass)]
    (present! runtime mount next-state)
    (doseq [effect effects]
      (try
        ((:run-effect! instance) effect)
        (catch Throwable error
          (binding [*out* *err*]
            (println "Presentation effect failed:" (pr-str effect) error)))))
    result))

(defn extract-stage!
  "Return a stage packet envelope. Render command extraction is supplied by
   the UI paint assembler; this function already provides stage filtering and
   mount geometry without guessed cross-stage frame coalescing." 
  [^UiRuntime runtime stage frame-context]
  (owner-thread! runtime)
  {:stage stage
   :frame-context frame-context
   :mounts (->> (:mounts (runtime-state runtime))
                vals
                (filter #(= stage (get-in % [:host :stage])))
                (mapv #(select-keys % [:handle :view-id :geometry :dirty])))} )

(defn semantics [^UiRuntime runtime mount]
  (get-in (instance! runtime mount) [:artifact :semantics]))

(defn unmount! [^UiRuntime runtime mount]
  (owner-thread! runtime)
  (when-let [instance (get-in (runtime-state runtime) [:mounts mount])]
    ((:close! instance) mount)
    (vswap! (:state runtime) update :mounts dissoc mount))
  nil)

(defn unmount-all! [^UiRuntime runtime]
  (owner-thread! runtime)
  (doseq [mount (keys (:mounts (runtime-state runtime)))]
    (unmount! runtime mount))
  nil)

(defn invalidate-render-resources! [^UiRuntime runtime]
  (owner-thread! runtime)
  (vswap! (:state runtime) update :resource-epoch inc)
  (:resource-epoch (runtime-state runtime)))