(ns cn.li.ac.content.ability.teleporter.penetrate-teleport-fx
  "Client FX for PenetrateTeleport: EntityTPMarking preview + through-wall glow.
  Matching original AcademyCraft: EntityTPMarking follows aim, teleport burst flash."
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def ^:private penetrate-teleport-effect-id :penetrate-teleport)

(defn default-penetrate-teleport-fx-runtime-state [] {:fx-state {}})
(defn penetrate-teleport-fx-snapshot []
  (or (level-effects/effect-state-snapshot penetrate-teleport-effect-id)
      (default-penetrate-teleport-fx-runtime-state)))
(defn reset-penetrate-teleport-fx-for-test! []
  (level-effects/reset-level-effect-state-for-test! penetrate-teleport-effect-id (default-penetrate-teleport-fx-runtime-state)) nil)
(defn clear-penetrate-teleport-owner! [owner-key]
  (level-effects/update-effect-state! penetrate-teleport-effect-id
    (fn [state] (update (or state (default-penetrate-teleport-fx-runtime-state)) :fx-state dissoc owner-key))) nil)

(defn- spawn-tp-marking! []
  (client-bridge/run-client-effect! :mcmod/spawn-local-scripted-effect {:effect-id "entity_tp_marking"}))
(defn- remove-tp-marking! []
  (client-bridge/run-client-effect! :mcmod/remove-local-scripted-effect {:effect-id "entity_tp_marking"}))

(defn- enqueue-state! [state {:keys [payload ctx-id channel owner-key]}]
  (let [state* (or state (default-penetrate-teleport-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [source-player-id world-id]} payload
        base-meta {:owner-key owner-key* :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id :channel channel :source-player-id source-player-id :world-id world-id}]
    (case (:mode payload)
      :start
      (do (spawn-tp-marking!) (update state* :fx-state assoc owner-key* (merge base-meta {:active? true :ttl 0})))
      :update
      (update state* :fx-state assoc-in [owner-key* :ttl] 0)
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
  (let [state* (or state (default-penetrate-teleport-fx-runtime-state))]
    (update state* :fx-state (fn [states] (reduce-kv (fn [acc k st] (assoc acc k (update st :ttl (fnil inc 0)))) {} states)))))

(defn- build-plan [_cp _hcp _tick] nil)

(defn init! []
  (fx-spec/register!
    {:id penetrate-teleport-effect-id
     :level {:initial-state (default-penetrate-teleport-fx-runtime-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan}
     :channels {:start {:topic :penetrate-tp/fx-start :mode :start}
                :update {:topic :penetrate-tp/fx-update :mode :update}
                :perform {:topic :penetrate-tp/fx-perform :mode :perform}
                :end {:topic :penetrate-tp/fx-end :mode :end}}})
  nil)
