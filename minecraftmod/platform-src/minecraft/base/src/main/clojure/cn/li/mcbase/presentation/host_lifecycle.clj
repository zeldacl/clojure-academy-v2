(ns cn.li.mcbase.presentation.host-lifecycle
  "Minecraft/base lifecycle seam for Presentation Runtime.

   This namespace intentionally knows no presentation-core classes. It owns
   callback registration and stage ordering; loader/version code supplies the
   concrete event callbacks and the Runtime API as opaque functions/maps.")

(def stages
  [:world-before-translucent
   :world-after-translucent
   :first-person
   :hud-underlay
   :hud
   :hud-overlay
   :screen
   :post-process])

(defn create []
  {:hosts (atom {})
   :callbacks (atom {})
   :resource-generation (atom 0)})

(defonce ^:private shared-lifecycle* (atom nil))

(defn shared
  "Return the process-wide lifecycle registry used by all loader callbacks.

   The value is intentionally an opaque Clojure map; no Presentation Core or
   Minecraft class crosses this base seam."
  []
  (or @shared-lifecycle*
      (let [value (create)]
        (or (compare-and-set! shared-lifecycle* nil value)
            @shared-lifecycle*))))

(defn register-host! [lifecycle host-id host-kind runtime-api]
  (when-not (keyword? host-id)
    (throw (ex-info "presentation host id must be a keyword" {:host-id host-id})))
  (swap! (:hosts lifecycle) assoc host-id
         {:id host-id :kind host-kind :runtime runtime-api})
  host-id)

(defn register-stage-callback! [lifecycle stage callback]
  (when-not (some #{stage} stages)
    (throw (ex-info "unknown presentation render stage" {:stage stage})))
  (swap! (:callbacks lifecycle) assoc stage callback)
  stage)

(defn dispatch-stage! [lifecycle stage frame-context]
  (when-let [callback (get @(:callbacks lifecycle) stage)]
    (callback frame-context)))

(defn host-api [lifecycle host-id]
  (get-in @(:hosts lifecycle) [host-id :runtime]))

(defn register-runtime! [lifecycle host-id host-kind runtime-api]
  "Register a neutral runtime API supplied by AC/content.

   This is the only platform-facing registration point for the new runtime;
   the API map may contain `:frame!`, `:mount!`, `:dispatch!`, and
   `:unmount-all!`, but no platform code may inspect its implementation."
  (when-not (map? runtime-api)
    (throw (ex-info "presentation runtime API must be a map" {:host-id host-id})))
  (register-host! lifecycle host-id host-kind runtime-api))

(defn frame! [lifecycle host-id frame-id delta-seconds width height]
  (when-let [frame-fn (:frame! (host-api lifecycle host-id))]
    (frame-fn frame-id delta-seconds width height)))

(defn dispatch-runtime-stage!
  "Extract the newest immutable frame for a stage callback.

   Submission remains version-owned: this function returns the opaque frame to
   the loader/backend seam and never invokes Minecraft or Presentation Core."
  [lifecycle host-id stage frame-id delta-seconds width height]
  (let [frame (frame! lifecycle host-id frame-id delta-seconds width height)]
    (when frame
      {:host-id host-id :stage stage :frame frame})))

(defn reload-resources! [lifecycle generation]
  (reset! (:resource-generation lifecycle) generation)
  (doseq [{:keys [runtime]} (vals @(:hosts lifecycle))]
    (when-let [reload! (:reload-resources! runtime)]
      (reload! generation)))
  generation)

(defn unregister-host! [lifecycle host-id]
  (when-let [entry (get @(:hosts lifecycle) host-id)]
    (when-let [unmount-all! (get-in entry [:runtime :unmount-all!])]
      (unmount-all!)))
  (swap! (:hosts lifecycle) dissoc host-id)
  nil)
