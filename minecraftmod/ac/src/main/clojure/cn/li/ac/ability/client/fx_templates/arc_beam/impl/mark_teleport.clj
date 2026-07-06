(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.mark-teleport
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
            [cn.li.ac.util.math.vec3 :as vec3]
            [clojure.string :as str]))

(defn- spawn-tp-marking! []
  (client-bridge/run-client-effect! :mcmod/spawn-local-scripted-effect {:effect-id "entity_tp_marking"}))

(defn- remove-tp-marking! []
  (client-bridge/run-client-effect! :mcmod/remove-local-scripted-effect {:effect-id "entity_tp_marking"}))

(defn- enqueue-state! [state ctx-id channel owner-key payload]
  (let [state* (or state {:effect-state {}})
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode target distance source-player-id world-id]} payload
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id :channel channel
                   :source-player-id source-player-id :world-id world-id}]
    (case mode
      :start
      (do (spawn-tp-marking!)
          (assoc-in state* [:effect-state owner-key*]
                    (merge base-meta {:active? true :target nil :distance 0.0 :ticks 0})))

      :update
      (assoc-in state* [:effect-state owner-key*]
                (merge base-meta (get-in state* [:effect-state owner-key*])
                       {:active? true :target target
                        :distance (double (or distance 0.0))
                        :ticks (long (or (:ticks (get-in state* [:effect-state owner-key*])) 0))}))

      :perform
      (do (remove-tp-marking!)
          (when (map? target)
            (client-particles/queue-particle-effect! (:queue-owner base-meta)
              {:type :particle :particle-type :portal
               :x (:x target) :y (double (or (:y target) 0.0)) :z (:z target)
               :count 16 :speed 0.08 :offset-x 0.9 :offset-y 0.8 :offset-z 0.9})
            (client-sounds/queue-sound-effect! (:queue-owner base-meta)
              {:type :sound :sound-id "my_mod:tp.tp" :volume 0.5 :pitch 1.0}))
          state*)

      :end
      (do (remove-tp-marking!)
          (update state* :effect-state dissoc owner-key*))

      state*)))

(defn- tick-state! [state]
  (let [state* (or state {:effect-state {}})]
    (update state* :effect-state
            (fn [states]
              (reduce-kv
                (fn [acc owner-key st]
                  (if-not (:active? st) acc
                    (let [ticks (inc (long (or (:ticks st) 0))) target (:target st)]
                      (when (and target (zero? (mod ticks 3)))
                        (client-particles/queue-particle-effect! (:queue-owner st)
                          {:type :particle :particle-type :portal
                           :x (:x target) :y (- (double (or (:y target) 0.0)) 0.5) :z (:z target)
                           :count 2 :speed 0.03 :offset-x 0.9 :offset-y 0.7 :offset-z 0.9}))
                      (assoc acc owner-key (assoc st :ticks ticks)))))
                {} states)))))

(defn- ground-ring-ops [target ticks distance]
  (let [base-radius (+ 0.55 (* 0.08 (Math/sin (* 0.18 (double ticks)))))
        radius (+ base-radius (* 0.04 (min 1.0 (/ (double distance) 20.0))))
        y (+ (double (:y target)) 0.02)
        segments 24
        color {:r 230 :g 236 :b 255 :a 180}]
    (vec (for [idx (range segments)
               :let [a0 (/ (* 2.0 Math/PI idx) segments)
                     a1 (/ (* 2.0 Math/PI (inc idx)) segments)
                     p0 {:x (+ (:x target) (* radius (Math/cos a0))) :y y :z (+ (:z target) (* radius (Math/sin a0)))}
                     p1 {:x (+ (:x target) (* radius (Math/cos a1))) :y y :z (+ (:z target) (* radius (Math/sin a1)))}]]
           (ru/line-op p0 p1 color)))))

(defn- billboard-ops [cam-pos target ticks]
  (let [center {:x (:x target) :y (+ (double (:y target)) 0.9) :z (:z target)}
        right (ru/camera-facing-right-axis center cam-pos)
        half-width (+ 0.34 (* 0.04 (Math/sin (* 0.22 (double ticks)))))
        half-height 0.9
        up {:x 0.0 :y half-height :z 0.0}
        side (vec3/v* right half-width)
        p0 (vec3/v+ (vec3/v- center side) up)
        p1 (vec3/v+ (vec3/v+ center side) up)
        p2 (vec3/v- (vec3/v+ center side) up)
        p3 (vec3/v- (vec3/v- center side) up)
        halo-width (* half-width 1.35) halo-height 1.12
        halo-side (vec3/v* right halo-width)
        halo-up {:x 0.0 :y halo-height :z 0.0}
        h0 (vec3/v+ (vec3/v- center halo-side) halo-up)
        h1 (vec3/v+ (vec3/v+ center halo-side) halo-up)
        h2 (vec3/v- (vec3/v+ center halo-side) halo-up)
        h3 (vec3/v- (vec3/v- center halo-side) halo-up)
        alpha (+ 85 (* 35 (+ 1.0 (Math/sin (* 0.25 (double ticks))))))]
    [(ru/quad-op "my_mod:textures/effects/glow_circle.png" h0 h1 h2 h3 {:r 160 :g 196 :b 255 :a (int alpha)})
     (ru/quad-op "my_mod:textures/effects/glow_circle.png" p0 p1 p2 p3 {:r 245 :g 250 :b 255 :a 180})]))

(defn- build-plan [camera-pos _hand-center-pos _tick]
  (let [ops (mapcat (fn [mk]
                      (when (and (:active? mk) (map? (:target mk)))
                        (let [{:keys [target ticks distance]} mk]
                          (concat (ground-ring-ops target ticks distance)
                                  (billboard-ops camera-pos target ticks)))))
                    (vals (:effect-state (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :mark-teleport))))]
    (when (seq ops) {:ops (vec ops)})))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:mark-teleport :level] [_ _] {:effect-state {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:mark-teleport :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:mark-teleport :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :mark-teleport [store owner-key]
  (update store :effect-state dissoc owner-key))
