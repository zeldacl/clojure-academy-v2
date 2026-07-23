(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.flashing
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
      :state-start
      (do (spawn-tp-marking!) (update state* :fx-state assoc owner-key* (merge base-meta {:preview nil :burst []})))
      :preview-start
      (update state* :fx-state update owner-key*
              (fn [st] (assoc (merge base-meta (or st {:burst []}))
                              :preview {:x (:to-x payload) :y (:to-y payload) :z (:to-z payload)})))
      :preview-update
      (update state* :fx-state update owner-key*
              (fn [st] (assoc (merge base-meta (or st {:burst []}))
                              :preview {:x (:to-x payload) :y (:to-y payload) :z (:to-z payload)})))
      :preview-end
      (update state* :fx-state update owner-key* (fn [st] (assoc (merge base-meta (or st {:burst []})) :preview nil)))
      :perform
      (do (remove-tp-marking!)
          (client-sounds/queue-sound-effect! (:queue-owner base-meta)
            {:type :sound :sound-id (modid/namespaced-path "tp.tp_flashing") :volume 1.0 :pitch (+ 0.95 (rand 0.2))})
          (update state* :fx-state update owner-key*
                  (fn [st] (update (merge base-meta (or st {:preview nil :burst []})) :burst (fnil conj [])
                                   {:ttl 8 :from {:x (:from-x payload) :y (:from-y payload) :z (:from-z payload)}
                                    :to {:x (:to-x payload) :y (:to-y payload) :z (:to-z payload)}}))))
      :state-end
      (do (remove-tp-marking!) (update state* :fx-state dissoc owner-key*))
      state*)))

(defn- tick-state! [state]
  (let [state* (or state {:fx-state {}})]
    (update state* :fx-state
            (fn [states]
              (reduce-kv
                (fn [acc owner-key st]
                  (doseq [b (:burst st)]
                    (let [{fx :x fy :y fz :z} (:from b) {tx :x ty :y tz :z} (:to b)]
                      (when (pos? (long (:ttl b)))
                        (client-particles/queue-particle-effect! (:queue-owner st)
                          {:type :particle :particle-type :portal :x (double fx) :y (double fy) :z (double fz)
                           :count 2 :speed 0.05 :offset-x 0.35 :offset-y 0.5 :offset-z 0.35})
                        (client-particles/queue-particle-effect! (:queue-owner st)
                          {:type :particle :particle-type :portal :x (double tx) :y (double ty) :z (double tz)
                           :count 2 :speed 0.05 :offset-x 0.35 :offset-y 0.5 :offset-z 0.35}))))
                  (let [burst' (->> (:burst st) (map #(update % :ttl dec)) (filter #(pos? (long (:ttl %)))) vec)]
                    (assoc acc owner-key (assoc st :burst burst'))))
                {} states)))))

(defn- preview-to-payload [_ctx-id _channel p] {:to-x (:to-x p) :to-y (:to-y p) :to-z (:to-z p)})

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:flashing :level] [_ _] {:fx-state {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:flashing :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:flashing :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :flashing [_ store owner-key]
  (update store :fx-state dissoc owner-key))
