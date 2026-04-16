(ns cn.li.ac.ability.client.hand-effects
  "Pure client-side hand effect state for first-person ability animations."
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(def ^:private directed-shock-sound "my_mod:vecmanip.directed_shock")
(def ^:private prepare-duration-ms 150.0)
(def ^:private punch-duration-ms 300.0)
(def ^:private groundshock-perform-step 3.4)
(def ^:private groundshock-perform-ticks 4)

(defonce ^:private directed-shock-effect (atom nil))
(defonce ^:private groundshock-effect (atom nil))
(defonce ^:private camera-pitch-deltas (atom []))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- enqueue-camera-pitch-delta! [delta]
  (swap! camera-pitch-deltas conj (double delta)))

(defn consume-camera-pitch-deltas! []
  (let [deltas @camera-pitch-deltas]
    (reset! camera-pitch-deltas [])
    deltas))

(defn- clamp01 [x]
  (max 0.0 (min 1.0 (double x))))

(defn- smoothstep [t]
  (let [x (clamp01 t)]
    (* x x (- 3.0 (* 2.0 x)))))

(defn- sample-curve [points t]
  (let [pairs (partition 2 1 points)
        x (clamp01 t)]
    (cond
      (<= x (ffirst points)) (double (second (first points)))
      (>= x (first (last points))) (double (second (last points)))
      :else
      (let [[[x0 y0] [x1 y1]] (or (some (fn [[[ax _ :as p0] [bx _ :as p1]]]
                                           (when (and (<= ax x) (<= x bx))
                                             [p0 p1]))
                                         pairs)
                                      [(first points) (last points)])
            local-t (if (= x0 x1) 1.0 (/ (- x x0) (- x1 x0)))
            eased (smoothstep local-t)]
        (+ (double y0) (* (- (double y1) (double y0)) eased))))))

(defn- prepare-transform [progress]
  {:tx (sample-curve [[0.0 0.0] [1.0 -0.02]] progress)
   :ty (sample-curve [[0.0 0.0] [0.5 0.2] [1.0 0.4]] progress)
   :tz (sample-curve [[0.0 0.0] [1.0 -0.05]] progress)
   :rot-x (sample-curve [[0.0 0.0] [1.0 -20.0]] progress)
   :rot-y 0.0
   :rot-z 0.0})

(defn- punch-transform [progress]
  {:tx (sample-curve [[0.0 -0.04] [0.5 -0.04] [1.0 0.0]] progress)
   :ty (sample-curve [[0.0 0.8] [0.5 0.75] [1.0 0.0]] progress)
   :tz (sample-curve [[0.0 0.0] [0.3 -0.4] [1.0 0.0]] progress)
   :rot-x (sample-curve [[0.0 -40.0] [0.5 -45.0] [1.0 0.0]] progress)
   :rot-y (sample-curve [[0.0 0.0] [0.3 10.0] [1.0 0.0]] progress)
   :rot-z 0.0})

(defn enqueue-hand-effect! [effect-id payload]
  (case effect-id
    :directed-shock
    (let [{:keys [mode performed?]} payload]
      (case mode
        :start
        (reset! directed-shock-effect {:stage :prepare
                                       :started-at (now-ms)})

        :perform
        (do
          (reset! directed-shock-effect {:stage :punch
                                         :started-at (now-ms)})
          (client-sounds/queue-sound-effect!
            {:type :sound
             :sound-id directed-shock-sound
             :volume 0.5
             :pitch 1.0}))

        :end
        (when-not performed?
          (reset! directed-shock-effect nil))

        nil))

    :groundshock
    (let [{:keys [mode charge-ticks performed?]} payload]
      (case mode
        :start
        (reset! groundshock-effect {:charge-ticks 0
                                    :perform-ticks 0
                                    :active? true})

        :update
        (swap! groundshock-effect
               (fn [state]
                 (let [prev (long (or (:charge-ticks state) 0))
                       next (long (max 0 (or charge-ticks 0)))]
                   (doseq [tick (range (inc prev) (inc next))]
                     (let [pitch-factor (cond
                                          (< tick 4) (/ tick 4.0)
                                          (<= tick 20) 1.0
                                          (<= tick 25) (- 1.0 (/ (- tick 20) 5.0))
                                          :else 0.0)]
                       (enqueue-camera-pitch-delta! (* -0.2 pitch-factor))))
                   {:charge-ticks next
                    :perform-ticks (long (or (:perform-ticks state) 0))
                    :active? true})))

        :perform
        (swap! groundshock-effect
               (fn [state]
                 {:charge-ticks (long (or (:charge-ticks state) 0))
                  :perform-ticks groundshock-perform-ticks
                  :active? false}))

        :end
        (when-not performed?
          (reset! groundshock-effect nil))

        nil))

    nil))

(defn current-directed-shock-transform []
  (when-let [{:keys [stage started-at]} @directed-shock-effect]
    (let [elapsed (- (now-ms) (long started-at))]
      (case stage
        :prepare
        (prepare-transform (min 1.0 (/ elapsed prepare-duration-ms)))

        :punch
        (let [progress (/ elapsed punch-duration-ms)]
          (if (>= progress 1.0)
            (do
              (reset! directed-shock-effect nil)
              nil)
            (punch-transform progress)))

        nil))))

(defn tick-hand-effects! []
  (when-let [{:keys [stage started-at]} @directed-shock-effect]
    (when (and (= stage :punch)
               (>= (- (now-ms) (long started-at)) punch-duration-ms))
      (reset! directed-shock-effect nil)))
  (swap! groundshock-effect
         (fn [state]
           (when state
             (let [remaining (long (or (:perform-ticks state) 0))]
               (when (pos? remaining)
                 (enqueue-camera-pitch-delta! groundshock-perform-step))
               (if (> remaining 1)
                 (assoc state :perform-ticks (dec remaining))
                 (when (or (:active? state) (pos? remaining))
                   (assoc state :perform-ticks 0))))))))