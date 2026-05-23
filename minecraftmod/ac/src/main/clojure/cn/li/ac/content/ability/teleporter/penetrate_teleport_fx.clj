(ns cn.li.ac.content.ability.teleporter.penetrate-teleport-fx
  "Client FX for PenetrateTP: brief glow through wall."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private fx-state (atom nil))

(defn- enqueue! [payload]
  (case (:mode payload)
    :start
    (reset! fx-state {:ttl 0 :active? true :available? false})

    :update
    (swap! fx-state
           (fn [st]
             (let [base (or st {:ttl 0 :active? true})]
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
        (client-particles/queue-particle-effect!
          {:type :particle :particle-type :enchantment-table
           :x (double x) :y (+ (double (:y payload)) 1.0) :z (double (:z payload))
           :count 12 :speed 0.06
           :offset-x 0.5 :offset-y 0.8 :offset-z 0.5}))
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id "my_mod:tp.penetrate_tp" :volume 0.65 :pitch 1.15}))
    :end
    (reset! fx-state nil)
    nil))

(defn- tick! []
  (swap! fx-state
         (fn [st]
           (when st
             (let [next-st (update st :ttl inc)]
               (when (and (:active? next-st)
                          (:x next-st)
                          (zero? (mod (:ttl next-st) 3)))
                 (client-particles/queue-particle-effect!
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
               next-st)))))

(defn- build-plan [_cp _hcp _tick] nil)

(defn init! []
  (level-effects/register-level-effect! :penetrate-teleport
    {:enqueue-fn    enqueue!
     :tick-fn       tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:penetrate-tp/fx-start :penetrate-tp/fx-update :penetrate-tp/fx-perform :penetrate-tp/fx-end]
    (fn [_ctx-id channel payload]
      (case channel
        :penetrate-tp/fx-start
        (level-effects/enqueue-level-effect! :penetrate-teleport {:mode :start})
        :penetrate-tp/fx-update
        (level-effects/enqueue-level-effect! :penetrate-teleport
          {:mode :update
           :distance (:distance payload)
           :available? (:available? payload)
           :x (:x payload) :y (:y payload) :z (:z payload)})
        :penetrate-tp/fx-perform
        (level-effects/enqueue-level-effect! :penetrate-teleport
          {:mode :perform :x (:x payload) :y (:y payload) :z (:z payload)})
        :penetrate-tp/fx-end
        (level-effects/enqueue-level-effect! :penetrate-teleport {:mode :end})
        nil)))
  nil)
