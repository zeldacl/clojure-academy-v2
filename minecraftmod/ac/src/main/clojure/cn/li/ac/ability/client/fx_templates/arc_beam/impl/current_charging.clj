(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.current-charging
  (:require [cn.li.ac.ability.client.arc-patterns :as arc-patterns]
            [cn.li.ac.ability.client.effects.academy-arc :as academy-arc]
            [cn.li.ac.ability.client.effects.rv3 :as rv3]
            [cn.li.ac.ability.client.fx-templates.arc-beam :as arc-beam]
            [cn.li.ac.client.effect-controller :as vfx-level]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.config.modid :as modid]
            [cn.li.mcmod.client.platform-bridge :as client-bridge]
            [cn.li.mcmod.util.log :as log]))

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
   :block-bounds nil
   :charged 0.0
   :visual-ticks 0
   :beam-visible? true
   :beam-shape-id 0
   :surround-state nil
   :started-at-ms 0
  :ending-at-ms 0
  :updated-at-ms 0})

(defn- now-ms []
  ;; Use game-time so charge animations pause with the game.
  (client-bridge/game-time-ms))

(defn- normalize-ratio [charge-ticks]
  (let [ticks (max 0 (long (or charge-ticks 0)))
        ratio (/ (double ticks) (double (visual-max-ticks)))]
    (max 0.0 (min 1.0 ratio))))

(def ^:private charge-loop-sound (modid/namespaced-path "em.charge_loop"))

(defn- base-meta [ctx-id channel payload]
  {:ctx-id ctx-id
   :channel channel
   :source-player-id (:source-player-id payload)
   :world-id (:world-id payload)})

;; vfx-core :transient migration (docs/04-systems/COMBAT_VFX_PLATFORM_GAPS.md
;; E section): one real vfx-core instance per (owner, activation) now, so
;; state is this cast's own map directly -- no more :states owner-map
;; wrapping. `owner-key` below is no longer a synthetic key this file
;; invents (resolve-owner-key, since deleted, used to build one out of
;; :source-player-id/ctx-id for exactly this purpose) -- it is now the real
;; owner descriptor.clj's :update passes through from vfx-core's own
;; instance.
;;
;; The try/catch wrapping below no longer guards what its comment used to
;; say: that reasoning was about fx_spec.clj's old :channels doseq handler
;; running multiple :targets in one un-isolated loop, and that handler was
;; deleted as dead code in E section P1.3 -- nothing here runs inside it
;; any more. The guards stay for a different, and for :transient effects
;; arguably bigger, reason: an uncaught throw from enqueue-state! propagates
;; up through descriptor's :update and into vfx-core's tick-instance, which
;; catches it, but tick!'s handling of a caught throw (:fault) is the SAME
;; index cleanup as a normal ::removed instance (see runtime.clj's tick!) --
;; an uncaught exception here would silently destroy this entire vfx-core
;; instance, not just skip one tick's worth of one track's update the way
;; the old shared-doseq framing implied. Client-effect side calls must
;; never be allowed to take the instance down with them, so every path
;; stays guarded.
;;
;; This case dispatch is preserved EXACTLY as it was before this migration,
;; including the fact that none of its branches match a real event today.
;; combat_content.clj's :current-charging skill sends exactly ONE :vfx
;; step, repeated every :period-ticks 5 for as long as the session holds:
;; :event :pulse, with :params {:strength 0.6} -- not :start/:update/:end,
;; and none of the :is-item/:target/:caster-pos/:charge-ticks/:block-pos
;; fields these branches read. In production today the charge loop sound
;; never starts, :active? never becomes true, and none of the beam/ring
;; visuals below ever render. Migrated structurally only.
(defn- enqueue-state! [state ctx-id channel owner-key payload]
  (let [state* (or state {})
        {:keys [mode] :as payload*} (or payload {})]
    (case mode
      :start
      (let [ts (now-ms)
            meta (base-meta ctx-id channel payload*)
            item? (boolean (:is-item payload*))
            arc-type (if item? :thin :normal)]
        (client-bridge/run-client-effect! :mcmod/start-loop-sound
          (try
            (let [{:keys [x y z]} (or (:caster-pos payload*) {:x 0.0 :y 0.0 :z 0.0})]
              {:key (str owner-key)
               :sound-id charge-loop-sound
               :volume 0.3
               :pitch 1.0
               :x (double (or x 0.0))
               :y (double (or y 0.0))
               :z (double (or z 0.0))})
            (catch Throwable e
              (log/warn "current-charging: start-loop-sound failed" e)
              nil)))
        (merge default-state
               meta
               {:owner-key owner-key
                :active? true
                :blending? false
                :is-item item?
                :good? false
                :charge-ticks 0
                :charge-ratio 0.0
                :target (:target payload*)
                :caster-pos (:caster-pos payload*)
                :block-pos nil
                :block-bounds nil
                :charged 0.0
                :visual-ticks 0
                ;; EntityArc starts with show=true and template 0.
                :beam-visible? true
                :beam-shape-id 0
                :surround-state
                (academy-arc/initial-surround-state arc-type ts)
                :started-at-ms ts
                :ending-at-ms 0
                :updated-at-ms ts}))

      :update
      (do
        (when-let [pos (:caster-pos payload*)]
          (try
            (let [{:keys [x y z]} pos]
              (client-bridge/run-client-effect! :mcmod/update-loop-sound-position
                {:key (str owner-key)
                 :x (double (or x 0.0))
                 :y (double (or y 0.0))
                 :z (double (or z 0.0))}))
            (catch Throwable e
              (log/warn "current-charging: update-loop-sound-position failed" e))))
        (let [ts (now-ms)]
          (-> (merge default-state state* (base-meta ctx-id channel payload*))
              (merge {:owner-key owner-key
                      :active? true
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
              (cond-> (contains? payload* :block-bounds)
                (assoc :block-bounds (:block-bounds payload*)))
              (cond-> (contains? payload* :charged)
                (assoc :charged (double (:charged payload*)))))))

      :end
      (do
        (try
          (client-bridge/run-client-effect! :mcmod/stop-loop-sound {:key (str owner-key)})
          (catch Throwable e
            (log/warn "current-charging: stop-loop-sound failed" e)))
        {})

      state*)))

(defn- visual-roll
  [salt visual-tick stream]
  (.nextDouble
   (java.util.Random.
    (long (hash [salt visual-tick stream])))))

(defn- visual-template-id
  [salt visual-tick]
  (.nextInt
   (java.util.Random.
    (long (hash [salt visual-tick :beam-template-pick])))
   20))

(defn- advance-visual-state
  [state]
  (if-not (:active? state)
    state
    (let [visual-tick (inc (long (or (:visual-ticks state) 0)))
          salt (long (or (:started-at-ms state) 0))
          visible? (boolean (:beam-visible? state))
          ;; EntityArc.onUpdate:
          ;;   visible -> hidden with showWiggle=.2
          ;;   hidden  -> visible with hideWiggle=.8
          beam-visible?
          (if visible?
            (not (< (visual-roll salt visual-tick :beam-hide) 0.2))
            (< (visual-roll salt visual-tick :beam-show) 0.8))
          beam-shape-id
          (if (< (visual-roll salt visual-tick :beam-template-swap) 0.8)
            (visual-template-id salt visual-tick)
            (int (or (:beam-shape-id state) 0)))
          arc-type (if (:is-item state) :thin :normal)]
      (assoc state
             :visual-ticks visual-tick
             :beam-visible? beam-visible?
             :beam-shape-id beam-shape-id
             :surround-state
             (academy-arc/tick-surround-state
              (:surround-state state)
              arc-type
              salt
              visual-tick)))))

(defn- tick-state!
  "Preserved exactly as it was before this migration: never signals a
   natural end (the original never dropped an owner via tick alone, only
   via :end's dissoc -- and :end never actually fires, see enqueue-state!
   above). Animation still advances locally every client tick regardless;
   it must not freeze on the last received server charge-ticks value."
  [store]
  (advance-visual-state (or store {})))

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
  (assoc (arc-patterns/get-pattern :charging)
         :amplitude 0.06
         :segments 20
         :width 0.1
         ;; ArcFactory branches are short descendants of a local segment,
         ;; not long secondary bolts spanning most of the main beam.
         :fork-count 2
         :fork-length 0.08
         :fork-angle 0.17))

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

;; Item mode uses EntitySurroundArc.THIN upstream: four 1.5-2.0 block
;; templates rendered at scale 0.3 (0.45-0.6 actual length), width 0.06.
(def ^:private item-spark-count 4)
(def ^:private item-spark-pattern
  (assoc spark-pattern
         :width 0.06
         :amplitude 0.46))

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
  circular ring. `salt` distinguishes one cast from another (e.g. the
  caster's exact position at cast time) — ticks alone resets to 0 on every
  new cast, so without it every cast under spark-life-ticks long would
  reroll from the exact same seed and land on the exact same 6 spots every
  time, reading as fixed placement rather than random."
  [cam-v cx cy cz ticks pattern salt]
  (vec
    (mapcat
      (fn [idx]
        (when (spark-visible? idx ticks)
          (let [life-window (quot ticks spark-life-ticks)
                anchor-seed (+ (* 1000 idx) life-window (* 31 (long salt)))
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
                [nx ny nz] (case face
                             0 [1.0 0.0 0.0]  1 [-1.0 0.0 0.0]
                             2 [0.0 1.0 0.0]  3 [0.0 -1.0 0.0]
                             4 [0.0 0.0 1.0]  5 [0.0 0.0 -1.0])
                start (rv3/v3 (+ cx ox) (+ cy oy) (+ cz oz))
                ;; Uniform random direction on the sphere, then flipped into
                ;; the outward hemisphere (relative to this face's normal) if
                ;; it happened to point inward — original's SubArc rotation is
                ;; unconstrained, but a spark reaching back into the block's
                ;; own opaque geometry gets depth-tested away, reading as
                ;; "buried, just a sliver poking out." A raw reflection is a
                ;; slightly denser hemisphere near the pole, not a perfect
                ;; cosine-weighted resample, but for a short decorative spark
                ;; that's an invisible difference and guarantees visibility.
                theta (* 2.0 Math/PI (.nextDouble rng))
                cos-phi (- (* 2.0 (.nextDouble rng)) 1.0)
                sin-phi (Math/sqrt (max 0.0 (- 1.0 (* cos-phi cos-phi))))
                raw-dx (* sin-phi (Math/cos theta))
                raw-dy cos-phi
                raw-dz (* sin-phi (Math/sin theta))
                inward? (neg? (+ (* raw-dx nx) (* raw-dy ny) (* raw-dz nz)))
                dx (if inward? (- raw-dx) raw-dx)
                dy (if inward? (- raw-dy) raw-dy)
                dz (if inward? (- raw-dz) raw-dz)
                end (rv3/v3 (+ cx ox (* spark-length dx))
                            (+ cy oy (* spark-length dy))
                            (+ cz oz (* spark-length dz)))
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
  [cam-v block-pos ticks salt]
  (let [[bx by bz] block-pos]
    (surround-spark-ops cam-v (+ (double bx) 0.5) (+ (double by) 0.5) (+ (double bz) 0.5)
                         ticks spark-pattern salt)))

(defn- caster-spark-ops
  [cam-v caster-pos ticks salt]
  (surround-spark-ops cam-v (double (:x caster-pos)) (+ (double (:y caster-pos)) 0.9) (double (:z caster-pos))
                       ticks spark-pattern salt))

(defn- rotate-y
  [[x y z] yaw-rad]
  (let [c (Math/cos yaw-rad)
        s (Math/sin yaw-rad)]
    [(+ (* x c) (* z s))
     y
     (+ (* (- x) s) (* z c))]))

(defn- upstream-surround-spark-ops
  "Port EntitySurroundArc/SubArc geometry.

   The generated arc is centered on its cube-surface anchor (SubArcHandler
   translates by -template.length/2), points in an unconstrained random 3D
   direction, and uses mode-specific cube dimensions/template sizes."
  [cam-v {:keys [x y z width height depth yaw-rad]}
   count* length-lo length-hi ticks pattern salt]
  (vec
   (mapcat
    (fn [idx]
      (when (spark-visible? idx ticks)
        (let [life-window (quot ticks spark-life-ticks)
              anchor-seed (+ (* 1000 idx) life-window (* 31 (long salt)))
              rng (java.util.Random. anchor-seed)
              hw (* 0.5 (double width))
              hh (* 0.5 (double height))
              hd (* 0.5 (double depth))
              face (.nextInt rng 6)
              u (- (* 2.0 (.nextDouble rng)) 1.0)
              v (- (* 2.0 (.nextDouble rng)) 1.0)
              local-anchor (case face
                             0 [hw (* u hh) (* v hd)]
                             1 [(- hw) (* u hh) (* v hd)]
                             2 [(* u hw) hh (* v hd)]
                             3 [(* u hw) (- hh) (* v hd)]
                             4 [(* u hw) (* v hh) hd]
                             5 [(* u hw) (* v hh) (- hd)])
              [ax ay az] (rotate-y local-anchor (double (or yaw-rad 0.0)))
              theta (* 2.0 Math/PI (.nextDouble rng))
              cos-phi (- (* 2.0 (.nextDouble rng)) 1.0)
              sin-phi (Math/sqrt (max 0.0 (- 1.0 (* cos-phi cos-phi))))
              direction (rotate-y [(* sin-phi (Math/cos theta))
                                   cos-phi
                                   (* sin-phi (Math/sin theta))]
                                  (double (or yaw-rad 0.0)))
              [dx dy dz] direction
              shape-tick (spark-shape-tick idx ticks)
              length-rng (java.util.Random. (+ (* 65537 idx) (* 31 shape-tick)))
              arc-length (+ (double length-lo)
                            (* (.nextDouble length-rng)
                               (- (double length-hi) (double length-lo))))
              half-length (* 0.5 arc-length)
              anchor-x (+ (double x) ax)
              anchor-y (+ (double y) ay)
              anchor-z (+ (double z) az)
              start (rv3/v3 (- anchor-x (* half-length dx))
                            (- anchor-y (* half-length dy))
                            (- anchor-z (* half-length dz)))
              end (rv3/v3 (+ anchor-x (* half-length dx))
                          (+ anchor-y (* half-length dy))
                          (+ anchor-z (* half-length dz)))
              shape-seed (+ (* 1000 idx) (* 31 shape-tick))]
          (zigzag-ops cam-v start end pattern shape-seed))))
    (range count*))))

(defn- upstream-target-spark-ops
  [cam-v block-pos ticks salt]
  (let [[bx by bz] block-pos]
    (upstream-surround-spark-ops
     cam-v
     {:x (+ (double bx) 0.5)
      :y (+ (double by) 0.5)
      :z (+ (double bz) 0.5)
      :width 1.0 :height 1.0 :depth 1.0}
     spark-count 0.9 1.2 ticks spark-pattern salt)))

(defn- item-body
  [own? view-pos caster-pos]
  (let [width (double (or (:player-width view-pos) 0.6))
        height (double (or (:player-height view-pos) 1.8))
        feet-x (if (and own? (number? (:player-x view-pos)))
                 (double (:player-x view-pos))
                 (double (:x caster-pos)))
        feet-y (if (and own? (number? (:player-y view-pos)))
                 (double (:player-y view-pos))
                 (- (double (:y caster-pos)) 1.62))
        feet-z (if (and own? (number? (:player-z view-pos)))
                 (double (:player-z view-pos))
                 (double (:z caster-pos)))]
    {:x feet-x
     ;; CubePointFactory is centered only on X/Z. EntitySurroundArc's entity
     ;; position is the player's feet, so Y spans [feet-y, feet-y+height].
     :y feet-y
     :z feet-z
     :width (* 1.3 width)
     :height (* 1.3 height)
     :depth (* 1.3 width)
     :yaw-rad (double (or (:player-yaw-rad view-pos) 0.0))}))

(defn- structure-bounds?
  [bounds]
  (and (sequential? bounds) (= 6 (count bounds)) (every? number? bounds)))

(defn- target-body
  "The cube the sparks cling to. CubePointFactory is centered on X/Z only, and
  EntitySurroundArc sits at updatePos(blockX + .5, blockY, blockZ + .5), so the
  body origin is bottom-center.

  Upstream hardcodes 1x1x1 because it can only ever target a multiblock's
  origin cell; this port charges through the controller from any cell, so it
  wraps the machine's full extent when the server reported one instead of
  hopping between cells with the crosshair. Sizing only changes where the
  anchors land — the spark count and template scale stay at upstream's values,
  so a big machine reads as sparser rather than as scaled-up arcs."
  [[bx by bz] bounds]
  (if (structure-bounds? bounds)
    (let [[min-x min-y min-z max-x max-y max-z] (map double bounds)
          width (inc (- max-x min-x))
          height (inc (- max-y min-y))
          depth (inc (- max-z min-z))]
      {:x (+ min-x (* 0.5 width))
       :y min-y
       :z (+ min-z (* 0.5 depth))
       :width width
       :height height
       :depth depth
       :yaw-rad 0.0})
    {:x (+ (double bx) 0.5)
     :y (double by)
     :z (+ (double bz) 0.5)
     :width 1.0
     :height 1.0
     :depth 1.0
     :yaw-rad 0.0}))

(defn- build-plan
  [camera-pos hand-center-pos _tick]
  (let [state (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :current-charging)
        cam-v (rv3/map->v3 camera-pos)]
    (when (:active? state)
      (let [own? (own-state? state hand-center-pos)
            ;; Beam vertex math still keys off the pure eye position
            ;; (matching the server's actual raycast origin) — using
            ;; hand-center-pos directly here instead skews the whole path,
            ;; not just its render origin. The *visual* caster-side offset
            ;; original applies (LambdaLib2 ViewOptimize.fix, "the beam
            ;; must start from the hand") is a separate, purely cosmetic
            ;; rigid shift applied to the WHOLE arc below via
            ;; :origin-offset — original moves both ends by the same
            ;; amount rather than pinning the far end to the exact target,
            ;; so this is faithful, not an approximation.
            caster-pos (:caster-pos state)
            target (:target state)
            block-pos (:block-pos state)
            block-bounds (:block-bounds state)
            item? (boolean (:is-item state))
            good? (boolean (:good? state))
            cast-salt (long (or (:started-at-ms state) 0))
            surround-state (:surround-state state)
            beam-ops (when (and (not item?)
                                (map? caster-pos)
                                (map? target)
                                (:beam-visible? state))
                       (academy-arc/entity-arc-ops
                        cam-v
                        (rv3/map->v3 caster-pos)
                        (rv3/map->v3 target)
                        (int (or (:beam-shape-id state) 0))
                        (arc-beam/local-frame-offset
                         caster-pos target
                         (if own?
                           arc-beam/first-person-view-offset
                           arc-beam/third-person-view-offset))))
            ;; Matches original: the surround sparks only ever appear
            ;; around the TARGET block once it's a valid energy receiver
            ;; (block mode) — never around the caster in block mode.
            ring-ops (when (and (not item?) good?
                                (sequential? block-pos)
                                (= 3 (count block-pos))
                                (map? surround-state))
                       (academy-arc/surround-arc-ops
                        cam-v
                        (target-body block-pos block-bounds)
                        :normal
                        surround-state
                        cast-salt))
            ;; Item mode has no beam/target at all — original wraps the
            ;; surround sparks around the PLAYER instead
            ;; (new EntitySurroundArc(player)).
            item-ring-ops
            (when (and item?
                       (map? caster-pos)
                       (map? surround-state))
              (academy-arc/surround-arc-ops
               cam-v
               (item-body own? hand-center-pos caster-pos)
               :thin
               surround-state
               cast-salt))
            ops (concat beam-ops ring-ops item-ring-ops)]
        (when (seq ops)
          {:ops (vec ops)})))))

(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! arc-beam/effect-initial-state [:current-charging :level] [_ _] {})
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! arc-beam/effect-enqueue-state! [:current-charging :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! arc-beam/effect-tick-state! [:current-charging :level] [_ _ store] (tick-state! store))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! arc-beam/effect-build-plan :current-charging
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(cn.li.ac.ability.client.fx-templates.arc-beam/register-method! arc-beam/effect-destroy! :current-charging
  [_ state]
  (when (:active? state)
    (try
      (client-bridge/run-client-effect! :mcmod/stop-loop-sound {:key (str (:owner-key state))})
      (catch Throwable e
        (log/warn "current-charging: stop-loop-sound failed" e)))))
;; No effect-clear-owner! override anymore -- superseded by :destroy-fn
;; above (build-spec wires it unconditionally via dispatch-destroy!), which
;; vfx-core's real destroy!/clear-owner! now reach correctly per instance.
