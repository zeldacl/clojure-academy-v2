(ns cn.li.ac.content.ability.vecmanip.storm-wing-fx
  "Client FX for Storm Wing: tornado ring visuals + dirt particles."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private effect-state (atom nil))
(def ^:private loop-sound "my_mod:vecmanip.storm_wing")

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [payload]
  (let [{:keys [mode phase charge-ticks charge-ratio]} payload]
    (case mode
      :start
      (do
        (reset! effect-state {:active? true :phase :charging :charge-ticks 0
                              :charge-ticks-needed (long (or charge-ticks 70))
                              :ticks 0})
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id loop-sound :volume 0.8 :pitch 1.0}))
      :update
      (swap! effect-state
             (fn [st]
               (assoc (or st {})
                      :active? true
                      :phase (or phase :charging)
                      :charge-ticks (long (or charge-ticks 0))
                      :charge-ratio (double (or charge-ratio 0.0)))))
      :end
      (reset! effect-state {:active? false :ticks 0})
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
                     {:type :sound :sound-id loop-sound :volume 0.5 :pitch 1.0}))
                 (assoc st :ticks ticks))
               nil)))))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [_camera-pos hand-center-pos _tick]
  (let [sw @effect-state]
    (when (and hand-center-pos sw (:active? sw))
      (let [center (dissoc hand-center-pos :player-uuid)
            sw-ticks (long (or (:ticks sw) 0))
            phase (or (:phase sw) :charging)
            charge-ratio (double (or (:charge-ratio sw) 0.0))
            ;; Spawn 12 dirt particles in sphere r=3-8 around player (flying only)
            _ (when (= phase :flying)
                (dotimes [_ 12]
                  (let [r  (+ 3.0 (* (rand) 5.0))
                        theta (* (rand) Math/PI)
                        phi   (* (rand) 2.0 Math/PI)]
                    (client-particles/queue-particle-effect!
                      {:type          :particle
                       :particle-type :block-crack
                       :block-id      "minecraft:dirt"
                       :x (+ (double (:x center)) (* r (Math/sin theta) (Math/cos phi)))
                       :y (+ (double (:y center)) (* r (Math/cos theta)))
                       :z (+ (double (:z center)) (* r (Math/sin theta) (Math/sin phi)))
                       :count 1
                       :speed 0.05
                       :offset-x 0.1 :offset-y 0.1 :offset-z 0.1}))))
            ;; Tornado rings
            alpha-raw (case phase
                        :charging (* 0.7 charge-ratio)
                        :flying   0.7
                        0.0)
            alpha (int (* 255 alpha-raw))
            offsets [[-0.1 -0.3 0.1 0]
                     [0.1  -0.3 0.1 45]
                     [-0.1 -0.5 -0.1 90]
                     [0.1  -0.5 -0.1 135]]]
        (when (> alpha 0)
          {:ops (vec
                  (mapcat
                    (fn [[ox oy oz sep-deg]]
                      (let [ring-center {:x (+ (double (:x center)) ox)
                                         :y (+ (double (:y center)) oy)
                                         :z (+ (double (:z center)) oz)}
                            angle (+ (* 3.0 (double sw-ticks)) sep-deg)
                            radius 0.35
                            segments 10
                            color {:r 200 :g 230 :b 255 :a alpha}]
                        (vec
                          (for [i (range segments)
                                :let [a0 (Math/toRadians (+ angle (* 36.0 i)))
                                      a1 (Math/toRadians (+ angle (* 36.0 (inc i))))
                                      p0 {:x (+ (:x ring-center) (* radius (Math/cos a0)))
                                          :y (:y ring-center)
                                          :z (+ (:z ring-center) (* radius (Math/sin a0)))}
                                      p1 {:x (+ (:x ring-center) (* radius (Math/cos a1)))
                                          :y (:y ring-center)
                                          :z (+ (:z ring-center) (* radius (Math/sin a1)))}]]
                            (ru/line-op p0 p1 color)))))
                    offsets))})))))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(level-effects/register-level-effect! :storm-wing
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:storm-wing/fx-start :storm-wing/fx-update :storm-wing/fx-end]
  (fn [_ctx-id channel payload]
    (case channel
      :storm-wing/fx-start
      (level-effects/enqueue-level-effect! :storm-wing
        {:mode :start :charge-ticks (long (or (:charge-ticks payload) 70))})
      :storm-wing/fx-update
      (level-effects/enqueue-level-effect! :storm-wing
        {:mode :update
         :phase (or (:phase payload) :charging)
         :charge-ticks (long (or (:charge-ticks payload) 0))
         :charge-ratio (double (or (:charge-ratio payload) 0.0))})
      :storm-wing/fx-end
      (level-effects/enqueue-level-effect! :storm-wing {:mode :end}))))
