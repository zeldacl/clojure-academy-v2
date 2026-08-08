(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.tp-mark
  "Shared tp_mark marker machinery (upstream EntityTPMarking + MarkRender).

  Both MarkTeleport and PenetrateTeleport spawn an EntityTPMarking client-side:
  a SimpleModelBiped humanoid textured with the tp_mark 7-frame effect
  sequence (frame = ticksExisted / 2.5 % 7), which copies the player's
  rotation every tick (front faces the local player — an upright model whose
  boxes turn with the yaw). MarkRender tints it via glColor4d: white when
  available, red (1, 0.2, 0.2) when not. Every tick the mark emits a green
  TPParticleFactory particle 40% of the time (rand.nextDouble() < 0.4)."
  (:require [cn.li.ac.ability.client.effects.billboard-particles :as bp]
            [cn.li.ac.ability.client.effects.rv3 :as rv3]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.config.modid :as modid]))

(def ^:private mark-frame-count 7)

(defn- mark-frame-texture
  [frame]
  (modid/asset-path "textures/effects/tp_mark"
                    (str (mod (long frame) mark-frame-count) ".png")))

(defn- rand-range
  [a b]
  (+ a (rand (- b a))))

(def ^:private tp-particle-texture
  (modid/asset-path "textures/effects" "tp_particle.png"))

(def ^:private model-parts
  "SimpleModelBiped (ModelBiped 0.0) parts: each is a 3D box (half-width,
  half-height, half-depth, center offsets, and per-face skin UV regions in
  the 256x128 tp_mark canvas — the 64x32 atlas layout scaled x4). The boxes
  are oriented with their FRONT (+z) along the player's look direction
  (glRotated(-rotationYaw)), so rotating the view shows different faces —
  the humanoid's facing is observable, like the 3D model."
  [;; head 8x8x8 -> 0.5^3, y 1.375..1.875
   {:hw 0.25 :hh 0.25 :hd 0.25 :cx 0.0 :cy 1.625
    :front [0.125 0.25 0.25 0.5] :back [0.375 0.5 0.25 0.5]
    :right [0.0 0.125 0.25 0.5] :left [0.25 0.375 0.25 0.5]
    :top [0.125 0.25 0.0 0.25] :bottom [0.25 0.375 0.0 0.25]}
   ;; body 8x12x4 -> 0.5x0.75x0.25, y 0.625..1.375
   {:hw 0.25 :hh 0.375 :hd 0.125 :cx 0.0 :cy 1.0
    :front [0.3125 0.4375 0.5 0.875] :back [0.5 0.625 0.5 0.875]
    :right [0.25 0.3125 0.5 0.875] :left [0.4375 0.5 0.5 0.875]
    :top [0.3125 0.4375 0.5 0.625] :bottom [0.3125 0.4375 0.75 0.875]}
   ;; right arm 4x12x4 -> 0.25x0.75x0.25 at x +0.375, y 0.625..1.375
   {:hw 0.125 :hh 0.375 :hd 0.125 :cx 0.375 :cy 1.0
    :front [0.6875 0.75 0.5 0.875] :back [0.75 0.8125 0.5 0.875]
    :right [0.625 0.6875 0.5 0.875] :left [0.8125 0.875 0.5 0.875]
    :top [0.6875 0.75 0.5 0.625] :bottom [0.6875 0.75 0.75 0.875]}
   ;; left arm at x -0.375 (mirrored regions)
   {:hw 0.125 :hh 0.375 :hd 0.125 :cx -0.375 :cy 1.0
    :front [0.5625 0.625 0.5 0.875] :back [0.5 0.5625 0.5 0.875]
    :right [0.5 0.5625 0.5 0.875] :left [0.5625 0.625 0.5 0.875]
    :top [0.5625 0.625 0.5 0.625] :bottom [0.5625 0.625 0.75 0.875]}
   ;; right leg 4x12x4 -> 0.25x0.75x0.25 at x +0.125, y 0..0.75
   {:hw 0.125 :hh 0.375 :hd 0.125 :cx 0.125 :cy 0.375
    :front [0.0625 0.125 0.5 0.875] :back [0.0 0.0625 0.5 0.875]
    :right [0.0 0.0625 0.5 0.875] :left [0.0625 0.125 0.5 0.875]
    :top [0.0625 0.125 0.5 0.625] :bottom [0.0625 0.125 0.75 0.875]}
   ;; left leg at x -0.125 (mirrored regions)
   {:hw 0.125 :hh 0.375 :hd 0.125 :cx -0.125 :cy 0.375
    :front [0.1875 0.25 0.5 0.875] :back [0.125 0.1875 0.5 0.875]
    :right [0.125 0.1875 0.5 0.875] :left [0.1875 0.25 0.5 0.875]
    :top [0.1875 0.25 0.5 0.625] :bottom [0.1875 0.25 0.75 0.875]}])

(defn- face-quads
  "Emit the six faces of one box: face-axis (front/back/right/left/top/bottom)
  oriented in the box's local frame, each a quad spanning the two tangent
  axes with the face's skin UV region."
  [texture center {:keys [hw hh hd]} f r u
   {:keys [front back right left top bottom]} color]
  (let [qf (fn [face-axis a1 h1 a2 h2 uv]
             (let [c (rv3/v+ center (rv3/v* face-axis (double hd)))
                   s1 (rv3/v* a1 (double h1))
                   s2 (rv3/v* a2 (double h2))]
               (ru/quad-op texture
                           (rv3/v+ (rv3/v- c s1) s2)
                           (rv3/v- (rv3/v- c s1) s2)
                           (rv3/v- (rv3/v+ c s1) s2)
                           (rv3/v+ (rv3/v+ c s1) s2)
                           (nth uv 0) (nth uv 1) (nth uv 2) (nth uv 3)
                           color)))]
    [(qf f r hw u hh front)
     (qf (rv3/v* f -1.0) r hw u hh back)
     (qf r f hd u hh right)
     (qf (rv3/v* r -1.0) f hd u hh left)
     (qf u r hw f hd top)
     (qf (rv3/v* u -1.0) r hw f hd bottom)]))

(defn humanoid-ops
  "Upstream MarkRender: SimpleModelBiped (feet at target, ~0.6 wide x 1.8
  tall) textured with the tp_mark frame sequence, frame =
  (int)((ticksExisted / 2.5) % 7). The mark copies the player's rotation
  every tick, so the figure's FRONT faces along the player's look direction
  (glRotated(-rotationYaw)) — the boxes' faces turn with the yaw, making the
  facing observable (the head-top face carries the drawn face). `color` is
  the tint (white when available; upstream MarkRender paints glColor4d(1,
  0.2, 0.2, 1) when not)."
  [^cn.li.mcmod.math.V3 cam-pos target ticks color]
  (let [feet-x (double (:x target))
        feet-y (double (:y target))
        feet-z (double (:z target))
        frame (mod (long (Math/floor (/ (double ticks) 2.5))) mark-frame-count)
        texture (mark-frame-texture frame)
        ;; The marker sits on the look ray — the horizontal camera->marker
        ;; direction IS the player's look (the model's +z after the yaw
        ;; rotation).
        fx (- feet-x (.x cam-pos))
        fz (- feet-z (.z cam-pos))
        flen (Math/sqrt (+ (* fx fx) (* fz fz)))
        f (if (> flen 1.0e-5)
            (rv3/v3 (/ fx flen) 0.0 (/ fz flen))
            rv3/unit-z)
        r (rv3/v3 (.z f) 0.0 (- (.x f)))
        u rv3/unit-y]
    (mapcat (fn [part]
              ;; Upstream MarkRender disables GL_DEPTH_TEST + GL_CULL_FACE:
              ;; the humanoid stays visible through walls even when the
              ;; destination is still inside one (penetrate's unavailable
              ;; case) — the renderer emits these with a no-depth, no-cull
              ;; translucent render type.
              (mapv #(assoc % :no-depth-test? true)
                    (face-quads texture
                                (rv3/v3 (+ feet-x (:cx part))
                                        (+ feet-y (:cy part))
                                        feet-z)
                                part f r u part color)))
            model-parts)))

(defn ambient-particle
  "Upstream EntityTPMarking.onUpdate TPParticleFactory particle: position
  offsets (±1, rand(0.2,1.6)-1.6, ±1) around the mark, velocity
  (±0.03, 0..0.05, ±0.03); size 0.1-0.2, alpha 153-204, fadeAfter(20, 20)
  with the template fade-in 5."
  [target]
  {:x (+ (double (:x target)) (rand-range -1.0 1.0))
   :y (+ (double (:y target)) (- (rand-range 0.2 1.6) 1.6))
   :z (+ (double (:z target)) (rand-range -1.0 1.0))
   :vx (rand-range -0.03 0.03)
   :vy (rand-range 0.0 0.05)
   :vz (rand-range -0.03 0.03)
   :size (rand-range 0.1 0.2)
   :texture tp-particle-texture
   :start-alpha (long (rand-range 153 204))
   :age 0 :life 20 :fade-in 5 :fade-out 20})

(defn tick-marker!
  "Advance the marker state: ticks + one TPParticleFactory spawn per tick at
  rand < 0.4 (upstream EntityTPMarking.onUpdate). `spawn-ambient?` is a
  predicate on the state deciding whether particles may spawn — upstream
  gates on `available` (penetrate) and both skills share the 0.4 roll."
  ([st] (tick-marker! st (fn [st] (map? (:target st)))))
  ([st spawn-ambient?]
   (let [target (:target st)]
     (assoc st
            :ticks (inc (long (or (:ticks st) 0)))
            :ambient-particles
            (cond-> (bp/tick-particles! (:ambient-particles st))
              (and (spawn-ambient? st) (< (rand) 0.4))
              (conj (ambient-particle target)))))))
