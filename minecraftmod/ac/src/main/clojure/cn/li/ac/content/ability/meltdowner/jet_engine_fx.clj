(ns cn.li.ac.content.ability.meltdowner.jet-engine-fx
  "Client FX for JetEngine skill: speed lines + launch flash."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private fx-state (atom nil))

(defn- enqueue! [payload]
  (case (:mode payload)
    :launch
    (do
      (reset! fx-state {:ttl 20 :speed (:speed payload 1.5)
                        :dx (:dx payload 0.0)
                        :dy (:dy payload 0.0)
                        :dz (:dz payload 0.0)})
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id "my_mod:md.jet_engine" :volume 0.8 :pitch 1.0}))
    :start
    (client-sounds/queue-sound-effect!
      {:type :sound :sound-id "my_mod:md.jet_charge" :volume 0.4 :pitch 1.0})
    nil))

(defn- tick! []
  (swap! fx-state
         (fn [st]
           (when (and st (pos? (long (or (:ttl st) 0))))
             (update st :ttl dec)))))

(defn- build-plan [_camera-pos _hand-center-pos tick]
  (when-let [st @fx-state]
    (when (pos? (long (or (:ttl st) 0)))
      (let [alpha (int (* 200 (/ (double (:ttl st)) 20.0)))]
        {:ops [{:type :screen-flash
                :r 200 :g 220 :b 255 :a (min 80 alpha)}]}))))

(level-effects/register-level-effect! :jet-engine
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:jet-engine/fx-start :jet-engine/fx-launch :jet-engine/fx-charge-max]
  (fn [_ctx-id channel payload]
    (case channel
      :jet-engine/fx-start
      (level-effects/enqueue-level-effect! :jet-engine {:mode :start})
      :jet-engine/fx-launch
      (level-effects/enqueue-level-effect! :jet-engine
        {:mode :launch
         :speed (:speed payload)
         :dx (:dx payload) :dy (:dy payload) :dz (:dz payload)})
      :jet-engine/fx-charge-max
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id "my_mod:md.jet_max" :volume 0.5 :pitch 1.2})
      nil)))
