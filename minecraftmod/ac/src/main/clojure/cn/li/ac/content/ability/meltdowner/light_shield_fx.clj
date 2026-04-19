(ns cn.li.ac.content.ability.meltdowner.light-shield-fx
  "Client FX for LightShield: glowing barrier effect."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private effect-state (atom nil))

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [payload]
  (let [{:keys [mode]} payload]
    (case mode
      :start
      (do
        (reset! effect-state {:active? true :ticks 0})
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id "my_mod:md.shield_on" :volume 0.7 :pitch 1.0}))
      :end
      (do
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id "my_mod:md.shield_off" :volume 0.5 :pitch 0.9})
        (reset! effect-state nil))
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (swap! effect-state
         (fn [st]
           (when (and st (:active? st))
             (let [ticks (inc (long (or (:ticks st) 0)))]
               ;; Periodic shield particles
               (when (zero? (mod ticks 5))
                 (client-particles/queue-particle-effect!
                   {:type :particle :particle-type :end-rod
                    :x 0.0 :y 1.0 :z 0.0
                    :count 3 :speed 0.15
                    :offset-x 0.8 :offset-y 0.8 :offset-z 0.8
                    :relative-to-camera? true}))
               (assoc st :ticks ticks))))))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [_camera-pos _hand-center-pos _tick]
  nil)

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(level-effects/register-level-effect! :light-shield
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:light-shield/fx-start :light-shield/fx-end]
  (fn [_ctx-id channel _payload]
    (case channel
      :light-shield/fx-start
      (level-effects/enqueue-level-effect! :light-shield {:mode :start})
      :light-shield/fx-end
      (level-effects/enqueue-level-effect! :light-shield {:mode :end})
      nil)))
