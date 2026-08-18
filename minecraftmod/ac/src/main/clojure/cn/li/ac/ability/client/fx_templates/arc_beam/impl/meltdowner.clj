(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.meltdowner
  (:require [cn.li.ac.ability.client.fx-templates.store-tick :as store-tick]
            [cn.li.ac.ability.client.effects.ray-composite :as ray-composite]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.client.effect-controller :as vfx-level]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.ac.ability.client.effects.rv3 :as vec3])
  (:import [cn.li.mcmod.math V3]))

(def ^:private charge-loop-sound (modid/namespaced-path "md.md_charge"))
(def ^:private fire-sound (modid/namespaced-path "md.meltdowner"))
(defn- loop-sound-key [ctx-id] (str "meltdowner/" ctx-id))
;; MDRayRender (RendererRayComposite "mdray"):
;;   cylinderIn  radius 0.17,  rgba(216, 248, 216, 230)
;;   cylinderOut radius 0.22,  rgba(106, 242, 106, 50)
;;   glow        width 1.5, white at alpha 0.8
;; The port had 0.09 for the outer and 0.09*0.42 for the inner — a quarter of
;; the original bore — in colours of its own.
(def ^:private ray-glow-textures (ray-composite/glow-textures "mdray"))
(def ^:private ray-glow-width 1.5)
(def ^:private ray-outer-radius 0.22)
(def ^:private ray-inner-radius 0.17)

;; EntityMDRay: life 50 ticks, blendIn 200ms, blendOut 700ms. EntityRayBase's
;; width holds at 1 (plus a [0, 0.1] wiggle) until the last widthShrinkTime,
;; which MDRay leaves at the 300ms default.
(def ^:private ray-life-ticks 50)
(def ^:private ray-blend-in-ticks 4.0)
(def ^:private ray-blend-out-ticks 14.0)
(def ^:private ray-width-shrink-ticks 6.0)

(defn- ray-alpha
  "EntityRayBase.getAlpha × the layer's own alpha."
  ^double [beam ^double base]
  (let [max-ttl (double (max 1 (or (:max-ttl beam) ray-life-ticks)))
        ttl (double (or (:ttl beam) 0))
        age (- max-ttl ttl)]
    (* base
       (min 1.0 (/ age ray-blend-in-ticks))
       (min 1.0 (/ ttl ray-blend-out-ticks)))))

(defn- ray-width-factor
  ^double [beam]
  (let [ttl (double (or (:ttl beam) 0))
        seed (double (or (:wiggle-seed beam) 0.0))
        ;; widthWiggle random-walks in [0, 0.1]; a deterministic sine reads the
        ;; same and keeps the renderer stateless.
        wiggle (* 0.05 (+ 1.0 (Math/sin (+ seed (* ttl 0.9)))))]
    (+ wiggle (if (> ttl ray-width-shrink-ticks)
                1.0
                (max 0.0 (/ ttl ray-width-shrink-ticks))))))

;; vfx-core :transient migration (docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md
;; E section): one real vfx-core instance per (owner, activation) now, so
;; state is this cast's own map directly -- :rays is a flat vector instead
;; of an owner-map of vectors (owner isolation comes from instance identity
;; itself).
;;
;; This case dispatch is preserved EXACTLY as it was before this migration,
;; including the fact that NONE of its branches match a real event today --
;; not even :perform/:reflect, unlike most of this batch's other files.
;; combat_content.clj's :meltdowner skill sends exactly ONE :vfx step, ever:
;; :event :release from its :release phase, with :params {:range 64.0} --
;; :release doesn't match :start/:update/:end/:perform/:reflect, so it falls
;; straight through to the trailing `state*` no-op default every time. In
;; production today: the charge loop sound never starts, no beam ever
;; spawns, the walk-speed slowdown and FOV zoom-while-charging never
;; activate. This is a MUCH simpler finding than it looks from the
;; surrounding code's complexity (the original migration plan for this file
;; expected to need a "fade after :end" state machine for beams that
;; outlive their charge -- that concern doesn't apply, because no beam is
;; ever created in the first place). Migrated structurally only.
(defn- enqueue! [state ctx-id channel _owner-key payload]
  (let [state* (or state {})
        {:keys [mode ticks charge-ratio performed? start end charge-ticks beam-length
                source-player-id player-id world-id caster-x caster-y caster-z]} (or payload {})
        ;; The content sends :player-id (the caster) on every charge event —
        ;; attribute the state to the caster so per-frame queries (walk-speed,
        ;; FOV zoom) can match their OWN charge instead of any nearby charge.
        source-player-id* (or source-player-id player-id)
        base-meta {:queue-owner (client-sounds/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id*
                   :world-id world-id}]
    (case mode
      :start
      (do
        ;; Original c_start: FollowEntitySound loop (AMBIENT, volume 1.0)
        ;; attached to the caster until stopped — not a re-queued one-shot.
        (client-bridge/run-client-effect!
         :mcmod/start-loop-sound-at-player
         {:key (loop-sound-key ctx-id)
          :sound-id charge-loop-sound
          :owner-uuid (str source-player-id*)
          :volume 1.0
          :pitch 1.0
          ;; setVolume(1.0) but never setLoop() — one playback that follows the
          ;; caster and is cut short by stop() on terminate.
          :loop? false})
        (merge state* base-meta {:active? true :ticks 0 :charge-ratio 0.0 :performed? false}))
      :update
      (assoc (merge base-meta state*)
             :active? true
             :ticks (long (or ticks 0))
             :charge-ratio (double (or charge-ratio 0.0))
             :caster-pos (when (and caster-x caster-y caster-z)
                           {:x (double caster-x)
                            :y (double caster-y)
                            :z (double caster-z)})
             :performed? false)
      :end
      (do
        ;; Original c_terminate: sound.stop() — the charge loop follows the
        ;; caster until the context ends, however it ends.
        (client-bridge/run-client-effect!
         :mcmod/stop-loop-sound
         {:key (loop-sound-key ctx-id)})
        (merge state* base-meta
               {:active? false :performed? (boolean performed?)
                :ticks 0 :charge-ratio 0.0}))
      :perform
      (let [state* (if (and start end)
                     ;; EntityMDRay.life is a flat 50 ticks — the port rolled
                     ;; 16-23, so the beam vanished in a third of the time and
                     ;; never twice the same.
                     (update state* :rays (fnil conj [])
                             (merge base-meta
                                    {:start (vec3/map->v3 start) :end (vec3/map->v3 end)
                                     :ttl ray-life-ticks :max-ttl ray-life-ticks
                                     :beam-length (double (or beam-length 30.0))
                                     :charge-ticks (int (or charge-ticks 20))
                                     :wiggle-seed (* 2.0 Math/PI (rand))
                                     :is-reflect? false}))
                     state*)]
        ;; ACSounds.playClient(player, "md.meltdowner", PLAYERS, 0.5f) — at the
        ;; caster, not wherever the listener happens to be standing.
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          (cond-> {:type :sound :sound-id fire-sound :volume 0.5 :pitch 1.0
                   :source :players}
            (map? start) (assoc :x (double (:x start))
                                :y (double (:y start))
                                :z (double (:z start)))))
        state*)
      :reflect
      (if (and start end)
        ;; c_reflected spawns the same EntityMDRay, so the same 50-tick life.
        (update state* :rays (fnil conj [])
                (merge base-meta
                       {:start (vec3/map->v3 start) :end (vec3/map->v3 end)
                        :ttl ray-life-ticks :max-ttl ray-life-ticks
                        :beam-length 10.0 :charge-ticks 20
                        :wiggle-seed (* 2.0 Math/PI (rand))
                        :is-reflect? true}))
        state*)
      state*)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- emit-ray-trail!
  "EntityMDRay.onUpdate: on 80% of ticks, one MdParticle at a random point
  0-10 blocks along the ray with a small random drift. The port drew the ray
  with no trail at all."
  [{:keys [start end] :as ray}]
  (when (and start end (< (rand) 0.8))
    (let [len (Math/max 1.0e-5 (vec3/vlen (vec3/v- end start)))
          t (Math/min 1.0 (/ (double (rand 10.0)) len))
          p (vec3/v+ start (vec3/v* (vec3/v- end start) t))]
      (client-particles/queue-particle-effect! (:queue-owner ray)
        {:type :particle :particle-type (modid/namespaced-path "md_particle")
         :x (.-x ^cn.li.mcmod.math.V3 p)
         :y (.-y ^cn.li.mcmod.math.V3 p)
         :z (.-z ^cn.li.mcmod.math.V3 p)
         :count 1 :speed 0.03
         :offset-x 1.0 :offset-y 1.0 :offset-z 1.0}))))

(defn- tick-state!
  "Returning nil ends the instance -- see vfx-core/runtime.clj's
   tick-instance. Stay alive while :active? or a ray is still animating.
   Mirrors the pre-migration store-tick/map-active-states behavior exactly:
   once :active? goes false, the charge-tracking fields (:ticks/
   :charge-ratio/:caster-pos/...) are dropped on the NEXT tick (matching
   map-active-states silently excluding every inactive owner from its
   result) -- only :rays survives past that point."
  [state]
  (let [state* (or state {})
        active? (boolean (:active? state*))]
    (doseq [ray (:rays state*)] (emit-ray-trail! ray))
    (let [rays (store-tick/tick-ttl-vec (:rays state*))]
      (when (or active? (seq rays))
        (if active?
          (do
            ;; The particle queue takes ABSOLUTE world coordinates, and this
            ;; was handing it the raw (r*sin, h, r*cos) offsets — every mote
            ;; spawned within a block of world origin, so the charge had no
            ;; visible particles at all. (Upstream's own loop is
            ;; `for (count <- rangei(2,3) to 0)`, an empty Scala range, so it
            ;; spawns none either; this is the effect it was written to
            ;; produce.)
            (when-let [pos (:caster-pos state*)]
              (dotimes [_ (+ 2 (rand-int 2))]
                (let [r (+ 0.7 (rand 0.3))
                      theta (rand (* 2 Math/PI))
                      h (+ 0.4 (rand 1.2))]
                  (client-particles/queue-particle-effect! (:queue-owner state*)
                    {:type :particle :particle-type (modid/namespaced-path "md_particle")
                     :x (+ (double (:x pos)) (* r (Math/sin theta)))
                     :y (+ (double (:y pos)) h)
                     :z (+ (double (:z pos)) (* r (Math/cos theta)))
                     :count 1 :speed 0.04
                     :offset-x 0.5 :offset-y 0.8 :offset-z 0.5}))))
            (assoc state* :ticks (inc (long (or (:ticks state*) 0))) :rays rays))
          {:rays rays})))))

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- local-walk-speed [ticks]
  (float (max 0.001 (- 0.1 (* 0.001 (double ticks))))))

;; ---------------------------------------------------------------------------
;; Charge camera zoom (original's charge pull-back, restored on release)
;; ---------------------------------------------------------------------------

(def ^:private fov-zoom-max-degrees 24.0)
(def ^:private fov-ease-rate 0.12)
(def ^:private fov-offset-eased* (atom 0.0))

(defn current-fov-offset
  "Smooth camera FOV offset (degrees) for the LOCAL player's own meltdowner
  charge: eases toward charge-ratio * fov-zoom-max-degrees while charging,
  back to 0 after release/abort. Per-frame (ComputeFov); the easing state is
  a module atom because it must keep decaying after the effect state's
  :end/abort has already cleared the :active? entry."
  [player-uuid]
  (let [md (vfx-level/instance-for-owner :meltdowner (str player-uuid) :level)
        target (if (and md (:active? md))
                 (* fov-zoom-max-degrees (double (or (:charge-ratio md) 0.0)))
                 0.0)]
    (swap! fov-offset-eased*
           (fn [cur] (+ (* cur (- 1.0 fov-ease-rate))
                        (* target fov-ease-rate))))
    @fov-offset-eased*))

(defn reset-fov-offset-for-test! []
  (reset! fov-offset-eased* 0.0)
  nil)

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan
  "Ray :start/:end are precomputed to V3 at enqueue time (see :perform /
  :reflect above) — a ray's endpoints never change after it's fired, so
  converting once there instead of once per frame here removes an
  otherwise-per-frame allocation for every live ray."
  [camera-pos hand-center-pos _tick]
  (let [state (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :meltdowner)
        ;; One real vfx-core instance now exists per active caster; this
        ;; still only draws the LOCAL player's own charge (walk-speed
        ;; override), because hand-center-pos is only ever the local
        ;; viewer's own position -- there is no remote-player position to
        ;; compare against otherwise. The rays below are NOT filtered by
        ;; this: they are world-positioned, visible to everyone, same as
        ;; before this migration (mirrors directed_blastwave.clj's
        ;; precedent for this owner-check).
        md (when (and (:active? state)
                      (or (nil? (:source-player-id state))
                          (nil? (:player-uuid hand-center-pos))
                          (= (str (:source-player-id state))
                             (str (:player-uuid hand-center-pos)))))
             state)
        current-rays (:rays state)
        ;; Original ViewOptimize: translate rays to the hand so the tube AND
        ;; glow board issue from off the caster's view axis and stay visible
        ;; in first person.
        fixed-rays (arc-beam/view-fix-rays hand-center-pos current-rays)
        ^V3 cam-v (vec3/map->v3 camera-pos)
        ws (when (and md (:active? md))
             (local-walk-speed (:ticks md)))
        ;; RendererRayComposite: the two cylinders as real tubes (upstream
        ;; radii, upstream colours) plus the three glow boards, emitted in the
        ;; composite's own order (glow first, then the inner tube).
        ray-plan (mapcat (fn [ray]
                           (let [w (ray-width-factor ray)
                                 ga (ray-alpha ray 1.0)
                                 seed (double (or (:wiggle-seed ray) 0.0))
                                 life (/ (double (:ttl ray)) (double (:max-ttl ray)))]
                             (ray-composite/composite-ops cam-v (:start ray) (:end ray)
                               {:glow {:textures ray-glow-textures
                                       :width (* ray-glow-width w)
                                       :color {:r 255 :g 255 :b 255
                                               :a (int (ray-composite/glow-alpha 204.0 ga seed life))}}
                                :inner {:radius (* ray-inner-radius w)
                                        :color {:r 216 :g 248 :b 216 :a (int (ray-alpha ray 230.0))}}
                                :outer {:radius (* ray-outer-radius w)
                                        :color {:r 106 :g 242 :b 106 :a (int (ray-alpha ray 50.0))}}})))
                         fixed-rays)]
    (when (or (seq ray-plan) ws)
      {:ops (vec ray-plan)
       :local-walk-speed ws})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:meltdowner :level] [_ _] {})
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:meltdowner :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue! store ctx-id channel owner-key payload))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:meltdowner :level] [_ _ store] (tick-state! store))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :meltdowner
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-destroy! :meltdowner
  [_ state]
  ;; Charge loop sound is context-bound; a fired ray is not (upstream
  ;; c_perform spawns EntityMDRay into the world and c_terminate only
  ;; restores walk speed and stops the sound — the ray lives out its own
  ;; life). Only release the sound if it might actually be playing.
  (when (:active? state)
    (client-bridge/run-client-effect!
     :mcmod/stop-loop-sound
     {:key (loop-sound-key (:ctx-id state))})))
;; No effect-clear-owner! override anymore -- superseded by :destroy-fn
;; above (build-spec wires it unconditionally via dispatch-destroy!), which
;; vfx-core's real destroy!/clear-owner! now reach correctly per instance.
