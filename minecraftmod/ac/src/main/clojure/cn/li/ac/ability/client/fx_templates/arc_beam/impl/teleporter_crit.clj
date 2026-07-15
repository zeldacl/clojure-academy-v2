(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.teleporter-crit
  (:require [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.runtime :as client-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [clojure.string :as str]))

(defn- crit-particle-config
  [crit-level]
  (case (long (or crit-level 0))
    2 {:primary-count 18 :secondary-count 10 :speed 0.14 :pitch 1.22 :volume 0.9}
    1 {:primary-count 12 :secondary-count 6 :speed 0.11 :pitch 1.12 :volume 0.75}
    {:primary-count 8 :secondary-count 0 :speed 0.09 :pitch 1.0 :volume 0.65}))

(defn- default-teleporter-crit-fx-runtime-state
  []
  {})

(defn- enqueue!
  [store ctx-id channel owner-key payload]
  (case (:mode payload)
    :crit-hit
    (let [{:keys [primary-count secondary-count speed pitch volume]}
          (crit-particle-config (:crit-level payload))
          x (double (or (:x payload) 0.0))
          y (double (or (:y payload) 0.0))
          z (double (or (:z payload) 0.0))]
      (when (:message-key payload)
        (runtime-hooks/client-show-combat-notice!
          :teleporter-crit
          {:message-key (:message-key payload)
           :args (:message-args payload)
           :duration-ms 1500
           :color [255 226 120]}))
      (client-particles/queue-current-particle-effect!
        {:type :particle :particle-type :portal
         :x x :y (+ y 0.4) :z z
         :count primary-count :speed speed
         :offset-x 0.65 :offset-y 0.65 :offset-z 0.65})
      (when (pos? secondary-count)
        (client-particles/queue-current-particle-effect!
          {:type :particle :particle-type :electric_spark
           :x x :y (+ y 0.8) :z z
           :count secondary-count :speed 0.05
           :offset-x 0.45 :offset-y 0.45 :offset-z 0.45}))
      (client-sounds/queue-current-sound-effect!
        {:type :sound :sound-id (modid/namespaced-path "tp.tp") :volume volume :pitch pitch}))
    nil)
  (or store {}))

(defn- tick!
  [store]
  (or store {}))

(defn- build-plan [_cp _hcp _tick]
  nil)

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:teleporter-crit :level] [_ _] {})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:teleporter-crit :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:teleporter-crit :level] [_ _ store] (tick! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :teleporter-crit
  [camera-pos hand-center-pos tick & args] (apply build-plan camera-pos hand-center-pos tick args))
