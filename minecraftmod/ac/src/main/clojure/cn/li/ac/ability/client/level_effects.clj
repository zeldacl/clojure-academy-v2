(ns cn.li.ac.ability.client.level-effects
  "Pure level effect state + render-plan generation for client-side ability visuals."
  (:require [cn.li.ac.ability.client.ability-runtime :as client-runtime]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private beam-effects (atom []))
(def ^:private beam-life-ticks 12)
(defonce ^:private mag-movement-effect (atom nil))
(def ^:private mag-movement-loop-sound "my_mod:em.move_loop")
(defonce ^:private mark-teleport-effect (atom nil))

(defonce ^:private thunder-bolt-arcs (atom []))
(def ^:private tb-main-arc-life 20)
(def ^:private tb-aoe-arc-life 20)

(defonce ^:private thunder-clap-effect (atom nil))

(defonce ^:private meltdowner-effect (atom nil))
(defonce ^:private meltdowner-rays (atom []))
(def ^:private meltdowner-charge-loop-sound "my_mod:md.md_charge")
(def ^:private meltdowner-fire-sound "my_mod:md.meltdowner")

(defonce ^:private blood-retrograde-effect (atom nil))
(defonce ^:private blood-retrograde-splashes (atom []))
(defonce ^:private blood-retrograde-sprays (atom []))
(def ^:private blood-retrograde-sound "my_mod:vecmanip.blood_retro")
(def ^:private blood-retrograde-splash-life 10)
(def ^:private blood-retrograde-spray-life 1200)

(defonce ^:private directed-blastwave-effect (atom nil))
(defonce ^:private directed-blastwave-waves (atom []))
(def ^:private directed-blastwave-sound "my_mod:vecmanip.directed_blast")
(def ^:private directed-blastwave-wave-life 15)

(def ^:private groundshock-sound "my_mod:vecmanip.groundshock")

;; Plasma Cannon: looping charge sound + charged-complete sound
;; Original: "vecmanip.plasma_cannon" (FollowEntitySound loop) and "vecmanip.plasma_cannon_t"
(defonce ^:private plasma-cannon-effect (atom nil))
(def ^:private plasma-cannon-loop-sound "my_mod:vecmanip.plasma_cannon")
(def ^:private plasma-cannon-charged-sound "my_mod:vecmanip.plasma_cannon_t")

(declare tick-thunder-bolt-arcs!)


(defn enqueue-level-effect! [effect-id payload]
  (case effect-id
    :railgun-shot
    (let [{:keys [mode start end hit-distance]} payload]
      (when (and start end)
        (swap! beam-effects conj {:start start
                                  :end end
                                  :mode (or mode :block-hit)
                                  :hit-distance (double (or hit-distance 18.0))
                                  :ttl beam-life-ticks
                                  :max-ttl beam-life-ticks})))

    :mag-movement
    (let [{:keys [mode target]} payload]
      (case mode
        :start
        (do
          (reset! mag-movement-effect {:active? true
                                       :target target
                                       :ticks 0})
          (client-sounds/queue-sound-effect!
            {:type :sound
             :sound-id mag-movement-loop-sound
             :volume 0.58
             :pitch 1.0}))

        :update
        (swap! mag-movement-effect
               (fn [st]
                 (if (:active? st)
                   (assoc st :target target)
                   {:active? true :target target :ticks 0})))

        :end
        (reset! mag-movement-effect nil)

        nil))

      :mark-teleport
      (let [{:keys [mode target distance]} payload]
        (case mode
          :start
          (reset! mark-teleport-effect {:active? true
                                        :target nil
                                        :distance 0.0
                                        :ticks 0})

          :update
          (swap! mark-teleport-effect
                 (fn [st]
                   (assoc (or st {})
                          :active? true
                          :target target
                          :distance (double (or distance 0.0))
                          :ticks (long (or (:ticks st) 0)))))

          :perform
          (do
            (when (map? target)
              (client-particles/queue-particle-effect!
                {:type :particle
                 :particle-type :portal
                 :x (:x target)
                 :y (double (or (:y target) 0.0))
                 :z (:z target)
                 :count 16
                 :speed 0.08
                 :offset-x 0.9
                 :offset-y 0.8
                 :offset-z 0.9}))
            (client-sounds/queue-sound-effect!
              {:type :sound
               :sound-id "my_mod:tp.tp"
               :volume 0.5
               :pitch 1.0}))

          :end
          (reset! mark-teleport-effect nil)

          nil))

      :thunder-clap
      (let [{:keys [mode ticks charge-ratio target performed?]} payload]
        (case mode
          :start
          (reset! thunder-clap-effect {:active? true
                                       :ticks 0
                                       :charge-ratio 0.0
                                       :target nil
                                       :performed? false})

          :update
          (swap! thunder-clap-effect
                 (fn [st]
                   (assoc (or st {})
                          :active? true
                          :ticks (long (or ticks 0))
                          :charge-ratio (double (or charge-ratio 0.0))
                          :target target)))

          :end
          (reset! thunder-clap-effect {:active? false
                                       :performed? (boolean performed?)
                                       :ticks 0
                                       :charge-ratio 0.0
                                       :target nil})

          nil))

      :thunder-bolt-strike
      (let [{:keys [start end aoe-points]} payload]
        (when (and start end)
          ;; 3 main arcs from caster eye to strike point (matching original 3 EntityArc)
          (dotimes [_ 3]
            (swap! thunder-bolt-arcs conj {:start start :end end
                                           :ttl tb-main-arc-life
                                           :max-ttl tb-main-arc-life
                                           :is-aoe? false}))
          ;; AOE arcs from strike point to each nearby entity
          (doseq [pt aoe-points]
            (when (map? pt)
              (let [life (+ 15 (rand-int 11))]
                (swap! thunder-bolt-arcs conj {:start end :end pt
                                               :ttl life :max-ttl life
                                               :is-aoe? true}))))
          ;; Sound (original: ACSounds.playClient "em.arc_strong" volume 0.6)
          (client-sounds/queue-sound-effect!
            {:type :sound
             :sound-id "my_mod:em.arc_strong"
             :volume 0.6
             :pitch 1.0})))

      :meltdowner
      (let [{:keys [mode ticks charge-ratio performed? start end charge-ticks beam-length]} payload]
        (case mode
          :start
          (do
            (reset! meltdowner-effect {:active? true
                                       :ticks 0
                                       :charge-ratio 0.0
                                       :performed? false})
            (client-sounds/queue-sound-effect!
              {:type :sound
               :sound-id meltdowner-charge-loop-sound
               :volume 1.0
               :pitch 1.0}))

          :update
          (swap! meltdowner-effect
                 (fn [st]
                   (assoc (or st {})
                          :active? true
                          :ticks (long (or ticks 0))
                          :charge-ratio (double (or charge-ratio 0.0))
                          :performed? false)))

          :end
          (reset! meltdowner-effect {:active? false
                                     :performed? (boolean performed?)
                                     :ticks 0
                                     :charge-ratio 0.0})

          :perform
          (do
            (when (and start end)
              (let [life (+ 16 (rand-int 8))]
                (swap! meltdowner-rays conj {:start start
                                             :end end
                                             :ttl life
                                             :max-ttl life
                                             :beam-length (double (or beam-length 30.0))
                                             :charge-ticks (int (or charge-ticks 20))
                                             :is-reflect? false})))
            (client-sounds/queue-sound-effect!
              {:type :sound
               :sound-id meltdowner-fire-sound
               :volume 0.5
               :pitch 1.0}))

          :reflect
          (when (and start end)
            (let [life (+ 10 (rand-int 6))]
              (swap! meltdowner-rays conj {:start start
                                           :end end
                                           :ttl life
                                           :max-ttl life
                                           :beam-length 10.0
                                           :charge-ticks 20
                                           :is-reflect? true})))

          nil))

                :blood-retrograde
                (let [{:keys [mode ticks charge-ratio performed? splashes sprays sound-pos]} payload]
             (case mode
               :start
               (reset! blood-retrograde-effect {:active? true
                       :ticks 0
                       :charge-ratio 0.0
                       :performed? false})

               :update
               (swap! blood-retrograde-effect
                 (fn [st]
                   (assoc (or st {})
                     :active? true
                     :ticks (long (or ticks 0))
                     :charge-ratio (double (or charge-ratio 0.0))
                     :performed? false)))

               :perform
               (do
                 (swap! blood-retrograde-splashes into
                   (map (fn [splash]
                     (assoc splash
                       :ttl blood-retrograde-splash-life
                       :max-ttl blood-retrograde-splash-life))
                   splashes))
                 (swap! blood-retrograde-sprays into
                   (map (fn [spray]
                     (assoc spray
                       :ttl blood-retrograde-spray-life
                       :max-ttl blood-retrograde-spray-life))
                   sprays))
                 (client-sounds/queue-sound-effect!
              {:type :sound
               :sound-id blood-retrograde-sound
               :volume 1.0
               :pitch 1.0
               :x (:x sound-pos)
               :y (:y sound-pos)
               :z (:z sound-pos)}))

               :end
               (reset! blood-retrograde-effect {:active? false
                       :ticks 0
                       :charge-ratio 0.0
                       :performed? (boolean performed?)})

               nil))

      :directed-blastwave
      (let [{:keys [mode charge-ticks punched? pos look-dir performed?]} payload]
        (case mode
          :start
          (reset! directed-blastwave-effect {:active? true
                                             :charge-ticks 0
                                             :punched? false
                                             :performed? false})

          :update
          (swap! directed-blastwave-effect
                 (fn [st]
                   (assoc (or st {})
                          :active? true
                          :charge-ticks (long (or charge-ticks 0))
                          :punched? (boolean punched?)
                          :performed? false)))

          :perform
          (do
            (when (map? pos)
              (let [dir (let [d (or look-dir {:x 0.0 :y 0.0 :z 1.0})
                              len (Math/sqrt (+ (* (:x d) (:x d))
                                                (* (:y d) (:y d))
                                                (* (:z d) (:z d))))
                              inv (/ 1.0 (max 1.0e-6 len))]
                          {:x (* (double (:x d)) inv)
                           :y (* (double (:y d)) inv)
                           :z (* (double (:z d)) inv)})
                    rings (+ 2 (rand-int 2))]
                (swap! directed-blastwave-waves conj
                       {:pos {:x (double (:x pos))
                              :y (double (:y pos))
                              :z (double (:z pos))}
                        :dir dir
                        :ttl directed-blastwave-wave-life
                        :max-ttl directed-blastwave-wave-life
                        :rings (vec (map (fn [idx]
                                           {:life (+ 8 (rand-int 5))
                                            :offset (+ (* idx 1.5) (- (* (rand) 0.6) 0.3))
                                            :size (* 1.0 (+ 0.8 (* (rand) 0.4)))
                                            :time-offset (+ (* idx 2) (- (rand-int 3) 1))})
                                         (range rings)))})))
            (client-sounds/queue-sound-effect!
              {:type :sound
               :sound-id directed-blastwave-sound
               :volume 0.5
               :pitch 1.0
               :x (double (or (:x pos) 0.0))
               :y (double (or (:y pos) 0.0))
               :z (double (or (:z pos) 0.0))}))

          :end
          (reset! directed-blastwave-effect {:active? false
                                             :charge-ticks 0
                                             :punched? false
                                             :performed? (boolean performed?)})

          nil))

      :groundshock
      (let [{:keys [mode affected-blocks]} payload]
        (case mode
          :perform
          (do
            (client-sounds/queue-sound-effect!
              {:type :sound
               :sound-id groundshock-sound
               :volume 2.0
               :pitch 1.0})
            (doseq [{:keys [x y z block-id]} affected-blocks]
              (client-particles/queue-particle-effect!
                {:type :particle
                 :particle-type :block-crack
                 :block-id (or block-id "minecraft:stone")
                 :x (+ (double x) 0.5)
                 :y (+ (double y) 1.0)
                 :z (+ (double z) 0.5)
                 :count (+ 4 (rand-int 4))
                 :speed 0.2
                 :offset-x 1.0
                 :offset-y 0.6
                 :offset-z 1.0})
              (when (< (rand) 0.5)
                (client-particles/queue-particle-effect!
                  {:type :particle
                   :particle-type :smoke
                   :x (+ (double x) 0.5 (- (* (rand) 0.6) 0.3))
                   :y (+ (double y) 1.0 (* (rand) 0.2))
                   :z (+ (double z) 0.5 (- (* (rand) 0.6) 0.3))
                   :count 1
                   :speed 0.04
                   :offset-x 0.04
                   :offset-y 0.05
                   :offset-z 0.04})))
            nil)

          nil))

      :plasma-cannon
      (let [{:keys [mode charge-ticks fully-charged? charge-pos flight-ticks
                    state destination pos performed?]} payload]
        (case mode
          :start
          ;; Original: c_begin() – spawn PlasmaBodyEffect, Tornado, start loop sound
          (do
            (reset! plasma-cannon-effect {:active?      true
                                          :charge-ticks 0
                                          :charge-pos   nil
                                          :flight-ticks 0
                                          :state        :charging
                                          :destination  nil
                                          :performed?   false})
            ;; Start looping charge sound (original: FollowEntitySound "vecmanip.plasma_cannon")
            (client-sounds/queue-sound-effect!
              {:type     :sound
               :sound-id plasma-cannon-loop-sound
               :volume   0.5
               :pitch    1.0}))

          :update
          (do
            (swap! plasma-cannon-effect
                   (fn [st]
                     (assoc (or st {})
                            :active?      true
                            :charge-ticks (long (or charge-ticks 0))
                            :flight-ticks (long (or flight-ticks 0))
                            :state        (or state :charging)
                            :charge-pos   (or charge-pos (:charge-pos st))
                            :destination  (or destination (:destination st)))))
            ;; Original: when (state == STATE_CHARGING && localTicker == chargeTime)
            ;;   → ACSounds.playClient "vecmanip.plasma_cannon_t" volume 0.5
            (when (boolean fully-charged?)
              (client-sounds/queue-sound-effect!
                {:type     :sound
                 :sound-id plasma-cannon-charged-sound
                 :volume   0.5
                 :pitch    1.0})))

          :perform
          ;; Original: explode() → world explosion visual + sound handled server-side,
          ;; client shows particle burst at impact position
          (when (map? pos)
            (let [tx (double (:x pos))
                  ty (double (:y pos))
                  tz (double (:z pos))]
              ;; Large particle burst at explosion site
              (client-particles/queue-particle-effect!
                {:type          :particle
                 :particle-type :explosion-large
                 :x tx :y ty :z tz
                 :count 1
                 :speed 0.0
                 :offset-x 0.0 :offset-y 0.0 :offset-z 0.0})
              (dotimes [_ 12]
                (client-particles/queue-particle-effect!
                  {:type          :particle
                   :particle-type :smoke-large
                   :x (+ tx (- (* (rand) 10.0) 5.0))
                   :y (+ ty (- (* (rand) 5.0) 2.5))
                   :z (+ tz (- (* (rand) 10.0) 5.0))
                   :count 1
                   :speed (+ 0.1 (* (rand) 0.3))
                   :offset-x 0.5 :offset-y 0.5 :offset-z 0.5}))
              ;; Explosion sound at impact
              (client-sounds/queue-sound-effect!
                {:type     :sound
                 :sound-id "minecraft:entity.generic.explode"
                 :volume   3.0
                 :pitch    0.8
                 :x tx :y ty :z tz})))

          :end
          ;; Original: c_terminate() – stop loop sound
          (do
            (reset! plasma-cannon-effect {:active?    false
                                          :performed? (boolean performed?)}))

          nil))

      nil))

(defn tick-level-effects! []
  (swap! beam-effects
         (fn [xs]
           (->> xs
                (map #(update % :ttl dec))
                (filter #(pos? (long (:ttl %))))
                vec)))
  (swap! mag-movement-effect
         (fn [st]
           (when (:active? st)
             (let [ticks (inc (long (or (:ticks st) 0)))]
               (when (zero? (mod ticks 10))
                 (client-sounds/queue-sound-effect!
                   {:type :sound
                    :sound-id mag-movement-loop-sound
                    :volume 0.4
                    :pitch 1.0}))
               (assoc st :ticks ticks)))))
    (swap! mark-teleport-effect
           (fn [st]
             (when (:active? st)
               (let [ticks (inc (long (or (:ticks st) 0)))
                     target (:target st)]
                 (when (and target (zero? (mod ticks 3)))
                   (client-particles/queue-particle-effect!
                     {:type :particle
                      :particle-type :portal
                      :x (:x target)
                      :y (- (double (or (:y target) 0.0)) 0.5)
                      :z (:z target)
                      :count 2
                      :speed 0.03
                      :offset-x 0.9
                      :offset-y 0.7
                      :offset-z 0.9}))
                 (assoc st :ticks ticks)))))
  (swap! thunder-clap-effect
         (fn [st]
           (when st
             (if (:active? st)
               (update st :ticks (fnil inc 0))
               nil))))
  (swap! meltdowner-effect
         (fn [st]
           (when st
             (if (:active? st)
               (let [ticks (inc (long (or (:ticks st) 0)))]
                 (when (zero? (mod ticks 10))
                   (client-sounds/queue-sound-effect!
                     {:type :sound
                      :sound-id meltdowner-charge-loop-sound
                      :volume 0.75
                      :pitch 1.0}))
                 (assoc st :ticks ticks))
               nil))))
  (swap! blood-retrograde-effect
         (fn [st]
           (when st
             (if (:active? st)
               (update st :ticks (fnil inc 0))
               nil))))
  (swap! directed-blastwave-effect
         (fn [st]
           (when st
             (if (:active? st)
               st
               nil))))
  ;; Plasma cannon: replay loop sound every 10 ticks while active
  ;; (original: FollowEntitySound that loops automatically)
  (swap! plasma-cannon-effect
         (fn [st]
           (when st
             (if (:active? st)
               (let [ticks (inc (long (or (:ticks st) 0)))]
                 (when (zero? (mod ticks 10))
                   (client-sounds/queue-sound-effect!
                     {:type     :sound
                      :sound-id plasma-cannon-loop-sound
                      :volume   0.4
                      :pitch    1.0}))
                 ;; Emit glow/flame particles at projectile's current position during flight
                 (let [cp (:charge-pos st)]
                   (when (and cp (= :go (:state st)))
                     (client-particles/queue-particle-effect!
                       {:type          :particle
                        :particle-type :flame
                        :x (double (:x cp))
                        :y (double (:y cp))
                        :z (double (:z cp))
                        :count 4
                        :speed 0.2
                        :offset-x 0.5 :offset-y 0.5 :offset-z 0.5})))
                 (assoc st :ticks ticks))
               nil))))
  (swap! meltdowner-rays
         (fn [xs]
           (->> xs
                (map #(update % :ttl dec))
                (filter #(pos? (long (:ttl %))))
                vec)))
  (swap! directed-blastwave-waves
         (fn [xs]
           (->> xs
                (map #(update % :ttl dec))
                (filter #(pos? (long (:ttl %))))
                vec)))
  (swap! blood-retrograde-splashes
         (fn [xs]
           (->> xs
                (map #(update % :ttl dec))
                (filter #(pos? (long (:ttl %))))
                vec)))
  (swap! blood-retrograde-sprays
         (fn [xs]
           (->> xs
                (map #(update % :ttl dec))
                (filter #(pos? (long (:ttl %))))
                vec)))
  (tick-thunder-bolt-arcs!))

(defn tick-thunder-bolt-arcs! []
  (swap! thunder-bolt-arcs
         (fn [xs]
           (->> xs
                (map #(update % :ttl dec))
                (filter #(pos? (long (:ttl %))))
                vec))))

(defn- v+ [a b]
  {:x (+ (double (:x a)) (double (:x b)))
   :y (+ (double (:y a)) (double (:y b)))
   :z (+ (double (:z a)) (double (:z b)))})

(defn- v- [a b]
  {:x (- (double (:x a)) (double (:x b)))
   :y (- (double (:y a)) (double (:y b)))
   :z (- (double (:z a)) (double (:z b)))})

(defn- v* [a scalar]
  {:x (* (double (:x a)) (double scalar))
   :y (* (double (:y a)) (double scalar))
   :z (* (double (:z a)) (double scalar))})

(defn- vlen [v]
  (Math/sqrt (+ (* (:x v) (:x v)) (* (:y v) (:y v)) (* (:z v) (:z v)))))

(defn- vnormalize [v]
  (let [len (max 1.0e-6 (vlen v))]
    (v* v (/ 1.0 len))))

(defn- vcross [a b]
  {:x (- (* (:y a) (:z b)) (* (:z a) (:y b)))
   :y (- (* (:z a) (:x b)) (* (:x a) (:z b)))
   :z (- (* (:x a) (:y b)) (* (:y a) (:x b)))})

(defn- with-alpha [color alpha]
  (assoc color :a (int (max 0 (min 255 alpha)))))

(defn- quad-op [texture p0 p1 p2 p3 color]
  {:kind :quad
   :texture texture
   :p0 p0 :p1 p1 :p2 p2 :p3 p3
   :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
   :color color})

(defn- line-op [p1 p2 color]
  {:kind :line :p1 p1 :p2 p2 :color color})

(defn- beam-right-axis [start end cam-pos]
  (let [dir (vnormalize (v- end start))
        mid (v* (v+ start end) 0.5)
        to-cam (vnormalize (v- cam-pos mid))
        raw (vcross dir to-cam)]
    (if (> (vlen raw) 1.0e-5)
      (vnormalize raw)
      {:x 1.0 :y 0.0 :z 0.0})))

(defn- camera-facing-right-axis [center cam-pos]
  (let [to-cam (vnormalize (v- cam-pos center))
        up {:x 0.0 :y 1.0 :z 0.0}
        raw (vcross up to-cam)]
    (if (> (vlen raw) 1.0e-5)
      (vnormalize raw)
      {:x 1.0 :y 0.0 :z 0.0})))

(defn- directed-blastwave-alpha-curve [t]
  (cond
    (< t 0.0) 0.0
    (< t 0.2) (/ t 0.2)
    (< t 0.8) 1.0
    (< t 1.0) (- 1.0 (/ (- t 0.8) 0.2))
    :else 0.0))

(defn- directed-blastwave-size-scale [ticks]
  (let [x (min 1.62 (max 0.0 (/ (double ticks) 20.0)))]
    (cond
      (< x 0.2)
      (+ 0.4 (* (/ x 0.2) (- 0.8 0.4)))

      (<= x 1.62)
      (+ 0.8 (* (/ (- x 0.2) (- 1.62 0.2)) (- 1.5 0.8)))

      :else 1.5)))

(defn- directed-blastwave-basis [dir]
  (let [n-dir (vnormalize dir)
        up-axis (if (> (Math/abs (double (:y n-dir))) 0.95)
                  {:x 1.0 :y 0.0 :z 0.0}
                  {:x 0.0 :y 1.0 :z 0.0})
        right (vnormalize (vcross n-dir up-axis))
        up (vnormalize (vcross right n-dir))]
    [right up n-dir]))

(defn- directed-blastwave-wave-ops [{:keys [pos dir ttl max-ttl rings]}]
  (let [ticks (- (long max-ttl) (long ttl))
        max-alpha (directed-blastwave-alpha-curve (/ (double ticks) (double (max 1 max-ttl))))
        size-scale (directed-blastwave-size-scale ticks)
        [right up forward] (directed-blastwave-basis dir)
        z-offset (/ (double ticks) 40.0)]
    (vec
      (mapcat
        (fn [{:keys [life offset size time-offset]}]
          (let [local-t (/ (- (double ticks) (double time-offset)) (double (max 1 life)))
                alpha (directed-blastwave-alpha-curve local-t)
                real-alpha (min max-alpha alpha)]
            (if (<= real-alpha 0.0)
              []
              (let [center (v+ pos (v* forward (+ (double offset) z-offset)))
                    ring-size (* (double size) size-scale)
                    side (v* right (* ring-size 0.5))
                    vertical (v* up (* ring-size 0.5))
                    p0 (v+ (v- center side) vertical)
                    p1 (v+ (v+ center side) vertical)
                    p2 (v- (v+ center side) vertical)
                    p3 (v- (v- center side) vertical)
                    alpha-i (int (max 0 (min 255 (* 255.0 real-alpha 0.7))))]
                [(quad-op "my_mod:textures/effects/glow_circle.png"
                          p0 p1 p2 p3
                          {:r 255 :g 255 :b 255 :a alpha-i})]))))
        rings))))

(defn- directed-blastwave-charge-ops [center charge-ticks punched?]
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
            [(line-op p0 p1 color)
             (line-op center p0 core)]))
        (range points)))))

(defn- beam-ops [cam-pos {:keys [start end ttl max-ttl]}]
  (let [life (/ (double ttl) (double (max 1 max-ttl)))
        right (beam-right-axis start end cam-pos)
        width (* 0.08 (+ 0.5 life))
        core-width (* width 0.45)
        outer-a (with-alpha {:r 236 :g 170 :b 93} (+ 50 (* 150 life)))
        inner-a (with-alpha {:r 241 :g 240 :b 222} (+ 80 (* 150 life)))
        r0 (v* right width)
        r1 (v* right core-width)
        p0 (v+ start r0)
        p1 (v- start r0)
        p2 (v- end r0)
        p3 (v+ end r0)
        c0 (v+ start r1)
        c1 (v- start r1)
        c2 (v- end r1)
        c3 (v+ end r1)]
    [(quad-op "minecraft:textures/entity/beacon_beam.png" p0 p1 p2 p3 outer-a)
     (quad-op "minecraft:textures/entity/beacon_beam.png" c0 c1 c2 c3 inner-a)
     (line-op start end (with-alpha {:r 165 :g 230 :b 255} (+ 40 (* 120 life))))]))

(defn- mag-movement-beam-ops [cam-pos start end tick]
  (let [phase (* 0.9 (double tick))
        tex-phase (* 1.7 (double tick))
        wiggle (+ 0.02
                  (* 0.02 (Math/sin phase))
                  (* 0.012 (Math/sin tex-phase)))
        flicker (+ (* 0.5 (+ 1.0 (Math/sin (* 0.27 (double tick)))))
                   (* 0.5 (+ 1.0 (Math/sin (* 0.53 (double tick))))))
        show-prob (+ 0.1 (* 0.35 flicker))
        hide-prob (+ 0.6 (* 0.25 (- 1.0 flicker)))
        outer-alpha (int (+ 45 (* 95 show-prob)))
        inner-alpha (int (+ 70 (* 120 hide-prob)))
        right (beam-right-axis start end cam-pos)
        r0 (v* right wiggle)
        r1 (v* right (* wiggle 0.52))
        p0 (v+ start r0)
        p1 (v- start r0)
        p2 (v- end r0)
        p3 (v+ end r0)
        c0 (v+ start r1)
        c1 (v- start r1)
        c2 (v- end r1)
        c3 (v+ end r1)
          outer-a {:r 89 :g 196 :b 255 :a outer-alpha}
          inner-a {:r 234 :g 250 :b 255 :a inner-alpha}]
    [(quad-op "minecraft:textures/entity/beacon_beam.png" p0 p1 p2 p3 outer-a)
     (quad-op "minecraft:textures/entity/beacon_beam.png" c0 c1 c2 c3 inner-a)
        (line-op start end {:r 161 :g 236 :b 255 :a (int (+ 90 (* 110 flicker)))})]))

(defn- impact-ring-ops [end ttl max-ttl]
  (let [life (/ (double ttl) (double (max 1 max-ttl)))
        radius (+ 0.12 (* 0.22 (- 1.0 life)))
        color (with-alpha {:r 188 :g 252 :b 238} (+ 20 (* 160 life)))
        segments 12]
    (vec
      (for [idx (range segments)
            :let [t0 (/ (* 2.0 Math/PI idx) segments)
                  t1 (/ (* 2.0 Math/PI (inc idx)) segments)
                  p0 {:x (+ (:x end) (* radius (Math/cos t0))) :y (:y end) :z (+ (:z end) (* radius (Math/sin t0)))}
                  p1 {:x (+ (:x end) (* radius (Math/cos t1))) :y (:y end) :z (+ (:z end) (* radius (Math/sin t1)))}]]
        (line-op p0 p1 color)))))

(defn- charge-hand-ops [center charge-ratio coin-active? tick]
  (let [radius (+ 0.11 (* 0.12 (double charge-ratio)))
        alpha (if coin-active? 210 170)
        pulse (+ radius (* 0.02 (Math/sin (* 0.25 tick))))
        points 14]
    (vec
      (mapcat
        (fn [idx]
          (let [t0 (/ (* 2.0 Math/PI idx) points)
                t1 (/ (* 2.0 Math/PI (inc idx)) points)
                p0 {:x (+ (:x center) (* pulse (Math/cos t0)))
                    :y (:y center)
                    :z (+ (:z center) (* pulse (Math/sin t0)))}
                p1 {:x (+ (:x center) (* pulse (Math/cos t1)))
                    :y (:y center)
                    :z (+ (:z center) (* pulse (Math/sin t1)))}]
            [(line-op p0 p1 {:r 120 :g 220 :b 255 :a alpha})
             (line-op center p0 {:r 90 :g 190 :b 255 :a 120})]))
        (range points)))))

(defn- mark-teleport-ground-ring-ops [target ticks distance]
  (let [base-radius (+ 0.55 (* 0.08 (Math/sin (* 0.18 (double ticks)))))
        radius (+ base-radius (* 0.04 (min 1.0 (/ (double distance) 20.0))))
        y (+ (double (:y target)) 0.02)
        segments 24
        color {:r 230 :g 236 :b 255 :a 180}]
    (vec
      (for [idx (range segments)
            :let [a0 (/ (* 2.0 Math/PI idx) segments)
                  a1 (/ (* 2.0 Math/PI (inc idx)) segments)
                  p0 {:x (+ (:x target) (* radius (Math/cos a0)))
                      :y y
                      :z (+ (:z target) (* radius (Math/sin a0)))}
                  p1 {:x (+ (:x target) (* radius (Math/cos a1)))
                      :y y
                      :z (+ (:z target) (* radius (Math/sin a1)))}]]
        (line-op p0 p1 color)))))

(defn- mark-teleport-billboard-ops [cam-pos target ticks]
  (let [center {:x (:x target)
                :y (+ (double (:y target)) 0.9)
                :z (:z target)}
        right (camera-facing-right-axis center cam-pos)
        half-width (+ 0.34 (* 0.04 (Math/sin (* 0.22 (double ticks)))))
        half-height 0.9
        up {:x 0.0 :y half-height :z 0.0}
        side (v* right half-width)
        p0 (v+ (v- center side) up)
        p1 (v+ (v+ center side) up)
        p2 (v- (v+ center side) up)
        p3 (v- (v- center side) up)
        halo-width (* half-width 1.35)
        halo-height 1.12
        halo-side (v* right halo-width)
        halo-up {:x 0.0 :y halo-height :z 0.0}
        h0 (v+ (v- center halo-side) halo-up)
        h1 (v+ (v+ center halo-side) halo-up)
        h2 (v- (v+ center halo-side) halo-up)
        h3 (v- (v- center halo-side) halo-up)
        alpha (+ 85 (* 35 (+ 1.0 (Math/sin (* 0.25 (double ticks))))))]
    [(quad-op "my_mod:textures/effects/glow_circle.png" h0 h1 h2 h3 {:r 160 :g 196 :b 255 :a (int alpha)})
     (quad-op "my_mod:textures/effects/glow_circle.png" p0 p1 p2 p3 {:r 245 :g 250 :b 255 :a 180})]))

(defn- mark-teleport-effect-ops [cam-pos mark-state]
  (let [{:keys [target ticks distance]} mark-state]
    (when (map? target)
      (concat
        (mark-teleport-ground-ring-ops target ticks distance)
        (mark-teleport-billboard-ops cam-pos target ticks)))))

(defn- thunder-bolt-arc-ops [cam-pos {:keys [start end ttl max-ttl is-aoe?]}]
  ;; Electric arc: bright white-yellow core, electric blue outer glow
  (let [life       (/ (double ttl) (double (max 1 max-ttl)))
        right      (beam-right-axis start end cam-pos)
        width      (if is-aoe?
                     (* 0.04 (+ 0.4 (* 0.6 life)))
                     (* 0.07 (+ 0.5 (* 0.5 life))))
        core-width (* width 0.4)
        ;; Bright yellow-white outer, pure white core (electric arc palette)
        outer-a    (with-alpha {:r 200 :g 230 :b 255} (int (+ 40 (* 180 life))))
        inner-a    (with-alpha {:r 255 :g 255 :b 255} (int (+ 60 (* 180 life))))
        r0         (v* right width)
        r1         (v* right core-width)
        p0         (v+ start r0)
        p1         (v- start r0)
        p2         (v- end r0)
        p3         (v+ end r0)
        c0         (v+ start r1)
        c1         (v- start r1)
        c2         (v- end r1)
        c3         (v+ end r1)]
    [(quad-op "minecraft:textures/entity/beacon_beam.png" p0 p1 p2 p3 outer-a)
     (quad-op "minecraft:textures/entity/beacon_beam.png" c0 c1 c2 c3 inner-a)
     (line-op start end (with-alpha {:r 160 :g 220 :b 255} (int (+ 60 (* 140 life)))))]))

(defn- thunder-clap-surround-ops [player-center ticks]
  (let [radius (+ 0.55 (* 0.25 (Math/sin (* 0.22 (double ticks)))))
        y (+ (double (:y player-center)) 0.2)
        segments 20
        color {:r 190 :g 232 :b 255 :a 170}]
    (vec
      (for [idx (range segments)
            :let [a0 (/ (* 2.0 Math/PI idx) segments)
                  a1 (/ (* 2.0 Math/PI (inc idx)) segments)
                  p0 {:x (+ (:x player-center) (* radius (Math/cos a0)))
                      :y y
                      :z (+ (:z player-center) (* radius (Math/sin a0)))}
                  p1 {:x (+ (:x player-center) (* radius (Math/cos a1)))
                      :y y
                      :z (+ (:z player-center) (* radius (Math/sin a1)))}]]
        (line-op p0 p1 color)))))

(defn- thunder-clap-target-mark-ops [target ticks charge-ratio]
  (let [base-radius (+ 0.55 (* 0.35 (double charge-ratio)))
        pulse (+ base-radius (* 0.08 (Math/sin (* 0.24 (double ticks)))))
        y (+ (double (:y target)) 0.03)
        segments 24
        color {:r 204 :g 204 :b 204 :a 179}]
    (vec
      (for [idx (range segments)
            :let [a0 (/ (* 2.0 Math/PI idx) segments)
                  a1 (/ (* 2.0 Math/PI (inc idx)) segments)
                  p0 {:x (+ (:x target) (* pulse (Math/cos a0)))
                      :y y
                      :z (+ (:z target) (* pulse (Math/sin a0)))}
                  p1 {:x (+ (:x target) (* pulse (Math/cos a1)))
                      :y y
                      :z (+ (:z target) (* pulse (Math/sin a1)))}]]
        (line-op p0 p1 color)))))

(defn- thunder-clap-local-walk-speed [ticks]
  (let [max-speed 0.1
        min-speed 0.001
        value (- max-speed (* (/ (- max-speed min-speed) 60.0) (double ticks)))]
    (float (max min-speed value))))

(defn- meltdowner-local-walk-speed [ticks]
  (float (max 0.001 (- 0.1 (* 0.001 (double ticks))))))

(defn- meltdowner-charge-ops [center ticks charge-ratio]
  (let [base-radius (+ 0.72 (* 0.28 (double charge-ratio)))
        pulse (+ base-radius (* 0.08 (Math/sin (* 0.23 (double ticks)))))
        y-base (+ (double (:y center)) 0.18)
        ring-segments 18]
    (vec
      (mapcat
        (fn [idx]
          (let [a0 (/ (* 2.0 Math/PI idx) ring-segments)
                a1 (/ (* 2.0 Math/PI (inc idx)) ring-segments)
                h (+ y-base (* 0.22 (Math/sin (+ (* 0.17 ticks) idx))))
                p0 {:x (+ (:x center) (* pulse (Math/cos a0)))
                    :y h
                    :z (+ (:z center) (* pulse (Math/sin a0)))}
                p1 {:x (+ (:x center) (* pulse (Math/cos a1)))
                    :y h
                    :z (+ (:z center) (* pulse (Math/sin a1))) }
                ray-color {:r 170 :g 255 :b 190 :a 170}
                link-color {:r 140 :g 240 :b 170 :a 120}]
            [(line-op p0 p1 ray-color)
             (line-op center p0 link-color)]))
        (range ring-segments)))))

(defn- meltdowner-ray-ops [cam-pos {:keys [start end ttl max-ttl is-reflect?]}]
  (let [life (/ (double ttl) (double (max 1 max-ttl)))
        right (beam-right-axis start end cam-pos)
        width (if is-reflect?
                (* 0.05 (+ 0.45 (* 0.55 life)))
                (* 0.09 (+ 0.6 (* 0.4 life))))
        core-width (* width 0.42)
        outer-a (with-alpha {:r 161 :g 255 :b 142} (int (+ 35 (* 170 life))))
        inner-a (with-alpha {:r 244 :g 255 :b 236} (int (+ 70 (* 170 life))))
        r0 (v* right width)
        r1 (v* right core-width)
        p0 (v+ start r0)
        p1 (v- start r0)
        p2 (v- end r0)
        p3 (v+ end r0)
        c0 (v+ start r1)
        c1 (v- start r1)
        c2 (v- end r1)
        c3 (v+ end r1)]
    [(quad-op "minecraft:textures/entity/beacon_beam.png" p0 p1 p2 p3 outer-a)
     (quad-op "minecraft:textures/entity/beacon_beam.png" c0 c1 c2 c3 inner-a)
     (line-op start end (with-alpha {:r 192 :g 255 :b 188} (int (+ 55 (* 150 life)))))]))

(defn- rotate-around-axis [vec axis degrees]
  (let [axis-unit (vnormalize axis)
        theta (Math/toRadians (double degrees))
        cos-theta (Math/cos theta)
        sin-theta (Math/sin theta)
        term1 (v* vec cos-theta)
        term2 (v* (vcross axis-unit vec) sin-theta)
        term3 (v* axis-unit (* (+ (* (:x axis-unit) (:x vec))
                                   (* (:y axis-unit) (:y vec))
                                   (* (:z axis-unit) (:z vec)))
                                (- 1.0 cos-theta)))]
    (vnormalize (v+ (v+ term1 term2) term3))))

(defn- billboard-up-axis [center cam-pos right]
  (let [to-cam (vnormalize (v- cam-pos center))
        raw (vcross to-cam right)]
    (if (> (vlen raw) 1.0e-5)
      (vnormalize raw)
      {:x 0.0 :y 1.0 :z 0.0})))

(defn- blood-retrograde-local-walk-speed [ticks]
  (let [ratio (min 1.0 (/ (double ticks) 20.0))]
    (float (+ 0.1 (* (- 0.007 0.1) ratio)))))

(defn- blood-retrograde-splash-ops [cam-pos {:keys [x y z size ttl max-ttl]}]
  (let [center {:x (double x) :y (double y) :z (double z)}
        half-size (* 0.5 (double (or size 1.0)))
        right (camera-facing-right-axis center cam-pos)
        up (billboard-up-axis center cam-pos right)
        side (v* right half-size)
        lift (v* up half-size)
        p0 (v+ (v- center side) lift)
        p1 (v+ (v+ center side) lift)
        p2 (v- (v+ center side) lift)
        p3 (v- (v- center side) lift)
        age (long (- (long (or max-ttl blood-retrograde-splash-life))
                     (long (or ttl blood-retrograde-splash-life))))
        frame (max 0 (min 9 age))]
    [(quad-op (str "my_mod:textures/effects/blood_splash/" frame ".png")
              p0 p1 p2 p3
              {:r 213 :g 29 :b 29 :a 200})]))

(defn- spray-face-basis [face]
  (case face
    :up    [{:x 0.0 :y 1.0 :z 0.0} {:x 1.0 :y 0.0 :z 0.0} {:x 0.0 :y 0.0 :z 1.0}]
    :down  [{:x 0.0 :y -1.0 :z 0.0} {:x 1.0 :y 0.0 :z 0.0} {:x 0.0 :y 0.0 :z -1.0}]
    :north [{:x 0.0 :y 0.0 :z -1.0} {:x 1.0 :y 0.0 :z 0.0} {:x 0.0 :y 1.0 :z 0.0}]
    :south [{:x 0.0 :y 0.0 :z 1.0} {:x -1.0 :y 0.0 :z 0.0} {:x 0.0 :y 1.0 :z 0.0}]
    :west  [{:x -1.0 :y 0.0 :z 0.0} {:x 0.0 :y 0.0 :z -1.0} {:x 0.0 :y 1.0 :z 0.0}]
    :east  [{:x 1.0 :y 0.0 :z 0.0} {:x 0.0 :y 0.0 :z 1.0} {:x 0.0 :y 1.0 :z 0.0}]
    [{:x 0.0 :y 1.0 :z 0.0} {:x 1.0 :y 0.0 :z 0.0} {:x 0.0 :y 0.0 :z 1.0}]))

(defn- blood-retrograde-spray-ops [{:keys [x y z face size rotation offset-u offset-v texture-id ttl]}]
  (let [[normal tangent bitangent] (spray-face-basis face)
        center (v+
                 {:x (+ (double x) 0.5)
                  :y (+ (double y) 0.5)
                  :z (+ (double z) 0.5)}
                 (v* normal 0.51))
        tangent' (rotate-around-axis tangent normal (double (or rotation 0.0)))
        bitangent' (rotate-around-axis bitangent normal (double (or rotation 0.0)))
        shifted (v+ center
                    (v+ (v* tangent' (double (or offset-u 0.0)))
                        (v* bitangent' (double (or offset-v 0.0)))))
        half-size (* 0.5 (double (or size 1.0)))
        side (v* tangent' half-size)
        lift (v* bitangent' half-size)
        p0 (v+ (v- shifted side) lift)
        p1 (v+ (v+ shifted side) lift)
        p2 (v- (v+ shifted side) lift)
        p3 (v- (v- shifted side) lift)
        life (if (and ttl (< ttl 60)) (/ (double ttl) 60.0) 1.0)
        tex-folder (if (contains? #{:up :down} face) "wall" "grnd")
        tex-index (max 0 (min 2 (long (or texture-id 0))))]
    [(quad-op (str "my_mod:textures/effects/blood_spray/" tex-folder "/" tex-index ".png")
              p0 p1 p2 p3
              {:r 255 :g 255 :b 255 :a (int (+ 40 (* 180 life)))})]))

(defn build-level-effect-plan [camera-pos hand-center-pos tick]
  (let [beams @beam-effects
        mag-move @mag-movement-effect
    mark-teleport @mark-teleport-effect
        thunder-clap @thunder-clap-effect
        meltdowner @meltdowner-effect
    blood-retrograde @blood-retrograde-effect
  directed-blastwave @directed-blastwave-effect
        player-uuid (:player-uuid hand-center-pos)
        charge-state (when player-uuid
                       (client-runtime/railgun-charge-visual-state player-uuid))
        beam-plan (mapcat (fn [beam]
                            (concat
                              (beam-ops camera-pos beam)
                              (impact-ring-ops (:end beam) (:ttl beam) (:max-ttl beam))))
                          beams)
        mag-plan (if (and hand-center-pos
                          (:active? mag-move)
                          (map? (:target mag-move)))
                   (mag-movement-beam-ops camera-pos
                                          (dissoc hand-center-pos :player-uuid)
                                          (:target mag-move)
                                          tick)
                   [])
         mark-teleport-plan (if (and mark-teleport
                 (:active? mark-teleport)
                 (map? (:target mark-teleport)))
               (mark-teleport-effect-ops camera-pos mark-teleport)
               [])
        charge-plan (if (and hand-center-pos (:active? charge-state))
                      (charge-hand-ops (dissoc hand-center-pos :player-uuid)
                                       (:charge-ratio charge-state)
                                       (:coin-active? charge-state)
                                       tick)
                      [])
        thunder-clap-plan (if (and hand-center-pos
                                   thunder-clap
                                   (:active? thunder-clap))
                            (let [player-center (dissoc hand-center-pos :player-uuid)
                                  ticks (long (or (:ticks thunder-clap) 0))
                                  ratio (double (or (:charge-ratio thunder-clap) 0.0))]
                              (concat
                                (thunder-clap-surround-ops player-center ticks)
                                (when (map? (:target thunder-clap))
                                  (thunder-clap-target-mark-ops (:target thunder-clap) ticks ratio))))
                            [])
        thunder-clap-walk-speed (when (and thunder-clap (:active? thunder-clap))
                                  (thunder-clap-local-walk-speed (:ticks thunder-clap)))
        meltdowner-plan (if (and hand-center-pos
                                 meltdowner
                                 (:active? meltdowner))
                          (let [center (dissoc hand-center-pos :player-uuid)
                                ticks (long (or (:ticks meltdowner) 0))
                                ratio (double (or (:charge-ratio meltdowner) 0.0))]
                            (meltdowner-charge-ops center ticks ratio))
                          [])
        meltdowner-walk-speed (when (and meltdowner (:active? meltdowner))
                                (meltdowner-local-walk-speed (:ticks meltdowner)))
        meltdowner-ray-plan (mapcat (fn [ray]
                                      (meltdowner-ray-ops camera-pos ray))
                                    @meltdowner-rays)
        blood-retrograde-plan (concat
                                (mapcat (fn [splash]
                                          (blood-retrograde-splash-ops camera-pos splash))
                                        @blood-retrograde-splashes)
                                (mapcat blood-retrograde-spray-ops @blood-retrograde-sprays))
        directed-blastwave-plan (concat
                                  (if (and hand-center-pos
                                           directed-blastwave
                                           (:active? directed-blastwave))
                                    (directed-blastwave-charge-ops
                                      (dissoc hand-center-pos :player-uuid)
                                      (long (or (:charge-ticks directed-blastwave) 0))
                                      (boolean (:punched? directed-blastwave)))
                                    [])
                                  (mapcat directed-blastwave-wave-ops @directed-blastwave-waves))
        blood-retrograde-walk-speed (when (and blood-retrograde (:active? blood-retrograde))
                                      (blood-retrograde-local-walk-speed (:ticks blood-retrograde)))
        local-walk-speed (let [cand (filter number? [thunder-clap-walk-speed
                                                     meltdowner-walk-speed
                                                     blood-retrograde-walk-speed])]
                           (when (seq cand)
                             (float (apply min cand))))
        tb-arc-plan (mapcat (fn [arc]
                              (thunder-bolt-arc-ops camera-pos arc))
                            @thunder-bolt-arcs)]
    (when (or (seq beam-plan)
              (seq mag-plan)
              (seq mark-teleport-plan)
              (seq charge-plan)
              (seq meltdowner-plan)
              (seq meltdowner-ray-plan)
                    (seq blood-retrograde-plan)
                (seq directed-blastwave-plan)
              (seq tb-arc-plan)
              (seq thunder-clap-plan)
              local-walk-speed)
      {:ops (vec (concat beam-plan
                         mag-plan
                         mark-teleport-plan
                         charge-plan
                         meltdowner-plan
                         thunder-clap-plan
                         tb-arc-plan
                   meltdowner-ray-plan
                 blood-retrograde-plan
                 directed-blastwave-plan))
       :local-walk-speed local-walk-speed})))