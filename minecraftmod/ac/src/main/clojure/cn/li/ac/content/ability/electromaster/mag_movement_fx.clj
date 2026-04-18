(ns cn.li.ac.content.ability.electromaster.mag-movement-fx
  "Client FX for Magnetic Movement: beam between hand and target."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private effect-state (atom nil))
(def ^:private loop-sound "my_mod:em.move_loop")

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [payload]
  (let [{:keys [mode target]} payload]
    (case mode
      :start
      (do
        (reset! effect-state {:active? true :target target :ticks 0})
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id loop-sound :volume 0.58 :pitch 1.0}))
      :update
      (swap! effect-state
             (fn [st]
               (if (:active? st)
                 (assoc st :target target)
                 {:active? true :target target :ticks 0})))
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
             (let [ticks (inc (long (or (:ticks st) 0)))]
               (when (zero? (mod ticks 10))
                 (client-sounds/queue-sound-effect!
                   {:type :sound :sound-id loop-sound :volume 0.4 :pitch 1.0}))
               (assoc st :ticks ticks))))))

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- mag-movement-beam-ops [cam-pos start end tick]
  (let [phase (* 0.9 (double tick))
        tex-phase (* 1.7 (double tick))
        wiggle (+ 0.02
                  (* 0.02 (Math/sin phase))
                  (* 0.012 (Math/sin tex-phase)))
        flicker (+ (* 0.5 (+ 1.0 (Math/sin (* 0.27 (double tick)))))
                   (* 0.5 (+ 1.0 (Math/sin (* 0.53 (double tick))))))
        show-prob (+ 0.1 (* 0.35 flicker))
        hide-prob (+ 0.6 (* 0.25 (- 1.0 flicker)))
        outer-alpha (int (+ 45 (* 95 show-prob)))
        inner-alpha (int (+ 70 (* 120 hide-prob)))
        right (ru/beam-right-axis start end cam-pos)
        r0 (ru/v* right wiggle)
        r1 (ru/v* right (* wiggle 0.52))
        p0 (ru/v+ start r0) p1 (ru/v- start r0) p2 (ru/v- end r0) p3 (ru/v+ end r0)
        c0 (ru/v+ start r1) c1 (ru/v- start r1) c2 (ru/v- end r1) c3 (ru/v+ end r1)
        outer-a {:r 89 :g 196 :b 255 :a outer-alpha}
        inner-a {:r 234 :g 250 :b 255 :a inner-alpha}]
    [(ru/quad-op "minecraft:textures/entity/beacon_beam.png" p0 p1 p2 p3 outer-a)
     (ru/quad-op "minecraft:textures/entity/beacon_beam.png" c0 c1 c2 c3 inner-a)
     (ru/line-op start end {:r 161 :g 236 :b 255 :a (int (+ 90 (* 110 flicker)))})]))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [camera-pos hand-center-pos tick]
  (let [mag-move @effect-state]
    (when (and hand-center-pos
               (:active? mag-move)
               (map? (:target mag-move)))
      {:ops (vec (mag-movement-beam-ops camera-pos
                                        (dissoc hand-center-pos :player-uuid)
                                        (:target mag-move)
                                        tick))})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(level-effects/register-level-effect! :mag-movement
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:mag-movement/fx-start :mag-movement/fx-update :mag-movement/fx-end]
  (fn [_ctx-id channel payload]
    (let [mode (case channel
                 :mag-movement/fx-start :start
                 :mag-movement/fx-update :update
                 :mag-movement/fx-end :end)]
      (level-effects/enqueue-level-effect!
        :mag-movement
        (assoc payload :mode mode)))))
