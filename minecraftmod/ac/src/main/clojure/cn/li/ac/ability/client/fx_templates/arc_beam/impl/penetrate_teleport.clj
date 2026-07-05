(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.penetrate-teleport
  (:require [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.runtime :as client-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.util.math.vec3 :as vec3]
            [clojure.string :as str]))

(defn- spawn-tp-marking! []
  (client-bridge/run-client-effect! :mcmod/spawn-local-scripted-effect {:effect-id "entity_tp_marking"}))
(defn- remove-tp-marking! []
  (client-bridge/run-client-effect! :mcmod/remove-local-scripted-effect {:effect-id "entity_tp_marking"}))

(defn- enqueue-state! [state ctx-id channel owner-key payload]
  (let [state* (or state {:fx-state {}})
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [source-player-id world-id]} payload
        base-meta {:owner-key owner-key* :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id :channel channel :source-player-id source-player-id :world-id world-id}]
    (case (:mode payload)
      :start
      (do (spawn-tp-marking!) (update state* :fx-state assoc owner-key* (merge base-meta {:active? true :ttl 0})))
      :update
      (update state* :fx-state update owner-key*
              (fn [st]
                (assoc (merge base-meta (or st {:active? true :ttl 0}))
                       :active? true
                       :ttl 0
                       :x (double (or (:x payload) 0.0))
                       :y (double (or (:y payload) 0.0))
                       :z (double (or (:z payload) 0.0))
                       :available? (boolean (:available? payload))
                       :distance (double (or (:distance payload) 0.0)))))
      :perform
      (do (remove-tp-marking!)
          (client-particles/queue-particle-effect! (:queue-owner base-meta)
            {:type :particle :particle-type :portal
             :x (double (or (:to-x payload) 0.0)) :y (+ 1.0 (double (or (:to-y payload) 0.0))) :z (double (or (:to-z payload) 0.0))
             :count 10 :speed 0.06 :offset-x 0.25 :offset-y 0.5 :offset-z 0.25})
          (client-sounds/queue-sound-effect! (:queue-owner base-meta)
            {:type :sound :sound-id "my_mod:tp.tp_flashing" :volume 0.5 :pitch 1.0})
          state*)
      :end
      (do (remove-tp-marking!) (update state* :fx-state dissoc owner-key*))
      state*)))

(defn- tick-state! [state]
  (let [state* (or state {:fx-state {}})]
    (update state* :fx-state (fn [states] (reduce-kv (fn [acc k st] (assoc acc k (update st :ttl (fnil inc 0)))) {} states)))))

(defn- build-plan [_cp _hcp _tick] nil)

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:penetrate-teleport :level] [_ _] {:fx-state {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:penetrate-teleport :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:penetrate-teleport :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :penetrate-teleport [store owner-key]
  (update store :fx-state dissoc owner-key))
