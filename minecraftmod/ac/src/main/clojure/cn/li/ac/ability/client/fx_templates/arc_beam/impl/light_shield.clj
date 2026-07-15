(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.light-shield
  (:require [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.hand-effects :as hand-effects]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.runtime :as client-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [clojure.string :as str])
  (:import [cn.li.mcmod.math V3]))

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (or store {:effect-state {}})
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id (modid/namespaced-path "md.shield_on") :volume 0.7 :pitch 1.0})
        (assoc-in store* [:effect-state owner-key*]
                  (merge base-meta {:active? true :ticks 0 :phase :startup})))
      :end
      (update store* :effect-state dissoc owner-key*)
      store*)))

(defn- tick-state!
  [store]
  (let [store* (or store {:effect-state {}})]
    (update store* :effect-state
      (fn [states]
        (reduce-kv
          (fn [acc owner-key st]
            (if-not (:active? st)
              acc
              (let [ticks (inc (long (or (:ticks st) 0)))]
                ;; MdParticleFactory particles (matching original: 30% per tick)
                (when (< (rand) 0.3)
                  (let [s 0.5]
                    (client-particles/queue-particle-effect! (:queue-owner st)
                      {:type :particle :particle-type :electric-spark
                       :x (+ (- (rand s) (/ s 2)) (- (rand 0.04) 0.02))
                       :y (+ 1.0 (- (rand s) (/ s 2)) (- (rand 0.04) 0.02))
                       :z (+ (- (rand s) (/ s 2)) (- (rand 0.04) 0.02))
                       :count 1 :speed 0.08
                       :offset-x 0.02 :offset-y 0.02 :offset-z 0.02
                       :motion-x (- (rand 0.04) 0.02)
                       :motion-y (- (rand 0.04) 0.02)
                       :motion-z (- (rand 0.04) 0.02)})))
                (assoc acc owner-key (assoc st :ticks ticks)))))
          {}
          states)))))

(defn- build-plan
  [camera-pos hand-center-pos tick]
  (when hand-center-pos
    (let [^V3 center (vec3/map->v3 (dissoc hand-center-pos :player-uuid))
          ^V3 camera-pos (vec3/map->v3 camera-pos)
          cx (.-x center) cy (.-y center) cz (.-z center)
          angle (* 0.12 (double (or tick 0)))
          ring-radius (+ 0.82 (* 0.06 (Math/sin (* 0.17 (double (or tick 0))))))
          segments 24
          outer-color (ru/with-alpha {:r 180 :g 240 :b 255} 150)
          inner-color (ru/with-alpha {:r 240 :g 255 :b 250} 110)
          ring-ops (mapcat
                     (fn [idx]
                       (let [a0 (+ angle (/ (* 2.0 Math/PI idx) segments))
                             a1 (+ angle (/ (* 2.0 Math/PI (inc idx)) segments))
                             p0 (vec3/v3 (+ cx (* ring-radius (Math/cos a0))) (+ cy 0.15) (+ cz (* ring-radius (Math/sin a0))))
                             p1 (vec3/v3 (+ cx (* ring-radius (Math/cos a1))) (+ cy 0.15) (+ cz (* ring-radius (Math/sin a1))))]
                         [(ru/line-op p0 p1 outer-color)
                          (ru/line-op center p0 inner-color)]))
                     (range segments))
          right (ru/camera-facing-right-axis center camera-pos)
          up (ru/billboard-up-axis center camera-pos right)
          half-size (+ 0.44 (* 0.07 (Math/sin (* 0.23 (double (or tick 0))))))
          side (vec3/v* right half-size)
          lift (vec3/v* up half-size)
          p0 (vec3/v+ (vec3/v- center side) lift)
          p1 (vec3/v+ (vec3/v+ center side) lift)
          p2 (vec3/v- (vec3/v+ center side) lift)
          p3 (vec3/v- (vec3/v- center side) lift)
          glow-op (ru/quad-op (modid/namespaced-path "textures/effects/glow_circle.png")
                              p0 p1 p2 p3
                              (ru/with-alpha {:r 165 :g 245 :b 255} 90))]
      {:ops (vec (cons glow-op ring-ops))})))

(defn- shield-end-sound! [_ctx-id _channel _payload]
  (client-sounds/queue-current-sound-effect!
    {:type :sound :sound-id (modid/namespaced-path "md.shield_loop") :volume 0.35 :pitch 0.95}))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:light-shield :level] [_ _] {:effect-state {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:light-shield :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:light-shield :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :light-shield [_ store owner-key]
  (update store :effect-state dissoc owner-key))
