(ns cn.li.ac.content.ability.teleporter.shift-teleport-fx
  "Client FX for ShiftTeleport: portal particles at arrival point."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private fx-state (atom {}))

(defn shift-teleport-fx-snapshot []
  {:fx-state @fx-state})

(defn reset-shift-teleport-fx-for-test! []
  (reset! fx-state {})
  nil)

(defn clear-shift-teleport-owner! [owner-key]
  (swap! fx-state dissoc owner-key)
  nil)

(defn- enqueue! [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])
        {:keys [source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case (:mode payload)
      :start  (swap! fx-state assoc owner-key*
                     (merge base-meta {:active? true :ttl 0 :target nil :target-count 0 :hand-valid? true}))
    :update
    (swap! fx-state update owner-key*
           (fn [st]
             (assoc (merge base-meta (or st {:active? true :ttl 0}))
                    :owner-key owner-key*
                    :ctx-id ctx-id
                    :channel channel
                    :source-player-id source-player-id
                    :world-id world-id
                    :active? true
                    :target {:x (double (or (:x payload) 0.0))
                             :y (double (or (:y payload) 0.0))
                             :z (double (or (:z payload) 0.0))}
                    :target-count (long (or (:target-count payload) 0))
                    :target-hit? (boolean (:target-hit? payload))
                    :hand-valid? (boolean (if (contains? payload :hand-valid?)
                                            (:hand-valid? payload)
                                            true)))))
    :perform
    (do
      (when-let [x (:x payload)]
        (client-particles/queue-particle-effect! (:queue-owner base-meta)
          {:type :particle :particle-type :portal
           :x (double x) :y (double (:y payload)) :z (double (:z payload))
           :count 10 :speed 0.1
           :offset-x 0.6 :offset-y 0.8 :offset-z 0.6}))
      (let [fx-target {:x (double (or (:x payload) 0.0))
                       :y (double (or (:y payload) 0.0))
                       :z (double (or (:z payload) 0.0))}
            from-pos {:x (double (or (:from-x payload) (:x payload) 0.0))
                      :y (double (or (:from-y payload) (:y payload) 0.0))
                      :z (double (or (:from-z payload) (:z payload) 0.0))}
            dx (- (:x fx-target) (:x from-pos))
            dy (- (:y fx-target) (:y from-pos))
            dz (- (:z fx-target) (:z from-pos))
            dist (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
            steps (max 1 (int (/ dist 0.8)))]
        (dotimes [idx steps]
          (let [t (/ (double (inc idx)) (double steps))]
            (client-particles/queue-particle-effect! (:queue-owner base-meta)
              {:type :particle :particle-type :portal
               :x (+ (:x from-pos) (* dx t))
               :y (+ (:y from-pos) (* dy t))
               :z (+ (:z from-pos) (* dz t))
               :count 2 :speed 0.05
               :offset-x 0.2 :offset-y 0.2 :offset-z 0.2}))))
      (client-sounds/queue-sound-effect! (:queue-owner base-meta)
        {:type :sound :sound-id "my_mod:tp.tp" :volume 0.5 :pitch 1.1}))
      :end    (clear-shift-teleport-owner! owner-key*)
      nil)))

(defn- tick! []
  (swap! fx-state
         (fn [states]
           (into {}
                 (map (fn [[owner-key st]]
                        (let [next-st (update st :ttl inc)]
                          (when (and (:active? next-st)
                                     (:target next-st)
                                     (:hand-valid? next-st)
                                     (zero? (mod (long (:ttl next-st)) 3)))
                            (client-particles/queue-particle-effect! (:queue-owner next-st)
                              {:type :particle
                               :particle-type (if (:target-hit? next-st) :electric_spark :portal)
                               :x (double (get-in next-st [:target :x]))
                               :y (+ 0.4 (double (get-in next-st [:target :y])))
                               :z (double (get-in next-st [:target :z]))
                               :count (if (pos? (long (:target-count next-st))) 2 1)
                               :speed 0.02
                               :offset-x 0.25
                               :offset-y 0.25
                               :offset-z 0.25}))
                          [owner-key next-st]))
                 states)))))

(defn- build-plan [_cp _hcp _tick] nil)

(defn init! []
  (level-effects/register-level-effect! :shift-teleport
    {:enqueue-event-fn enqueue!
     :tick-fn       tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:shift-tp/fx-start :shift-tp/fx-update :shift-tp/fx-perform :shift-tp/fx-end]
    (fn [ctx-id channel payload]
      (let [meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
      (case channel
        :shift-tp/fx-start
        (level-effects/enqueue-level-effect! :shift-teleport (merge meta-payload {:mode :start})
                                             {:ctx-id ctx-id :channel channel})
        :shift-tp/fx-update
        (level-effects/enqueue-level-effect! :shift-teleport
          (merge meta-payload
                 {:mode :update
                  :x (:x payload)
                  :y (:y payload)
                  :z (:z payload)
                  :target-count (:target-count payload)
                  :target-hit? (:target-hit? payload)
                  :hand-valid? (:hand-valid? payload)})
          {:ctx-id ctx-id :channel channel})
        :shift-tp/fx-perform
        (level-effects/enqueue-level-effect! :shift-teleport
          (merge meta-payload
                 {:mode :perform
                  :x (:x payload) :y (:y payload) :z (:z payload)
                  :from-x (:from-x payload) :from-y (:from-y payload) :from-z (:from-z payload)})
          {:ctx-id ctx-id :channel channel})
        :shift-tp/fx-end
        (level-effects/enqueue-level-effect! :shift-teleport (merge meta-payload {:mode :end})
                                             {:ctx-id ctx-id :channel channel})
        nil))))
  nil)
