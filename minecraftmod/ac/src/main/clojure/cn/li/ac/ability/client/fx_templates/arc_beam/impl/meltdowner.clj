(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.meltdowner
  (:require [cn.li.ac.ability.client.fx-templates.store-tick :as store-tick]
            [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
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
            [cn.li.mcmod.util.log :as log]
            [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [clojure.string :as str])
  (:import [cn.li.mcmod.math V3]))

(defn- update-meltdowner-fx-state!
  [f & args]
  (apply level-effects/update-effect-state! :meltdowner f args))
(def ^:private charge-loop-sound (modid/namespaced-path "md.md_charge"))
(def ^:private fire-sound (modid/namespaced-path "md.meltdowner"))
(defn- loop-sound-key [ctx-id] (str "meltdowner/" ctx-id))
(def ^:private meltdowner-ray-style
  {:width (fn [{:keys [is-reflect?]} life]
            (if is-reflect?
              (* 0.05 (+ 0.45 (* 0.55 life)))
              (* 0.09 (+ 0.6 (* 0.4 life)))))
   :core-ratio 0.42
   :outer-rgb {:r 161 :g 255 :b 142}
   :outer-alpha (fn [_ life] (int (+ 35 (* 170 life))))
   :inner-rgb {:r 244 :g 255 :b 236}
   :inner-alpha (fn [_ life] (int (+ 70 (* 170 life))))
   :line-rgb {:r 192 :g 255 :b 188}
   :line-alpha (fn [_ life] (int (+ 55 (* 150 life))))})

(defn- all-rays []
  (mapcat val (:rays (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :meltdowner))))

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [store ctx-id channel owner-key payload]
  (let [store* (or store {:effect-state {} :rays {}})
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode ticks charge-ratio performed? start end charge-ticks beam-length source-player-id player-id world-id]} (or payload {})
        ;; The content sends :player-id (the caster) on every charge event —
        ;; attribute the state to the caster so per-frame queries (walk-speed,
        ;; FOV zoom) can match their OWN charge instead of any nearby charge.
        source-player-id* (or source-player-id player-id)
        base-meta {:owner-key owner-key*
                   :queue-owner (client-sounds/current-effect-owner)
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
          :pitch 1.0})
        (assoc-in store* [:effect-state owner-key*]
                  (merge base-meta {:active? true :ticks 0 :charge-ratio 0.0 :performed? false})))
      :update
      (assoc-in store* [:effect-state owner-key*]
                (merge base-meta
                       (get-in store* [:effect-state owner-key*])
                       {:owner-key owner-key*
                        :ctx-id ctx-id
                        :channel channel
                        :source-player-id source-player-id
                        :world-id world-id
                        :active? true
                        :ticks (long (or ticks 0))
                        :charge-ratio (double (or charge-ratio 0.0))
                        :performed? false}))
      :end
      (do
        ;; Original c_terminate: sound.stop() — the charge loop follows the
        ;; caster until the context ends, however it ends.
        (client-bridge/run-client-effect!
         :mcmod/stop-loop-sound
         {:key (loop-sound-key ctx-id)})
        (assoc-in store* [:effect-state owner-key*]
                  (merge base-meta
                         {:active? false :performed? (boolean performed?)
                          :ticks 0 :charge-ratio 0.0})))
      :perform
      (let [store* (if (and start end)
                      (let [life (+ 16 (rand-int 8))]
                        (update-in store* [:rays owner-key*] (fnil conj [])
                                   (merge base-meta
                                          {:start (vec3/map->v3 start) :end (vec3/map->v3 end)
                                           :ttl life :max-ttl life
                                           :beam-length (double (or beam-length 30.0))
                                           :charge-ticks (int (or charge-ticks 20))
                                           :is-reflect? false})))
                      store*)]
        (client-sounds/queue-sound-effect! (:queue-owner base-meta)
          {:type :sound :sound-id fire-sound :volume 0.5 :pitch 1.0})
        store*)
      :reflect
      (if (and start end)
        (let [life (+ 10 (rand-int 6))]
          (update-in store* [:rays owner-key*] (fnil conj [])
                     (merge base-meta
                            {:start (vec3/map->v3 start) :end (vec3/map->v3 end)
                             :ttl life :max-ttl life
                             :beam-length 10.0 :charge-ticks 20
                             :is-reflect? true})))
        store*)
      store*)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- charge-ring-segments-local
  "Ring segment endpoints relative to center — depends only on ticks (charge
  animation phase) and charge-ratio (pulse radius), both tick-rate state, so
  precomputed once per tick here rather than every frame in build-plan."
  [ticks charge-ratio]
  (let [base-radius (+ 0.72 (* 0.28 (double charge-ratio)))
        pulse (+ base-radius (* 0.08 (Math/sin (* 0.23 (double ticks)))))
        y-base 0.18
        ring-segments 18]
    (vec
      (for [idx (range ring-segments)
            :let [a0 (/ (* 2.0 Math/PI idx) ring-segments)
                  a1 (/ (* 2.0 Math/PI (inc idx)) ring-segments)
                  h (+ y-base (* 0.22 (Math/sin (+ (* 0.17 ticks) idx))))]]
        {:p0 {:x (* pulse (Math/cos a0)) :y h :z (* pulse (Math/sin a0))}
         :p1 {:x (* pulse (Math/cos a1)) :y h :z (* pulse (Math/sin a1))}}))))

(defn- tick-state!
  [store]
  (let [store* (or store {:effect-state {} :rays {}})
        effect-state* (store-tick/map-active-states
                       (:effect-state store*)
                       (fn [_owner-key st]
                         (let [ticks (inc (long (or (:ticks st) 0)))]
                           ;; The charge loop is a continuous FollowEntitySound
                           ;; started on :start and stopped on :end — no re-queue.
                           ;; MdParticleFactory particles (matching original: 2-3 per tick)
                           (dotimes [_ (+ 2 (rand-int 2))]
                             (let [r (+ 0.7 (rand 0.3))
                                   theta (rand (* 2 Math/PI))
                                   h (+ -1.2 (rand 1.2))]
                               (client-particles/queue-particle-effect! (:queue-owner st)
                                 {:type :particle :particle-type :electric-spark
                                  :x (* r (Math/sin theta))
                                  :y h
                                  :z (* r (Math/cos theta))
                                  :count 1 :speed 0.08
                                  :offset-x 0.03 :offset-y 0.03 :offset-z 0.03
                                  :motion-x (- (rand 0.06) 0.03)
                                  :motion-y (+ 0.01 (rand 0.04))
                                  :motion-z (- (rand 0.06) 0.03)})))
                           (assoc st
                             :ticks ticks
                             :charge-ring-segments-local
                             (charge-ring-segments-local ticks (double (or (:charge-ratio st) 0.0)))))))
        rays* (store-tick/tick-ttl-items-by-owner (:rays store*))]
    (assoc store* :effect-state effect-state* :rays rays*)))

(defn- tick!
  ([]
   (update-meltdowner-fx-state!
     (fn [store]
       (tick-state! store)))
   nil)
  ([store]
   (tick-state! store)))

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- charge-ops
  "segments-local: precomputed by charge-ring-segments-local (per tick); this
  fn only translates by the live hand-center each frame."
  [^V3 center segments-local]
  (let [cx (.-x center) cy (.-y center) cz (.-z center)
        ray-color {:r 170 :g 255 :b 190 :a 170}
        link-color {:r 140 :g 240 :b 170 :a 120}]
    (vec
      (mapcat
        (fn [{:keys [p0 p1]}]
          (let [p0' (vec3/v3 (+ cx (:x p0)) (+ cy (:y p0)) (+ cz (:z p0)))
                p1' (vec3/v3 (+ cx (:x p1)) (+ cy (:y p1)) (+ cz (:z p1)))]
            [(ru/line-op p0' p1' ray-color)
             (ru/line-op center p0' link-color)]))
        segments-local))))

(defn- local-walk-speed [ticks]
  (float (max 0.001 (- 0.1 (* 0.001 (double ticks))))))

(defn- matching-active-state [hand-center-pos]
  (some (fn [st]
          (when (and (:active? st)
                     (or (nil? (:source-player-id st))
                         (nil? (:player-uuid hand-center-pos))
                         (= (str (:source-player-id st))
                            (str (:player-uuid hand-center-pos)))))
            st))
  (vals (:effect-state (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :meltdowner)))))

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
  (let [md (matching-active-state {:player-uuid (str player-uuid)})
        target (if md
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
  (let [md (matching-active-state hand-center-pos)
        current-rays (all-rays)
        ;; Original ViewOptimize: translate rays to the hand so the tube AND
        ;; glow board issue from off the caster's view axis and stay visible
        ;; in first person.
        fixed-rays (arc-beam/view-fix-rays hand-center-pos current-rays)
        ^V3 cam-v (vec3/map->v3 camera-pos)
        charge-plan (if (and hand-center-pos md (:active? md) (seq (:charge-ring-segments-local md)))
                      (charge-ops (vec3/map->v3 (dissoc hand-center-pos :player-uuid))
                                  (:charge-ring-segments-local md))
                      [])
        ws (when (and md (:active? md))
             (local-walk-speed (:ticks md)))
        ;; Tube (RendererRayCylinder-style) rays, from the hand-fixed start —
        ;; the near tube walls are then off the caster's view axis and stay
        ;; visible in first person.
        ray-plan (mapcat #(fx-beam/fading-tube-beam-ops % meltdowner-ray-style) fixed-rays)
        ;; Original RendererRayGlow board: the wide soft quad that carries the
        ;; first-person look (fixed up-and-back axis (0,1,-0.5) + hand-fixed
        ;; start keep it off the view ray; third person uses the
        ;; view-perpendicular axis).
        glow-plan (mapcat #(fx-beam/fading-glow-board-ops
                            cam-v % meltdowner-ray-style
                            {:first-person? (boolean (:first-person? hand-center-pos))})
                          fixed-rays)]
    (when (or (seq charge-plan) (seq ray-plan) (seq glow-plan) ws)
      {:ops (vec (concat charge-plan ray-plan glow-plan))
       :local-walk-speed ws})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:meltdowner :level] [_ _] {:effect-state {} :rays {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:meltdowner :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:meltdowner :level] [_ _ store] (tick! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :meltdowner
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :meltdowner [_ store owner-key]
  ;; Charge state and loop sound are context-bound; a fired ray is not.
  ;; Upstream c_perform spawns EntityMDRay into the world and c_terminate only
  ;; restores walk speed and stops the sound — the ray lives out its own life.
  ;; See railgun_shot.clj's clear-owner for the full shape of this bug.
  (client-bridge/run-client-effect!
   :mcmod/stop-loop-sound
   {:key (loop-sound-key (second owner-key))})
  (update store :effect-state dissoc owner-key))
