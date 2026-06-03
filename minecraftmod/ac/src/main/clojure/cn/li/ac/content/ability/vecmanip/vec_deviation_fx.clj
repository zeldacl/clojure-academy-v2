(ns cn.li.ac.content.ability.vecmanip.vec-deviation-fx
  "Client FX for VecDeviation: ring overlay + deflection wave billboards."
  (:require [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]))

(def ^:private vec-deviation-effect-id :vec-deviation)
(def ^:private sound-id "my_mod:vecmanip.vec_deviation")

(defn default-vec-deviation-fx-runtime-state
  []
  {:effect-state {}
   :wave-effects {}})

(defn vec-deviation-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot vec-deviation-effect-id)
      (default-vec-deviation-fx-runtime-state)))

(defn reset-vec-deviation-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    vec-deviation-effect-id
    (default-vec-deviation-fx-runtime-state))
  nil)

(defn clear-vec-deviation-owner!
  [owner-key]
  (level-effects/update-effect-state!
    vec-deviation-effect-id
    (fn [state]
      (-> (or state (default-vec-deviation-fx-runtime-state))
          (update :effect-state dissoc owner-key)
          (update :wave-effects dissoc owner-key))))
  nil)

(defn- enqueue-state!
  [store {:keys [payload ctx-id channel owner-key]}]
  (let [store* (or store (default-vec-deviation-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode x y z marked? source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (assoc-in store* [:effect-state owner-key*]
                (merge base-meta {:active? true :ticks 0}))
      :end
      (assoc-in store* [:effect-state owner-key*]
                (merge base-meta {:active? false :ticks 0}))
      :stop-entity
      (if marked?
        (let [life (+ 10 (rand-int 6))]
          (update-in store* [:wave-effects owner-key*]
            (fnil conj [])
            (merge base-meta
                   {:x (double (or x 0.0))
                    :y (double (or y 0.0))
                    :z (double (or z 0.0))
                    :ttl life
                    :max-ttl life})))
        store*)
      :play
      (do
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id sound-id :volume 0.5 :pitch 1.0
           :x (double (or x 0.0)) :y (double (or y 0.0)) :z (double (or z 0.0))})
        store*)
      store*)))

(defn- tick-state!
  [store]
  (let [state* (or store (default-vec-deviation-fx-runtime-state))]
    (assoc state*
           :effect-state
           (into {}
                 (keep (fn [[owner-key st]]
                         (when (:active? st)
                           [owner-key (update st :ticks (fnil inc 0))])))
                 (:effect-state state*))
           :wave-effects
           (into {}
                 (keep (fn [[owner-key xs]]
                         (let [live (->> xs
                                         (map #(update % :ttl dec))
                                         (filter #(pos? (long (:ttl %))))
                                         vec)]
                           (when (seq live)
                             [owner-key live]))))
                 (:wave-effects state*)))))

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

(defn- ring-ops
  [center ticks]
  (let [radius (+ 0.7 (* 0.08 (Math/sin (* 0.19 (double ticks)))))
        y (+ (double (:y center)) 0.25)
        segments 28
        color {:r 210 :g 240 :b 255 :a 170}
        spoke-color {:r 170 :g 210 :b 245 :a 120}
        spoke-step (/ segments 4)]
    (vec
      (mapcat
        (fn [idx]
          (let [a0 (/ (* 2.0 Math/PI idx) segments)
                a1 (/ (* 2.0 Math/PI (inc idx)) segments)
                p0 {:x (+ (:x center) (* radius (Math/cos a0))) :y y :z (+ (:z center) (* radius (Math/sin a0)))}
                p1 {:x (+ (:x center) (* radius (Math/cos a1))) :y y :z (+ (:z center) (* radius (Math/sin a1)))}
                ring-op (ru/line-op p0 p1 color)
                spoke-op (when (zero? (mod idx spoke-step))
                           (ru/line-op center p0 spoke-color))]
            (if spoke-op [ring-op spoke-op] [ring-op])))
        (range segments)))))

(defn- wave-ops
  [cam-pos {:keys [x y z ttl max-ttl]}]
  (let [life (/ (double ttl) (double (max 1 max-ttl)))
        alpha (int (max 0 (min 255 (* 170.0 life))))
        center {:x (double x) :y (+ (double y) 0.6) :z (double z)}
        right (ru/camera-facing-right-axis center cam-pos)
        up (ru/billboard-up-axis center cam-pos right)
        half-size (+ 0.35 (* 0.65 (- 1.0 life)))
        side (ru/v* right half-size)
        lift (ru/v* up half-size)
        p0 (ru/v+ (ru/v- center side) lift)
        p1 (ru/v+ (ru/v+ center side) lift)
        p2 (ru/v- (ru/v+ center side) lift)
        p3 (ru/v- (ru/v- center side) lift)]
    [(ru/quad-op "my_mod:textures/effects/glow_circle.png"
                 p0 p1 p2 p3
                 {:r 190 :g 225 :b 255 :a alpha})]))

(defn- build-plan
  [camera-pos hand-center-pos _tick]
  (let [{:keys [effect-state wave-effects]} (vec-deviation-fx-snapshot)
        vd (matching-active-state effect-state hand-center-pos)
        current-waves (mapcat val wave-effects)
        ring-plan (if (and hand-center-pos vd (:active? vd))
                    (ring-ops (dissoc hand-center-pos :player-uuid)
                              (long (or (:ticks vd) 0)))
                    [])
        wave-plan (mapcat #(wave-ops camera-pos %) current-waves)]
    (when (or (seq ring-plan) (seq wave-plan))
      {:ops (vec (concat ring-plan wave-plan))})))

(defn init!
  []
  (fx-spec/register!
    {:id vec-deviation-effect-id
     :level {:initial-state (default-vec-deviation-fx-runtime-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan}
     :channels {:start {:topic :vec-deviation/fx-start :mode :start}
                :end {:topic :vec-deviation/fx-end :mode :end}
                :stop-entity {:topic :vec-deviation/fx-stop-entity :mode :stop-entity
                              :level-payload (fn [_ _ p]
                                               {:x (double (or (:x p) 0.0))
                                                :y (double (or (:y p) 0.0))
                                                :z (double (or (:z p) 0.0))
                                                :marked? (boolean (:marked? p))})}
                :play {:topic :vec-deviation/fx-play :mode :play
                       :level-payload (fn [_ _ p]
                                        {:x (double (or (:x p) 0.0))
                                         :y (double (or (:y p) 0.0))
                                         :z (double (or (:z p) 0.0))})}}})
  nil)
