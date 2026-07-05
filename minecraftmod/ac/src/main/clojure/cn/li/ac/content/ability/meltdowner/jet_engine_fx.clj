(ns cn.li.ac.content.ability.meltdowner.jet-engine-fx
  "Client FX for JetEngine parity flow.

  Stages:
  - mark-start / mark-update / mark-end
  - trigger-start / trigger-update / trigger-end"
  (:require [cn.li.ac.util.math.vec3 :as vec3] [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def ^:private jet-engine-effect-id :jet-engine)
(def ^:private local-scripted-effect-key :mcmod/spawn-local-scripted-effect)
(def ^:private local-remove-scripted-effect-key :mcmod/remove-local-scripted-effect)
(def ^:private diamond-shield-effect-id "entity_diamond_shield")

(def ^:private mark-ttl 8)
(def ^:private trigger-ttl 20)
(def ^:private min-segment-length 1.0e-5)

(defn- clamp01 [x]
  (max 0.0 (min 1.0 (double x))))

(defn- lerp-pos [a b t]
  (let [k (clamp01 t)]
    (vec3/v+ a (vec3/v* (vec3/v- b a) k))))

(defn- safe-trail-right-axis [dir]
  (let [up-axis (if (> (Math/abs (double (:y dir))) 0.95)
                  {:x 1.0 :y 0.0 :z 0.0}
                  {:x 0.0 :y 1.0 :z 0.0})
        right (vec3/vcross dir up-axis)]
    (if (> (vec3/vlen right) min-segment-length)
      (vec3/vnorm right)
      {:x 1.0 :y 0.0 :z 0.0})))

(defn- trail-layer-ops [start pos ttl trigger-ticks]
  (let [travel (vec3/v- pos start)
        distance (vec3/vlen travel)]
    (if (< distance min-segment-length)
      []
      (let [dir (vec3/vnorm travel)
            right (safe-trail-right-axis dir)
            ttl-k (clamp01 (/ (double ttl) (double trigger-ttl)))
            base-half (+ 0.08 (* 0.05 ttl-k))]
        (vec
          (mapcat (fn [idx]
                    (let [layer (double idx)
                          head-t (- 1.0 (* 0.07 layer))
                          tail-t (- head-t (+ 0.2 (* 0.09 layer) (* 0.006 (double trigger-ticks))))
                          head (lerp-pos start pos head-t)
                          tail (lerp-pos start pos tail-t)
                          half-width (* base-half (+ 1.0 (* 0.18 layer)))
                          side (vec3/v* right half-width)
                          p0 (vec3/v+ tail side)
                          p1 (vec3/v+ head side)
                          p2 (vec3/v- head side)
                          p3 (vec3/v- tail side)
                          alpha (int (max 0 (min 255 (* 210.0 ttl-k (- 1.0 (* 0.17 layer))))))
                          color (fx-beam/rgba {:r 172 :g 240 :b 255} alpha)]
                      (when (> alpha 0)
                        [(fx-beam/glow-line-quad-op p0 p1 p2 p3 color)
                         (ru/line-op tail head {:r 200 :g 248 :b 255 :a (min 255 (+ 16 alpha))})])))
                  (range 4)))))))

(defn- impact-billboard-ops [cam-pos target ttl trigger-ticks]
  (if (map? cam-pos)
    (let [center {:x (:x target) :y (+ (double (:y target)) 0.45) :z (:z target)}
          right (ru/camera-facing-right-axis center cam-pos)
          up (ru/billboard-up-axis center cam-pos right)
          ttl-k (clamp01 (/ (double ttl) (double trigger-ttl)))
          pulse (+ 0.52 (* 0.14 (Math/sin (* 0.35 (double trigger-ticks)))))
          outer-half (* pulse (+ 1.0 (* 0.2 ttl-k)))
          outer-v (* 0.62 (+ 1.0 (* 0.15 ttl-k)))
          inner-half (* outer-half 0.62)
          inner-v (* outer-v 0.62)
          outer-side (vec3/v* right outer-half)
          outer-up (vec3/v* up outer-v)
          inner-side (vec3/v* right inner-half)
          inner-up (vec3/v* up inner-v)
          o0 (vec3/v+ (vec3/v- center outer-side) outer-up)
          o1 (vec3/v+ (vec3/v+ center outer-side) outer-up)
          o2 (vec3/v- (vec3/v+ center outer-side) outer-up)
          o3 (vec3/v- (vec3/v- center outer-side) outer-up)
          i0 (vec3/v+ (vec3/v- center inner-side) inner-up)
          i1 (vec3/v+ (vec3/v+ center inner-side) inner-up)
          i2 (vec3/v- (vec3/v+ center inner-side) inner-up)
          i3 (vec3/v- (vec3/v- center inner-side) inner-up)
          outer-a (int (max 0 (min 255 (* 165.0 ttl-k))))
          inner-a (int (max 0 (min 255 (* 210.0 ttl-k))))]
      [(ru/quad-op "my_mod:textures/effects/glow_circle.png" o0 o1 o2 o3 {:r 145 :g 220 :b 255 :a outer-a})
       (ru/quad-op "my_mod:textures/effects/glow_circle.png" i0 i1 i2 i3 {:r 225 :g 252 :b 255 :a inner-a})])
    []))

(defn- impact-spike-ops [target ttl trigger-ticks]
  (let [center {:x (:x target) :y (+ (double (:y target)) 0.08) :z (:z target)}
        ttl-k (clamp01 (/ (double ttl) (double trigger-ttl)))
        radius (+ 0.38 (* 0.18 (Math/sin (* 0.26 (double trigger-ticks)))))
        y-lift (+ 0.06 (* 0.03 ttl-k))
        alpha (int (max 0 (min 255 (* 200.0 ttl-k))))
        color {:r 214 :g 248 :b 255 :a alpha}
        inner {:r 180 :g 230 :b 255 :a (int (max 0 (min 255 (* 135.0 ttl-k))))}]
    (vec
      (mapcat (fn [idx]
                (let [a (/ (* 2.0 Math/PI idx) 8.0)
                      tip {:x (+ (:x center) (* radius (Math/cos a)))
                           :y (+ (:y center) y-lift)
                           :z (+ (:z center) (* radius (Math/sin a)))}]
                  [(ru/line-op center tip color)
                   (ru/line-op {:x (:x center) :y (+ (:y center) 0.16) :z (:z center)} tip inner)]))
              (range 8)))))

(defn default-jet-engine-fx-runtime-state
  []
  {:fx-state {}})

(defn jet-engine-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot jet-engine-effect-id)
      (default-jet-engine-fx-runtime-state)))

(defn reset-jet-engine-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    jet-engine-effect-id
    (default-jet-engine-fx-runtime-state))
  nil)

(defn clear-jet-engine-owner!
  [owner-key]
  (level-effects/update-effect-state!
    jet-engine-effect-id
    (fn [store]
      (update (or store (default-jet-engine-fx-runtime-state)) :fx-state dissoc owner-key)))
  nil)

(defn- ring-ops [target radius color]
  (let [segments 24
        y (+ (double (:y target)) 0.05)]
    (vec
      (for [idx (range segments)
            :let [a0 (/ (* 2.0 Math/PI idx) segments)
                  a1 (/ (* 2.0 Math/PI (inc idx)) segments)
                  p0 {:x (+ (double (:x target)) (* radius (Math/cos a0)))
                      :y y
                      :z (+ (double (:z target)) (* radius (Math/sin a0)))}
                  p1 {:x (+ (double (:x target)) (* radius (Math/cos a1)))
                      :y y
                      :z (+ (double (:z target)) (* radius (Math/sin a1)))}]]
        (ru/line-op p0 p1 color)))))

(defn- spawn-diamond-shield!
  []
  (let [entity-uuid (client-bridge/run-client-effect! local-scripted-effect-key
                                                       {:effect-id diamond-shield-effect-id})]
    (when (seq entity-uuid)
      entity-uuid)))

(defn- remove-diamond-shield!
  [entity-uuid]
  (when (seq entity-uuid)
    (client-bridge/run-client-effect! local-remove-scripted-effect-key
                                      {:entity-uuid entity-uuid})))

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (or store (default-jet-engine-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode start target pos hold-ticks trigger-ticks shield-entity-uuid]} (or payload {})]
    (case mode
      :mark-start
      (do
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id "my_mod:md.jet_charge" :volume 0.45 :pitch 1.0})
        (assoc-in store* [:fx-state owner-key*]
                  {:phase :marking
                   :target target
                   :hold-ticks (long (or hold-ticks 0))
                   :ttl mark-ttl}))

      :mark-update
      (assoc-in store* [:fx-state owner-key*]
                (merge (get-in store* [:fx-state owner-key*])
                       {:phase :marking
                        :target target
                        :hold-ticks (long (or hold-ticks 0))
                        :ttl mark-ttl}))

      :mark-end
      (let [st (get-in store* [:fx-state owner-key*])]
        (if (= :triggering (:phase st))
          store*
          (update store* :fx-state dissoc owner-key*)))

      :trigger-start
      (let [prev-state (get-in store* [:fx-state owner-key*])
            entering-trigger? (not= :triggering (:phase prev-state))
            spawned-uuid (when entering-trigger?
                           ;; Keep parity with upstream JetEngine: spawn diamond-shield once on trigger phase entry.
                           (spawn-diamond-shield!))]
        (when entering-trigger?
          (client-sounds/queue-current-sound-effect!
            {:type :sound :sound-id "my_mod:md.jet_engine" :volume 0.8 :pitch 1.0}))
        (assoc-in store* [:fx-state owner-key*]
                  (merge prev-state
                         {:phase :triggering
                          :start start
                          :target target
                          :pos (or pos start)
                          :trigger-ticks (long (or trigger-ticks 0))
                          :ttl trigger-ttl
                          :shield-entity-uuid (or spawned-uuid
                                                  (:shield-entity-uuid prev-state))})))

      :trigger-update
      (assoc-in store* [:fx-state owner-key*]
                (merge (get-in store* [:fx-state owner-key*])
                       {:phase :triggering
                        :pos pos
                        :trigger-ticks (long (or trigger-ticks 0))
                        :shield-entity-uuid (or shield-entity-uuid
                                                (get-in store* [:fx-state owner-key* :shield-entity-uuid]))
                        :ttl trigger-ttl}))

      :trigger-end
      (let [st (get-in store* [:fx-state owner-key*])
            shield-entity-uuid (:shield-entity-uuid st)]
        (remove-diamond-shield! shield-entity-uuid)
        (update store* :fx-state dissoc owner-key*))

      store*)))

(defn- tick-state!
  [store]
  (let [store* (or store (default-jet-engine-fx-runtime-state))]
    ;; MdParticleFactory particles during trigger (matching original: 10-11 per tick)
    (doseq [[_ st] (:fx-state store*)]
      (when (= :triggering (:phase st))
        (let [trigger-ticks (long (or (:trigger-ticks st) 0))
              pos (:pos st)]
          (dotimes [_ (+ 10 (rand-int 2))]
            (client-particles/queue-particle-effect! (:queue-owner st)
              {:type :particle :particle-type :electric-spark
               :x (+ (double (:x pos)) (- (rand 0.6) 0.3))
               :y (+ (double (:y pos)) (- (rand 0.6) 0.3))
               :z (+ (double (:z pos)) (- (rand 0.6) 0.3))
               :count 1 :speed 0.1
               :offset-x 0.02 :offset-y 0.02 :offset-z 0.02
               :motion-x (- (rand 0.04) 0.02)
               :motion-y (- (rand 0.04) 0.02)
               :motion-z (- (rand 0.04) 0.02)})))))
    (update store* :fx-state
      (fn [states]
        (into {}
              (keep (fn [[owner-key st]]
                      (let [ttl (long (or (:ttl st) 0))]
                        (if (> ttl 1)
                          [owner-key (update st :ttl dec)]
                          (do
                            (remove-diamond-shield! (:shield-entity-uuid st))
                            nil)))))
              states)))))

(defn- build-plan
  [camera-pos _hand-center-pos _tick]
  (let [states (vals (:fx-state (jet-engine-fx-snapshot)))
        marking-states (filter #(= :marking (:phase %)) states)
        triggering-states (filter #(= :triggering (:phase %)) states)
        mark-ops (vec
                   (mapcat (fn [st]
                             (when-let [target (:target st)]
                               (ring-ops target
                                         (+ 0.55 (* 0.2 (Math/sin (* 0.15 (double (:hold-ticks st))))))
                                         {:r 120 :g 245 :b 255 :a 170})))
                           marking-states))
        trigger-ops (vec
                      (mapcat (fn [st]
                                (let [ttl (long (or (:ttl st) 0))
                                      start (:start st)
                                      pos (:pos st)
                                      target (:target st)
                                      trigger-ticks (long (or (:trigger-ticks st) 0))
                                      alpha (int (* 215 (/ (double ttl) (double trigger-ttl))))
                                      impact-color {:r 210 :g 250 :b 255 :a (min 180 (+ 40 alpha))}
                                      impact-radius (+ 0.45 (* 0.18 (Math/sin (* 0.3 (double trigger-ticks)))))]
                                  (when (pos? ttl)
                                    (concat
                                      (when (and start pos)
                                        (trail-layer-ops start pos ttl trigger-ticks))
                                      (when target
                                        (concat
                                          (ring-ops target impact-radius impact-color)
                                          (impact-spike-ops target ttl trigger-ticks)
                                          (impact-billboard-ops camera-pos target ttl trigger-ticks)))))))
                              triggering-states))
        alpha (->> triggering-states
                   (map #(long (or (:ttl %) 0)))
                   (filter pos?)
                   (map #(int (* 220 (/ (double %) (double trigger-ttl))))
                   )
                   (reduce max 0))
        flash-op (when (pos? alpha)
                   {:type :screen-flash
                    :r 200 :g 220 :b 255 :a (min 85 alpha)})
        ws (when (seq triggering-states) 0.07)  ;; walk speed during trigger (matching original)
        ops (cond-> (into mark-ops trigger-ops)
              flash-op (conj flash-op))]
    (cond-> (when (seq ops) {:ops ops})
      ws (assoc :local-walk-speed (float ws)))))

(defn init!
  []
  (fx-spec/register!
    {:id jet-engine-effect-id
     :level {:initial-state (default-jet-engine-fx-runtime-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan}
     :channels {:start {:topic :jet-engine/fx-start}
                :update {:topic :jet-engine/fx-update}
                :end {:topic :jet-engine/fx-end}
                :trigger-start {:topic :jet-engine/fx-trigger-start}
                :trigger-update {:topic :jet-engine/fx-trigger-update}
                :trigger-end {:topic :jet-engine/fx-trigger-end}}})
  nil)
