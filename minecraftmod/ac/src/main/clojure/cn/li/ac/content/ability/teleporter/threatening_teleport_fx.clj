(ns cn.li.ac.content.ability.teleporter.threatening-teleport-fx
  "Client FX for ThreateningTeleport: EntityMarker aim indicator + teleport flash.
  Matching original AcademyCraft: EntityMarker follows target, teleport burst flash."
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def ^:private threatening-teleport-effect-id :threatening-teleport)

(defn default-threatening-teleport-fx-runtime-state []
  {:fx-state {}})

(defn threatening-teleport-fx-snapshot []
  (or (level-effects/effect-state-snapshot threatening-teleport-effect-id)
      (default-threatening-teleport-fx-runtime-state)))

(defn reset-threatening-teleport-fx-for-test! []
  (level-effects/reset-level-effect-state-for-test!
    threatening-teleport-effect-id
    (default-threatening-teleport-fx-runtime-state))
  nil)

(defn clear-threatening-teleport-owner! [owner-key]
  (level-effects/update-effect-state!
    threatening-teleport-effect-id
    (fn [state] (update (or state (default-threatening-teleport-fx-runtime-state)) :fx-state dissoc owner-key)))
  nil)

(defn- spawn-marker!
  "Spawn EntityMarker (matching original)."
  []
  (client-bridge/run-client-effect! :mcmod/spawn-local-scripted-effect
    {:effect-id "entity_marker"}))

(defn- remove-marker!
  []
  (client-bridge/run-client-effect! :mcmod/remove-local-scripted-effect
    {:effect-id "entity_marker"}))

(defn- enqueue-state! [state {:keys [payload ctx-id channel owner-key]}]
  (let [state* (or state (default-threatening-teleport-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id :channel channel
                   :source-player-id source-player-id :world-id world-id}]
    (case (:mode payload)
      :start
      (do (spawn-marker!)
          (update state* :fx-state assoc owner-key*
                  (merge base-meta {:active? true :ttl 0 :aim nil :hit? false})))
      :update
      (update state* :fx-state update owner-key*
              (fn [st] (assoc (merge base-meta (or st {:active? true :ttl 0}))
                              :aim {:x (double (or (:target-x payload) 0.0))
                                    :y (double (or (:target-y payload) 0.0))
                                    :z (double (or (:target-z payload) 0.0))}
                              :hit? (boolean (:hit? payload)))))
      :perform
      (do (remove-marker!)
          (when (:hit? payload)
            (client-particles/queue-particle-effect! (:queue-owner base-meta)
              {:type :particle :particle-type :portal
               :x (double (or (:target-x payload) 0.0))
               :y (+ 1.0 (double (or (:target-y payload) 0.0)))
               :z (double (or (:target-z payload) 0.0))
               :count 8 :speed 0.08 :offset-x 0.3 :offset-y 0.3 :offset-z 0.3}))
          (client-sounds/queue-sound-effect! (:queue-owner base-meta)
            {:type :sound :sound-id "my_mod:tp.tp_shift" :volume 0.5 :pitch 1.0})
          state*)
      :end
      (do (remove-marker!) (update state* :fx-state dissoc owner-key*))
      state*)))

(defn- tick-state! [state]
  (let [state* (or state (default-threatening-teleport-fx-runtime-state))]
    (update state* :fx-state
            (fn [states] (reduce-kv (fn [acc k st] (assoc acc k (update st :ttl (fnil inc 0)))) {} states)))))

(defn- build-plan [_cp _hcp _tick] nil)

(defn- target-payload [_ctx-id _channel p]
  {:target-x (:target-x p) :target-y (:target-y p) :target-z (:target-z p) :hit? (:hit? p)})

(defn init! []
  (fx-spec/register!
    {:id threatening-teleport-effect-id
     :level {:initial-state (default-threatening-teleport-fx-runtime-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan}
     :channels {:start {:topic :threatening-tp/fx-start :mode :start}
                :update {:topic :threatening-tp/fx-update :mode :update :level-payload target-payload}
                :perform {:topic :threatening-tp/fx-perform :mode :perform :level-payload target-payload}
                :end {:topic :threatening-tp/fx-end :mode :end}}})
  nil)
