(ns cn.li.ac.content.ability.meltdowner.electron-bomb-fx
  "Client FX for ElectronBomb: orbiting ball spawn + beam flash."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private effect-state (atom nil))

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [payload]
  (let [{:keys [mode x y z dx dy dz start end]} payload]
    (case mode
      :spawn
      (do
        (reset! effect-state
                {:active? true :ticks 0
                 :x (double (or x 0.0)) :y (double (or y 0.0)) :z (double (or z 0.0))
                 :dx (double (or dx 0.0)) :dy (double (or dy 0.0)) :dz (double (or dz 0.0))})
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id "my_mod:md.eb_spawn" :volume 0.6 :pitch 1.2}))
      :beam
      (do
        (when (and start end)
          (client-particles/queue-particle-effect!
            {:type :particle :particle-type :electric-spark
             :x (double (or (:x end) 0.0))
             :y (double (or (:y end) 0.0))
             :z (double (or (:z end) 0.0))
             :count 8 :speed 0.2
             :offset-x 0.5 :offset-y 0.5 :offset-z 0.5}))
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id "my_mod:md.eb_explode" :volume 0.8 :pitch 1.0})
        (reset! effect-state nil))
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
             (let [ticks (inc (long (or (:ticks st) 0)))]
               ;; Spawn orbiting particles
               (when (zero? (mod ticks 3))
                 (let [angle (* 0.4 (double ticks))
                       ox (* 0.9 (Math/cos angle))
                       oz (* 0.9 (Math/sin angle))]
                   (client-particles/queue-particle-effect!
                     {:type :particle :particle-type :electric-spark
                      :x (+ (:x st) ox) :y (:y st) :z (+ (:z st) oz)
                      :count 1 :speed 0.05
                      :offset-x 0.1 :offset-y 0.1 :offset-z 0.1})))
               (if (> ticks 40)
                 nil
                 (assoc st :ticks ticks)))))))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [_camera-pos _hand-center-pos _tick]
  nil)

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(level-effects/register-level-effect! :electron-bomb
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:electron-bomb/fx-spawn :electron-bomb/fx-beam :electron-bomb/fx-end]
  (fn [_ctx-id channel payload]
    (case channel
      :electron-bomb/fx-spawn
      (level-effects/enqueue-level-effect! :electron-bomb
        {:mode :spawn
         :x (:x payload) :y (:y payload) :z (:z payload)
         :dx (:dx payload) :dy (:dy payload) :dz (:dz payload)})
      :electron-bomb/fx-beam
      (level-effects/enqueue-level-effect! :electron-bomb
        {:mode :beam :start (:start payload) :end (:end payload)})
      :electron-bomb/fx-end
      (level-effects/enqueue-level-effect! :electron-bomb {:mode :end})
      nil)))
