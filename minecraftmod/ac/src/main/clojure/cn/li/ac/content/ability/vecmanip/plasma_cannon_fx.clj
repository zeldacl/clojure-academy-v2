(ns cn.li.ac.content.ability.vecmanip.plasma-cannon-fx
  "Client FX for Plasma Cannon: charge particles + explosion."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private effect-state (atom nil))
(def ^:private loop-sound "my_mod:vecmanip.plasma_cannon")
(def ^:private charged-sound "my_mod:vecmanip.plasma_cannon_t")

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [payload]
  (let [{:keys [mode charge-ticks fully-charged? charge-pos flight-ticks
                state destination pos performed?]} payload]
    (case mode
      :start
      (do
        (reset! effect-state {:active? true :charge-ticks 0 :charge-pos nil
                              :flight-ticks 0 :state :charging :destination nil
                              :performed? false})
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id loop-sound :volume 0.5 :pitch 1.0}))
      :update
      (do
        (swap! effect-state
               (fn [st]
                 (assoc (or st {})
                        :active? true
                        :charge-ticks (long (or charge-ticks 0))
                        :flight-ticks (long (or flight-ticks 0))
                        :state (or state :charging)
                        :charge-pos (or charge-pos (:charge-pos st))
                        :destination (or destination (:destination st)))))
        (when (boolean fully-charged?)
          (client-sounds/queue-sound-effect!
            {:type :sound :sound-id charged-sound :volume 0.5 :pitch 1.0})))
      :perform
      (when (map? pos)
        (let [tx (double (:x pos)) ty (double (:y pos)) tz (double (:z pos))]
          (client-particles/queue-particle-effect!
            {:type :particle :particle-type :explosion-large
             :x tx :y ty :z tz :count 1 :speed 0.0
             :offset-x 0.0 :offset-y 0.0 :offset-z 0.0})
          (dotimes [_ 12]
            (client-particles/queue-particle-effect!
              {:type :particle :particle-type :smoke-large
               :x (+ tx (- (* (rand) 10.0) 5.0))
               :y (+ ty (- (* (rand) 5.0) 2.5))
               :z (+ tz (- (* (rand) 10.0) 5.0))
               :count 1 :speed (+ 0.1 (* (rand) 0.3))
               :offset-x 0.5 :offset-y 0.5 :offset-z 0.5}))
          (client-sounds/queue-sound-effect!
            {:type :sound :sound-id "minecraft:entity.generic.explode"
             :volume 3.0 :pitch 0.8 :x tx :y ty :z tz})))
      :end
      (reset! effect-state {:active? false :performed? (boolean performed?)})
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (swap! effect-state
         (fn [st]
           (when st
             (if (:active? st)
               (let [ticks (inc (long (or (:ticks st) 0)))]
                 (when (zero? (mod ticks 10))
                   (client-sounds/queue-sound-effect!
                     {:type :sound :sound-id loop-sound :volume 0.4 :pitch 1.0}))
                 (let [cp (:charge-pos st)]
                   (when (and cp (= :go (:state st)))
                     (client-particles/queue-particle-effect!
                       {:type :particle :particle-type :flame
                        :x (double (:x cp)) :y (double (:y cp)) :z (double (:z cp))
                        :count 4 :speed 0.2
                        :offset-x 0.5 :offset-y 0.5 :offset-z 0.5})))
                 (assoc st :ticks ticks))
               nil)))))

;; ---------------------------------------------------------------------------
;; Build plan (plasma cannon has no render ops, only particles/sounds)
;; ---------------------------------------------------------------------------

(defn- plasma-balls
  [{:keys [x y z]} ticks state]
  (let [t (double (or ticks 0))
        phase (* 0.23 t)
        state-mul (if (= state :go) 1.25 1.0)
        base-r (* 0.55 state-mul)
        h-wave (* 0.08 (Math/sin (* 0.15 t)))]
    [{:x x
      :y (+ y h-wave)
      :z z
      :size (* 0.95 state-mul)}
     {:x (+ x (* base-r (Math/cos (+ phase 0.0))))
      :y (+ y (* 0.18 (Math/sin (+ phase 0.7))))
      :z (+ z (* base-r (Math/sin (+ phase 0.0))))
      :size 0.62}
     {:x (+ x (* base-r (Math/cos (+ phase 2.09))))
      :y (+ y (* 0.18 (Math/sin (+ phase 2.79))))
      :z (+ z (* base-r (Math/sin (+ phase 2.09))))
      :size 0.62}
     {:x (+ x (* base-r (Math/cos (+ phase 4.18))))
      :y (+ y (* 0.18 (Math/sin (+ phase 4.91))))
      :z (+ z (* base-r (Math/sin (+ phase 4.18))))
      :size 0.62}
     {:x (+ x (* 0.35 (Math/cos (* 0.41 t))))
      :y (+ y (* 0.25 (Math/sin (* 0.47 t))))
      :z (+ z (* 0.35 (Math/sin (* 0.41 t))))
      :size 0.45}
     {:x (+ x (* 0.3 (Math/cos (+ (* 0.53 t) 1.3))))
      :y (+ y (* 0.22 (Math/sin (+ (* 0.59 t) 0.4))))
      :z (+ z (* 0.3 (Math/sin (+ (* 0.53 t) 1.3))))
      :size 0.4}]))

(defn- build-plan [_camera-pos _hand-center-pos _tick]
  (when-let [st @effect-state]
    (when (and (:active? st) (map? (:charge-pos st)))
      (let [cp (:charge-pos st)
            ticks (long (or (:ticks st) 0))
            state (:state st)
            charge-ticks (long (or (:charge-ticks st) 0))
            ramp (min 1.0 (/ charge-ticks 24.0))
            alpha (double (* (if (= state :go) 1.0 0.85) (+ 0.2 (* 0.8 ramp))))
            radius (+ 0.95 (* 0.35 ramp) (* 0.08 (Math/sin (* 0.21 ticks))))
            balls (plasma-balls cp ticks state)]
        {:ops [{:kind :plasma-body
                :center {:x (double (:x cp))
                         :y (double (:y cp))
                         :z (double (:z cp))}
                :radius (double radius)
                :alpha (double alpha)
                :balls balls}]}))))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(level-effects/register-level-effect! :plasma-cannon
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:plasma-cannon/fx-start :plasma-cannon/fx-update
   :plasma-cannon/fx-perform :plasma-cannon/fx-end]
  (fn [_ctx-id channel payload]
    (case channel
      :plasma-cannon/fx-start
      (level-effects/enqueue-level-effect! :plasma-cannon {:mode :start})
      :plasma-cannon/fx-update
      (level-effects/enqueue-level-effect! :plasma-cannon
        {:mode :update
         :charge-ticks (long (or (:charge-ticks payload) 0))
         :fully-charged? (boolean (:fully-charged? payload))
         :charge-pos (:charge-pos payload)
         :flight-ticks (long (or (:flight-ticks payload) 0))
         :state (or (:state payload) :charging)
         :destination (:destination payload)})
      :plasma-cannon/fx-perform
      (level-effects/enqueue-level-effect! :plasma-cannon
        {:mode :perform :pos (:pos payload)})
      :plasma-cannon/fx-end
      (level-effects/enqueue-level-effect! :plasma-cannon
        {:mode :end :performed? (boolean (:performed? payload))}))))
