(ns cn.li.ac.content.ability.teleporter.threatening-teleport-fx
  "Client FX for ThreateningTeleport: aim indicator + teleport flash."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private fx-state (atom {}))

(defn threatening-teleport-fx-snapshot
  []
  {:fx-state @fx-state})

(defn reset-threatening-teleport-fx-for-test!
  []
  (reset! fx-state {})
  nil)

(defn clear-threatening-teleport-owner!
  [owner-key]
  (swap! fx-state dissoc owner-key)
  nil)

(defn- enqueue! [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id (:source-player-id payload)
                   :world-id (:world-id payload)}]
  (case (:mode payload)
    :start
    (swap! fx-state assoc owner-key*
           (merge base-meta {:ttl 0 :active? true :aim nil :attacked? false}))
    :update
    (swap! fx-state update owner-key*
           (fn [st]
             (merge base-meta
                    (or st {:ttl 0 :active? true})
                    {:aim {:x (double (or (:drop-x payload) 0.0))
                           :y (double (or (:drop-y payload) 0.0))
                           :z (double (or (:drop-z payload) 0.0))}
                     :attacked? (boolean (:attacked? payload))})))
    :perform
    (do
      (swap! fx-state update owner-key*
             (fn [st]
               (merge base-meta (or st {:ttl 0 :active? false}) {:perform-ttl 15})))
      (when-let [x (:drop-x payload)]
        (client-particles/queue-particle-effect!
          {:type :particle :particle-type :portal
           :x (double x) :y (double (:drop-y payload)) :z (double (:drop-z payload))
           :count 20 :speed 0.12
           :offset-x 0.8 :offset-y 1.0 :offset-z 0.8}))
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id "my_mod:tp.threatening_tp" :volume 0.7 :pitch 1.0}))
    :end
    (swap! fx-state dissoc owner-key*)
      nil)))

(defn- tick! []
  (swap! fx-state
         (fn [states]
           (into {}
                 (map (fn [[owner-key st]]
                        (let [next-st (-> st
                                          (update :ttl (fnil inc 0))
                                          (update :perform-ttl (fn [t] (when (and t (pos? (long t))) (dec (long t))))))]
                          (when (and (:aim next-st) (zero? (mod (long (:ttl next-st)) 3)))
                            (client-particles/queue-particle-effect!
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
                          [owner-key next-st]))
                 states)))))

(defn- build-plan [_cp _hcp _tick]
  nil)

(defn init! []
  (level-effects/register-level-effect! :threatening-teleport
    {:enqueue-event-fn enqueue!
     :tick-fn       tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:threatening-tp/fx-start :threatening-tp/fx-update :threatening-tp/fx-perform :threatening-tp/fx-end]
    (fn [ctx-id channel payload]
      (case channel
        :threatening-tp/fx-start
        (level-effects/enqueue-level-effect! :threatening-teleport {:mode :start}
                                             {:ctx-id ctx-id :channel channel})
        :threatening-tp/fx-update
        (level-effects/enqueue-level-effect! :threatening-teleport
          {:mode :update
           :drop-x (:drop-x payload)
           :drop-y (:drop-y payload)
           :drop-z (:drop-z payload)
           :attacked? (:attacked? payload)}
          {:ctx-id ctx-id :channel channel})
        :threatening-tp/fx-perform
        (level-effects/enqueue-level-effect! :threatening-teleport
          {:mode :perform
           :start-x (:start-x payload)
           :start-y (:start-y payload)
           :start-z (:start-z payload)
           :drop-x (:drop-x payload)
           :drop-y (:drop-y payload)
           :drop-z (:drop-z payload)
           :attacked? (:attacked? payload)
           :dropped? (:dropped? payload)}
          {:ctx-id ctx-id :channel channel})
        :threatening-tp/fx-end
        (level-effects/enqueue-level-effect! :threatening-teleport {:mode :end}
                                             {:ctx-id ctx-id :channel channel})
        nil)))
  nil)
