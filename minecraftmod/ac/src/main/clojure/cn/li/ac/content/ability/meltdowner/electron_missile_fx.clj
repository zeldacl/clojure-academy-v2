(ns cn.li.ac.content.ability.meltdowner.electron-missile-fx
  "Client FX for ElectronMissile: orbiting sparks + impact flash per fired ball."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private impacts (atom []))

(defn- enqueue! [payload]
  (case (:mode payload)
    :start
    (client-sounds/queue-sound-effect!
      {:type :sound :sound-id "my_mod:md.em_start" :volume 0.5 :pitch 1.0})
    :fire
    (do
      (swap! impacts
             (fn [q]
               (let [q2 (conj q (assoc payload :ttl 10))]
                 (if (> (count q2) 8)
                   (subvec q2 (- (count q2) 8))
                   q2))))
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id "my_mod:md.em_fire" :volume 0.35 :pitch (+ 0.85 (rand 0.3))}))
    nil))

(defn- tick! []
  (swap! impacts
         (fn [q]
           (->> q
                (map (fn [b] (update b :ttl dec)))
                (filter (fn [b] (pos? (:ttl b))))
                vec)))
  (doseq [impact @impacts]
    (when-let [tx (:target-x impact)]
      (client-particles/queue-particle-effect!
        {:type :particle :particle-type :electric-spark
         :x (+ (double tx) 0.5)
         :y (+ (double (:target-y impact)) 0.5)
         :z (+ (double (:target-z impact)) 0.5)
         :count 2 :speed 0.2
         :offset-x 0.25 :offset-y 0.25 :offset-z 0.25}))))

(defn- build-plan [_camera-pos _hand-center-pos _tick]
  nil)

(level-effects/register-level-effect! :electron-missile
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:electron-missile/fx-start :electron-missile/fx-fire]
  (fn [_ctx-id channel payload]
    (case channel
      :electron-missile/fx-start
      (level-effects/enqueue-level-effect! :electron-missile {:mode :start})
      :electron-missile/fx-fire
      (level-effects/enqueue-level-effect! :electron-missile
        {:mode :fire
         :target-x (:target-x payload)
         :target-y (:target-y payload)
         :target-z (:target-z payload)})
      nil)))
