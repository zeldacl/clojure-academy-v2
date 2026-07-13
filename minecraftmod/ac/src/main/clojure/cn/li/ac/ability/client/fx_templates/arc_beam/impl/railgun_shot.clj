(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.railgun-shot
  (:require [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.runtime :as client-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [clojure.string :as str])
  (:import [cn.li.mcmod.math V3]))

(def ^:private beam-life-ticks 50)

(def ^:private railgun-beam-style
  ;; Blend curves matching original EntityRayBase:
  ;;   blendInTime=150ms (~3 ticks), blendOutTime=1000ms (~20 ticks), widthShrinkTime=800ms (~16 ticks)
  (let [blend-in-ticks  3    ;; 150ms / 50ms per tick
        blend-out-ticks 20   ;; 1000ms / 50ms per tick
        width-shrink-ticks 16 ;; 800ms / 50ms per tick
        fade-in    (fn [life] (min 1.0 (/ (max 0.0 (- 1.0 life)) (/ blend-in-ticks 50.0))))
        fade-out   (fn [life] (min 1.0 (/ life (/ blend-out-ticks 50.0))))
        shrink     (fn [life] (max 0.0 (- 1.0 (/ (max 0 (- life (/ width-shrink-ticks 50.0)))
                                                  (/ (- 50 width-shrink-ticks) 50.0)))))
        alpha-fn   (fn [life] (min (fade-in life) (fade-out life)))]
    {:width       (fn [beam life]
                     (let [seed (double (or (:wiggle-seed beam) 0.0))
                           width-wiggle (+ 1.0 (* 0.3 (Math/sin (+ seed (* life 20.0)))))]
                       (* 0.08 (+ 0.5 life) width-wiggle (fade-in life) (shrink life))))
     :core-ratio  0.45
     :outer-rgb   {:r 236 :g 170 :b 93}
     :outer-alpha (fn [_ life] (+ 50 (* 150 (alpha-fn life))))
     :inner-rgb   {:r 241 :g 240 :b 222}
     :inner-alpha (fn [_ life] (+ 80 (* 150 (alpha-fn life))))
     :line-rgb    {:r 165 :g 230 :b 255}
     :line-alpha  (fn [_ life] (+ 40 (* 120 (alpha-fn life))))
     ;; Glow layer (independent pass, rendered before core beam)
     :glow {:width       (fn [beam life]
                           (let [seed (double (or (:wiggle-seed beam) 0.0))
                                 glow-wiggle (+ 1.0 (* 0.1 (Math/sin (+ seed 1.5 (* life 15.0)))))]
                             (* 1.1 (+ 0.5 life) glow-wiggle (fade-in life))))
            :core-ratio  1.0
            :outer-rgb   {:r 236 :g 170 :b 93}
            :outer-alpha (fn [_ life] (* 60 (+ 0.5 life) (alpha-fn life)))
            :start-fix   -0.3
            :end-fix     0.3}}))









(defn- all-beam-effects []
  (mapcat val (:beam-effects (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :railgun-shot))))

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (if (contains? (or store {}) :beam-effects)
                 (or store {:beam-effects {}})
                 {:beam-effects {}})
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode start end hit-distance source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (when (and start end)
      (update-in store* [:beam-effects owner-key*] (fnil conj [])
                 (merge base-meta
                        {:start start
                         :end end
                         :mode (or mode :block-hit)
                         :hit-distance (double (or hit-distance 18.0))
                         :ttl beam-life-ticks
                         :max-ttl beam-life-ticks
                         :wiggle-seed (* 2.0 Math/PI (rand))})))))  ;; random phase [0, 2π)

(defn- tick-state!
  [store]
  (let [store* (if (contains? (or store {}) :beam-effects)
                 (or store {:beam-effects {}})
                 {:beam-effects {}})]
    (update store* :beam-effects
      (fn [by-owner]
        (into {}
              (keep (fn [[owner-key xs]]
                      (let [live (->> xs
                                      (map #(update % :ttl dec))
                                      (filter #(pos? (long (:ttl %))))
                                      vec)]
                        (when (seq live)
                          [owner-key live]))))
              by-owner)))))

(defn- impact-ring-ops [^V3 end ttl max-ttl]
  (let [life (/ (double ttl) (double (max 1 max-ttl)))
        radius (+ 0.12 (* 0.22 (- 1.0 life)))
        color (ru/with-alpha {:r 188 :g 252 :b 238} (+ 20 (* 160 life)))
        segments 12
        ex (.-x end) ey (.-y end) ez (.-z end)]
    (vec
      (for [idx (range segments)
            :let [t0 (/ (* 2.0 Math/PI idx) segments)
                  t1 (/ (* 2.0 Math/PI (inc idx)) segments)
                  p0 (vec3/v3 (+ ex (* radius (Math/cos t0))) ey (+ ez (* radius (Math/sin t0))))
                  p1 (vec3/v3 (+ ex (* radius (Math/cos t1))) ey (+ ez (* radius (Math/sin t1))))]]
        (ru/line-op p0 p1 color)))))

(defn- charge-hand-ops [^V3 camera-pos ^V3 hand-center charge-start-ms charge-ratio coin-active? game-ticks]
  (let [elapsed-ms (if charge-start-ms (max 0 (- (* (double game-ticks) 50.0) (double charge-start-ms))) 0)
        ;; 40 frames at 40ms each = 1.6s total animation
        frame (min 39 (int (/ elapsed-ms 40.0)))
        texture-path (str "my_mod:textures/effects/arc_burst/" frame ".png")
        alpha (if coin-active? 1.0 0.8)
        ;; Billboard quad: 0.6x0.6 square facing camera, centered at hand-center
        ;; Original uses scale 0.4 with offset (0.26, -0.15, -0.24)
        half-size 0.12
        ;; Simple upward offset from hand center (matches original first-person offset direction)
        cx (+ (.-x hand-center) 0.26)
        cy (+ (.-y hand-center) -0.15)
        cz (.-z hand-center)
        p0 (vec3/v3 (- cx half-size) (- cy half-size) cz)
        p1 (vec3/v3 (+ cx half-size) (- cy half-size) cz)
        p2 (vec3/v3 (+ cx half-size) (+ cy half-size) cz)
        p3 (vec3/v3 (- cx half-size) (+ cy half-size) cz)
        a (int (* 255 alpha))]
    [{:kind :quad
      :texture texture-path
      :p0 p0 :p1 p1 :p2 p2 :p3 p3
      :color {:r 255 :g 255 :b 255 :a a}}]))

(defn- build-plan [camera-pos hand-center-pos game-ticks]
  (let [beams (all-beam-effects)
        ^V3 cam-v (vec3/map->v3 camera-pos)
        player-uuid (:player-uuid hand-center-pos)
        charge-state (when player-uuid
                       (client-runtime/railgun-charge-visual-state player-uuid (* (double game-ticks) 50.0)))
        beam-plan (mapcat (fn [beam]
                            (let [beam-v (assoc beam :start (vec3/map->v3 (:start beam)) :end (vec3/map->v3 (:end beam)))]
                              (concat
                                ;; Arc/lightning branches
                                (arc-fx/railgun-arc-ops cam-v beam-v {})
                                ;; Glow layer (behind core beam)
                                (when-let [glow-style (:glow railgun-beam-style)]
                                  (fx-beam/fading-beam-ops cam-v beam-v glow-style))
                                (fx-beam/fading-beam-ops cam-v beam-v railgun-beam-style)
                                (impact-ring-ops (:end beam-v) (:ttl beam) (:max-ttl beam)))))
                          beams)
        charge-plan (if (and hand-center-pos (:active? charge-state))
                      (charge-hand-ops cam-v
                                       (vec3/map->v3 (dissoc hand-center-pos :player-uuid))
                                       (:charge-start-ms charge-state)
                                       (:charge-ratio charge-state)
                                       (:coin-active? charge-state)
                                       game-ticks)
                      [])]
    (when (or (seq beam-plan) (seq charge-plan))
      {:ops (vec (concat beam-plan charge-plan))})))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:railgun-shot :level] [_ _] {:beam-effects {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:railgun-shot :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:railgun-shot :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :railgun-shot [store owner-key]
  (update store :beam-effects dissoc owner-key))
