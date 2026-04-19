(ns cn.li.ac.content.ability.meltdowner.scatter-bomb-fx
  "Client FX for ScatterBomb: ball spawn + scatter beam flashes."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private effect-state (atom nil))

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [payload]
  (let [{:keys [mode x y z count start end balls]} payload]
    (case mode
      :start
      (do
        (reset! effect-state {:active? true :ticks 0 :balls 0})
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id "my_mod:md.sb_charge" :volume 0.5 :pitch 1.0}))
      :ball
      (do
        (swap! effect-state assoc :balls (int (or count 0)))
        (client-particles/queue-particle-effect!
          {:type :particle :particle-type :electric-spark
           :x (double (or x 0.0))
           :y (double (or y 0.0))
           :z (double (or z 0.0))
           :count 4 :speed 0.1
           :offset-x 0.3 :offset-y 0.3 :offset-z 0.3}))
      :beam
      (do
        (when (and start end)
          (client-particles/queue-particle-effect!
            {:type :particle :particle-type :electric-spark
             :x (double (or (:x end) 0.0))
             :y (double (or (:y end) 0.0))
             :z (double (or (:z end) 0.0))
             :count 4 :speed 0.15
             :offset-x 0.4 :offset-y 0.4 :offset-z 0.4}))
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id "my_mod:md.eb_explode" :volume 0.4 :pitch 1.2}))
      :end
      (reset! effect-state nil)
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (swap! effect-state
         (fn [st]
           (when (and st (:active? st))
             (assoc st :ticks (inc (long (or (:ticks st) 0))))))))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [_camera-pos _hand-center-pos _tick]
  nil)

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(level-effects/register-level-effect! :scatter-bomb
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:scatter-bomb/fx-start :scatter-bomb/fx-ball :scatter-bomb/fx-beam :scatter-bomb/fx-end]
  (fn [_ctx-id channel payload]
    (case channel
      :scatter-bomb/fx-start
      (level-effects/enqueue-level-effect! :scatter-bomb {:mode :start})
      :scatter-bomb/fx-ball
      (level-effects/enqueue-level-effect! :scatter-bomb
        {:mode :ball :x (:x payload) :y (:y payload) :z (:z payload) :count (:count payload)})
      :scatter-bomb/fx-beam
      (level-effects/enqueue-level-effect! :scatter-bomb
        {:mode :beam :start (:start payload) :end (:end payload)})
      :scatter-bomb/fx-end
      (level-effects/enqueue-level-effect! :scatter-bomb {:mode :end})
      nil)))
