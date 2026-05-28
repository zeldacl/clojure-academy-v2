(ns cn.li.ac.content.ability.vecmanip.storm-wing-fx
  "Client FX for Storm Wing: tornado ring visuals + dirt particles."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defn default-storm-wing-fx-runtime-state
  []
  {:effect-state {}})

(defn create-storm-wing-fx-runtime
  ([]
   (create-storm-wing-fx-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-storm-wing-fx-runtime-state))}}]
   {::runtime ::storm-wing-fx-runtime
    :state* state*}))

(def ^:dynamic *storm-wing-fx-runtime* nil)

(defonce ^:private installed-storm-wing-fx-runtime
  (create-storm-wing-fx-runtime))

(defn- storm-wing-fx-runtime?
  [runtime]
  (and (map? runtime)
       (= ::storm-wing-fx-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-storm-wing-fx-runtime
  [runtime f]
  (when-not (storm-wing-fx-runtime? runtime)
    (throw (ex-info "Expected Storm Wing FX runtime"
                    {:value runtime})))
  (binding [*storm-wing-fx-runtime* runtime]
    (f)))

(defmacro with-storm-wing-fx-runtime
  [runtime & body]
  `(call-with-storm-wing-fx-runtime ~runtime (fn [] ~@body)))

(defn- current-storm-wing-fx-runtime
  []
  (or *storm-wing-fx-runtime*
      installed-storm-wing-fx-runtime))

(defn- storm-wing-fx-state-atom
  []
  (:state* (current-storm-wing-fx-runtime)))

(defn- storm-wing-fx-state-snapshot
  []
  @(storm-wing-fx-state-atom))

(defn- update-storm-wing-fx-state!
  [f & args]
  (apply swap! (storm-wing-fx-state-atom) f args))

(def ^:private loop-sound "my_mod:vecmanip.storm_wing")

(defn storm-wing-fx-snapshot
  []
  (storm-wing-fx-state-snapshot))

(defn reset-storm-wing-fx-for-test!
  []
  (reset! (storm-wing-fx-state-atom) (default-storm-wing-fx-runtime-state))
  nil)

(defn clear-storm-wing-owner!
  [owner-key]
  (update-storm-wing-fx-state! update :effect-state dissoc owner-key)
  nil)

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode phase charge-ticks charge-ratio source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        (update-storm-wing-fx-state!
          update :effect-state assoc owner-key*
          (merge base-meta
                 {:active? true :phase :charging :charge-ticks 0
                  :charge-ticks-needed (long (or charge-ticks 70))
                  :ticks 0}))
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id loop-sound :volume 0.8 :pitch 1.0}))
      :update
      (update-storm-wing-fx-state!
        update :effect-state update owner-key*
        (fn [st]
          (assoc (merge base-meta (or st {}))
                 :owner-key owner-key*
                 :ctx-id ctx-id
                 :channel channel
                 :source-player-id source-player-id
                 :world-id world-id
                 :active? true
                 :phase (or phase :charging)
                 :charge-ticks (long (or charge-ticks 0))
                 :charge-ratio (double (or charge-ratio 0.0)))))
      :end
      (update-storm-wing-fx-state!
        update :effect-state assoc owner-key* (merge base-meta {:active? false :ticks 0}))
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (update-storm-wing-fx-state!
    update :effect-state
    (fn [states]
      (into {}
            (keep (fn [[owner-key st]]
                    (when (:active? st)
                      (let [ticks (inc (long (or (:ticks st) 0)))]
                        (when (zero? (mod ticks 10))
                          (client-sounds/queue-sound-effect! (:queue-owner st)
                            {:type :sound :sound-id loop-sound :volume 0.5 :pitch 1.0}))
                        [owner-key (assoc st :ticks ticks)]))))
            states))))

(defn- matching-active-state [effect-state hand-center-pos]
  (some (fn [st]
          (when (and (:active? st)
                     (or (nil? (:source-player-id st))
                         (nil? (:player-uuid hand-center-pos))
                         (= (str (:source-player-id st))
                            (str (:player-uuid hand-center-pos)))))
            st))
        (vals effect-state)))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [_camera-pos hand-center-pos _tick]
  (let [effect-state (:effect-state (storm-wing-fx-state-snapshot))
        sw (matching-active-state effect-state hand-center-pos)]
    (when (and hand-center-pos sw (:active? sw))
      (let [center (dissoc hand-center-pos :player-uuid)
            sw-ticks (long (or (:ticks sw) 0))
            phase (or (:phase sw) :charging)
            charge-ratio (double (or (:charge-ratio sw) 0.0))
            ;; Spawn 12 dirt particles in sphere r=3-8 around player (flying only)
            _ (when (= phase :flying)
                (dotimes [_ 12]
                  (let [r  (+ 3.0 (* (rand) 5.0))
                        theta (* (rand) Math/PI)
                        phi   (* (rand) 2.0 Math/PI)]
                    (client-particles/queue-particle-effect! (:queue-owner sw)
                      {:type          :particle
                       :particle-type :block-crack
                       :block-id      "minecraft:dirt"
                       :x (+ (double (:x center)) (* r (Math/sin theta) (Math/cos phi)))
                       :y (+ (double (:y center)) (* r (Math/cos theta)))
                       :z (+ (double (:z center)) (* r (Math/sin theta) (Math/sin phi)))
                       :count 1
                       :speed 0.05
                       :offset-x 0.1 :offset-y 0.1 :offset-z 0.1}))))
            ;; Tornado rings
            alpha-raw (case phase
                        :charging (* 0.7 charge-ratio)
                        :flying   0.7
                        0.0)
            alpha (int (* 255 alpha-raw))
            offsets [[-0.1 -0.3 0.1 0]
                     [0.1  -0.3 0.1 45]
                     [-0.1 -0.5 -0.1 90]
                     [0.1  -0.5 -0.1 135]]]
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

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defn init! []
  (level-effects/register-level-effect! :storm-wing
    {:enqueue-event-fn enqueue!
     :tick-fn       tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:storm-wing/fx-start :storm-wing/fx-update :storm-wing/fx-end]
    (fn [ctx-id channel payload]
      (case channel
        :storm-wing/fx-start
        (level-effects/enqueue-level-effect! :storm-wing
          (merge (select-keys payload [:effect-instance-id :source-player-id :world-id])
                 {:mode :start :charge-ticks (long (or (:charge-ticks payload) 70))})
          {:ctx-id ctx-id :channel channel})
        :storm-wing/fx-update
        (level-effects/enqueue-level-effect! :storm-wing
          (merge (select-keys payload [:effect-instance-id :source-player-id :world-id])
                 {:mode :update
                  :phase (or (:phase payload) :charging)
                  :charge-ticks (long (or (:charge-ticks payload) 0))
                  :charge-ratio (double (or (:charge-ratio payload) 0.0))})
          {:ctx-id ctx-id :channel channel})
        :storm-wing/fx-end
        (level-effects/enqueue-level-effect! :storm-wing
                                             (merge (select-keys payload [:effect-instance-id :source-player-id :world-id])
                                                    {:mode :end})
                                             {:ctx-id ctx-id :channel channel}))))
  nil)
