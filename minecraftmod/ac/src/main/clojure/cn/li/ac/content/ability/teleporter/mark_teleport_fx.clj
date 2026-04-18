(ns cn.li.ac.content.ability.teleporter.mark-teleport-fx
  "Client FX for Mark Teleport: ground ring + billboard marker."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private effect-state (atom nil))

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [payload]
  (let [{:keys [mode target distance]} payload]
    (case mode
      :start
      (reset! effect-state {:active? true :target nil :distance 0.0 :ticks 0})
      :update
      (swap! effect-state
             (fn [st]
               (assoc (or st {})
                      :active? true
                      :target target
                      :distance (double (or distance 0.0))
                      :ticks (long (or (:ticks st) 0)))))
      :perform
      (do
        (when (map? target)
          (client-particles/queue-particle-effect!
            {:type :particle :particle-type :portal
             :x (:x target) :y (double (or (:y target) 0.0)) :z (:z target)
             :count 16 :speed 0.08
             :offset-x 0.9 :offset-y 0.8 :offset-z 0.9}))
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id "my_mod:tp.tp" :volume 0.5 :pitch 1.0}))
      :end
      (reset! effect-state nil)
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (swap! effect-state
         (fn [st]
           (when (:active? st)
             (let [ticks (inc (long (or (:ticks st) 0)))
                   target (:target st)]
               (when (and target (zero? (mod ticks 3)))
                 (client-particles/queue-particle-effect!
                   {:type :particle :particle-type :portal
                    :x (:x target)
                    :y (- (double (or (:y target) 0.0)) 0.5)
                    :z (:z target)
                    :count 2 :speed 0.03
                    :offset-x 0.9 :offset-y 0.7 :offset-z 0.9}))
               (assoc st :ticks ticks))))))

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- ground-ring-ops [target ticks distance]
  (let [base-radius (+ 0.55 (* 0.08 (Math/sin (* 0.18 (double ticks)))))
        radius (+ base-radius (* 0.04 (min 1.0 (/ (double distance) 20.0))))
        y (+ (double (:y target)) 0.02)
        segments 24
        color {:r 230 :g 236 :b 255 :a 180}]
    (vec
      (for [idx (range segments)
            :let [a0 (/ (* 2.0 Math/PI idx) segments)
                  a1 (/ (* 2.0 Math/PI (inc idx)) segments)
                  p0 {:x (+ (:x target) (* radius (Math/cos a0)))
                      :y y
                      :z (+ (:z target) (* radius (Math/sin a0)))}
                  p1 {:x (+ (:x target) (* radius (Math/cos a1)))
                      :y y
                      :z (+ (:z target) (* radius (Math/sin a1)))}]]
        (ru/line-op p0 p1 color)))))

(defn- billboard-ops [cam-pos target ticks]
  (let [center {:x (:x target)
                :y (+ (double (:y target)) 0.9)
                :z (:z target)}
        right (ru/camera-facing-right-axis center cam-pos)
        half-width (+ 0.34 (* 0.04 (Math/sin (* 0.22 (double ticks)))))
        half-height 0.9
        up {:x 0.0 :y half-height :z 0.0}
        side (ru/v* right half-width)
        p0 (ru/v+ (ru/v- center side) up)
        p1 (ru/v+ (ru/v+ center side) up)
        p2 (ru/v- (ru/v+ center side) up)
        p3 (ru/v- (ru/v- center side) up)
        halo-width (* half-width 1.35)
        halo-height 1.12
        halo-side (ru/v* right halo-width)
        halo-up {:x 0.0 :y halo-height :z 0.0}
        h0 (ru/v+ (ru/v- center halo-side) halo-up)
        h1 (ru/v+ (ru/v+ center halo-side) halo-up)
        h2 (ru/v- (ru/v+ center halo-side) halo-up)
        h3 (ru/v- (ru/v- center halo-side) halo-up)
        alpha (+ 85 (* 35 (+ 1.0 (Math/sin (* 0.25 (double ticks))))))]
    [(ru/quad-op "my_mod:textures/effects/glow_circle.png" h0 h1 h2 h3 {:r 160 :g 196 :b 255 :a (int alpha)})
     (ru/quad-op "my_mod:textures/effects/glow_circle.png" p0 p1 p2 p3 {:r 245 :g 250 :b 255 :a 180})]))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [camera-pos _hand-center-pos _tick]
  (let [mk @effect-state]
    (when (and mk (:active? mk) (map? (:target mk)))
      (let [{:keys [target ticks distance]} mk]
        {:ops (vec (concat
                     (ground-ring-ops target ticks distance)
                     (billboard-ops camera-pos target ticks)))}))))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(level-effects/register-level-effect! :mark-teleport
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:mark-teleport/fx-start :mark-teleport/fx-update :mark-teleport/fx-end :mark-teleport/fx-perform]
  (fn [_ctx-id channel payload]
    (case channel
      :mark-teleport/fx-start
      (level-effects/enqueue-level-effect! :mark-teleport {:mode :start})
      :mark-teleport/fx-update
      (level-effects/enqueue-level-effect! :mark-teleport
        {:mode :update :target (:target payload)
         :distance (double (or (:distance payload) 0.0))})
      :mark-teleport/fx-end
      (level-effects/enqueue-level-effect! :mark-teleport {:mode :end})
      :mark-teleport/fx-perform
      (level-effects/enqueue-level-effect! :mark-teleport
        {:mode :perform :target (:target payload)
         :distance (double (or (:distance payload) 0.0))}))))
