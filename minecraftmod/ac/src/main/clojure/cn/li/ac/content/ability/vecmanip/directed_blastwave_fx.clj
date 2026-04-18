(ns cn.li.ac.content.ability.vecmanip.directed-blastwave-fx
  "Client FX for Directed Blastwave: charge ring + expanding wave rings."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private effect-state (atom nil))
(defonce ^:private waves (atom []))
(def ^:private sound-id "my_mod:vecmanip.directed_blast")
(def ^:private wave-life 15)

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [payload]
  (let [{:keys [mode charge-ticks punched? pos look-dir performed?]} payload]
    (case mode
      :start
      (reset! effect-state {:active? true :charge-ticks 0 :punched? false :performed? false})
      :update
      (swap! effect-state
             (fn [st]
               (assoc (or st {})
                      :active? true
                      :charge-ticks (long (or charge-ticks 0))
                      :punched? (boolean punched?)
                      :performed? false)))
      :perform
      (do
        (when (map? pos)
          (let [dir (let [d (or look-dir {:x 0.0 :y 0.0 :z 1.0})
                          len (Math/sqrt (+ (* (:x d) (:x d))
                                            (* (:y d) (:y d))
                                            (* (:z d) (:z d))))
                          inv (/ 1.0 (max 1.0e-6 len))]
                      {:x (* (double (:x d)) inv)
                       :y (* (double (:y d)) inv)
                       :z (* (double (:z d)) inv)})
                rings (+ 2 (rand-int 2))]
            (swap! waves conj
                   {:pos {:x (double (:x pos)) :y (double (:y pos)) :z (double (:z pos))}
                    :dir dir
                    :ttl wave-life :max-ttl wave-life
                    :rings (vec (map (fn [idx]
                                       {:life (+ 8 (rand-int 5))
                                        :offset (+ (* idx 1.5) (- (* (rand) 0.6) 0.3))
                                        :size (* 1.0 (+ 0.8 (* (rand) 0.4)))
                                        :time-offset (+ (* idx 2) (- (rand-int 3) 1))})
                                     (range rings)))})))
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id sound-id :volume 0.5 :pitch 1.0
           :x (double (or (:x pos) 0.0))
           :y (double (or (:y pos) 0.0))
           :z (double (or (:z pos) 0.0))}))
      :end
      (reset! effect-state {:active? false :charge-ticks 0 :punched? false
                            :performed? (boolean performed?)})
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (swap! effect-state
         (fn [st]
           (when st
             (if (:active? st) st nil))))
  (swap! waves
         (fn [xs] (->> xs (map #(update % :ttl dec)) (filter #(pos? (long (:ttl %)))) vec))))

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- alpha-curve [t]
  (cond
    (< t 0.0) 0.0
    (< t 0.2) (/ t 0.2)
    (< t 0.8) 1.0
    (< t 1.0) (- 1.0 (/ (- t 0.8) 0.2))
    :else 0.0))

(defn- size-scale [ticks]
  (let [x (min 1.62 (max 0.0 (/ (double ticks) 20.0)))]
    (cond
      (< x 0.2) (+ 0.4 (* (/ x 0.2) (- 0.8 0.4)))
      (<= x 1.62) (+ 0.8 (* (/ (- x 0.2) (- 1.62 0.2)) (- 1.5 0.8)))
      :else 1.5)))

(defn- basis [dir]
  (let [n-dir (ru/vnormalize dir)
        up-axis (if (> (Math/abs (double (:y n-dir))) 0.95)
                  {:x 1.0 :y 0.0 :z 0.0}
                  {:x 0.0 :y 1.0 :z 0.0})
        right (ru/vnormalize (ru/vcross n-dir up-axis))
        up (ru/vnormalize (ru/vcross right n-dir))]
    [right up n-dir]))

(defn- wave-ops [{:keys [pos dir ttl max-ttl rings]}]
  (let [ticks (- (long max-ttl) (long ttl))
        max-alpha (alpha-curve (/ (double ticks) (double (max 1 max-ttl))))
        ss (size-scale ticks)
        [right up forward] (basis dir)
        z-offset (/ (double ticks) 40.0)]
    (vec
      (mapcat
        (fn [{:keys [life offset size time-offset]}]
          (let [local-t (/ (- (double ticks) (double time-offset)) (double (max 1 life)))
                alpha (alpha-curve local-t)
                real-alpha (min max-alpha alpha)]
            (if (<= real-alpha 0.0)
              []
              (let [center (ru/v+ pos (ru/v* forward (+ (double offset) z-offset)))
                    ring-size (* (double size) ss)
                    side (ru/v* right (* ring-size 0.5))
                    vertical (ru/v* up (* ring-size 0.5))
                    p0 (ru/v+ (ru/v- center side) vertical)
                    p1 (ru/v+ (ru/v+ center side) vertical)
                    p2 (ru/v- (ru/v+ center side) vertical)
                    p3 (ru/v- (ru/v- center side) vertical)
                    alpha-i (int (max 0 (min 255 (* 255.0 real-alpha 0.7))))]
                [(ru/quad-op "my_mod:textures/effects/glow_circle.png"
                             p0 p1 p2 p3
                             {:r 255 :g 255 :b 255 :a alpha-i})]))))
        rings))))

(defn- charge-ops [center charge-ticks punched?]
  (let [progress (min 1.0 (/ (double charge-ticks) 50.0))
        radius (+ 0.1 (* 0.16 progress))
        pulse (+ radius (* 0.025 (Math/sin (* 0.22 charge-ticks))))
        points 16
        alpha (if punched? 220 170)
        color {:r 225 :g 245 :b 255 :a alpha}
        core {:r 178 :g 220 :b 245 :a (int (* 0.7 alpha))}]
    (vec
      (mapcat
        (fn [idx]
          (let [a0 (/ (* 2.0 Math/PI idx) points)
                a1 (/ (* 2.0 Math/PI (inc idx)) points)
                p0 {:x (+ (:x center) (* pulse (Math/cos a0)))
                    :y (:y center)
                    :z (+ (:z center) (* pulse (Math/sin a0)))}
                p1 {:x (+ (:x center) (* pulse (Math/cos a1)))
                    :y (:y center)
                    :z (+ (:z center) (* pulse (Math/sin a1)))}]
            [(ru/line-op p0 p1 color)
             (ru/line-op center p0 core)]))
        (range points)))))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [_camera-pos hand-center-pos _tick]
  (let [db @effect-state
        current-waves @waves
        charge-plan (if (and hand-center-pos db (:active? db))
                      (charge-ops (dissoc hand-center-pos :player-uuid)
                                  (long (or (:charge-ticks db) 0))
                                  (boolean (:punched? db)))
                      [])
        wave-plan (mapcat wave-ops current-waves)]
    (when (or (seq charge-plan) (seq wave-plan))
      {:ops (vec (concat charge-plan wave-plan))})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(level-effects/register-level-effect! :directed-blastwave
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:directed-blastwave/fx-start :directed-blastwave/fx-update
   :directed-blastwave/fx-perform :directed-blastwave/fx-end]
  (fn [_ctx-id channel payload]
    (case channel
      :directed-blastwave/fx-start
      (level-effects/enqueue-level-effect! :directed-blastwave {:mode :start})
      :directed-blastwave/fx-update
      (level-effects/enqueue-level-effect! :directed-blastwave
        {:mode :update
         :charge-ticks (long (or (:charge-ticks payload) 0))
         :punched? (boolean (:punched? payload))})
      :directed-blastwave/fx-perform
      (level-effects/enqueue-level-effect! :directed-blastwave
        {:mode :perform
         :pos (:pos payload) :look-dir (:look-dir payload)
         :charge-ticks (long (or (:charge-ticks payload) 0))})
      :directed-blastwave/fx-end
      (level-effects/enqueue-level-effect! :directed-blastwave
        {:mode :end :performed? (boolean (:performed? payload))}))))
