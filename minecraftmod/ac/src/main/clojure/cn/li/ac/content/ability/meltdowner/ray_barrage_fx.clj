(ns cn.li.ac.content.ability.meltdowner.ray-barrage-fx
  "Client FX for RayBarrage skill: multi-beam flash with electric afterimages."
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]))

(def ^:private ray-barrage-effect-id :ray-barrage)

(defn default-ray-barrage-fx-runtime-state
  []
  {:beam-queue {}})

(defn ray-barrage-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot ray-barrage-effect-id)
      (default-ray-barrage-fx-runtime-state)))

(defn reset-ray-barrage-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    ray-barrage-effect-id
    (default-ray-barrage-fx-runtime-state))
  nil)

(defn clear-ray-barrage-owner!
  [owner-key]
  (level-effects/update-effect-state!
    ray-barrage-effect-id
    (fn [store]
      (update (or store (default-ray-barrage-fx-runtime-state)) :beam-queue dissoc owner-key)))
  nil)

(defn- all-beams
  []
  (mapcat val (:beam-queue (ray-barrage-fx-snapshot))))

(defn- enqueue-state!
  [store {:keys [payload ctx-id channel owner-key]}]
  (let [store* (or store (default-ray-barrage-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}
        beam (merge base-meta (or payload {}) {:ttl 12})]
    (update store* :beam-queue
      (fn [by-owner]
        (let [q (vec (get by-owner owner-key*))
              q* (if (> (count q) 10)
                   (subvec q (- (count q) 10))
                   q)]
          (assoc by-owner owner-key* (conj q* beam)))))))

(defn- tick-state!
  [store]
  (let [store* (or store (default-ray-barrage-fx-runtime-state))]
    (update store* :beam-queue
      (fn [by-owner]
        (into {}
              (keep (fn [[owner-key q]]
                      (let [live (->> q
                                      (map (fn [b] (update b :ttl dec)))
                                      (filter (fn [b] (pos? (:ttl b))))
                                      vec)]
                        (when (seq live)
                          [owner-key live]))))
              by-owner)))))

(defn- build-plan
  [_camera-pos _hand-center-pos _tick]
  (when-let [beams (seq (all-beams))]
    {:ops (mapcat
            (fn [beam]
              (let [{:keys [from-x from-y from-z to-x to-y to-z ttl]} beam
                    alpha (int (* 180 (/ (double ttl) 12.0)))
                    col {:r 255 :g 100 :b 50 :a alpha}]
                [(ru/line-op {:x (double from-x) :y (double from-y) :z (double from-z)}
                             {:x (double to-x)   :y (double to-y)   :z (double to-z)}
                             col)]))
            beams)}))

(defn init!
  []
  (level-effects/register-level-effect! ray-barrage-effect-id
    {:initial-state (default-ray-barrage-fx-runtime-state)
     :enqueue-state-fn enqueue-state!
     :tick-state-fn tick-state!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:ray-barrage/fx-beam]
    (fn [ctx-id channel payload]
      (when (= channel :ray-barrage/fx-beam)
        (when-let [beam-end (:beam-end payload)]
          (let [origin (:origin payload)
                meta-payload (select-keys payload [:effect-instance-id :source-player-id :world-id])]
            (level-effects/enqueue-level-effect! ray-barrage-effect-id
              (merge meta-payload
                     {:from-x (:x origin) :from-y (:y origin) :from-z (:z origin)
                      :to-x (:x beam-end) :to-y (:y beam-end) :to-z (:z beam-end)})
              {:ctx-id ctx-id :channel channel})
            (client-sounds/queue-current-sound-effect!
              {:type :sound :sound-id "my_mod:md.ray_barrage" :volume 0.4 :pitch (+ 0.9 (rand 0.2))}))))))
  nil)
