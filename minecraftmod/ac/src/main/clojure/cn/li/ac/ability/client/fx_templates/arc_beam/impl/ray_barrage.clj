(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.ray-barrage
  "Ray-barrage client FX: green tube rays (RendererRayComposite style).

  Original visuals:
  - MSG_EFFECT_PRERAY spawns EntityBarrageRayPre — ONE small green ray from
    the caster's eye to the aim target (life 30, or 50 when it hit a
    silbarn).
  - MSG_EFFECT_BARRAGE spawns EntityMdRayBarrage at the silbarn position —
    25~30 small green rays scattered around the caster's CURRENT aim
    (yaw ±50~60deg, pitch ±25~30deg), life 50.

  Both render with the same mdray_small composite (inner 216,248,216 /
  outer 106,242,106 / soft glow); the port renders them as tube + glow
  board ops (see beam-ops) so they are visible from any camera, including
  the caster's own on-axis first-person view. The preray applies the
  ViewOptimize hand fix like the original; the barrage does not
  (EntityMdRayBarrage.needsViewOptimize() == false)."
  (:require [cn.li.ac.ability.client.fx-templates.store-tick :as store-tick]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [cn.li.mcmod.util.log :as log]))

(def ^:private ray-style
  {:width 0.052
   :core-ratio 0.86
   :outer-rgb {:r 106 :g 242 :b 106}
   :outer-alpha (fn [_ life] (int (* 60 (+ 0.2 (* 0.8 life)))))
   :inner-rgb {:r 216 :g 248 :b 216}
   :inner-alpha (fn [_ life] (int (* 230 (+ 0.15 (* 0.85 life)))))})

(defn- all-rays
  []
  (mapcat val (:beam-queue (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :ray-barrage))))

(defn- look-dir-from-yaw-pitch
  "Minecraft look vector from yaw/pitch in degrees."
  [yaw-deg pitch-deg]
  (let [yaw (Math/toRadians (double yaw-deg))
        pitch (Math/toRadians (double pitch-deg))
        cp (Math/cos pitch)]
    {:dx (* -1.0 (Math/sin yaw) cp)
     :dy (Math/sin pitch)
     :dz (* (Math/cos yaw) cp)}))

(defn- barrage-sub-rays
  "25~30 sub rays from the silbarn position, scattered around the caster's
  aim by the original's SubRay offsets (yaw ±uniform(50,60)deg,
  pitch ±uniform(25,30)deg), length 15."
  [silbarn-pos yaw pitch]
  (let [count (long (+ 25 (rand-int 6)))
        max-angle (+ 50.0 (rand 10.0))
        base (vec3/map->v3 silbarn-pos)
        length 15.0]
    (vec
      (for [_ (range count)]
        (let [yaw-offset (- (rand (* 2.0 max-angle)) max-angle)
              pitch-offset (- (rand max-angle) (/ max-angle 2.0))
              dir (look-dir-from-yaw-pitch (+ (double (or yaw 0.0)) yaw-offset)
                                           (+ (double (or pitch 0.0)) pitch-offset))]
          {:start base
           :end (vec3/v+ base (vec3/v3 (* length (:dx dir))
                                       (* length (:dy dir))
                                       (* length (:dz dir))))
           :ttl 50 :max-ttl 50
           :barrage? true})))))

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (or store {:beam-queue {}})
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}
        rays (case mode
               ;; Original c_spawnPreRay: one ray from the caster's eye
               ;; (y + 1.6) to the aim point; life 50 when it hit a silbarn.
               :preray
               (when (and (map? (:start payload)) (map? (:end payload)))
                 (let [life (if (true? (:hit? payload)) 50 30)]
                   [(merge base-meta
                           {:start (vec3/map->v3 (:start payload))
                            :end (vec3/map->v3 (:end payload))
                            :ttl life :max-ttl life})]))
               ;; Original c_spawnBarrage: sub rays from the silbarn position
               ;; around the caster's aim, no view optimization.
               :barrage
               (barrage-sub-rays (:silbarn payload)
                                 (:yaw payload) (:pitch payload))
               nil)]
    (if (seq rays)
      (update store* :beam-queue
        (fn [by-owner]
          (assoc by-owner owner-key* (vec (concat (get by-owner owner-key*) rays)))))
      store*)))

(defn- tick-state!
  [store]
  (let [store* (or store {:beam-queue {}})]
    (update store* :beam-queue
      store-tick/tick-ttl-items-by-owner)))

(defn- expanding-barrage-beam
  "Barrage sub rays grow OUT of the silbarn: the endpoint starts at the
  silbarn and extends to full length over the first 10 ticks (upstream's
  200ms blend-in), so the burst reads as rays SHOOTING from the silbarn
  instead of a full-length fan popping in instantly."
  [beam]
  (if-not (:barrage? beam)
    beam
    (let [ttl (long (:ttl beam))
          max-ttl (long (:max-ttl beam))
          scale (max 0.0 (min 1.0 (/ (double (- max-ttl ttl)) 10.0)))
          start (:start beam)
          end (:end beam)]
      (if (>= scale 1.0)
        beam
        (assoc beam :end (vec3/v+ start (vec3/v* (vec3/v- end start) scale)))))))

(defn- build-plan
  [camera-pos hand-center-pos _tick]
  (when-let [rays (seq (all-rays))]
    (let [first-person? (boolean (:first-person? hand-center-pos))
          ;; Preray follows the original's needsViewOptimize: hand-fix the
          ;; START only, keeping the END anchored on the aim point ("Don't
          ;; fix end to get accurate pointing direction"); barrage rays are
          ;; not view-optimized (they issue from the silbarn, off the
          ;; caster's view axis already).
          preray-fixed (arc-beam/view-fix-rays hand-center-pos
                                               (filterv #(not (:barrage? %)) rays)
                                               {:fix-end? false})
          barrage (map expanding-barrage-beam (filterv :barrage? rays))
          fixed (into preray-fixed barrage)]
      {:ops (vec
             (mapcat
              (fn [beam]
                (concat
                 (fx-beam/fading-tube-beam-ops beam ray-style)
                 (fx-beam/fading-glow-board-ops
                  camera-pos beam ray-style
                  {:first-person? (and first-person? (not (:barrage? beam)))})))
              fixed))})))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:ray-barrage :level] [_ _] {:beam-queue {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:ray-barrage :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:ray-barrage :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :ray-barrage
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :ray-barrage [_ store _owner-key]
  ;; The rays are one-shot world visuals (upstream EntityBarrageRayPre /
  ;; EntityMdRayBarrage carry their own lives). The :instant context ends on
  ;; the same tick as perform, so MSG-CTX-TERMINATED reaches
  ;; clear-effect-owner! immediately — clearing the queue here deleted every
  ;; ray a frame after it appeared. They expire on their own ttl instead.
  store)
