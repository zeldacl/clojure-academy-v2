(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.flesh-ripping
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

(def ^:private stale-owner-ttl-ticks 80)









(defn- spawn-marker!
  "Spawn EntityMarker at player position (matching original l_startEffect)."
  []
  (client-bridge/run-client-effect! :mcmod/spawn-local-scripted-effect
    {:effect-id "entity_marker"}))

(defn- spawn-blood-splash!
  "Spawn EntityBloodSplash at target (matching original EntityBloodSplash on hit)."
  []
  (client-bridge/run-client-effect! :mcmod/spawn-local-scripted-effect
    {:effect-id "entity_blood_splash"}))

(defn- remove-marker!
  []
  (client-bridge/run-client-effect! :mcmod/remove-local-scripted-effect
    {:effect-id "entity_marker"}))

(defn- enqueue-state! [state ctx-id channel owner-key payload]
  (let [state* (or state {:fx-state {}})
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case (:mode payload)
      :start
      (do
        ;; Spawn EntityMarker at player (matching original l_startEffect)
        (spawn-marker!)
        (update state* :fx-state assoc owner-key*
                (merge base-meta {:active? true :ttl 0 :aim nil :hit? false :target-uuid nil})))

      :update
      (update state* :fx-state update owner-key*
              (fn [st]
                (assoc (merge base-meta (or st {:active? true :ttl 0}))
                       :active? true
                       :ttl 0
                       :aim {:x (double (or (:target-x payload) 0.0))
                             :y (double (or (:target-y payload) 0.0))
                             :z (double (or (:target-z payload) 0.0))}
                       :hit? (boolean (:hit? payload))
                       :target-uuid (:target-uuid payload))))

      :perform
      (do
        (when (:hit? payload)
          ;; EntityBloodSplash on hit (matching original EntityBloodSplash)
          (spawn-blood-splash!))
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id (modid/namespaced-path "tp.guts") :volume 0.6 :pitch 0.95})
        ;; Remove marker after perform
        (remove-marker!)
        state*)

      :end
      (do
        (remove-marker!)
        (update state* :fx-state dissoc owner-key*))

      state*)))

(defn- tick-state! [state]
  (let [state* (or state {:fx-state {}})]
    (update state* :fx-state
            (fn [states]
              (reduce-kv
                (fn [acc owner-key st]
                  (let [next-st (update st :ttl (fnil inc 0))]
                    (if (> (long (:ttl next-st)) stale-owner-ttl-ticks)
                      acc
                      (assoc acc owner-key next-st))))
                {}
                states)))))

(defn- build-plan [_cp _hcp _tick] nil)

(defn- flesh-target-payload [_ctx-id _channel p]
  {:target-x (:target-x p)
   :target-y (:target-y p)
   :target-z (:target-z p)
   :hit? (:hit? p)
   :target-uuid (:target-uuid p)})

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:flesh-ripping :level] [_ _] {:fx-state {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:flesh-ripping :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:flesh-ripping :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :flesh-ripping [store owner-key]
  (update store :fx-state dissoc owner-key))
