(ns cn.li.ac.content.ability.meltdowner.ray-barrage-fx
  "Client FX for RayBarrage skill: multi-beam flash with electric afterimages."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private beam-queue (atom []))

(defn- enqueue-beam! [payload]
  (swap! beam-queue
         (fn [q]
           (conj (if (> (count q) 10) (subvec q (- (count q) 10)) q)
                 (assoc payload :ttl 12)))))

(defn- tick! []
  (swap! beam-queue
         (fn [q]
           (->> q
                (map (fn [b] (update b :ttl dec)))
                (filter (fn [b] (pos? (:ttl b))))
                vec))))

(defn- build-plan [_camera-pos _hand-center-pos _tick]
  (when-let [beams (seq @beam-queue)]
    {:ops (mapcat
            (fn [beam]
              (let [{:keys [from-x from-y from-z to-x to-y to-z ttl]} beam
                    alpha (int (* 180 (/ (double ttl) 12.0)))
                    col {:r 255 :g 100 :b 50 :a alpha}]
                [(ru/line-op {:x (double from-x) :y (double from-y) :z (double from-z)}
                             {:x (double to-x)   :y (double to-y)   :z (double to-z)}
                             col)]))
            beams)}))

(level-effects/register-level-effect! :ray-barrage
  {:enqueue-fn    enqueue-beam!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:ray-barrage/fx-beam]
  (fn [_ctx-id channel payload]
    (when (= channel :ray-barrage/fx-beam)
      (when-let [beam-end (:beam-end payload)]
        (let [origin (:origin payload)]
          (level-effects/enqueue-level-effect! :ray-barrage
            {:from-x (:x origin) :from-y (:y origin) :from-z (:z origin)
             :to-x   (:x beam-end) :to-y (:y beam-end) :to-z (:z beam-end)})
          (client-sounds/queue-sound-effect!
            {:type :sound :sound-id "my_mod:md.ray_barrage" :volume 0.4 :pitch (+ 0.9 (rand 0.2))}))))))
