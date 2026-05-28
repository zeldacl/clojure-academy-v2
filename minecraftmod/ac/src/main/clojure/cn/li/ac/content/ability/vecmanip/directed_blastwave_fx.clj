(ns cn.li.ac.content.ability.vecmanip.directed-blastwave-fx
  "Client FX for Directed Blastwave: charge ring + expanding wave rings."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defn default-directed-blastwave-fx-runtime-state
  []
  {:effect-state {}
   :waves {}})

(defn create-directed-blastwave-fx-runtime
  ([]
   (create-directed-blastwave-fx-runtime {}))
  ([{:keys [state*]
     :or {state* (atom (default-directed-blastwave-fx-runtime-state))}}]
   {::runtime ::directed-blastwave-fx-runtime
    :state* state*}))

(def ^:dynamic *directed-blastwave-fx-runtime* nil)

(defonce ^:private installed-directed-blastwave-fx-runtime
  (create-directed-blastwave-fx-runtime))

(defn- directed-blastwave-fx-runtime?
  [runtime]
  (and (map? runtime)
       (= ::directed-blastwave-fx-runtime (::runtime runtime))
       (some? (:state* runtime))))

(defn call-with-directed-blastwave-fx-runtime
  [runtime f]
  (when-not (directed-blastwave-fx-runtime? runtime)
    (throw (ex-info "Expected Directed Blastwave FX runtime"
                    {:value runtime})))
  (binding [*directed-blastwave-fx-runtime* runtime]
    (f)))

(defmacro with-directed-blastwave-fx-runtime
  [runtime & body]
  `(call-with-directed-blastwave-fx-runtime ~runtime (fn [] ~@body)))

(defn- current-directed-blastwave-fx-runtime
  []
  (or *directed-blastwave-fx-runtime*
      installed-directed-blastwave-fx-runtime))

(defn- directed-blastwave-fx-state-atom
  []
  (:state* (current-directed-blastwave-fx-runtime)))

(defn- directed-blastwave-fx-state-snapshot
  []
  @(directed-blastwave-fx-state-atom))

(defn- update-directed-blastwave-fx-state!
  [f & args]
  (apply swap! (directed-blastwave-fx-state-atom) f args))

(def ^:private sound-id "my_mod:vecmanip.directed_blast")
(def ^:private wave-life 15)

(defn directed-blastwave-fx-snapshot
  []
  (directed-blastwave-fx-state-snapshot))

(defn reset-directed-blastwave-fx-for-test!
  []
  (reset! (directed-blastwave-fx-state-atom) (default-directed-blastwave-fx-runtime-state))
  nil)

(defn clear-directed-blastwave-owner!
  [owner-key]
  (update-directed-blastwave-fx-state!
    (fn [state]
      (-> state
          (update :effect-state dissoc owner-key)
          (update :waves dissoc owner-key))))
  nil)

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [{:keys [payload ctx-id channel owner-key]}]
  (let [owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode charge-ticks punched? pos look-dir performed? source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (update-directed-blastwave-fx-state!
        update :effect-state assoc owner-key*
        (merge base-meta {:active? true :charge-ticks 0 :punched? false :performed? false}))
      :update
      (update-directed-blastwave-fx-state!
        update :effect-state update owner-key*
        (fn [st]
          (assoc (merge base-meta (or st {}))
                 :owner-key owner-key*
                 :ctx-id ctx-id
                 :channel channel
                 :source-player-id source-player-id
                 :world-id world-id
                 :active? true
                 :charge-ticks (long (or charge-ticks 0))
                 :punched? (boolean punched?)
                 :performed? false)))
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
                                                    (range rings)))})))]
        (when wave-entry
          (update-directed-blastwave-fx-state! update-in [:waves owner-key*] (fnil conj []) wave-entry))
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id sound-id :volume 0.5 :pitch 1.0
           :x (double (or (:x pos) 0.0))
           :y (double (or (:y pos) 0.0))
           :z (double (or (:z pos) 0.0))}))
      :end
      (update-directed-blastwave-fx-state!
        update :effect-state assoc owner-key*
        (merge base-meta {:active? false :charge-ticks 0 :punched? false
                          :performed? (boolean performed?)}))
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (update-directed-blastwave-fx-state!
    (fn [{:keys [effect-state waves] :as state}]
      (assoc state
             :effect-state
             (into {}
                   (keep (fn [[owner-key st]]
                           (when (:active? st)
                             [owner-key st])))
                   effect-state)
             :waves
             (into {}
                   (keep (fn [[owner-key xs]]
                           (let [live (->> xs
                                           (map #(update % :ttl dec))
                                           (filter #(pos? (long (:ttl %))))
                                           vec)]
                             (when (seq live)
                               [owner-key live]))))
                   waves)))))

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- alpha-curve [t]
  (cond
    (< t 0.0) 0.0
    (< t 0.2) (/ t 0.2)
    (< t 0.8) 1.0
    (< t 1.0) (- 1.0 (/ (- t 0.8) 0.2))
    :else 0.0))

(defn- size-scale [ticks]
  (let [x (min 1.62 (max 0.0 (/ (double ticks) 20.0)))]
    (cond
      (< x 0.2) (+ 0.4 (* (/ x 0.2) (- 0.8 0.4)))
      (<= x 1.62) (+ 0.8 (* (/ (- x 0.2) (- 1.62 0.2)) (- 1.5 0.8)))
      :else 1.5)))

(defn- basis [dir]
  (let [n-dir (ru/vnormalize dir)
        up-axis (if (> (Math/abs (double (:y n-dir))) 0.95)
                  {:x 1.0 :y 0.0 :z 0.0}
                  {:x 0.0 :y 1.0 :z 0.0})
        right (ru/vnormalize (ru/vcross n-dir up-axis))
        up (ru/vnormalize (ru/vcross right n-dir))]
    [right up n-dir]))

(defn- wave-ops [{:keys [pos dir ttl max-ttl rings]}]
  (let [ticks (- (long max-ttl) (long ttl))
        max-alpha (alpha-curve (/ (double ticks) (double (max 1 max-ttl))))
        ss (size-scale ticks)
        [right up forward] (basis dir)
        z-offset (/ (double ticks) 40.0)]
    (vec
      (mapcat
        (fn [{:keys [life offset size time-offset]}]
          (let [local-t (/ (- (double ticks) (double time-offset)) (double (max 1 life)))
                alpha (alpha-curve local-t)
                real-alpha (min max-alpha alpha)]
            (if (<= real-alpha 0.0)
              []
              (let [center (ru/v+ pos (ru/v* forward (+ (double offset) z-offset)))
                    ring-size (* (double size) ss)
                    side (ru/v* right (* ring-size 0.5))
                    vertical (ru/v* up (* ring-size 0.5))
                    p0 (ru/v+ (ru/v- center side) vertical)
                    p1 (ru/v+ (ru/v+ center side) vertical)
                    p2 (ru/v- (ru/v+ center side) vertical)
                    p3 (ru/v- (ru/v- center side) vertical)
                    alpha-i (int (max 0 (min 255 (* 255.0 real-alpha 0.7))))]
                [(ru/quad-op "my_mod:textures/effects/glow_circle.png"
                             p0 p1 p2 p3
                             {:r 255 :g 255 :b 255 :a alpha-i})]))))
        rings))))

(defn- charge-ops [center charge-ticks punched?]
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

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [_camera-pos hand-center-pos _tick]
  (let [{:keys [effect-state waves]} (directed-blastwave-fx-state-snapshot)
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

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defn init! []
  (level-effects/register-level-effect! :directed-blastwave
    {:enqueue-event-fn enqueue!
     :tick-fn       tick!
     :build-plan-fn build-plan})
  (fx-registry/register-fx-channels!
    [:directed-blastwave/fx-start :directed-blastwave/fx-update
     :directed-blastwave/fx-perform :directed-blastwave/fx-end]
    (fn [ctx-id channel payload]
      (case channel
        :directed-blastwave/fx-start
        (level-effects/enqueue-level-effect! :directed-blastwave {:mode :start}
                                             {:ctx-id ctx-id :channel channel})
        :directed-blastwave/fx-update
        (level-effects/enqueue-level-effect! :directed-blastwave
          {:mode :update
           :charge-ticks (long (or (:charge-ticks payload) 0))
           :punched? (boolean (:punched? payload))}
          {:ctx-id ctx-id :channel channel})
        :directed-blastwave/fx-perform
        (level-effects/enqueue-level-effect! :directed-blastwave
          {:mode :perform
           :pos (:pos payload) :look-dir (:look-dir payload)
           :charge-ticks (long (or (:charge-ticks payload) 0))}
          {:ctx-id ctx-id :channel channel})
        :directed-blastwave/fx-end
        (level-effects/enqueue-level-effect! :directed-blastwave
          {:mode :end :performed? (boolean (:performed? payload))}
          {:ctx-id ctx-id :channel channel}))))
  nil)
