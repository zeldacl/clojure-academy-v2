(ns cn.li.ac.client.effect-controller
  "AC composition root for the final typed VFX catalog.

  This adapter owns no skill handlers, singleton aggregates, channels, or
  legacy descriptors. Every client instance is created and retired by the
  final VFX runtime from a stable `(effect-id, owner, instance-key)` signal;
  AC only supplies the catalog and presentation-facing side-channel reads."
  (:require [cn.li.mcmod.runtime.vfx-contract :as contract]
            [cn.li.vfx.final-client :as core])
  (:import [java.util ArrayDeque]))

(defonce ^:private runtime* (atom nil))
(defonce ^:private frozen?* (atom false))
(defonce ^:private screen-flashes* (atom {}))
(defonce ^:private camera-fov-targets* (atom {}))
(defonce ^:private camera-fov-eased* (atom {}))
(defonce ^:private camera-pitch* (ArrayDeque. 1024))

(defn runtime []
  (or @runtime*
      (let [created (core/create-runtime {:max-instances 2048 :max-batches 32768})]
        (if (compare-and-set! runtime* nil created) created @runtime*))))

(defn warmup! []
  (core/tick! (runtime) {:tick-id -1 :delta-seconds 0.0})
  (let [frame (core/sample-frame! (runtime) {:frame-id -1 :partial-tick 0.0})]
    (core/release-frame! (runtime) -1)
    (boolean frame)))

(defn register-catalog! [catalog]
  (doseq [[effect-id descriptor] (:effects catalog)]
    (when-not (contains? (core/registered-effects (runtime)) effect-id)
      (core/register-effect!
       (runtime)
       (assoc descriptor
              :id effect-id
              :init (fn [_] (or (:state-slots descriptor) {}))
              :update (fn [state {:keys [instance]}]
                        (let [duration (or (get-in instance [:params :duration-ticks])
                                           (get-in descriptor [:control-graph :duration-ticks]))]
                          (if (and (= :transient (:lifecycle descriptor))
                                   duration
                                   (>= (long (:age instance)) (long duration)))
                            nil
                            state)))))))
  nil)

(defn freeze! []
  (core/freeze-registry! (runtime))
  (reset! frozen?* true)
  nil)

(defn- update-presentation-sidechannels! [signal]
  (let [owner (some-> (:owner signal) str)
        params (or (:params signal) {})]
    (when owner
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
          nil)))))

(defn dispatch-signal! [signal]
  (let [signal (contract/signal signal)]
    (update-presentation-sidechannels! signal)
    (core/dispatch-signal! (runtime) signal))
  nil)

(defn clear-owner! [owner]
  (let [owner (str owner)]
    (swap! screen-flashes* dissoc owner)
    (swap! camera-fov-targets* dissoc owner)
    (swap! camera-fov-eased* dissoc owner)
    (core/clear-owner! (runtime) owner))
  nil)

(defn active? [] (pos? (count @(:instances (runtime)))))

(defn tick!
  ([] (tick! {:tick-id (quot (System/currentTimeMillis) 50)
              :delta-seconds 0.05}))
  ([context]
   (swap! screen-flashes*
          (fn [states]
            (into {}
                  (keep (fn [[owner state]]
                          (let [remaining (dec (long (or (:remaining-ticks state) 0)))]
                            (when (pos? remaining)
                              [owner (assoc state :remaining-ticks remaining)])))
                        states))))
   (core/tick! (runtime) (merge {:tick-id (quot (System/currentTimeMillis) 50)
                                 :delta-seconds 0.05} context))
   nil))

(defn sample-frame! [context]
  (core/sample-frame! (runtime) (merge {:partial-tick 0.0} context)))
(defn sample-java-frame! [context]
  (:java-frame (sample-frame! context)))
(defn frame-stage [frame-id stage] (core/frame-stage (runtime) frame-id stage))
(defn latest-frame-stage [stage] (core/latest-frame-stage (runtime) stage))
(defn release-frame! [frame-id] (core/release-frame! (runtime) frame-id))
(defn clear-world! [world-id] (core/clear-world! (runtime) world-id))
(defn reload-resources! [generation] (core/reload-resources! (runtime) generation))
(defn registered-effects [] (core/registered-effects (runtime)))

(defn effect-state-snapshot [owner effect-id]
  (when-let [instance-id (core/instance-for-owner (runtime) effect-id (str owner))]
    (let [instance (get @(:instances (runtime)) instance-id)]
      { :effect-state {(:instance-key instance) (:params instance)}
        :fx-state {(:instance-key instance) (:params instance)}})))

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

(defn add-camera-pitch-delta!
  [owner delta]
  (when-not owner
    (throw (ex-info "camera pitch delta requires an owner" {})))
  (when (< (.size ^ArrayDeque camera-pitch*) 1024)
    (.addLast ^ArrayDeque camera-pitch* [(str owner) (float delta)]))
  nil)

(defn drain-camera-pitch-deltas!
  ([owner]
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
     (persistent! out))))

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
    :drain-camera-pitch-deltas! drain-camera-pitch-deltas!}))

(defn reset-for-test! []
  (reset! runtime* nil)
  (reset! frozen?* false)
  (reset! screen-flashes* {})
  (reset! camera-fov-targets* {})
  (reset! camera-fov-eased* {})
  (.clear ^ArrayDeque camera-pitch*)
  nil)
