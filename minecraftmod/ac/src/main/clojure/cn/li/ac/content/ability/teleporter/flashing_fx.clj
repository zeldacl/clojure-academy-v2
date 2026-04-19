(ns cn.li.ac.content.ability.teleporter.flashing-fx
  "Client FX for Flashing toggle skill: brief afterimage at departure point."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private blink-queue (atom []))

(defn- enqueue! [payload]
  (case (:mode payload)
    :start  nil
    :blink
    (do
      (swap! blink-queue
             (fn [q]
               (let [q2 (conj q (assoc payload :ttl 8))]
                 (if (> (count q2) 12) (subvec q2 (- (count q2) 12)) q2))))
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id "my_mod:tp.flash" :volume 0.3 :pitch (+ 1.0 (rand 0.3))}))
    :end    (reset! blink-queue [])
    nil))

(defn- tick! []
  (swap! blink-queue
         (fn [q]
           (->> q
                (map (fn [b] (update b :ttl dec)))
                (filter (fn [b] (pos? (:ttl b))))
                vec)))
  (doseq [b @blink-queue]
    (when-let [x (:from-x b)]
      (when (zero? (mod (long (:ttl b)) 3))
        (client-particles/queue-particle-effect!
          {:type :particle :particle-type :portal
           :x (double x) :y (double (:from-y b)) :z (double (:from-z b))
           :count 3 :speed 0.06
           :offset-x 0.3 :offset-y 0.6 :offset-z 0.3})))))

(defn- build-plan [_cp _hcp _tick] nil)

(level-effects/register-level-effect! :flashing
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:flashing/fx-start :flashing/fx-blink :flashing/fx-end]
  (fn [_ctx-id channel payload]
    (case channel
      :flashing/fx-start
      (level-effects/enqueue-level-effect! :flashing {:mode :start})
      :flashing/fx-blink
      (level-effects/enqueue-level-effect! :flashing
        {:mode :blink
         :from-x (:from-x payload)
         :from-y (:from-y payload)
         :from-z (:from-z payload)})
      :flashing/fx-end
      (level-effects/enqueue-level-effect! :flashing {:mode :end})
      nil)))
