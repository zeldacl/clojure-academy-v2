(ns cn.li.ability.client-vfx-v2
  "VFX cutover: client composition root for the NEW vfx-core engine
   (cn.li.vfx.api, backed by cn.li.vfx.runtime + cn.li.vfx.frame),
   parallel to cn.li.ability.client-vfx (the old engine's own composition
   root, which this namespace does not replace -- client_vfx_test.clj
   calls its dispatch-signal!/register-catalog!/runtime directly and
   asserts old-engine-specific instance-map shapes, so it stays fully
   intact, mirroring how dispatch-intent! stayed untouched through the
   combat cutover -- see NODE_LANGUAGE.md's own \"two engines\" framing,
   now extended to VFX).

   Design differences from client-vfx, deliberate, not an oversight: the
   new engine's registry is an IMMUTABLE map built once at runtime-
   creation time (cn.li.vfx.runtime/create-client-runtime), not
   incrementally registered/frozen -- there is exactly one real catalog
   (AC's own ac/vfx-v4/*.edn), so register-catalog!/freeze!'s whole
   multi-tenant-collision-guard concern does not apply here; create-
   runtime below takes a :catalog-compile zero-arg fn instead (mirroring
   cn.li.ability.engine-v2's own create-runtime contract).

   Screen-flash/camera-fov/camera-pitch side-channel tracking is
   DUPLICATED from client-vfx rather than shared: it is small, genuinely
   engine-agnostic utility state that never touches instance/dispatch
   internals, and duplicating it here is a much smaller, safer footprint
   than building a shared dependency between a namespace several tests
   exercise directly and one that does not exist yet."
  (:require [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.runtime.vfx-contract :as contract]
            [cn.li.vfx.api :as core])
  (:import [java.util ArrayDeque]))

(defonce ^:private runtime* (atom nil))
(defonce ^:private screen-flashes* (atom {}))
(defonce ^:private camera-fov-targets* (atom {}))
(defonce ^:private camera-fov-eased* (atom {}))
(defonce ^:private local-walk-speed-targets* (atom {}))
(defonce ^:private preview-runtime* (atom nil))
(defonce ^:private production-options* (atom nil))
(defonce ^:private loop-audio-keys* (atom {}))

(defonce ^:private camera-pitch* (ArrayDeque. 1024))

(defn create-runtime
  [{:keys [catalog-compile max-frames] :or {max-frames 8}}]
  (when-not (ifn? catalog-compile)
    (throw (ex-info "client-vfx-v2 requires catalog-compile" {})))
  (core/create-runtime (catalog-compile) {:max-frames max-frames}))

(defn install-production! [options]
  (reset! production-options* options)
  (reset! runtime* (create-runtime options))
  (reset! preview-runtime* nil))

(defn runtime [] @runtime*)

(defn- preview-runtime []
  (or @preview-runtime*
      (when-let [production @runtime*]
        (let [preview (core/create-runtime (:registry production)
                                           {:max-frames 2})]
          (if (compare-and-set! preview-runtime* nil preview)
            preview
            @preview-runtime*)))))

(defn start-preview!
  "Run one isolated editor preview instance. It never enters the production
   runtime or production VFX frame pool."
  [effect-id params]
  (let [rt (or (preview-runtime)
               (throw (ex-info "production VFX runtime is not installed" {})))
        owner "editor-preview"
        key [:editor-preview effect-id]]
    (core/dispatch-signal! rt
                           (contract/signal
                            {:op :spawn :effect-id effect-id :owner owner
                             :instance-key key :event-seq 1 :state-seq 1
                             :params (or params {})}))
    key))

(defn tick-preview! [delta-seconds]
  (when-let [rt @preview-runtime*]
    (core/tick! rt (double (or delta-seconds 0.05))))
  nil)

(defn sample-preview-frame! []
  (when-let [rt @preview-runtime*]
    (:java-frame (core/sample-frame! rt))))

(defn stop-preview! []
  (when-let [rt @preview-runtime*]
    (core/clear-owner! rt "editor-preview"))
  (reset! preview-runtime* nil)
  nil)

(defn- update-presentation-sidechannels! [signal]
  (let [owner (some-> (:owner signal) str)
        params (or (:params signal) {})
        instance (or (:instance-key signal)
                     (:instance-id signal)
                     [:effect (:effect-id signal)])]
    (when owner
      ;; Several legacy level effects (Jet Engine, Meltdowner and Thunder
      ;; Clap) expose the same owner-local movement slowdown while their
      ;; primary VFX uses a different effect id.  Keep this transport-neutral:
      ;; any V4 signal may carry an explicit :local-walk-speed field and the
      ;; side channel follows that signal's lifecycle.  A missing field means
      ;; "leave the current value alone"; an explicit nil clears it.
      (when (#{:destroy :release :clear-owner} (:op signal))
        (swap! local-walk-speed-targets*
               (fn [targets]
                 (if (= :clear-owner (:op signal))
                   (dissoc targets owner)
                   (let [entries (dissoc (get targets owner {}) instance)]
                     (if (seq entries)
                       (assoc targets owner entries)
                       (dissoc targets owner)))))))
      (when (contains? params :local-walk-speed)
        (let [speed (:local-walk-speed params)]
          (if (number? speed)
            (swap! local-walk-speed-targets*
                   update owner (fnil assoc {}) instance (double speed))
            (swap! local-walk-speed-targets*
                   (fn [targets]
                     (if (= :clear-owner (:op signal))
                       (dissoc targets owner)
                       (let [entries (dissoc (get targets owner {}) instance)]
                         (if (seq entries)
                           (assoc targets owner entries)
                           (dissoc targets owner)))))))))
      (when (= :screen-flash-session (:effect-id signal))
        (case (:op signal)
          (:spawn :update :trigger :snapshot)
          (swap! screen-flashes* assoc owner
                {:alpha (double (or (:alpha params) 0.0))
                 :remaining-ticks (long (or (:duration-ticks params) 1))})
          (:destroy :release :clear-owner) (swap! screen-flashes* dissoc owner)
          nil))
      (when (= :camera-fov-session (:effect-id signal))
        (case (:op signal)
          (:spawn :update :trigger :snapshot)
          (swap! camera-fov-targets* assoc owner
                (double (or (:offset params) 0.0)))
          (:destroy :release :clear-owner) (swap! camera-fov-targets* dissoc owner)
          nil))
      ;; Blood Retrograde's charge is a presentation side-channel rather than
      ;; geometry: the old client effect slowed only the owning player's walk
      ;; speed while charging. Keep the owner/instance lifecycle in the V4
      ;; dispatcher and expose the resolved value through the neutral host ABI.
      (when (= :blood-retrograde-charge (:effect-id signal))
        (case (:op signal)
          (:spawn :update :trigger :snapshot)
          (swap! local-walk-speed-targets*
                 update owner (fnil assoc {}) instance
                 (double (or (:speed params) 0.1)))
          (:destroy :release :clear-owner)
          (swap! local-walk-speed-targets*
                 (fn [targets]
                   (if (= :clear-owner (:op signal))
                     (dissoc targets owner)
                     (let [entries (dissoc (get targets owner {}) instance)]
                       (if (seq entries)
                         (assoc targets owner entries)
                         (dissoc targets owner))))))
          nil)))))

(defn- loop-audio-key [signal]
  (or (:instance-key signal) (:instance-id signal)))

(defn- stop-loop-audio! [key]
  (when (and key (client-bridge/operation-installed? :run-client-effect))
    (client-bridge/run-client-effect!
     :mcmod/stop-loop-sound {:key (pr-str key)}))
  nil)

(defn- update-loop-audio-lifecycle! [signal]
  (when (= :audio-loop-session (:effect-id signal))
    (let [owner (some-> (:owner signal) str)
          key (loop-audio-key signal)]
      (case (:op signal)
        (:spawn :update :trigger :snapshot)
        (when (and owner key)
          (swap! loop-audio-keys* update owner (fnil conj #{}) key))

        (:destroy :release)
        (do
          (stop-loop-audio! key)
          (when (and owner key)
            (swap! loop-audio-keys*
                   (fn [owners]
                     (let [keys (disj (get owners owner #{}) key)]
                       (if (seq keys)
                         (assoc owners owner keys)
                         (dissoc owners owner)))))))
        nil))))

(defn dispatch-signal! [signal]
  (let [signal (contract/signal signal)]
    (update-presentation-sidechannels! signal)
    (update-loop-audio-lifecycle! signal)
    (core/dispatch-signal! (runtime) signal))
  nil)

(defn clear-owner! [owner]
  (let [owner (str owner)]
    (doseq [key (get @loop-audio-keys* owner)]
      (stop-loop-audio! key))
    (swap! loop-audio-keys* dissoc owner)
    (swap! screen-flashes* dissoc owner)
    (swap! camera-fov-targets* dissoc owner)
    (swap! camera-fov-eased* dissoc owner)
    (swap! local-walk-speed-targets* dissoc owner)
    (core/clear-owner! (runtime) owner))
  nil)

(defn active? [] (pos? (count @(:instances (runtime)))))

(defn tick!
  ([] (tick! {:tick-id (quot (System/currentTimeMillis) 50) :delta-seconds 0.05}))
  ([context]
   (swap! screen-flashes*
         (fn [states]
           (into {}
                 (keep (fn [[owner state]]
                        (let [remaining (dec (long (or (:remaining-ticks state) 0)))]
                          (when (pos? remaining)
                            [owner (assoc state :remaining-ticks remaining)])))
                       states))))
   (core/tick! (runtime) (double (or (:delta-seconds context) 0.05)))
   nil))

(defn sample-frame! [_context] (core/sample-frame! (runtime)))
(defn sample-java-frame! [context] (:java-frame (sample-frame! context)))
(defn frame-stage [frame-id stage] (core/frame-stage (runtime) frame-id stage))
(defn latest-frame-stage [stage] (core/latest-frame-stage (runtime) stage))
(defn release-frame! [frame-id] (core/release-frame! (runtime) frame-id))
(defn clear-world! [world-id] (core/clear-world! (runtime) world-id))
(defn reload-resources! [generation] (core/reload-resources! (runtime) generation))
(defn registered-effects [] (core/registered-effects (runtime)))

(defn effect-state-snapshot [owner effect-id]
  (when-let [instance-key (core/instance-for-owner (runtime) effect-id (str owner))]
    (let [user (:user (get @(:instances (runtime)) instance-key))]
      {:effect-state {instance-key user} :fx-state {instance-key user}})))

(defn screen-flash-alpha [owner]
  (double (or (:alpha (get @screen-flashes* (str owner))) 0.0)))

(defn current-fov-offset [player-uuid]
  (let [owner (some-> player-uuid str)
        target (double (or (get @camera-fov-targets* owner) 0.0))]
    (if owner
      (double (or (get (swap! camera-fov-eased*
                             (fn [values]
                               (let [current (double (or (get values owner) 0.0))
                                     value (+ (* current 0.88) (* target 0.12))]
                                 (if (and (zero? target) (< (Math/abs value) 1.0e-4))
                                   (dissoc values owner)
                                   (assoc values owner value)))))
                owner)
            0.0))
      0.0)))

(defn local-walk-speed [player-uuid]
  "Return the slowest owner-local walk-speed override, or nil when no V4 effect owns it."
  (when-let [owner (some-> player-uuid str)]
    (when-let [speeds (seq (get @local-walk-speed-targets* owner))]
      (reduce min (map val speeds)))))

(defn add-camera-pitch-delta!
  [owner delta]
  (when-not owner
    (throw (ex-info "camera pitch delta requires an owner" {})))
  (when (< (.size ^ArrayDeque camera-pitch*) 1024)
    (.addLast ^ArrayDeque camera-pitch* [(str owner) (float delta)]))
  nil)

(defn drain-camera-pitch-deltas!
  [owner]
  (when-not owner
    (throw (ex-info "camera pitch drain requires an owner" {})))
  (let [owner (str owner) out (transient []) remaining (ArrayDeque. 1024)]
    (loop []
      (when-let [entry (.pollFirst ^ArrayDeque camera-pitch*)]
        (if (= owner (first entry))
          (conj! out (second entry))
          (.addLast ^ArrayDeque remaining entry))
        (recur)))
    (doseq [entry remaining] (.addLast ^ArrayDeque camera-pitch* entry))
    (persistent! out)))

(defn required-anchors [] #{:camera :local-player :world})
(defn resource-snapshot []
  {:generation (core/resource-generation (runtime))
   :effects (vec (sort (map name (registered-effects))))})

(defn vfx-host-api []
  (contract/validate-host-api
   {:schema-version contract/schema-version
    :required-anchors required-anchors
    :tick! tick!
    :sample-frame! sample-frame!
    :sample-java-frame! sample-java-frame!
    :frame-stage frame-stage
    :latest-frame-stage latest-frame-stage
    :release-frame! release-frame!
    :clear-world! clear-world!
    :resource-snapshot resource-snapshot
    :reload-resources! reload-resources!
    :active? active?
    :fov-offset current-fov-offset
    :local-walk-speed local-walk-speed
    :drain-camera-pitch-deltas! drain-camera-pitch-deltas!}))

(defn reset-for-test! []
  (reset! runtime* nil)
  (reset! preview-runtime* nil)
  (reset! production-options* nil)
  (reset! screen-flashes* {})
  (reset! camera-fov-targets* {})
  (reset! camera-fov-eased* {})
  (reset! local-walk-speed-targets* {})
  (reset! loop-audio-keys* {})
  (.clear ^ArrayDeque camera-pitch*)
  nil)

