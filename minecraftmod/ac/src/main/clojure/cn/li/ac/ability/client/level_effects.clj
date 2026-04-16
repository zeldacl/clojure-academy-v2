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
  (swap! meltdowner-rays
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

(defn build-level-effect-plan [camera-pos hand-center-pos tick]
  (let [beams @beam-effects
        mag-move @mag-movement-effect
  mark-teleport @mark-teleport-effect
        thunder-clap @thunder-clap-effect
        meltdowner @meltdowner-effect
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
        local-walk-speed (let [cand (filter number? [thunder-clap-walk-speed meltdowner-walk-speed])]
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
                         meltdowner-ray-plan))
       :local-walk-speed local-walk-speed})))