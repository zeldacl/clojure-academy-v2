(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.shift-teleport
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

(defn- spawn-entity-marker! []
  (client-bridge/run-client-effect! :mcmod/spawn-local-scripted-effect {:effect-id "entity_marker"}))
(defn- remove-entity-marker! []
  (client-bridge/run-client-effect! :mcmod/remove-local-scripted-effect {:effect-id "entity_marker"}))

(defn- enqueue-state! [state ctx-id channel owner-key payload]
  (let [state* (or state {:fx-state {}})
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [source-player-id world-id]} payload
        base-meta {:owner-key owner-key* :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id :channel channel :source-player-id source-player-id :world-id world-id}]
    (case (:mode payload)
      :start
      (do (spawn-entity-marker!)
          (update state* :fx-state assoc owner-key*
                  (merge base-meta {:active? true :ttl 0 :target nil :target-count 0 :hand-valid? true})))
      :update
      (update state* :fx-state update owner-key*
              (fn [st]
                (assoc (merge base-meta (or st {:active? true :ttl 0}))
                       :active? true
                       :target {:x (double (or (:x payload) 0.0))
                                :y (double (or (:y payload) 0.0))
                                :z (double (or (:z payload) 0.0))}
                       :target-count (long (or (:target-count payload) 0))
                       :target-hit? (boolean (:target-hit? payload))
                       :hand-valid? (boolean (if (contains? payload :hand-valid?) (:hand-valid? payload) true)))))
      :perform
      (do (remove-entity-marker!)
          ;; Burst particles at destination (matching original multiple marker cleanup)
          (when-let [x (:x payload)]
            (client-particles/queue-particle-effect! (:queue-owner base-meta)
              {:type :particle :particle-type :portal :x (double x) :y (double (:y payload)) :z (double (:z payload))
               :count 10 :speed 0.1 :offset-x 0.6 :offset-y 0.8 :offset-z 0.6}))
          ;; Trail particles from source to destination
          (let [fx-target {:x (double (or (:x payload) 0.0)) :y (double (or (:y payload) 0.0)) :z (double (or (:z payload) 0.0))}
                from-pos {:x (double (or (:from-x payload) (:x payload) 0.0))
                          :y (double (or (:from-y payload) (:y payload) 0.0))
                          :z (double (or (:from-z payload) (:z payload) 0.0))}
                dx (- (:x fx-target) (:x from-pos)) dy (- (:y fx-target) (:y from-pos)) dz (- (:z fx-target) (:z from-pos))
                dist (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
                steps (max 1 (int (/ dist 0.8)))]
            (dotimes [idx steps]
              (let [t (/ (double (inc idx)) (double steps))]
                (client-particles/queue-particle-effect! (:queue-owner base-meta)
                  {:type :particle :particle-type :portal
                   :x (+ (:x from-pos) (* dx t)) :y (+ (:y from-pos) (* dy t)) :z (+ (:z from-pos) (* dz t))
                   :count 2 :speed 0.05 :offset-x 0.2 :offset-y 0.2 :offset-z 0.2}))))
          (client-sounds/queue-sound-effect! (:queue-owner base-meta)
            {:type :sound :sound-id "my_mod:tp.tp" :volume 0.5 :pitch 1.1})
          state*)
      :end
      (do (remove-entity-marker!) (update state* :fx-state dissoc owner-key*))
      state*)))

(defn- tick-state! [state]
  (let [state* (or state {:fx-state {}})]
    (update state* :fx-state
            (fn [states]
              (reduce-kv
                (fn [acc owner-key st]
                  (let [next-st (update st :ttl (fnil inc 0))]
                    ;; Persistent marker entity handles block visibility;
                    ;; light particles at target for additional visual cue
                    (when (and (:active? next-st) (:target next-st) (:hand-valid? next-st)
                               (zero? (mod (long (:ttl next-st)) 6)))
                      (client-particles/queue-particle-effect! (:queue-owner next-st)
                        {:type :particle
                         :particle-type (if (:target-hit? next-st) :electric_spark :portal)
                         :x (double (get-in next-st [:target :x]))
                         :y (+ 0.4 (double (get-in next-st [:target :y])))
                         :z (double (get-in next-st [:target :z]))
                         :count (if (pos? (long (:target-count next-st))) 2 1)
                         :speed 0.02 :offset-x 0.25 :offset-y 0.25 :offset-z 0.25}))
                    (assoc acc owner-key next-st)))
                {} states)))))

(defn- build-plan [_cp _hcp _tick] nil)

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:shift-teleport :level] [_ _] {:fx-state {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:shift-teleport :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:shift-teleport :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :shift-teleport [store owner-key]
  (update store :fx-state dissoc owner-key))
