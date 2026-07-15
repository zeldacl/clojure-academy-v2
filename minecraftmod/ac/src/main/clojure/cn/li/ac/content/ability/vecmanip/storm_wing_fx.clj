(ns cn.li.ac.content.ability.vecmanip.storm-wing-fx
  "Client FX for Storm Wing: tornado ring visuals + dirt particles."
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]))

(def ^:private storm-wing-effect-id :storm-wing)
(def ^:private loop-sound (modid/namespaced-path "vecmanip.storm_wing"))

(defn default-storm-wing-fx-runtime-state
  []
  {:effect-state {}})

(defn storm-wing-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot storm-wing-effect-id)
      (default-storm-wing-fx-runtime-state)))

(defn reset-storm-wing-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    storm-wing-effect-id
    (default-storm-wing-fx-runtime-state))
  nil)

(defn clear-storm-wing-owner!
  [owner-key]
  (level-effects/update-effect-state!
    storm-wing-effect-id
    (fn [store]
      (update (or store (default-storm-wing-fx-runtime-state)) :effect-state dissoc owner-key)))
  nil)

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (or store (default-storm-wing-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode phase charge-ticks charge-ratio source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id loop-sound :volume 0.8 :pitch 1.0})
        (assoc-in store* [:effect-state owner-key*]
                  (merge base-meta
                         {:active? true :phase :charging :charge-ticks 0
                          :charge-ticks-needed (long (or charge-ticks 70))
                          :ticks 0})))
      :update
      (assoc-in store* [:effect-state owner-key*]
                (assoc (merge base-meta (get-in store* [:effect-state owner-key*] {}))
                       :owner-key owner-key*
              :queue-owner (or (get-in store* [:effect-state owner-key* :queue-owner])
                     (:queue-owner base-meta))
                       :ctx-id ctx-id
                       :channel channel
                       :source-player-id source-player-id
                       :world-id world-id
                       :active? true
                       :phase (or phase :charging)
                       :charge-ticks (long (or charge-ticks 0))
                       :charge-ratio (double (or charge-ratio 0.0))))
      :end
      (assoc-in store* [:effect-state owner-key*]
                (merge base-meta {:active? false :ticks 0}))
      store*)))

(defn- tick-state!
  [store]
  (let [store* (or store (default-storm-wing-fx-runtime-state))]
    (update store* :effect-state
      (fn [states]
        (into {}
              (keep (fn [[owner-key st]]
                      (when (:active? st)
                        (let [ticks (inc (long (or (:ticks st) 0)))]
                          (when (zero? (mod ticks 10))
                            (client-sounds/queue-sound-effect! (:queue-owner st)
                              {:type :sound :sound-id loop-sound :volume 0.5 :pitch 1.0}))
                          [owner-key (assoc st :ticks ticks)]))))
              states)))))

(defn- matching-active-state
  [effect-state hand-center-pos]
  (some (fn [st]
          (when (and (:active? st)
                     (or (nil? (:source-player-id st))
                         (nil? (:player-uuid hand-center-pos))
                         (= (str (:source-player-id st))
                            (str (:player-uuid hand-center-pos)))))
            st))
        (vals effect-state)))

(defn- build-plan
  [_camera-pos hand-center-pos _tick]
  (let [{:keys [effect-state]} (storm-wing-fx-snapshot)
        sw (matching-active-state effect-state hand-center-pos)]
    (when (and hand-center-pos sw (:active? sw))
      (let [center (dissoc hand-center-pos :player-uuid)
            sw-ticks (long (or (:ticks sw) 0))
            phase (or (:phase sw) :charging)
            charge-ratio (double (or (:charge-ratio sw) 0.0))
            _ (when (= phase :flying)
                (dotimes [_ 12]
                  (let [r (+ 3.0 (* (rand) 5.0))
                        theta (* (rand) Math/PI)
                        phi (* (rand) 2.0 Math/PI)]
                    (client-particles/queue-particle-effect! (:queue-owner sw)
                      {:type :particle
                       :particle-type :block-crack
                       :block-id "minecraft:dirt"
                       :x (+ (double (:x center)) (* r (Math/sin theta) (Math/cos phi)))
                       :y (+ (double (:y center)) (* r (Math/cos theta)))
                       :z (+ (double (:z center)) (* r (Math/sin theta) (Math/sin phi)))
                       :count 1
                       :speed 0.05
                       :offset-x 0.1 :offset-y 0.1 :offset-z 0.1}))))
            alpha-raw (case phase
                        :charging (* 0.7 charge-ratio)
                        :flying 0.7
                        0.0)
            alpha (int (* 255 alpha-raw))
            offsets [[-0.1 -0.3 0.1 0]
                     [0.1 -0.3 0.1 45]
                     [-0.1 -0.5 -0.1 90]
                     [0.1 -0.5 -0.1 135]]]
        (when (> alpha 0)
          {:ops (vec
                  (mapcat
                    (fn [[ox oy oz sep-deg]]
                      (let [ring-center {:x (+ (double (:x center)) ox)
                                         :y (+ (double (:y center)) oy)
                                         :z (+ (double (:z center)) oz)}
                            angle (+ (* 3.0 (double sw-ticks)) sep-deg)
                            radius 0.35
                            segments 10
                            color {:r 200 :g 230 :b 255 :a alpha}]
                        (vec
                          (for [i (range segments)
                                :let [a0 (Math/toRadians (+ angle (* 36.0 i)))
                                      a1 (Math/toRadians (+ angle (* 36.0 (inc i))))
                                      p0 {:x (+ (:x ring-center) (* radius (Math/cos a0)))
                                          :y (:y ring-center)
                                          :z (+ (:z ring-center) (* radius (Math/sin a0)))}
                                      p1 {:x (+ (:x ring-center) (* radius (Math/cos a1)))
                                          :y (:y ring-center)
                                          :z (+ (:z ring-center) (* radius (Math/sin a1)))}]]
                            (ru/line-op p0 p1 color)))))
                    offsets))})))))

(defn init!
  []
  (fx-spec/register!
    {:id storm-wing-effect-id
     :level {:initial-state (default-storm-wing-fx-runtime-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan}
     :channels {:start {:topic :storm-wing/fx-start :mode :start
                        :level-payload (fn [_ _ p]
                                         {:charge-ticks (long (or (:charge-ticks p) 70))})}
                :update {:topic :storm-wing/fx-update :mode :update
                         :level-payload (fn [_ _ p]
                                          {:phase (or (:phase p) :charging)
                                           :charge-ticks (long (or (:charge-ticks p) 0))
                                           :charge-ratio (double (or (:charge-ratio p) 0.0))})}
                :end {:topic :storm-wing/fx-end :mode :end}}})
  nil)
