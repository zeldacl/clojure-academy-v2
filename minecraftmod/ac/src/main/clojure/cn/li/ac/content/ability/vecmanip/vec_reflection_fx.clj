(ns cn.li.ac.content.ability.vecmanip.vec-reflection-fx
  "Client FX for VecReflection: reflection wave billboards (upstream
  WaveEffect) at the affected entities."
  (:require
            [cn.li.ac.ability.client.fx-templates.store-tick :as store-tick]
            [cn.li.ac.config.modid :as modid] [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.cubic-curve :as curve]
            [cn.li.ac.ability.client.render-util :as ru]))

(def ^:private vec-reflection-effect-id :vec-reflection)
(def ^:private sound-id (modid/namespaced-path "vecmanip.vec_reflection"))

(defn default-vec-reflection-fx-runtime-state
  []
  {:effect-state {}
   :wave-effects {}})

(defn vec-reflection-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot vec-reflection-effect-id)
      (default-vec-reflection-fx-runtime-state)))

(defn reset-vec-reflection-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    vec-reflection-effect-id
    (default-vec-reflection-fx-runtime-state))
  nil)

(defn clear-vec-reflection-owner!
  [owner-key]
  (level-effects/update-effect-state!
    vec-reflection-effect-id
    (fn [state]
      (-> (or state (default-vec-reflection-fx-runtime-state))
          (update :effect-state dissoc owner-key)
          (update :wave-effects dissoc owner-key))))
  nil)

(defn- queue-reflection-sound!
  [x y z]
  (client-sounds/queue-current-sound-effect!
    {:type :sound :sound-id sound-id :volume 0.5 :pitch 1.0
     :x (double (or x 0.0))
     :y (double (or y 0.0))
     :z (double (or z 0.0))}))

;; WaveEffect(world, rings = 2, size = 1.1), life 15 ticks. Each ring gets its
;; own life, a depth offset along the caster's view normal, a size jitter and a
;; start delay, exactly as the entity's constructor rolls them.
(def ^:private wave-life-ticks 15)
(def ^:private wave-rings 2)
(def ^:private wave-size 1.1)

;; WaveEffectRenderer's two curves.
(def ^:private alpha-curve
  (curve/curve [[0.0 0.0] [0.2 1.0] [0.5 1.0] [0.8 1.0] [1.0 0.0]]))
(def ^:private size-curve
  (curve/curve [[0.0 0.4] [0.2 0.8] [2.5 1.5]]))

(defn- ranged
  ^double [^double from ^double to]
  (+ from (rand (- to from))))

(defn- rangei
  [from to]
  (+ (long from) (rand-int (- (long to) (long from)))))

(defn- build-rings
  []
  (mapv (fn [idx]
          {:life (rangei 8 12)
           :offset (+ (* idx 1.5) (ranged -0.3 0.3))
           :size (* wave-size (ranged 0.8 1.2))
           :time-offset (+ (* idx 2) (rangei -1 1))})
        (range wave-rings)))

(defn- enqueue-wave
  [store owner-key base-meta x y z yaw-rad pitch-rad]
  (update-in store [:wave-effects owner-key]
    (fnil conj [])
    (merge base-meta
           {:x (double (or x 0.0))
            :y (double (or y 0.0))
            :z (double (or z 0.0))
            ;; eff.rotationYaw = player.rotationYawHead, rotationPitch =
            ;; player.rotationPitch -- frozen at spawn, and the CASTER's, so it
            ;; travels with the message rather than being read off whoever
            ;; happens to be watching.
            :yaw-rad (double (or yaw-rad 0.0))
            :pitch-rad (double (or pitch-rad 0.0))
            :rings (build-rings)
            :ttl wave-life-ticks
            :max-ttl wave-life-ticks})))

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (or store (default-vec-reflection-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode x y z reflected? yaw-rad pitch-rad source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (assoc-in store* [:effect-state owner-key*]
                (merge base-meta {:active? true :ticks 0}))
      :end
      (assoc-in store* [:effect-state owner-key*]
                (merge base-meta {:active? false :ticks 0}))
      :reflect-entity
      (if reflected?
        (do
          (queue-reflection-sound! x y z)
          (enqueue-wave store* owner-key* base-meta x y z yaw-rad pitch-rad))
        store*)
      :play
      (do
        (queue-reflection-sound! x y z)
        (enqueue-wave store* owner-key* base-meta x y z yaw-rad pitch-rad))
      store*)))

(defn- tick-state!
  [store]
  (let [state* (or store (default-vec-reflection-fx-runtime-state))]
    (assoc state*
           :effect-state (store-tick/keep-active-inc-ticks (:effect-state state*))
           :wave-effects (store-tick/tick-ttl-items-by-owner (:wave-effects state*)))))

(defn- view-basis
  "The effect frame: glRotated(-yaw) then glRotated(pitch) sends local +z along
  the caster's look, which is what the rings stack and drift along."
  [^double yaw ^double pitch]
  (let [sy (Math/sin yaw) cy (Math/cos yaw)
        sp (Math/sin pitch) cp (Math/cos pitch)]
    {:right (vec3/v3 cy 0.0 sy)
     :up (vec3/v3 (* (- sp) sy) cp (* sp cy))
     :fwd (vec3/v3 (* (- cp) sy) (- sp) (* cp cy))}))

(defn- wave-ops
  "WaveEffectRenderer: one quad per ring, all in the caster's view plane,
  spaced along its normal by the ring's offset and pushed forward by
  ticksExisted/40 as a group. Each ring fades on the shared alpha curve -- its
  own progress capped by the whole effect's -- and scales on the size curve.
  Depth test and cull off, white, at 0.7 of the curve's alpha."
  [{:keys [x y z ttl max-ttl rings yaw-rad pitch-rad]}]
  (let [max-ttl (double (max 1 (or max-ttl wave-life-ticks)))
        age (- max-ttl (double (or ttl 0)))
        max-alpha (max 0.0 (min 1.0 (curve/value-at alpha-curve (/ age max-ttl))))
        {:keys [right up fwd]} (view-basis (double (or yaw-rad 0.0))
                                           (double (or pitch-rad 0.0)))
        origin (vec3/v3 (double x) (double y) (double z))
        drift (vec3/v+ origin (vec3/v* fwd (/ age 40.0)))
        size-scale (curve/value-at size-curve (max 0.0 (min 1.62 (/ age 20.0))))]
    (keep (fn [{:keys [life offset size time-offset]}]
            (let [ring-alpha (max 0.0 (min 1.0 (curve/value-at
                                                 alpha-curve
                                                 (/ (- age (double time-offset))
                                                    (double (max 1 life))))))
                  real-alpha (min max-alpha ring-alpha)]
              (when (pos? real-alpha)
                (let [center (vec3/v+ drift (vec3/v* fwd (double offset)))
                      ;; createBillboard(-.5, -.5, 1, 1) is a 1x1 quad, so the
                      ;; half-extent is 0.5 before the ring's own scale.
                      half (* 0.5 (double size) size-scale)
                      side (vec3/v* right half)
                      lift (vec3/v* up half)
                      p0 (vec3/v+ (vec3/v- center side) lift)
                      p1 (vec3/v+ (vec3/v+ center side) lift)
                      p2 (vec3/v- (vec3/v+ center side) lift)
                      p3 (vec3/v- (vec3/v- center side) lift)]
                  (assoc
                    (ru/quad-op (modid/asset-path "textures" "effects/glow_circle.png")
                                p0 p1 p2 p3
                                {:r 255 :g 255 :b 255
                                 :a (int (max 0 (min 255 (* 255.0 real-alpha 0.7))))})
                    :no-depth-test? true)))))
          rings)))

(defn- build-plan
  [_camera-pos _hand-center-pos _tick _query-fn]
  (let [{:keys [wave-effects]} (vec-reflection-fx-snapshot)
        wave-plan (mapcat wave-ops (mapcat val wave-effects))]
    (when (seq wave-plan)
      {:ops (vec wave-plan)})))

(defn init!
  []
  (fx-spec/register!
    {:id vec-reflection-effect-id
     :level {:initial-state (default-vec-reflection-fx-runtime-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan}
     :channels {:start {:topic :vec-reflection/fx-start :mode :start}
                :end {:topic :vec-reflection/fx-end :mode :end}
                :reflect-entity {:topic :vec-reflection/fx-reflect-entity :mode :reflect-entity
                                 :level-payload (fn [_ _ p]
                                                  {:x (double (or (:x p) 0.0))
                                                   :y (double (or (:y p) 0.0))
                                                   :z (double (or (:z p) 0.0))
                                                   :reflected? (boolean (:reflected? p))
                                                   :yaw-rad (:yaw-rad p)
                                                   :pitch-rad (:pitch-rad p)})}
                :play {:topic :vec-reflection/fx-play :mode :play
                       :level-payload (fn [_ _ p]
                                        {:x (double (or (:x p) 0.0))
                                         :y (double (or (:y p) 0.0))
                                         :z (double (or (:z p) 0.0))
                                         :yaw-rad (:yaw-rad p)
                                         :pitch-rad (:pitch-rad p)})}}})
  nil)
