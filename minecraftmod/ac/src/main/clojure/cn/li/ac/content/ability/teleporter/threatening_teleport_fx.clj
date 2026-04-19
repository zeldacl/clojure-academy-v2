(ns cn.li.ac.content.ability.teleporter.threatening-teleport-fx
  "Client FX for ThreateningTeleport: aim indicator + teleport flash."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private fx-state (atom nil))

(defn- enqueue! [payload]
  (case (:mode payload)
    :start
    (reset! fx-state {:ttl 0 :active? true})
    :perform
    (do
      (swap! fx-state assoc :perform-ttl 15)
      (when-let [x (:x payload)]
        (client-particles/queue-particle-effect!
          {:type :particle :particle-type :portal
           :x (double x) :y (double (:y payload)) :z (double (:z payload))
           :count 20 :speed 0.12
           :offset-x 0.8 :offset-y 1.0 :offset-z 0.8}))
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id "my_mod:tp.threatening_tp" :volume 0.7 :pitch 1.0}))
    :end
    (reset! fx-state nil)
    nil))

(defn- tick! []
  (swap! fx-state
         (fn [st]
           (when st
             (-> st
                 (update :ttl inc)
                 (update :perform-ttl (fn [t] (when (and t (pos? (long t))) (dec (long t))))))))))

(defn- build-plan [_cp _hcp _tick]
  nil)

(level-effects/register-level-effect! :threatening-teleport
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:threatening-tp/fx-start :threatening-tp/fx-perform :threatening-tp/fx-end]
  (fn [_ctx-id channel payload]
    (case channel
      :threatening-tp/fx-start
      (level-effects/enqueue-level-effect! :threatening-teleport {:mode :start})
      :threatening-tp/fx-perform
      (level-effects/enqueue-level-effect! :threatening-teleport
        {:mode :perform :x (:x payload) :y (:y payload) :z (:z payload)})
      :threatening-tp/fx-end
      (level-effects/enqueue-level-effect! :threatening-teleport {:mode :end})
      nil)))
