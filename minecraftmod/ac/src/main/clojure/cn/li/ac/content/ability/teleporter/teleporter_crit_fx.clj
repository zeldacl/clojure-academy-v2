(ns cn.li.ac.content.ability.teleporter.teleporter-crit-fx
  "Client FX for teleporter critical hits shared across teleporter attack skills."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private fx-state (atom {:ttl 0}))

(defn- crit-particle-config
  [crit-level]
  (case (long (or crit-level 0))
    2 {:primary-count 18 :secondary-count 10 :speed 0.14 :pitch 1.22 :volume 0.9}
    1 {:primary-count 12 :secondary-count 6 :speed 0.11 :pitch 1.12 :volume 0.75}
    {:primary-count 8 :secondary-count 0 :speed 0.09 :pitch 1.0 :volume 0.65}))

(defn- enqueue!
  [payload]
  (case (:mode payload)
    :crit-hit
    (let [{:keys [primary-count secondary-count speed pitch volume]}
          (crit-particle-config (:crit-level payload))
          x (double (or (:x payload) 0.0))
          y (double (or (:y payload) 0.0))
          z (double (or (:z payload) 0.0))]
      (swap! fx-state update :ttl inc)
      (client-particles/queue-particle-effect!
        {:type :particle :particle-type :portal
         :x x :y (+ y 0.4) :z z
         :count primary-count :speed speed
         :offset-x 0.65 :offset-y 0.65 :offset-z 0.65})
      (when (pos? secondary-count)
        (client-particles/queue-particle-effect!
          {:type :particle :particle-type :electric_spark
           :x x :y (+ y 0.8) :z z
           :count secondary-count :speed 0.05
           :offset-x 0.45 :offset-y 0.45 :offset-z 0.45}))
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id "my_mod:tp.tp" :volume volume :pitch pitch}))
    nil))

(defn- tick! []
  (swap! fx-state update :ttl inc))

(defn- build-plan [_cp _hcp _tick]
  nil)

(defn init! []
  (level-effects/register-level-effect! :teleporter-crit
    {:enqueue-fn enqueue!
     :tick-fn tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:teleporter/fx-crit-hit]
    (fn [_ctx-id channel payload]
      (case channel
        :teleporter/fx-crit-hit
        (level-effects/enqueue-level-effect! :teleporter-crit
          {:mode :crit-hit
           :x (:x payload)
           :y (:y payload)
           :z (:z payload)
           :crit-level (:crit-level payload)
           :target-uuid (:target-uuid payload)
           :skill-id (:skill-id payload)})
        nil)))
  nil)
