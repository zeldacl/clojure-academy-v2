(ns cn.li.ac.content.ability.electromaster.mag-movement-fx
  "Client FX for Magnetic Movement: beam between hand and target."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private effect-state (atom nil))
(def ^:private loop-sound "my_mod:em.move_loop")

(defn- magnetic-beam-style [tick]
  (let [phase (* 0.9 (double tick))
        tex-phase (* 1.7 (double tick))
        wiggle (+ 0.02
                  (* 0.02 (Math/sin phase))
                  (* 0.012 (Math/sin tex-phase)))
        flicker (+ (* 0.5 (+ 1.0 (Math/sin (* 0.27 (double tick)))))
                   (* 0.5 (+ 1.0 (Math/sin (* 0.53 (double tick))))))
        show-prob (+ 0.1 (* 0.35 flicker))
        hide-prob (+ 0.6 (* 0.25 (- 1.0 flicker)))]
    {:width wiggle
     :core-width (* wiggle 0.52)
     :outer-rgb {:r 89 :g 196 :b 255}
     :outer-alpha (int (+ 45 (* 95 show-prob)))
     :inner-rgb {:r 234 :g 250 :b 255}
     :inner-alpha (int (+ 70 (* 120 hide-prob)))
     :line-rgb {:r 161 :g 236 :b 255}
     :line-alpha (int (+ 90 (* 110 flicker)))}))

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

(defn- build-plan [camera-pos hand-center-pos tick]
  (let [mag-move @effect-state]
    (when (and hand-center-pos
               (:active? mag-move)
               (map? (:target mag-move)))
      {:ops (vec (fx-beam/beam-ops camera-pos
                                   (dissoc hand-center-pos :player-uuid)
                                   (:target mag-move)
                                   (magnetic-beam-style tick)))})))

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
