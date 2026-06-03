(ns cn.li.ac.content.ability.teleporter.threatening-teleport-fx
  "Client FX for ThreateningTeleport: aim indicator + teleport flash."
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.level-effects :as level-effects]))

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
    (fn [state]
      (update (or state (default-threatening-teleport-fx-runtime-state)) :fx-state dissoc owner-key)))
  nil)

(defn- enqueue-state! [state {:keys [payload ctx-id channel owner-key]}]
  (let [state* (or state (default-threatening-teleport-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id (:source-player-id payload)
                   :world-id (:world-id payload)}]
    (case (:mode payload)
      :start
      (update state* :fx-state assoc owner-key* (merge base-meta {:ttl 0 :active? true :aim nil :attacked? false}))
      :update
      (update state* :fx-state update owner-key*
              (fn [st]
                (merge base-meta
                       (or st {:ttl 0 :active? true})
                       {:aim {:x (double (or (:drop-x payload) 0.0))
                              :y (double (or (:drop-y payload) 0.0))
                              :z (double (or (:drop-z payload) 0.0))}
                        :attacked? (boolean (:attacked? payload))})))
      :perform
      (do
        (when-let [x (:drop-x payload)]
          (client-particles/queue-particle-effect! (:queue-owner base-meta)
                                                   {:type :particle :particle-type :portal
                                                    :x (double x) :y (double (:drop-y payload)) :z (double (:drop-z payload))
                                                    :count 20 :speed 0.12
                                                    :offset-x 0.8 :offset-y 1.0 :offset-z 0.8}))
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
                                           {:type :sound :sound-id "my_mod:tp.threatening_tp" :volume 0.7 :pitch 1.0})
        (update state* :fx-state update owner-key*
                (fn [st]
                  (merge base-meta (or st {:ttl 0 :active? false}) {:perform-ttl 15}))))
      :end
      (update state* :fx-state dissoc owner-key*)
      state*)))

(defn- tick-state! [state]
  (let [state* (or state (default-threatening-teleport-fx-runtime-state))]
    (update state* :fx-state
            (fn [states]
              (reduce-kv
                (fn [acc owner-key st]
                  (let [next-st (-> st
                                    (update :ttl (fnil inc 0))
                                    (update :perform-ttl (fn [t]
                                                           (when (and t (pos? (long t)))
                                                             (dec (long t))))))]
                    (when (and (:aim next-st) (zero? (mod (long (:ttl next-st)) 3)))
                      (client-particles/queue-particle-effect! (:queue-owner next-st)
                                                               {:type :particle
                                                                :particle-type (if (:attacked? next-st) :electric_spark :portal)
                                                                :x (double (get-in next-st [:aim :x]))
                                                                :y (+ 0.4 (double (get-in next-st [:aim :y])))
                                                                :z (double (get-in next-st [:aim :z]))
                                                                :count 2
                                                                :speed 0.02
                                                                :offset-x 0.25
                                                                :offset-y 0.25
                                                                :offset-z 0.25}))
                    (assoc acc owner-key next-st)))
                {}
                states)))))

(defn- build-plan [_cp _hcp _tick] nil)

(defn init! []
  (fx-spec/register!
    {:id threatening-teleport-effect-id
     :level {:initial-state (default-threatening-teleport-fx-runtime-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan}
     :channels {:start {:topic :threatening-teleport/fx-start :mode :start}
                :update {:topic :threatening-teleport/fx-update :mode :update
                         :level-payload (fn [_ _ p]
                                          {:drop-x (:drop-x p) :drop-y (:drop-y p) :drop-z (:drop-z p)
                                           :attacked? (:attacked? p)})}
                :perform {:topic :threatening-teleport/fx-perform :mode :perform
                          :level-payload (fn [_ _ p]
                                           {:start-x (:start-x p) :start-y (:start-y p) :start-z (:start-z p)
                                            :drop-x (:drop-x p) :drop-y (:drop-y p) :drop-z (:drop-z p)
                                            :attacked? (:attacked? p) :dropped? (:dropped? p)})}
                :end {:topic :threatening-teleport/fx-end :mode :end}}})
  nil)