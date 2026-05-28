(ns cn.li.ac.content.ability.teleporter.flesh-ripping-fx
  "Client FX for FleshRipping: red particle burst on target."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

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

(defn- enqueue-state! [state {:keys [payload ctx-id channel owner-key]}]
  (let [state* (or state (default-flesh-ripping-fx-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case (:mode payload)
      :start
      (update state* :fx-state assoc owner-key*
              (merge base-meta {:active? true :ttl 0 :aim nil :hit? false :target-uuid nil}))

      :update
      (update state* :fx-state update owner-key*
              (fn [st]
                (assoc (merge base-meta (or st {:active? true :ttl 0}))
                       :owner-key owner-key*
                       :ctx-id ctx-id
                       :channel channel
                       :source-player-id source-player-id
                       :world-id world-id
                       :active? true
                       :ttl 0
                       :aim {:x (double (or (:target-x payload) 0.0))
                             :y (double (or (:target-y payload) 0.0))
                             :z (double (or (:target-z payload) 0.0))}
                       :hit? (boolean (:hit? payload))
                       :target-uuid (:target-uuid payload))))

      :perform
      (do
        (when-let [x (:target-x payload)]
          (client-particles/queue-particle-effect! (:queue-owner base-meta)
            {:type :particle :particle-type :damage-indicator
             :x (double x) :y (+ (double (:target-y payload)) 1.0) :z (double (:target-z payload))
             :count 12 :speed 0.1
             :offset-x 0.4 :offset-y 0.6 :offset-z 0.4}))
        (when (:hit? payload)
          (client-particles/queue-particle-effect! (:queue-owner base-meta)
            {:type :particle :particle-type :portal
             :x (double (or (:target-x payload) 0.0))
             :y (+ 0.4 (double (or (:target-y payload) 0.0)))
             :z (double (or (:target-z payload) 0.0))
             :count 6 :speed 0.05
             :offset-x 0.2 :offset-y 0.3 :offset-z 0.2}))
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id "my_mod:tp.flesh_ripping" :volume 0.6 :pitch 0.95})
        state*)

      :end
      (update state* :fx-state dissoc owner-key*)

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
                      (do
                        (when (and (:active? next-st)
                                   (:aim next-st)
                                   (zero? (mod (long (:ttl next-st)) 3)))
                          (client-particles/queue-particle-effect! (:queue-owner next-st)
                            {:type :particle
                             :particle-type (if (:hit? next-st) :damage-indicator :portal)
                             :x (double (get-in next-st [:aim :x]))
                             :y (+ 0.2 (double (get-in next-st [:aim :y])))
                             :z (double (get-in next-st [:aim :z]))
                             :count (if (:hit? next-st) 2 1)
                             :speed 0.02
                             :offset-x 0.12
                             :offset-y 0.12
                             :offset-z 0.12}))
                        (assoc acc owner-key next-st)))))
                {}
                states)))))

(defn- build-plan [_cp _hcp _tick] nil)

(defn init! []
  (level-effects/register-level-effect! flesh-ripping-effect-id
    {:initial-state (default-flesh-ripping-fx-state)
     :enqueue-state-fn enqueue-state!
     :tick-state-fn tick-state!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:flesh-ripping/fx-start :flesh-ripping/fx-update :flesh-ripping/fx-perform :flesh-ripping/fx-end]
    (fn [ctx-id channel payload]
      (let [meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
        (case channel
          :flesh-ripping/fx-start
          (level-effects/enqueue-level-effect! :flesh-ripping (merge meta-payload {:mode :start})
                                               {:ctx-id ctx-id :channel channel})
        :flesh-ripping/fx-update
        (level-effects/enqueue-level-effect! :flesh-ripping
          (merge meta-payload
                 {:mode :update
                  :target-x (:target-x payload)
                  :target-y (:target-y payload)
                  :target-z (:target-z payload)
                  :hit? (:hit? payload)
                  :target-uuid (:target-uuid payload)})
          {:ctx-id ctx-id :channel channel})
        :flesh-ripping/fx-perform
        (level-effects/enqueue-level-effect! :flesh-ripping
          (merge meta-payload
                 {:mode :perform
                  :target-x (:target-x payload)
                  :target-y (:target-y payload)
                  :target-z (:target-z payload)
                  :hit? (:hit? payload)
                  :target-uuid (:target-uuid payload)})
          {:ctx-id ctx-id :channel channel})
        :flesh-ripping/fx-end
        (level-effects/enqueue-level-effect! :flesh-ripping (merge meta-payload {:mode :end})
                                             {:ctx-id ctx-id :channel channel})
        nil))))
  nil)
