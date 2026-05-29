(ns cn.li.ac.content.ability.teleporter.penetrate-teleport-fx
  "Client FX for PenetrateTP: brief glow through wall."
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]))

(def ^:private penetrate-teleport-effect-id :penetrate-teleport)

(defn default-penetrate-teleport-fx-runtime-state []
  {:fx-state {}})

(defn penetrate-teleport-fx-snapshot []
  (or (level-effects/effect-state-snapshot penetrate-teleport-effect-id)
      (default-penetrate-teleport-fx-runtime-state)))

(defn reset-penetrate-teleport-fx-for-test! []
  (level-effects/reset-level-effect-state-for-test!
    penetrate-teleport-effect-id
    (default-penetrate-teleport-fx-runtime-state))
  nil)

(defn clear-penetrate-teleport-owner! [owner-key]
  (level-effects/update-effect-state!
    penetrate-teleport-effect-id
    (fn [state]
      (update (or state (default-penetrate-teleport-fx-runtime-state)) :fx-state dissoc owner-key)))
  nil)

(defn- enqueue-state! [state {:keys [payload ctx-id channel owner-key]}]
  (let [state* (or state (default-penetrate-teleport-fx-runtime-state))
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
      (update state* :fx-state assoc owner-key* (merge base-meta {:ttl 0 :active? true :available? false}))
      :update
      (update state* :fx-state update owner-key*
              (fn [st]
                (let [base (merge base-meta (or st {:ttl 0 :active? true}))]
                  (assoc base
                         :active? true
                         :available? (boolean (:available? payload))
                         :distance (double (or (:distance payload) 0.0))
                         :x (:x payload)
                         :y (:y payload)
                         :z (:z payload)))))
      :perform
      (do
        (when-let [x (:x payload)]
          (client-particles/queue-particle-effect! (:queue-owner base-meta)
                                                   {:type :particle :particle-type :enchantment-table
                                                    :x (double x) :y (+ (double (:y payload)) 1.0) :z (double (:z payload))
                                                    :count 12 :speed 0.06
                                                    :offset-x 0.5 :offset-y 0.8 :offset-z 0.5}))
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
                                           {:type :sound :sound-id "my_mod:tp.penetrate_tp" :volume 0.65 :pitch 1.15})
        state*)
      :end
      (update state* :fx-state dissoc owner-key*)
      state*)))

(defn- tick-state! [state]
  (let [state* (or state (default-penetrate-teleport-fx-runtime-state))]
    (update state* :fx-state
            (fn [states]
              (reduce-kv
                (fn [acc owner-key st]
                  (let [next-st (update st :ttl (fnil inc 0))]
                    (when (and (:active? next-st)
                               (:x next-st)
                               (zero? (mod (long (:ttl next-st)) 3)))
                      (client-particles/queue-particle-effect! (:queue-owner next-st)
                                                               {:type :particle
                                                                :particle-type (if (:available? next-st) :portal :smoke)
                                                                :x (double (:x next-st))
                                                                :y (+ (double (:y next-st)) 1.0)
                                                                :z (double (:z next-st))
                                                                :count (if (:available? next-st) 5 2)
                                                                :speed 0.02
                                                                :offset-x 0.35
                                                                :offset-y 0.6
                                                                :offset-z 0.35}))
                    (assoc acc owner-key next-st)))
                {}
                states)))))

(defn- build-plan [_cp _hcp _tick] nil)

(defn init! []
  (level-effects/register-level-effect! penetrate-teleport-effect-id
                                        {:initial-state (default-penetrate-teleport-fx-runtime-state)
                                         :enqueue-state-fn enqueue-state!
                                         :tick-state-fn tick-state!
                                         :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:penetrate-tp/fx-start :penetrate-tp/fx-update :penetrate-tp/fx-perform :penetrate-tp/fx-end]
    (fn [ctx-id channel payload]
      (let [meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
        (case channel
          :penetrate-tp/fx-start
          (level-effects/enqueue-level-effect! :penetrate-teleport (merge meta-payload {:mode :start})
                                               {:ctx-id ctx-id :channel channel})
          :penetrate-tp/fx-update
          (level-effects/enqueue-level-effect! :penetrate-teleport
                                               (merge meta-payload
                                                      {:mode :update
                                                       :distance (:distance payload)
                                                       :available? (:available? payload)
                                                       :x (:x payload) :y (:y payload) :z (:z payload)})
                                               {:ctx-id ctx-id :channel channel})
          :penetrate-tp/fx-perform
          (level-effects/enqueue-level-effect! :penetrate-teleport
                                               (merge meta-payload {:mode :perform :x (:x payload) :y (:y payload) :z (:z payload)})
                                               {:ctx-id ctx-id :channel channel})
          :penetrate-tp/fx-end
          (level-effects/enqueue-level-effect! :penetrate-teleport (merge meta-payload {:mode :end})
                                               {:ctx-id ctx-id :channel channel})
          nil))))
  nil)