(ns cn.li.ac.content.ability.teleporter.shift-teleport-fx
  "Client FX for ShiftTeleport: portal particles at arrival point."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private fx-state (atom nil))

(defn- enqueue! [payload]
  (case (:mode payload)
    :start  (reset! fx-state {:active? true :ttl 0})
    :perform
    (do
      (when-let [x (:x payload)]
        (client-particles/queue-particle-effect!
          {:type :particle :particle-type :portal
           :x (double x) :y (double (:y payload)) :z (double (:z payload))
           :count 10 :speed 0.1
           :offset-x 0.6 :offset-y 0.8 :offset-z 0.6}))
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id "my_mod:tp.tp" :volume 0.5 :pitch 1.1}))
    :end    (reset! fx-state nil)
    nil))

(defn- tick! []
  (swap! fx-state (fn [st] (when st (update st :ttl inc)))))

(defn- build-plan [_cp _hcp _tick] nil)

(level-effects/register-level-effect! :shift-teleport
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:shift-tp/fx-start :shift-tp/fx-perform :shift-tp/fx-end]
  (fn [_ctx-id channel payload]
    (case channel
      :shift-tp/fx-start
      (level-effects/enqueue-level-effect! :shift-teleport {:mode :start})
      :shift-tp/fx-perform
      (level-effects/enqueue-level-effect! :shift-teleport
        {:mode :perform :x (:x payload) :y (:y payload) :z (:z payload)})
      :shift-tp/fx-end
      (level-effects/enqueue-level-effect! :shift-teleport {:mode :end})
      nil)))
