(ns cn.li.presentation.core.animation
  "Deterministic, allocation-light timeline sampling in Clojure.

   Timelines are immutable data. A controller may retain the returned clock
   atom, while sampling never invokes arbitrary user code or backend methods."
  (:import [java.lang Math]))

(def easing
  {:linear identity
   :ease-in (fn [t] (* t t))
   :ease-out (fn [t] (- 1.0 (* (- 1.0 t) (- 1.0 t))))
   :ease-in-out (fn [t]
                  (if (< t 0.5)
                    (* 2.0 t t)
                    (- 1.0 (* -2.0 (dec t) (dec t)))))} )

(defn timeline
  "Create a timeline clock from duration, loop count and keyframe tracks."
  [{:keys [duration-ms loops tracks] :or {duration-ms 0 loops 1 tracks {}}}]
  (when (neg? (long duration-ms))
    (throw (ex-info "timeline duration must be non-negative" {:duration-ms duration-ms})))
  {:duration-ms (long duration-ms)
   :loops (if (= loops :infinite) :infinite (max 1 (long loops)))
   :tracks (into {} (map (fn [[property frames]]
                           [property (vec (sort-by :at frames))]) tracks))
   :time-ms (atom 0)
   :done? (atom false)})

(defn advance!
  [timeline delta-ms]
  (let [duration (:duration-ms timeline)
        old @(:time-ms timeline)
        next-time (if (pos? duration) (+ old (max 0 (long delta-ms))) 0)
        max-time (if (= :infinite (:loops timeline)) Long/MAX_VALUE
                     (* duration (:loops timeline)))]
    (reset! (:time-ms timeline) (min next-time max-time))
    (reset! (:done? timeline) (and (not= :infinite (:loops timeline))
                                   (>= next-time max-time)))
    timeline))

(declare done?)

(defn- local-time [timeline]
  (let [duration (:duration-ms timeline)
        time @(:time-ms timeline)]
    (cond
      (zero? duration) 0
      (done? timeline) duration
      :else (mod time duration))))

(defn- interpolate [a b t]
  (cond
    (and (number? a) (number? b)) (+ (double a) (* (- (double b) (double a)) t))
    (= a b) a
    :else (if (< t 1.0) a b)))

(defn sample-track [frames time-ms]
  (let [frames (vec frames)]
    (cond
      (empty? frames) nil
      (<= (double time-ms) (double (:at (first frames)))) (:value (first frames))
      (>= (double time-ms) (double (:at (last frames)))) (:value (last frames))
      :else
      (let [pair (first (filter (fn [[a b]]
                                  (and (<= (:at a) time-ms)
                                       (<= time-ms (:at b))))
                                (partition 2 1 frames)))]
        (if-not pair
          (:value (last frames))
          (let [[left right] pair
                span (double (- (:at right) (:at left)))
                raw (if (pos? span) (/ (- (double time-ms) (:at left)) span) 0.0)
                ease-fn (get easing (or (:easing right) :linear) identity)
                eased (double (max 0.0 (min 1.0 (ease-fn raw))))]
            (interpolate (:value left) (:value right) eased)))))))

(defn sample [timeline]
  (let [time (local-time timeline)]
    (into {} (map (fn [[property frames]] [property (sample-track frames time)])
                  (:tracks timeline)))))

(defn done? [timeline] (boolean @(:done? timeline)))
