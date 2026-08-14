(ns cn.li.platform.neutral.vfx
  "Platform-neutral VFX seam; it forwards only mcmod ABI data."
  (:require [cn.li.platform.neutral.client-runtime :as client-runtime]))

(defonce ^:private render-frame* (atom 0))

(defn next-frame-id!
  "Allocate a monotonically increasing real-render-frame id.  Logical tick
   ids remain a separate field and are used only for fixed-step updates."
  []
  (swap! render-frame* inc))

(defn- host-api []
  (client-runtime/call-adapter :vfx-host-api))

(defn tick!
  "Advance the runtime once per logical game tick."
  [tick-context]
  (when-let [tick! (:tick! (host-api))]
    (tick! tick-context)))

(defn sample-frame!
  "Sample one immutable frame for the current render frame."
  [frame-context]
  (when-let [sample! (:sample-frame! (host-api))]
    (sample! frame-context)))

(defn level-plan!
  "Read the neutral world batch payload for a frame.  The platform never
   knows effect ids or runtime state; it only unwraps the mcmod Frame/Batch
   ABI produced by the opaque host." 
  [frame-context]
  (let [batches (get-in (sample-frame! (merge {:partial-tick 0.0} frame-context))
                        [:stages :world-after-translucent])
        plans (keep #(-> % :payload first) batches)
        ops (vec (mapcat #(or (:ops %) []) plans))
        walk-speed (reduce (fn [current plan]
                             (let [candidate (:local-walk-speed plan)]
                               (if (number? candidate)
                                 (if (number? current)
                                   (min (double current) (double candidate))
                                   (double candidate))
                                 current)))
                           nil plans)]
    {:ops ops
     :frame-id (:frame-id frame-context)
     :local-walk-speed (when (number? walk-speed) (float walk-speed))}))

(defn active? []
  (boolean (some-> (host-api) :active?)))

(defn fov-offset [player-uuid]
  (when-let [f (:fov-offset (host-api))]
    (f player-uuid)))

(defn hand-transform []
  (when-let [f (:hand-transform (host-api))]
    (f)))

(defn drain-camera-pitch-deltas! [owner]
  (when-let [f (:drain-camera-pitch-deltas! (host-api))]
    (f owner)))

(defn frame-stage
  [frame-id stage]
  (when-let [frame-stage! (:frame-stage (host-api))]
    (frame-stage! frame-id stage)))

(defn release-frame!
  [frame-id]
  (when-let [release! (:release-frame! (host-api))]
    (release! frame-id))
  nil)

(defn clear-world!
  [world-id]
  (when-let [clear! (:clear-world! (host-api))]
    (clear! world-id))
  nil)
