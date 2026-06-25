(ns cn.li.ac.content.ability.teleporter.flesh-ripping-fx
  "Client FX for FleshRipping: EntityMarker aim preview + EntityBloodSplash on hit.
  Matching original AcademyCraft: gray marker (no target) → red marker (target locked)."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def ^:private flesh-ripping-effect-id :flesh-ripping)
(def ^:private stale-owner-ttl-ticks 80)

(defn default-flesh-ripping-fx-state
  []
  {:fx-state {}})

(defn flesh-ripping-fx-snapshot []
  (or (level-effects/effect-state-snapshot flesh-ripping-effect-id)
      (default-flesh-ripping-fx-state)))

(defn reset-flesh-ripping-fx-for-test! []
  (level-effects/reset-level-effect-state-for-test!
    flesh-ripping-effect-id
    (default-flesh-ripping-fx-state))
  nil)

(defn clear-flesh-ripping-owner! [owner-key]
  (level-effects/update-effect-state!
    flesh-ripping-effect-id
    (fn [state]
      (update (or state (default-flesh-ripping-fx-state)) :fx-state dissoc owner-key)))
  nil)

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

(defn- enqueue-state! [state {:keys [payload ctx-id channel owner-key]}]
  (let [state* (or state (default-flesh-ripping-fx-state))
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
          {:type :sound :sound-id "my_mod:tp.guts" :volume 0.6 :pitch 0.95})
        ;; Remove marker after perform
        (remove-marker!)
        state*)

      :end
      (do
        (remove-marker!)
        (update state* :fx-state dissoc owner-key*))

      state*)))

(defn- tick-state! [state]
  (let [state* (or state (default-flesh-ripping-fx-state))]
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

(defn init! []
  (fx-spec/register!
    {:id flesh-ripping-effect-id
     :level {:initial-state (default-flesh-ripping-fx-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan}
     :channels {:start {:topic :flesh-ripping/fx-start :mode :start}
                :update {:topic :flesh-ripping/fx-update :mode :update
                         :level-payload flesh-target-payload}
                :perform {:topic :flesh-ripping/fx-perform :mode :perform
                          :level-payload flesh-target-payload}
                :end {:topic :flesh-ripping/fx-end :mode :end}}})
  nil)
