(ns cn.li.ac.content.ability.vecmanip.vec-accel-fx
  "Client FX for VecAccel: trajectory preview ribbon."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private effect-state (atom nil))
(def ^:private sound-id "my_mod:vecmanip.vec_accel")

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [payload]
  (let [{:keys [mode charge-ticks can-perform? look-dir init-vel]} payload]
    (case mode
      :start
      (reset! effect-state {:active? true :charge-ticks 0
                            :can-perform? false
                            :look-dir {:x 0.0 :y 0.0 :z 1.0}
                            :init-vel {:x 0.0 :y 0.0 :z 1.0}})
      :update
      (swap! effect-state
             (fn [st]
               (assoc (or st {:active? true})
                      :active? true
                      :charge-ticks (long (or charge-ticks 0))
                      :can-perform? (boolean can-perform?)
                      :look-dir (or look-dir {:x 0.0 :y 0.0 :z 1.0})
                      :init-vel (or init-vel {:x 0.0 :y 0.0 :z 1.0}))))
      :perform
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id sound-id :volume 0.35 :pitch 1.0})
      :end
      (reset! effect-state {:active? false})
      nil)))

;; ---------------------------------------------------------------------------
;; Tick (no-op, state is driven by updates)
;; ---------------------------------------------------------------------------

(defn- tick! [] nil)

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- trajectory-ops [camera-pos effect]
  (when (and effect (:active? effect))
    (let [init-vel  (or (:init-vel effect) {:x 0.0 :y 0.0 :z 1.0})
          look-dir  (or (:look-dir effect) {:x 0.0 :y 0.0 :z 1.0})
          can-do?   (boolean (:can-perform? effect))
          px        (double (:x camera-pos))
          py        (- (double (:y camera-pos)) 1.62)
          pz        (double (:z camera-pos))
          lx        (double (:x look-dir))
          ly        (double (:y look-dir))
          lz        (double (:z look-dir))
          horiz-len (Math/sqrt (+ (* lx lx) (* lz lz)))
          safe-h    (max 1.0e-8 horiz-len)
          rot-x     (* (/ lz safe-h) -0.08)
          rot-z     (* (/ (- lx) safe-h) -0.08)
          off-x     (- rot-x (* lx 0.12))
          off-y     (- 1.56   (* ly 0.12))
          off-z     (- rot-z  (* lz 0.12))
          start-pos {:x (+ px off-x) :y (+ py off-y) :z (+ pz off-z)}
          h         0.02
          dt        0.02]
      (loop [pos   start-pos
             vel   {:x (double (:x init-vel))
                    :y (double (:y init-vel))
                    :z (double (:z init-vel))}
             prev  nil
             ops   (transient [])
             idx   0]
        (let [vel2  {:x (* (double (:x vel)) 0.98)
                     :y (* (double (:y vel)) 0.98)
                     :z (* (double (:z vel)) 0.98)}
              pos2  {:x (+ (double (:x pos)) (* (double (:x vel2)) dt))
                     :y (+ (double (:y pos)) (* (double (:y vel2)) dt))
                     :z (+ (double (:z pos)) (* (double (:z vel2)) dt))}
              vel3  (assoc vel2 :y (- (double (:y vel2)) (* dt 1.9)))]
          (when (and prev (< idx 99))
            (let [raw-alpha (max 0.0 (- 0.7 (* idx 0.021)))
                  a-int     (int (* raw-alpha 255.0))]
              (when (> a-int 0)
                (let [color (if can-do?
                              {:r 255 :g 255 :b 255 :a a-int}
                              {:r 255 :g  51 :b  51 :a a-int})
                      p0 {:x (double (:x prev)) :y (+ (double (:y prev)) h) :z (double (:z prev))}
                      p1 {:x (double (:x pos))  :y (+ (double (:y pos))  h) :z (double (:z pos))}
                      p2 {:x (double (:x pos))  :y (- (double (:y pos))  h) :z (double (:z pos))}
                      p3 {:x (double (:x prev)) :y (- (double (:y prev)) h) :z (double (:z prev))}]
                  (conj! ops (ru/quad-op "my_mod:textures/effects/glow_line.png"
                                         p0 p1 p2 p3 color))))))
          (if (>= idx 99)
            (persistent! ops)
            (recur pos2 vel3 pos ops (inc idx))))))))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [camera-pos _hand-center-pos _tick]
  (let [va @effect-state]
    (when (and va (:active? va))
      (let [ops (trajectory-ops camera-pos va)]
        (when (seq ops)
          {:ops (vec ops)})))))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(level-effects/register-level-effect! :vec-accel
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:vec-accel/fx-start :vec-accel/fx-update :vec-accel/fx-perform :vec-accel/fx-end]
  (fn [_ctx-id channel payload]
    (case channel
      :vec-accel/fx-start
      (level-effects/enqueue-level-effect! :vec-accel {:mode :start})
      :vec-accel/fx-update
      (level-effects/enqueue-level-effect! :vec-accel
        {:mode :update
         :charge-ticks (long (or (:charge-ticks payload) 0))
         :can-perform? (boolean (:can-perform? payload))
         :look-dir (:look-dir payload)
         :init-vel (:init-vel payload)})
      :vec-accel/fx-perform
      (level-effects/enqueue-level-effect! :vec-accel {:mode :perform})
      :vec-accel/fx-end
      (level-effects/enqueue-level-effect! :vec-accel
        {:mode :end :performed? (boolean (:performed? payload))}))))
