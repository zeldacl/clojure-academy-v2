(ns cn.li.ac.content.ability.teleporter.mark-teleport-fx
  "Client FX for Mark Teleport: EntityTPMarking + ground ring + billboard marker.
  Matching original AcademyCraft: EntityTPMarking follows target position."
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]))

(def ^:private mark-teleport-effect-id :mark-teleport)

(defn default-mark-teleport-fx-runtime-state []
  {:effect-state {}})

(defn mark-teleport-fx-snapshot []
  (or (level-effects/effect-state-snapshot mark-teleport-effect-id)
      (default-mark-teleport-fx-runtime-state)))

(defn reset-mark-teleport-fx-for-test! []
  (level-effects/reset-level-effect-state-for-test!
    mark-teleport-effect-id
    (default-mark-teleport-fx-runtime-state))
  nil)

(defn clear-mark-teleport-owner! [owner-key]
  (level-effects/update-effect-state!
    mark-teleport-effect-id
    (fn [state]
      (update (or state (default-mark-teleport-fx-runtime-state)) :effect-state dissoc owner-key)))
  nil)

(defn- spawn-tp-marking! []
  (client-bridge/run-client-effect! :mcmod/spawn-local-scripted-effect {:effect-id "entity_tp_marking"}))

(defn- remove-tp-marking! []
  (client-bridge/run-client-effect! :mcmod/remove-local-scripted-effect {:effect-id "entity_tp_marking"}))

(defn- enqueue-state! [state {:keys [payload ctx-id channel owner-key]}]
  (let [state* (or state (default-mark-teleport-fx-runtime-state))
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
  (let [state* (or state (default-mark-teleport-fx-runtime-state))]
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
        side (ru/v* right half-width)
        p0 (ru/v+ (ru/v- center side) up)
        p1 (ru/v+ (ru/v+ center side) up)
        p2 (ru/v- (ru/v+ center side) up)
        p3 (ru/v- (ru/v- center side) up)
        halo-width (* half-width 1.35) halo-height 1.12
        halo-side (ru/v* right halo-width)
        halo-up {:x 0.0 :y halo-height :z 0.0}
        h0 (ru/v+ (ru/v- center halo-side) halo-up)
        h1 (ru/v+ (ru/v+ center halo-side) halo-up)
        h2 (ru/v- (ru/v+ center halo-side) halo-up)
        h3 (ru/v- (ru/v- center halo-side) halo-up)
        alpha (+ 85 (* 35 (+ 1.0 (Math/sin (* 0.25 (double ticks))))))]
    [(ru/quad-op "my_mod:textures/effects/glow_circle.png" h0 h1 h2 h3 {:r 160 :g 196 :b 255 :a (int alpha)})
     (ru/quad-op "my_mod:textures/effects/glow_circle.png" p0 p1 p2 p3 {:r 245 :g 250 :b 255 :a 180})]))

(defn- build-plan [camera-pos _hand-center-pos _tick]
  (let [ops (mapcat (fn [mk]
                      (when (and (:active? mk) (map? (:target mk)))
                        (let [{:keys [target ticks distance]} mk]
                          (concat (ground-ring-ops target ticks distance)
                                  (billboard-ops camera-pos target ticks)))))
                    (vals (:effect-state (mark-teleport-fx-snapshot))))]
    (when (seq ops) {:ops (vec ops)})))

(defn init! []
  (fx-spec/register!
    {:id mark-teleport-effect-id
     :level {:initial-state (default-mark-teleport-fx-runtime-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan}
     :channels {:start {:topic :mark-teleport/fx-start :mode :start}
                :update {:topic :mark-teleport/fx-update :mode :update
                         :level-payload (fn [_ _ p] {:target (:target p) :distance (double (or (:distance p) 0.0))})}
                :end {:topic :mark-teleport/fx-end :mode :end}
                :perform {:topic :mark-teleport/fx-perform :mode :perform
                          :level-payload (fn [_ _ p] {:target (:target p) :distance (double (or (:distance p) 0.0))})}}})
  nil)
