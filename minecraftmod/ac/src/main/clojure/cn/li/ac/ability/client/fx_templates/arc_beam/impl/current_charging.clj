(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.current-charging
  (:require [cn.li.ac.ability.client.arc-patterns :as arc-patterns]
            [cn.li.ac.ability.client.effects.rv3 :as rv3]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.util.log :as log]
            [clojure.string :as str]))

(defn- visual-max-ticks
  "Read the visual-max-ticks for current-charging from skill config.
   Falls back to 40 ticks (2 seconds at 20 tps)."
  []
  (max 1 (int (or (skill-config/tunable-int :current-charging :charge.visual-max-ticks) 40))))


(def ^:private default-state
  {:active? false
   :blending? false
   :is-item false
   :good? false
   :charge-ticks 0
   :charge-ratio 0.0
   :target nil
  :caster-pos nil
   :block-pos nil
   :charged 0.0
   :started-at-ms 0
  :ending-at-ms 0
  :updated-at-ms 0})

(def ^:private blend-out-ms 200)
(def ^:private active-stale-ms 500)









(defn- current-store []
  (let [store (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :current-charging)]
    (if (contains? store :states)
      store
      {:states {}})))

(defn- state-for-selector [store selector]
  (let [states (:states store)]
    (or (cond
          (vector? selector)
          (get states selector)

          (some? selector)
          (some (fn [[_ st]]
                  (when (and (:source-player-id st)
                             (= (str selector) (str (:source-player-id st))))
                    st))
                states)

          :else
          (or (some (fn [[_ st]]
                      (when (:active? st) st))
                    states)
              (some (fn [[_ st]]
                      (when (:blending? st) st))
                    states)))
        default-state)))



(defn- now-ms []
  ;; Use game-time so charge animations pause with the game.
  (client-bridge/game-time-ms))

(defn- normalize-ratio [charge-ticks]
  (let [ticks (max 0 (long (or charge-ticks 0)))
        ratio (/ (double ticks) (double (visual-max-ticks)))]
    (max 0.0 (min 1.0 ratio))))

(defn- resolve-owner-key [ctx-id _channel explicit-owner-key payload]
  (or explicit-owner-key
      (when-let [source-player-id (:source-player-id payload)]
        [:source-player source-player-id])
      [:ctx ctx-id]))

(def ^:private charge-loop-sound (modid/namespaced-path "em.charge_loop"))

(defn- base-meta [owner-key ctx-id channel payload]
  {:owner-key owner-key
   :ctx-id ctx-id
   :channel channel
   :source-player-id (:source-player-id payload)
   :world-id (:world-id payload)})

;; Runs synchronously inside the :current-charging fx-spec's dispatch
;; doseq (fx_spec.clj channel-handler), which processes :targets in
;; declared order ([:hand :level] here) with no per-target exception
;; isolation — an uncaught throw here would abort that doseq before the
;; :level track (the one build-plan reads for the beam/ring) ever gets its
;; state update for this tick. Client-effect side calls must never be
;; allowed to break the render-state pipeline, so every path is guarded.

(defn- start-loop-sound-at! [owner-key pos]
  (try
    (let [{:keys [x y z]} (or pos {:x 0.0 :y 0.0 :z 0.0})]
      (client-bridge/run-client-effect! :mcmod/start-loop-sound
        {:key (str owner-key)
         :sound-id charge-loop-sound
         :volume 0.8
         :pitch 1.0
         :x (double (or x 0.0))
         :y (double (or y 0.0))
         :z (double (or z 0.0))}))
    (catch Throwable e
      (log/warn "current-charging: start-loop-sound-at! failed" e))))

(defn- update-loop-sound-position! [owner-key pos]
  (when pos
    (try
      (let [{:keys [x y z]} pos]
        (client-bridge/run-client-effect! :mcmod/update-loop-sound-position
          {:key (str owner-key)
           :x (double (or x 0.0))
           :y (double (or y 0.0))
           :z (double (or z 0.0))}))
      (catch Throwable e
        (log/warn "current-charging: update-loop-sound-position! failed" e)))))

(defn- stop-loop-sound! [owner-key]
  (try
    (client-bridge/run-client-effect! :mcmod/stop-loop-sound {:key (str owner-key)})
    (catch Throwable e
      (log/warn "current-charging: stop-loop-sound! failed" e))))

(defn- enqueue-state! [store ctx-id channel owner-key payload]
  (let [store* (if (contains? (or store {}) :states)
                 (or store {:states {}})
                 {:states {}})
        {:keys [mode] :as payload*} (or payload {})
        owner-key* (resolve-owner-key ctx-id channel owner-key payload*)]
    (case mode
      :start
      (let [ts (now-ms)
            meta (base-meta owner-key* ctx-id channel payload*)]
        (start-loop-sound-at! owner-key* (:caster-pos payload*))
        (assoc-in store* [:states owner-key*]
                  (merge default-state
                         meta
                         {:active? true
                          :blending? false
                          :is-item (boolean (:is-item payload*))
                          :good? false
                          :charge-ticks 0
                          :charge-ratio 0.0
                          :target nil
                          :caster-pos (:caster-pos payload*)
                          :block-pos nil
                          :charged 0.0
                          :started-at-ms ts
                          :ending-at-ms 0
                          :updated-at-ms ts})))

      :update
      (do
        (update-loop-sound-position! owner-key* (:caster-pos payload*))
        (let [ts (now-ms)]
          (update-in store* [:states owner-key*]
                     (fn [state]
                       (-> (merge default-state state (base-meta owner-key* ctx-id channel payload*))
                           (merge {:active? true
                                   :blending? false
                                   :updated-at-ms ts})
                           (cond-> (contains? payload* :is-item)
                             (assoc :is-item (boolean (:is-item payload*))))
                           (cond-> (contains? payload* :good?)
                             (assoc :good? (boolean (:good? payload*))))
                           (cond-> (contains? payload* :charge-ticks)
                             (assoc :charge-ticks (max 0 (long (:charge-ticks payload*)))
                                    :charge-ratio (normalize-ratio (:charge-ticks payload*))))
                           (cond-> (contains? payload* :target)
                             (assoc :target (:target payload*)))
                           (cond-> (contains? payload* :caster-pos)
                             (assoc :caster-pos (:caster-pos payload*)))
                           (cond-> (contains? payload* :block-pos)
                             (assoc :block-pos (:block-pos payload*)))
                           (cond-> (contains? payload* :charged)
                             (assoc :charged (double (:charged payload*)))))))))

      :end
      (let [ts (now-ms)]
        ;; Sound is stopped by tick-state! once the blend window elapses,
        ;; not here — this lets the loop linger through the same short
        ;; fade the visual rings use instead of cutting off on key-up.
        (update-in store* [:states owner-key*]
             (fn [state]
               (-> (merge default-state state (base-meta owner-key* ctx-id channel payload*))
             (merge {:active? false
               :blending? true
               :is-item (boolean (:is-item payload*))
               :charge-ticks 0
               :charge-ratio 0.0
               :ending-at-ms ts
               :updated-at-ms ts})
             (assoc :good? false)))))

      store*)))

(defn- tick-state!
  [store]
  (let [store* (if (contains? (or store {}) :states)
                 (or store {:states {}})
                 {:states {}})
        now-ms (now-ms)
        states' (into {}
                      (keep (fn [[owner-key st]]
                              (cond
                                (and (:active? st)
                                     (< (- now-ms (long (or (:updated-at-ms st)
                                                             (:started-at-ms st)
                                                             0)))
                                        active-stale-ms))
                                [owner-key st]

                                (and (:blending? st)
                                     (< (- now-ms (long (or (:ending-at-ms st) 0))) blend-out-ms))
                                [owner-key st]

                                :else
                                (do
                                  (stop-loop-sound! owner-key)
                                  nil))))
                      (:states store*))]
    (assoc store* :states states')))

;; A perfectly straight billboard beam is geometrically invisible whenever
;; the camera looks straight down its own axis — exactly this skill's
;; scenario, since the beam always runs from the caster's own eye to their
;; own crosshair target, so camera direction ≈ beam direction every frame.
;; A flat quad's width only reads as a face when it's angled toward the
;; camera; end-on, its silhouette has zero screen-space width regardless of
;; world-space width, color, or texture. Original AcademyCraft's own
;; EntityArc/ArcPatterns never draws a smooth straight beam for exactly this
;; reason — it's always a jagged zigzag path (arc-patterns/generate-zigzag-
;; segments), whose per-segment sideways deviation guarantees some part of
;; the arc is never edge-on, from any viewing angle including straight down
;; the caster's own sightline. :charging is the original's own named preset
;; for this skill ("chargingArc").
(defn- zigzag-ops
  ([cam-v start end pattern seed]
   (zigzag-ops cam-v start end pattern seed nil))
  ([cam-v start end pattern seed origin-offset]
   (let [vertices (arc-patterns/generate-zigzag-segments start end
                    {:segments (:segments pattern)
                     :amplitude (:amplitude pattern)
                     :seed seed})
         life-ratio 0.5] ;; life-fade-alpha's flat full-brightness middle 60%
     (ru/zigzag-arc-ops cam-v vertices pattern
       {:life-ratio life-ratio
        :wiggle-phase (arc-patterns/wiggle-phase)
        :effective-wiggle (arc-patterns/effective-wiggle-amount pattern life-ratio)
        :origin-offset origin-offset}))))

;; Original's own chargingArc config (ArcPatterns.java): branchFactor=0.3,
;; passes=5, width=0.1, maxOffset=1.2 on a 20-block reference length — i.e.
;; amplitude as a FRACTION of length is 1.2/20 = 0.06, not this framework's
;; :charging preset default of 0.3 (a port-local guess that was never
;; actually checked against the original numbers, and is ~5x too large —
;; that's what made the bolt look scattered rather than a coherent zigzag).
;; segments 20 → generate-zigzag-segments' passes = ceil(log2(20)) = 5,
;; matching the original's passes exactly.
(def ^:private charging-beam-pattern
  (assoc (arc-patterns/get-pattern :charging) :amplitude 0.06 :segments 20 :fork-count 0))

(defn- own-state?
  [st hand-center-pos]
  (or (nil? (:source-player-id st))
      (nil? (:player-uuid hand-center-pos))
      (= (str (:source-player-id st)) (str (:player-uuid hand-center-pos)))))

;; Original's EntitySurroundArc is NOT a ring/circle at all (confirmed
;; against CubePointFactory + SubArc in the original source): CurrentCharging
;; spawns `count`=6 short independent arcs, each anchored at a uniformly
;; random point on the target's bounding-box surface (random face, random
;; position on that face) and pointing in a fully random 3D direction — small
;; sparks stuck to the surface, not strands tangent to a circle around it.
(def ^:private spark-count 6)
;; Original generates NORMAL-type templates at width=0.3, length 3-4 blocks,
;; then draws them scaled 0.3x (SubArcHandler.drawAll) — i.e. actual on-screen
;; width ≈0.09, length ≈0.9-1.2. An earlier version of this code used length
;; 0.35 directly (no scale step), reading as a stubby blob instead of a
;; recognizable short arc.
(def ^:private spark-length 1.0)

;; Original's surround-arc template config (ArcFactory via EntitySurroundArc):
;; maxOffset=0.8 over a [3,4]-block generation length ≈ 0.8/3.5 fraction,
;; passes=3. segments 6 → passes = ceil(log2(6)) = 3, matching.
(def ^:private spark-pattern
  (assoc (arc-patterns/get-pattern :thin-continuous)
         :width 0.09
         :amplitude 0.23
         :segments 6
         :fork-count 0
         :color-outer {:r 150 :g 232 :b 255}
         :color-inner {:r 235 :g 252 :b 255}
         :color-line {:r 190 :g 244 :b 255}))

;; Original's SubArc.life = 30 ticks (~1.5s, with slight per-tick-increment
;; jitter): the ANCHOR (surface point + overall direction) is fixed for the
;; whole life, never repositioned mid-life. Regeneration is a single batch
;; for the whole pool (SubArcHandler only regenerates once ALL 6 have died),
;; not staggered per-strand — approximated here as one shared window so all
;; 6 anchors move together, each still landing at its own independent random
;; point.
(def ^:private spark-life-ticks 30)

;; What actually reads as "jumping" in original is NOT the anchor moving —
;; it's SubArc swapping which of 10 pre-baked jagged templates it displays,
;; via an independent ~30%-per-tick coin flip PER STRAND (not a shared
;; fixed period — each spark decorrelated from the others), at the SAME
;; fixed anchor/direction. Reseeding just the zigzag displacement (not the
;; start/end points) reproduces that: the spark stays rooted to the same
;; surface point but its jagged shape visibly snaps to a different
;; silhouette every few ticks, on its own independent schedule.
(def ^:private spark-shape-swap-chance 0.3)

;; SubArc.tick()'s independent on/off Markov chain (~40% off-chance when
;; lit, ~30% on-chance when dark) settles to ≈43% visible, re-rolled every
;; tick — layered on top of the anchor/shape cycles above for extra crackle.
(def ^:private spark-visible-chance 0.43)

(defn- spark-visible?
  [idx ticks]
  (< (.nextDouble (java.util.Random. (+ (* 7919 idx) (long ticks))))
     spark-visible-chance))

(defn- spark-shape-tick
  "The most recent tick <= ticks where spark idx's independent per-tick
  swap-chance coin flip would have fired, found by scanning backward — this
  makes 'which shape is currently displayed' a pure function of (idx,
  ticks) with no persistent per-spark state to track, while still behaving
  like original's per-tick coin flip: naturally decorrelated across sparks
  (each idx has its own hash sequence) rather than synchronized on a fixed
  period. Average gap between hits is ~1/0.3 ≈ 3.3 ticks, so the backward
  scan is short in practice; bounded at ticks=0 regardless."
  [idx ticks]
  (loop [t (long ticks)]
    (if (or (<= t 0)
            (< (.nextDouble (java.util.Random. (+ (* 104729 idx) t)))
               spark-shape-swap-chance))
      t
      (recur (dec t)))))

(defn- surround-spark-ops
  "count small random-surface-point, random-direction sparks around
  [cx,y,cz] — see spark-count/spark-pattern above for why this replaced a
  circular ring."
  [cam-v cx cy cz ticks pattern]
  (vec
    (mapcat
      (fn [idx]
        (when (spark-visible? idx ticks)
          (let [life-window (quot ticks spark-life-ticks)
                anchor-seed (+ (* 1000 idx) life-window)
                rng (java.util.Random. anchor-seed)
                ;; Random point on a unit cube's surface centered at [cx cy cz]:
                ;; pick one of 6 faces, then a uniform point on that face.
                face (.nextInt rng 6)
                u (- (.nextDouble rng) 0.5)
                v (- (.nextDouble rng) 0.5)
                [ox oy oz] (case face
                             0 [0.5 u v]     1 [-0.5 u v]
                             2 [u 0.5 v]     3 [u -0.5 v]
                             4 [u v 0.5]     5 [u v -0.5])
                start (rv3/v3 (+ cx ox) (+ cy oy) (+ cz oz))
                ;; Uniform random direction on the sphere for the spark's reach.
                theta (* 2.0 Math/PI (.nextDouble rng))
                cos-phi (- (* 2.0 (.nextDouble rng)) 1.0)
                sin-phi (Math/sqrt (max 0.0 (- 1.0 (* cos-phi cos-phi))))
                end (rv3/v3 (+ cx ox (* spark-length sin-phi (Math/cos theta)))
                            (+ cy oy (* spark-length cos-phi))
                            (+ cz oz (* spark-length sin-phi (Math/sin theta))))
                ;; Independent per-spark coin-flip cycle: only the jagged
                ;; path reshapes, anchor/direction above stay put for the
                ;; full life-window.
                shape-seed (+ (* 1000 idx) (* 31 (spark-shape-tick idx ticks)))]
            (zigzag-ops cam-v start end pattern shape-seed))))
      (range spark-count))))

(defn- target-spark-ops
  "block-pos is the block's [x y z] minimum-corner integer coordinates
  (Minecraft convention) — CubePointFactory in original samples the surface
  of the BLOCK's own bounding box, centered at block-pos + 0.5 in each axis,
  not the raycast surface-hit point (`target`): treating a point already ON
  the surface as a cube's center put roughly half the sparks floating past
  the block into open air and the other half embedded inside its solid
  volume, reading as sparks buried in the block with only a sliver poking
  out."
  [cam-v block-pos ticks]
  (let [[bx by bz] block-pos]
    (surround-spark-ops cam-v (+ (double bx) 0.5) (+ (double by) 0.5) (+ (double bz) 0.5)
                         ticks spark-pattern)))

(defn- caster-spark-ops
  [cam-v caster-pos ticks]
  (surround-spark-ops cam-v (double (:x caster-pos)) (+ (double (:y caster-pos)) 0.9) (double (:z caster-pos))
                       ticks spark-pattern))

(defn- build-plan
  [camera-pos hand-center-pos tick]
  (let [store (:states (level-effects/effect-state-snapshot :current-charging))
        active-states (filter :active? (vals (or store {})))
        cam-v (rv3/map->v3 camera-pos)
        trace? (zero? (mod (long (or tick 0)) 20))
        beam-count* (atom 0)
        ring-count* (atom 0)
        ops (vec
              (mapcat
                (fn [st]
                  (let [own? (own-state? st hand-center-pos)
                        ;; Item-mode ring anchor: the local player's rendered
                        ;; hand offset when it's our own effect (frame-smooth),
                        ;; else the last synced caster-pos for bystanders.
                        hand-pos (or (and own? hand-center-pos)
                                     (:caster-pos st)
                                     hand-center-pos)
                        ;; Beam vertex math still keys off the pure eye
                        ;; position (matching the server's actual raycast
                        ;; origin) — using hand-center-pos directly here
                        ;; instead skews the whole path, not just its render
                        ;; origin. The *visual* caster-side offset original
                        ;; applies (LambdaLib2 ViewOptimize.fix, "the beam
                        ;; must start from the hand") is a separate, purely
                        ;; cosmetic rigid shift applied to the WHOLE arc
                        ;; below via :origin-offset — original moves both
                        ;; ends by the same amount rather than pinning the
                        ;; far end to the exact target, so this is faithful,
                        ;; not an approximation.
                        caster-pos (:caster-pos st)
                        target (:target st)
                        block-pos (:block-pos st)
                        ticks (long (or (:charge-ticks st) 0))
                        item? (boolean (:is-item st))
                        good? (boolean (:good? st))
                        beam-ops (when (and (not item?) (map? caster-pos) (map? target))
                                   (zigzag-ops cam-v
                                               (rv3/map->v3 caster-pos)
                                               (rv3/map->v3 target)
                                               charging-beam-pattern
                                               ticks
                                               (arc-beam/local-frame-offset
                                                 caster-pos target
                                                 (if own?
                                                   arc-beam/first-person-view-offset
                                                   arc-beam/third-person-view-offset))))
                        ;; Matches original: the surround sparks only ever
                        ;; appear around the TARGET block once it's a valid
                        ;; energy receiver (block mode) — never around the
                        ;; caster in block mode.
                        ring-ops (when (and (not item?) good?
                                            (sequential? block-pos) (= 3 (count block-pos)))
                                   (target-spark-ops cam-v block-pos ticks))
                        ;; Item mode has no beam/target at all — original
                        ;; wraps the surround sparks around the PLAYER
                        ;; instead (new EntitySurroundArc(player)).
                        item-ring-ops (when (and item? (map? hand-pos))
                                        (caster-spark-ops cam-v (dissoc hand-pos :player-uuid) ticks))]
                    (when trace?
                      (swap! beam-count* + (count beam-ops))
                      (swap! ring-count* + (count ring-ops) (count item-ring-ops)))
                    (concat beam-ops ring-ops item-ring-ops)))
                active-states))]
    (when trace?
      (log/info "[CC-TRACE][CLIENT][BUILD-PLAN]"
                {:active-count (count active-states)
                 :ops-count (count ops)
                 :beam-ops @beam-count*
                 :ring-ops @ring-count*
                 :first-op (first ops)}))
    (when (seq ops)
      {:ops ops})))

(defmethod arc-beam/effect-initial-state [:current-charging :level] [_ _] {:states {}})
(defmethod arc-beam/effect-enqueue-state! [:current-charging :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod arc-beam/effect-tick-state! [:current-charging :level] [_ _ store] (tick-state! store))
(defmethod arc-beam/effect-build-plan :current-charging
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(defmethod arc-beam/effect-clear-owner! :current-charging [_ store owner-key]
  (assoc store :states (dissoc (:states store) owner-key)))
