(ns cn.li.ac.ability.client.level-effects
  "Pure level effect state + render-plan generation for client-side ability visuals."
  (:require [cn.li.ac.ability.client.ability-runtime :as client-runtime]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private beam-effects (atom []))
(def ^:private beam-life-ticks 12)
(defonce ^:private mag-movement-effect (atom nil))
(def ^:private mag-movement-loop-sound "my_mod:em.move_loop")

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
               (assoc st :ticks ticks))))))

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

(defn build-level-effect-plan [camera-pos hand-center-pos tick]
  (let [beams @beam-effects
        mag-move @mag-movement-effect
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
        charge-plan (if (and hand-center-pos (:active? charge-state))
                      (charge-hand-ops (dissoc hand-center-pos :player-uuid)
                                       (:charge-ratio charge-state)
                                       (:coin-active? charge-state)
                                       tick)
                      [])]
    (when (or (seq beam-plan) (seq mag-plan) (seq charge-plan))
      {:ops (vec (concat beam-plan mag-plan charge-plan))})))