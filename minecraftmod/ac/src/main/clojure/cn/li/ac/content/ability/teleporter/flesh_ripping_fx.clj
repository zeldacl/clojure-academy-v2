(ns cn.li.ac.content.ability.teleporter.flesh-ripping-fx
  "Client FX for FleshRipping: red particle burst on target."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private fx-state (atom nil))

(defn- enqueue! [payload]
  (case (:mode payload)
    :start (reset! fx-state {:active? true :ttl 0 :aim nil :hit? false :target-uuid nil})
    :update
    (swap! fx-state
           (fn [st]
             (assoc (or st {:active? true :ttl 0})
                    :active? true
                    :aim {:x (double (or (:target-x payload) 0.0))
                          :y (double (or (:target-y payload) 0.0))
                          :z (double (or (:target-z payload) 0.0))}
                    :hit? (boolean (:hit? payload))
                    :target-uuid (:target-uuid payload))))
    :perform
    (do
      (when-let [x (:target-x payload)]
        (client-particles/queue-particle-effect!
          {:type :particle :particle-type :damage-indicator
           :x (double x) :y (+ (double (:target-y payload)) 1.0) :z (double (:target-z payload))
           :count 12 :speed 0.1
           :offset-x 0.4 :offset-y 0.6 :offset-z 0.4}))
      (when (:hit? payload)
        (client-particles/queue-particle-effect!
          {:type :particle :particle-type :portal
           :x (double (or (:target-x payload) 0.0))
           :y (+ 0.4 (double (or (:target-y payload) 0.0)))
           :z (double (or (:target-z payload) 0.0))
           :count 6 :speed 0.05
           :offset-x 0.2 :offset-y 0.3 :offset-z 0.2}))
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id "my_mod:tp.flesh_ripping" :volume 0.6 :pitch 0.95}))
    :end (reset! fx-state nil)
    nil))

(defn- tick! []
  (swap! fx-state
         (fn [st]
           (when st
             (let [next-st (update st :ttl inc)]
               (when (and (:active? next-st)
                          (:aim next-st)
                          (zero? (mod (long (:ttl next-st)) 3)))
                 (client-particles/queue-particle-effect!
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
               next-st)))))

(defn- build-plan [_cp _hcp _tick] nil)

(defn init! []
  (level-effects/register-level-effect! :flesh-ripping
    {:enqueue-fn    enqueue!
     :tick-fn       tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:flesh-ripping/fx-start :flesh-ripping/fx-update :flesh-ripping/fx-perform :flesh-ripping/fx-end]
    (fn [_ctx-id channel payload]
      (case channel
        :flesh-ripping/fx-start
        (level-effects/enqueue-level-effect! :flesh-ripping {:mode :start})
        :flesh-ripping/fx-update
        (level-effects/enqueue-level-effect! :flesh-ripping
          {:mode :update
           :target-x (:target-x payload)
           :target-y (:target-y payload)
           :target-z (:target-z payload)
           :hit? (:hit? payload)
           :target-uuid (:target-uuid payload)})
        :flesh-ripping/fx-perform
        (level-effects/enqueue-level-effect! :flesh-ripping
          {:mode :perform
           :target-x (:target-x payload)
           :target-y (:target-y payload)
           :target-z (:target-z payload)
           :hit? (:hit? payload)
           :target-uuid (:target-uuid payload)})
        :flesh-ripping/fx-end
        (level-effects/enqueue-level-effect! :flesh-ripping {:mode :end})
        nil)))
  nil)
