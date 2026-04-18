(ns cn.li.ac.content.ability.electromaster.railgun-fx
  "Client FX for Railgun: beam effects + charge hand aura."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.runtime :as client-runtime]))

;; ---------------------------------------------------------------------------
;; State
;; ---------------------------------------------------------------------------

(defonce ^:private beam-effects (atom []))
(def ^:private beam-life-ticks 12)

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [payload]
  (let [{:keys [mode start end hit-distance]} payload]
    (when (and start end)
      (swap! beam-effects conj {:start start
                                :end end
                                :mode (or mode :block-hit)
                                :hit-distance (double (or hit-distance 18.0))
                                :ttl beam-life-ticks
                                :max-ttl beam-life-ticks}))))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (swap! beam-effects
         (fn [xs]
           (->> xs
                (map #(update % :ttl dec))
                (filter #(pos? (long (:ttl %))))
                vec))))

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- beam-ops [cam-pos {:keys [start end ttl max-ttl]}]
  (let [life (/ (double ttl) (double (max 1 max-ttl)))
        right (ru/beam-right-axis start end cam-pos)
        width (* 0.08 (+ 0.5 life))
        core-width (* width 0.45)
        outer-a (ru/with-alpha {:r 236 :g 170 :b 93} (+ 50 (* 150 life)))
        inner-a (ru/with-alpha {:r 241 :g 240 :b 222} (+ 80 (* 150 life)))
        r0 (ru/v* right width)
        r1 (ru/v* right core-width)
        p0 (ru/v+ start r0) p1 (ru/v- start r0) p2 (ru/v- end r0) p3 (ru/v+ end r0)
        c0 (ru/v+ start r1) c1 (ru/v- start r1) c2 (ru/v- end r1) c3 (ru/v+ end r1)]
    [(ru/quad-op "minecraft:textures/entity/beacon_beam.png" p0 p1 p2 p3 outer-a)
     (ru/quad-op "minecraft:textures/entity/beacon_beam.png" c0 c1 c2 c3 inner-a)
     (ru/line-op start end (ru/with-alpha {:r 165 :g 230 :b 255} (+ 40 (* 120 life))))]))

(defn- impact-ring-ops [end ttl max-ttl]
  (let [life (/ (double ttl) (double (max 1 max-ttl)))
        radius (+ 0.12 (* 0.22 (- 1.0 life)))
        color (ru/with-alpha {:r 188 :g 252 :b 238} (+ 20 (* 160 life)))
        segments 12]
    (vec
      (for [idx (range segments)
            :let [t0 (/ (* 2.0 Math/PI idx) segments)
                  t1 (/ (* 2.0 Math/PI (inc idx)) segments)
                  p0 {:x (+ (:x end) (* radius (Math/cos t0))) :y (:y end) :z (+ (:z end) (* radius (Math/sin t0)))}
                  p1 {:x (+ (:x end) (* radius (Math/cos t1))) :y (:y end) :z (+ (:z end) (* radius (Math/sin t1)))}]]
        (ru/line-op p0 p1 color)))))

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
            [(ru/line-op p0 p1 {:r 120 :g 220 :b 255 :a alpha})
             (ru/line-op center p0 {:r 90 :g 190 :b 255 :a 120})]))
        (range points)))))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [camera-pos hand-center-pos tick]
  (let [beams @beam-effects
        player-uuid (:player-uuid hand-center-pos)
        charge-state (when player-uuid
                       (client-runtime/railgun-charge-visual-state player-uuid))
        beam-plan (mapcat (fn [beam]
                            (concat
                              (beam-ops camera-pos beam)
                              (impact-ring-ops (:end beam) (:ttl beam) (:max-ttl beam))))
                          beams)
        charge-plan (if (and hand-center-pos (:active? charge-state))
                      (charge-hand-ops (dissoc hand-center-pos :player-uuid)
                                       (:charge-ratio charge-state)
                                       (:coin-active? charge-state)
                                       tick)
                      [])]
    (when (or (seq beam-plan) (seq charge-plan))
      {:ops (vec (concat beam-plan charge-plan))})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(level-effects/register-level-effect! :railgun-shot
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:railgun/fx-shot :railgun/fx-reflect]
  (fn [_ctx-id _channel payload]
    (level-effects/enqueue-level-effect! :railgun-shot payload)))
