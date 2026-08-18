(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.directed-blastwave
  (:require [cn.li.ac.ability.client.fx-templates.store-tick :as store-tick]
            [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]
            [cn.li.ac.client.effect-controller :as vfx-hand]
            [cn.li.ac.client.effect-controller :as vfx-level]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.runtime :as client-runtime]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.hooks.core :as runtime-hooks]
            [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [clojure.string :as str]
            [cn.li.ac.ability.client.fx-templates.arc-beam])
  (:import [cn.li.mcmod.math V3]))

(def ^:private sound-id (modid/namespaced-path "vecmanip.directed_blast"))
(def ^:private wave-life 15)

;; vfx-core :transient migration (docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md
;; E section): one real vfx-core instance per (owner, activation) now, so
;; state is this cast's own map directly -- no more :effect-state/:waves
;; owner-map wrapping (owner isolation comes from instance identity itself).
;;
;; This case dispatch is preserved EXACTLY as it was before this migration.
;; combat_content.clj's :directed-blastwave skill sends exactly ONE :vfx
;; step, ever: :event :perform from its :release phase, with
;; :params {:radius 3.0} -- no :start/:update/:end, and none of the
;; :pos/:look-dir/:charge-ticks/:punched?/:performed? fields the branches
;; read. In production today :perform still unconditionally queues its
;; sound cue (below), but at world origin (0,0,0) rather than the caster's
;; position, since :pos is always nil here; :active? never becomes true
;; (only :start/:update would set it, and neither fires) so the charge-ring
;; visual never renders, and wave-entry is always nil (:pos required) so the
;; wave visual never renders either. Migrated structurally only.
(defn- enqueue-state!
  [store ctx-id channel _owner-key payload]
  (let [store* (or store {})
        {:keys [mode charge-ticks punched? pos look-dir performed? source-player-id world-id]} (or payload {})
        base-meta {:ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (merge store* base-meta {:active? true :charge-ticks 0 :punched? false :performed? false})
      :update
      (assoc (merge base-meta store*)
             :active? true
             :charge-ticks (long (or charge-ticks 0))
             :punched? (boolean punched?)
             :performed? false)
      :perform
      (let [wave-entry (when (map? pos)
                         (let [d (or look-dir {:x 0.0 :y 0.0 :z 1.0})
                               len (Math/sqrt (+ (* (:x d) (:x d))
                                                 (* (:y d) (:y d))
                                                 (* (:z d) (:z d))))
                               inv (/ 1.0 (max 1.0e-6 len))
                               dir {:x (* (double (:x d)) inv)
                                    :y (* (double (:y d)) inv)
                                    :z (* (double (:z d)) inv)}
                               rings (+ 2 (rand-int 2))]
                           (merge base-meta
                                  {:pos {:x (double (:x pos)) :y (double (:y pos)) :z (double (:z pos))}
                                   :dir dir
                                   :ttl wave-life :max-ttl wave-life
                                   :rings (vec (map (fn [idx]
                                                      {:life (+ 8 (rand-int 5))
                                                       :offset (+ (* idx 1.5) (- (* (rand) 0.6) 0.3))
                                                       :size (* 1.0 (+ 0.8 (* (rand) 0.4)))
                                                       :time-offset (+ (* idx 2) (- (rand-int 3) 1))})
                                                    (range rings)))})))
            updated-store (if wave-entry
                            (update store* :waves (fnil conj []) wave-entry)
                            store*)]
        (client-sounds/queue-current-sound-effect!
          {:type :sound :sound-id sound-id :volume 0.5 :pitch 1.0
           :x (double (or (:x pos) 0.0))
           :y (double (or (:y pos) 0.0))
           :z (double (or (:z pos) 0.0))})
        updated-store)
      :end
      (merge store* base-meta {:active? false :charge-ticks 0 :punched? false
                                :performed? (boolean performed?)})
      store*)))

(defn- tick-state!
  "Returning nil ends the instance -- see vfx-core/runtime.clj's
   tick-instance. Stay alive while :active? or a wave is still animating,
   mirroring vec_deviation's tick-state! (no :ticks bump, matching the
   pre-migration keep-active without inc-ticks)."
  [store]
  (let [store* (or store {})
        active? (boolean (:active? store*))
        waves (store-tick/tick-ttl-vec (:waves store*))]
    (when (or active? (seq waves))
      (assoc store* :waves waves))))

(defn- alpha-curve
  [t]
  (cond
    (< t 0.0) 0.0
    (< t 0.2) (/ t 0.2)
    (< t 0.8) 1.0
    (< t 1.0) (- 1.0 (/ (- t 0.8) 0.2))
    :else 0.0))

(defn- size-scale
  [ticks]
  (let [x (min 1.62 (max 0.0 (/ (double ticks) 20.0)))]
    (cond
      (< x 0.2) (+ 0.4 (* (/ x 0.2) (- 0.8 0.4)))
      (<= x 1.62) (+ 0.8 (* (/ (- x 0.2) (- 1.62 0.2)) (- 1.5 0.8)))
      :else 1.5)))

(defn- basis
  [^V3 dir]
  (let [n-dir (vec3/vnorm dir)
        up-axis (if (> (Math/abs (.-y n-dir)) 0.95)
                  vec3/unit-x
                  vec3/unit-y)
        right (vec3/vnorm (vec3/vcross n-dir up-axis))
        up (vec3/vnorm (vec3/vcross right n-dir))]
    [right up n-dir]))

(defn- wave-ops
  [{:keys [pos dir ttl max-ttl rings]}]
  (let [^V3 pos (vec3/map->v3 pos)
        ^V3 dir (vec3/map->v3 dir)
        ticks (- (long max-ttl) (long ttl))
        max-alpha (alpha-curve (/ (double ticks) (double (max 1 max-ttl))))
        ss (size-scale ticks)
        [right up forward] (basis dir)
        z-offset (/ (double ticks) 40.0)]
    (vec
      (mapcat
        (fn [{:keys [life offset size time-offset]}]
          (let [local-t (- (/ (double ticks) (double (max 1 life))) (/ (double time-offset) (double (max 1 life))))
                alpha (alpha-curve local-t)
                real-alpha (min max-alpha alpha)]
            (if (<= real-alpha 0.0)
              []
              (let [center (vec3/v+ pos (vec3/v* forward (+ (double offset) z-offset)))
                    ring-size (* (double size) ss)
                    side (vec3/v* right (* ring-size 0.5))
                    vertical (vec3/v* up (* ring-size 0.5))
                    p0 (vec3/v+ (vec3/v- center side) vertical)
                    p1 (vec3/v+ (vec3/v+ center side) vertical)
                    p2 (vec3/v- (vec3/v+ center side) vertical)
                    p3 (vec3/v- (vec3/v- center side) vertical)
                    alpha-i (int (max 0 (min 255 (* 255.0 real-alpha 0.7))))]
                [(ru/quad-op (modid/namespaced-path "textures/effects/glow_circle.png")
                             p0 p1 p2 p3
                             {:r 255 :g 255 :b 255 :a alpha-i})]))))
        rings))))

(defn- charge-ops
  [^V3 center charge-ticks punched?]
  (let [progress (min 1.0 (/ (double charge-ticks) 50.0))
        radius (+ 0.1 (* 0.16 progress))
        pulse (+ radius (* 0.025 (Math/sin (* 0.22 charge-ticks))))
        points 16
        cx (.-x center) cy (.-y center) cz (.-z center)
        alpha (if punched? 220 170)
        color {:r 225 :g 245 :b 255 :a alpha}
        core {:r 178 :g 220 :b 245 :a (int (* 0.7 alpha))}]
    (vec
      (mapcat
        (fn [idx]
          (let [a0 (/ (* 2.0 Math/PI idx) points)
                a1 (/ (* 2.0 Math/PI (inc idx)) points)
                p0 (vec3/v3 (+ cx (* pulse (Math/cos a0))) cy (+ cz (* pulse (Math/sin a0))))
                p1 (vec3/v3 (+ cx (* pulse (Math/cos a1))) cy (+ cz (* pulse (Math/sin a1))))]
            [(ru/line-op p0 p1 color)
             (ru/line-op center p0 core)]))
        (range points)))))

(defn- build-plan
  [_camera-pos hand-center-pos _tick]
  (let [state (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :directed-blastwave)
        current-waves (:waves state)
        ;; One real vfx-core instance now exists per active caster; the
        ;; charge ring still only draws for the LOCAL player's own instance,
        ;; because hand-center-pos is only ever the local viewer's own hand
        ;; position -- there is no remote-player hand endpoint to draw the
        ;; ring at. The waves above are NOT filtered by this: they are
        ;; world-positioned (upstream's own spawned WaveEffect), visible to
        ;; everyone the same as before this migration, same as
        ;; mag_movement's precedent for this owner-check.
        db (when (and (:active? state)
                      (or (nil? (:source-player-id state))
                          (nil? (:player-uuid hand-center-pos))
                          (= (str (:source-player-id state))
                             (str (:player-uuid hand-center-pos)))))
             state)
        charge-plan (if (and hand-center-pos db (:active? db))
                      (charge-ops (vec3/map->v3 (dissoc hand-center-pos :player-uuid))
                                  (long (or (:charge-ticks db) 0))
                                  (boolean (:punched? db)))
                      [])
        wave-plan (mapcat wave-ops current-waves)]
    (when (or (seq charge-plan) (seq wave-plan))
      {:ops (vec (concat charge-plan wave-plan))})))

(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:directed-blastwave :level] [_ _] {})
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:directed-blastwave :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:directed-blastwave :level] [_ _ store] (tick-state! store))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :directed-blastwave
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
;; No effect-clear-owner! override anymore -- no live caller, no
;; side-effecting resource here (see mark_teleport.clj's migration commit).
;; The wave is upstream's own spawned WaveEffect entity, unaffected by this
;; file's teardown either way.
