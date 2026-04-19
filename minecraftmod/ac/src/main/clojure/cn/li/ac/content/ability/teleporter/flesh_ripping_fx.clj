(ns cn.li.ac.content.ability.teleporter.flesh-ripping-fx
  "Client FX for FleshRipping: red particle burst on target."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private fx-state (atom nil))

(defn- enqueue! [payload]
  (case (:mode payload)
    :start (reset! fx-state {:active? true :ttl 0})
    :perform
    (do
      (when-let [x (:target-x payload)]
        (client-particles/queue-particle-effect!
          {:type :particle :particle-type :damage-indicator
           :x (double x) :y (+ (double (:target-y payload)) 1.0) :z (double (:target-z payload))
           :count 10 :speed 0.1
           :offset-x 0.4 :offset-y 0.6 :offset-z 0.4}))
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id "my_mod:tp.flesh_ripping" :volume 0.6 :pitch 0.95}))
    :end (reset! fx-state nil)
    nil))

(defn- tick! []
  (swap! fx-state (fn [st] (when st (update st :ttl inc)))))

(defn- build-plan [_cp _hcp _tick] nil)

(level-effects/register-level-effect! :flesh-ripping
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:flesh-ripping/fx-start :flesh-ripping/fx-perform :flesh-ripping/fx-end]
  (fn [_ctx-id channel payload]
    (case channel
      :flesh-ripping/fx-start
      (level-effects/enqueue-level-effect! :flesh-ripping {:mode :start})
      :flesh-ripping/fx-perform
      (level-effects/enqueue-level-effect! :flesh-ripping
        {:mode :perform
         :target-x (:target-x payload)
         :target-y (:target-y payload)
         :target-z (:target-z payload)})
      :flesh-ripping/fx-end
      (level-effects/enqueue-level-effect! :flesh-ripping {:mode :end})
      nil)))
