(ns cn.li.ac.content.ability.vecmanip.vec-reflection-fx
  "Client FX for VecReflection: double ring + reflection wave billboards."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private effect-state (atom nil))
(defonce ^:private wave-effects (atom []))
(def ^:private sound-id "my_mod:vecmanip.vec_reflection")

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [payload]
  (let [{:keys [mode x y z reflected?]} payload]
    (case mode
      :start
      (reset! effect-state {:active? true :ticks 0})
      :end
      (reset! effect-state {:active? false :ticks 0})
      :reflect-entity
      (when reflected?
        (let [life (+ 8 (rand-int 6))]
          (swap! wave-effects conj
                 {:x (double x) :y (double y) :z (double z)
                  :ttl life :max-ttl life})))
      :play
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id sound-id :volume 0.5 :pitch 1.0
         :x (double x) :y (double y) :z (double z)})
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (swap! effect-state
         (fn [st]
           (when st
             (if (:active? st)
               (update st :ticks (fnil inc 0))
               nil))))
  (swap! wave-effects
         (fn [xs] (->> xs (map #(update % :ttl dec)) (filter #(pos? (long (:ttl %)))) vec))))

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- double-ring-ops [center ticks]
  (let [base-angle (* 0.15 (double ticks))
        outer-r (+ 0.9 (* 0.06 (Math/sin (* 0.2 (double ticks)))))
        inner-r (* outer-r 0.55)
        y-outer (+ (double (:y center)) 0.2)
        y-inner (+ (double (:y center)) 0.35)
        segments 28
        outer-color {:r 255 :g 210 :b 180 :a 160}
        inner-color {:r 255 :g 180 :b 140 :a 130}]
    (vec
      (concat
        ;; outer ring
        (for [i (range segments)
              :let [a0 (+ base-angle (/ (* 2.0 Math/PI i) segments))
                    a1 (+ base-angle (/ (* 2.0 Math/PI (inc i)) segments))]]
          (ru/line-op
            {:x (+ (:x center) (* outer-r (Math/cos a0)))
             :y y-outer
             :z (+ (:z center) (* outer-r (Math/sin a0)))}
            {:x (+ (:x center) (* outer-r (Math/cos a1)))
             :y y-outer
             :z (+ (:z center) (* outer-r (Math/sin a1)))}
            outer-color))
        ;; inner ring (counter-rotating)
        (for [i (range segments)
              :let [a0 (- (- base-angle) (/ (* 2.0 Math/PI i) segments))
                    a1 (- (- base-angle) (/ (* 2.0 Math/PI (inc i)) segments))]]
          (ru/line-op
            {:x (+ (:x center) (* inner-r (Math/cos a0)))
             :y y-inner
             :z (+ (:z center) (* inner-r (Math/sin a0)))}
            {:x (+ (:x center) (* inner-r (Math/cos a1)))
             :y y-inner
             :z (+ (:z center) (* inner-r (Math/sin a1)))}
            inner-color))))))

(defn- wave-ops [cam-pos {:keys [x y z ttl max-ttl]}]
  (let [life (/ (double ttl) (double (max 1 max-ttl)))
        alpha (int (max 0 (min 255 (* 180.0 life))))
        center {:x (double x) :y (+ (double y) 0.6) :z (double z)}
        right (ru/camera-facing-right-axis center cam-pos)
        up (ru/billboard-up-axis center cam-pos right)
        half-size (+ 0.4 (* 0.6 (- 1.0 life)))
        side (ru/v* right half-size)
        lift (ru/v* up half-size)
        p0 (ru/v+ (ru/v- center side) lift)
        p1 (ru/v+ (ru/v+ center side) lift)
        p2 (ru/v- (ru/v+ center side) lift)
        p3 (ru/v- (ru/v- center side) lift)]
    [(ru/quad-op "my_mod:textures/effects/glow_circle.png"
                 p0 p1 p2 p3
                 {:r 255 :g 200 :b 160 :a alpha})]))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [camera-pos hand-center-pos _tick]
  (let [vr @effect-state
        current-waves @wave-effects
        ring-plan (if (and hand-center-pos vr (:active? vr))
                    (double-ring-ops (dissoc hand-center-pos :player-uuid)
                                    (long (or (:ticks vr) 0)))
                    [])
        wave-plan (mapcat #(wave-ops camera-pos %) current-waves)]
    (when (or (seq ring-plan) (seq wave-plan))
      {:ops (vec (concat ring-plan wave-plan))})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(level-effects/register-level-effect! :vec-reflection
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:vec-reflection/fx-start :vec-reflection/fx-end
   :vec-reflection/fx-reflect-entity :vec-reflection/fx-play]
  (fn [_ctx-id channel payload]
    (case channel
      :vec-reflection/fx-start
      (level-effects/enqueue-level-effect! :vec-reflection {:mode :start})
      :vec-reflection/fx-end
      (level-effects/enqueue-level-effect! :vec-reflection {:mode :end})
      :vec-reflection/fx-reflect-entity
      (level-effects/enqueue-level-effect! :vec-reflection
        {:mode :reflect-entity
         :x (double (or (:x payload) 0.0))
         :y (double (or (:y payload) 0.0))
         :z (double (or (:z payload) 0.0))
         :reflected? (boolean (:reflected? payload))})
      :vec-reflection/fx-play
      (level-effects/enqueue-level-effect! :vec-reflection
        {:mode :play
         :x (double (or (:x payload) 0.0))
         :y (double (or (:y payload) 0.0))
         :z (double (or (:z payload) 0.0))}))))
