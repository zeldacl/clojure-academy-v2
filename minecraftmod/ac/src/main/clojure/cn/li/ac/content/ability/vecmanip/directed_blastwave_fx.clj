(ns cn.li.ac.content.ability.vecmanip.directed-blastwave-fx
  "Client FX for Directed Blastwave: charge ring + expanding wave rings."
  (:require [cn.li.ac.util.math.vec3 :as vec3] [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]))

(def ^:private directed-blastwave-effect-id :directed-blastwave)
(def ^:private sound-id "my_mod:vecmanip.directed_blast")
(def ^:private wave-life 15)

(defn default-directed-blastwave-fx-runtime-state
  []
  {:effect-state {}
   :waves {}})

(defn directed-blastwave-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot directed-blastwave-effect-id)
      (default-directed-blastwave-fx-runtime-state)))

(defn reset-directed-blastwave-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    directed-blastwave-effect-id
    (default-directed-blastwave-fx-runtime-state))
  nil)

(defn clear-directed-blastwave-owner!
  [owner-key]
  (level-effects/update-effect-state!
    directed-blastwave-effect-id
    (fn [state]
      (-> (or state (default-directed-blastwave-fx-runtime-state))
          (update :effect-state dissoc owner-key)
          (update :waves dissoc owner-key))))
  nil)

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (or store (default-directed-blastwave-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode charge-ticks punched? pos look-dir performed? source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (assoc-in store* [:effect-state owner-key*]
                (merge base-meta {:active? true :charge-ticks 0 :punched? false :performed? false}))
      :update
      (assoc-in store* [:effect-state owner-key*]
                (assoc (merge base-meta (get-in store* [:effect-state owner-key*] {}))
                       :owner-key owner-key*
                       :ctx-id ctx-id
                       :channel channel
                       :source-player-id source-player-id
                       :world-id world-id
                       :active? true
                       :charge-ticks (long (or charge-ticks 0))
                       :punched? (boolean punched?)
                       :performed? false))
      :perform
      (let [wave-entry (when (map? pos)
                         (let [d (or look-dir {:x 0.0 :y 0.0 :z 1.0})
                               len (Math/sqrt (+ (* (:x d) (:x d))
                                                 (* (:y d) (:y d))
                                                 (* (:z d) (:z d))))
                               inv (/ 1.0 (max 1.0e-6 len))
                               dir {:x (* (double (:x d)) inv)
                                    :y (* (double (:y d)) inv)
                                    :z (* (double (:z d)) inv)}
                               rings (+ 2 (rand-int 2))]
                           (merge base-meta
                                  {:pos {:x (double (:x pos)) :y (double (:y pos)) :z (double (:z pos))}
                                   :dir dir
                                   :ttl wave-life :max-ttl wave-life
                                   :rings (vec (map (fn [idx]
                                                      {:life (+ 8 (rand-int 5))
                                                       :offset (+ (* idx 1.5) (- (* (rand) 0.6) 0.3))
                                                       :size (* 1.0 (+ 0.8 (* (rand) 0.4)))
                                                       :time-offset (+ (* idx 2) (- (rand-int 3) 1))})
                                                    (range rings)))})))
            updated-store (if wave-entry
                            (update-in store* [:waves owner-key*] (fnil conj []) wave-entry)
                            store*)]
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id sound-id :volume 0.5 :pitch 1.0
           :x (double (or (:x pos) 0.0))
           :y (double (or (:y pos) 0.0))
           :z (double (or (:z pos) 0.0))})
        updated-store)
      :end
      (assoc-in store* [:effect-state owner-key*]
                (merge base-meta {:active? false :charge-ticks 0 :punched? false
                                  :performed? (boolean performed?)}))
      store*)))

(defn- tick-state!
  [store]
  (let [state* (or store (default-directed-blastwave-fx-runtime-state))]
    (assoc state*
           :effect-state
           (into {}
                 (keep (fn [[owner-key st]]
                         (when (:active? st)
                           [owner-key st])))
                 (:effect-state state*))
           :waves
           (into {}
                 (keep (fn [[owner-key xs]]
                         (let [live (->> xs
                                         (map #(update % :ttl dec))
                                         (filter #(pos? (long (:ttl %))))
                                         vec)]
                           (when (seq live)
                             [owner-key live]))))
                 (:waves state*)))))

(defn- alpha-curve
  [t]
  (cond
    (< t 0.0) 0.0
    (< t 0.2) (/ t 0.2)
    (< t 0.8) 1.0
    (< t 1.0) (- 1.0 (/ (- t 0.8) 0.2))
    :else 0.0))

(defn- size-scale
  [ticks]
  (let [x (min 1.62 (max 0.0 (/ (double ticks) 20.0)))]
    (cond
      (< x 0.2) (+ 0.4 (* (/ x 0.2) (- 0.8 0.4)))
      (<= x 1.62) (+ 0.8 (* (/ (- x 0.2) (- 1.62 0.2)) (- 1.5 0.8)))
      :else 1.5)))

(defn- basis
  [dir]
  (let [n-dir (vec3/vnorm dir)
        up-axis (if (> (Math/abs (double (:y n-dir))) 0.95)
                  {:x 1.0 :y 0.0 :z 0.0}
                  {:x 0.0 :y 1.0 :z 0.0})
        right (vec3/vnorm (vec3/vcross n-dir up-axis))
        up (vec3/vnorm (vec3/vcross right n-dir))]
    [right up n-dir]))

(defn- wave-ops
  [{:keys [pos dir ttl max-ttl rings]}]
  (let [ticks (- (long max-ttl) (long ttl))
        max-alpha (alpha-curve (/ (double ticks) (double (max 1 max-ttl))))
        ss (size-scale ticks)
        [right up forward] (basis dir)
        z-offset (/ (double ticks) 40.0)]
    (vec
      (mapcat
        (fn [{:keys [life offset size time-offset]}]
          (let [local-t (- (/ (double ticks) (double (max 1 life))) (/ (double time-offset) (double (max 1 life))))
                alpha (alpha-curve local-t)
                real-alpha (min max-alpha alpha)]
            (if (<= real-alpha 0.0)
              []
              (let [center (vec3/v+ pos (vec3/v* forward (+ (double offset) z-offset)))
                    ring-size (* (double size) ss)
                    side (vec3/v* right (* ring-size 0.5))
                    vertical (vec3/v* up (* ring-size 0.5))
                    p0 (vec3/v+ (vec3/v- center side) vertical)
                    p1 (vec3/v+ (vec3/v+ center side) vertical)
                    p2 (vec3/v- (vec3/v+ center side) vertical)
                    p3 (vec3/v- (vec3/v- center side) vertical)
                    alpha-i (int (max 0 (min 255 (* 255.0 real-alpha 0.7))))]
                [(ru/quad-op "my_mod:textures/effects/glow_circle.png"
                             p0 p1 p2 p3
                             {:r 255 :g 255 :b 255 :a alpha-i})]))))
        rings))))

(defn- charge-ops
  [center charge-ticks punched?]
  (let [progress (min 1.0 (/ (double charge-ticks) 50.0))
        radius (+ 0.1 (* 0.16 progress))
        pulse (+ radius (* 0.025 (Math/sin (* 0.22 charge-ticks))))
        points 16
        alpha (if punched? 220 170)
        color {:r 225 :g 245 :b 255 :a alpha}
        core {:r 178 :g 220 :b 245 :a (int (* 0.7 alpha))}]
    (vec
      (mapcat
        (fn [idx]
          (let [a0 (/ (* 2.0 Math/PI idx) points)
                a1 (/ (* 2.0 Math/PI (inc idx)) points)
                p0 {:x (+ (:x center) (* pulse (Math/cos a0)))
                    :y (:y center)
                    :z (+ (:z center) (* pulse (Math/sin a0)))}
                p1 {:x (+ (:x center) (* pulse (Math/cos a1)))
                    :y (:y center)
                    :z (+ (:z center) (* pulse (Math/sin a1)))}]
            [(ru/line-op p0 p1 color)
             (ru/line-op center p0 core)]))
        (range points)))))

(defn- build-plan
  [_camera-pos hand-center-pos _tick]
  (let [{:keys [effect-state waves]} (directed-blastwave-fx-snapshot)
        db (some (fn [st]
                   (when (and (:active? st)
                              (or (nil? (:source-player-id st))
                                  (nil? (:player-uuid hand-center-pos))
                                  (= (str (:source-player-id st))
                                     (str (:player-uuid hand-center-pos)))))
                     st))
                 (vals effect-state))
        current-waves (mapcat val waves)
        charge-plan (if (and hand-center-pos db (:active? db))
                      (charge-ops (dissoc hand-center-pos :player-uuid)
                                  (long (or (:charge-ticks db) 0))
                                  (boolean (:punched? db)))
                      [])
        wave-plan (mapcat wave-ops current-waves)]
    (when (or (seq charge-plan) (seq wave-plan))
      {:ops (vec (concat charge-plan wave-plan))})))

(defn init!
  []
  (fx-spec/register!
    {:id directed-blastwave-effect-id
     :level {:initial-state (default-directed-blastwave-fx-runtime-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan}
     :channels {:start {:topic :directed-blastwave/fx-start :mode :start}
                :update {:topic :directed-blastwave/fx-update :mode :update
                         :level-payload (fn [_ _ p]
                                          {:charge-ticks (long (or (:charge-ticks p) 0))
                                           :punched? (boolean (:punched? p))})}
                :perform {:topic :directed-blastwave/fx-perform :mode :perform
                          :level-payload (fn [_ _ p]
                                           {:pos (:pos p) :look-dir (:look-dir p)
                                            :charge-ticks (long (or (:charge-ticks p) 0))})}
                :end {:topic :directed-blastwave/fx-end :mode :end
                      :level-payload (fn [_ _ p]
                                       {:performed? (boolean (:performed? p))})}}})
  nil)
