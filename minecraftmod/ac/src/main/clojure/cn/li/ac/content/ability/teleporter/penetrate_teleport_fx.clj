(ns cn.li.ac.content.ability.teleporter.penetrate-teleport-fx
  "Client FX for PenetrateTP: brief glow through wall."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private fx-state (atom nil))

(defn- enqueue! [payload]
  (case (:mode payload)
    :start
    (reset! fx-state {:ttl 0 :active? true})
    :perform
    (do
      (when-let [x (:x payload)]
        (client-particles/queue-particle-effect!
          {:type :particle :particle-type :enchantment-table
           :x (double x) :y (+ (double (:y payload)) 1.0) :z (double (:z payload))
           :count 12 :speed 0.06
           :offset-x 0.5 :offset-y 0.8 :offset-z 0.5}))
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id "my_mod:tp.penetrate_tp" :volume 0.65 :pitch 1.15}))
    :end
    (reset! fx-state nil)
    nil))

(defn- tick! []
  (swap! fx-state (fn [st] (when st (update st :ttl inc)))))

(defn- build-plan [_cp _hcp _tick] nil)

(level-effects/register-level-effect! :penetrate-teleport
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:penetrate-tp/fx-start :penetrate-tp/fx-perform :penetrate-tp/fx-end]
  (fn [_ctx-id channel payload]
    (case channel
      :penetrate-tp/fx-start
      (level-effects/enqueue-level-effect! :penetrate-teleport {:mode :start})
      :penetrate-tp/fx-perform
      (level-effects/enqueue-level-effect! :penetrate-teleport
        {:mode :perform :x (:x payload) :y (:y payload) :z (:z payload)})
      :penetrate-tp/fx-end
      (level-effects/enqueue-level-effect! :penetrate-teleport {:mode :end})
      nil)))
