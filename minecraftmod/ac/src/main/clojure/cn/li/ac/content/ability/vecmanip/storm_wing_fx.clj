(ns cn.li.ac.content.ability.vecmanip.storm-wing-fx
  "Client FX for Storm Wing: 4 tornado columns behind the caster (port of the
  original StormWingEffect + TornadoEffect/TornadoRenderer), dirt dust
  particles, and an entity-following loop sound (original FollowEntitySound).

  The tornado ring stacks are randomized once at :start (TornadoEffect's
  constructor); per tick the noise-driven ring displacement / radius /
  texture scroll are sampled (TornadoRenderer's calcdx/r/rot at GameTimer*4);
  per frame build-plan applies the player-yaw/pitch + back-tilt transform
  (StormWingEffectRender) and emits the textured ring quads.

  Note the original's `rot` is a texture-U scroll (`u0 = uStep*idx - rot`),
  NOT a geometric spin: the ring's vertex ring is fixed and the texture
  slides around it. Each quad covers exactly 1/div of the texture, so the
  20 segments wrap tornado_ring.png once around the column."
  (:require [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.rv3 :as vec3]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.ability.client.fx-spec :as fx-spec]
            [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.mcmod.client.platform-bridge :as client-bridge])
  (:import [cn.li.mcmod.math V3]))

(def ^:private storm-wing-effect-id :storm-wing)
(def ^:private loop-sound (modid/namespaced-path "vecmanip.storm_wing"))
(defn- loop-sound-key [ctx-id] (str "storm-wing/" ctx-id))

;; ---------------------------------------------------------------------------
;; Tornado constants (original TornadoEffect(2, 0.16, dscale=2.0))
;; ---------------------------------------------------------------------------

(def ^:private tornado-ht 2.0)
(def ^:private tornado-divide 40) ;; original TornadoEffect divide
(def ^:private tornado-sz 0.16)
(def ^:private tornado-dscale 2.0)
(def ^:private ring-segments 20) ;; original TornadoRenderer div
(def ^:private u-step (/ 1.0 (double ring-segments)))
(def ^:private terminate-ticks 15) ;; original StormWingEffect TERMINATE_TICK

;; Original TornadoRenderer.circleData: (sin, cos) per segment, indexed
;; mod div so the last quad closes the ring back onto segment 0.
(def ^:private ring-circle
  (mapv (fn [i]
          (let [rad (* 2.0 Math/PI (/ (double i) (double ring-segments)))]
            [(Math/sin rad) (Math/cos rad)]))
        (range ring-segments)))

;; Original StormWingEffect tornadoList: setTransform +
;; setRotation(0, sepY, sepZ) — the Y and Z angles are independent per column.
(def ^:private tornado-transforms
  [{:ox -0.1 :oy -0.3 :oz 0.1 :sep-y 45 :sep-z 45}
   {:ox 0.1 :oy -0.3 :oz 0.1 :sep-y -45 :sep-z -45}
   {:ox -0.1 :oy -0.5 :oz -0.1 :sep-y -45 :sep-z 45}
   {:ox 0.1 :oy -0.5 :oz -0.1 :sep-y 45 :sep-z -45}])

(def ^:private tornado-ring-texture
  (modid/asset-path "textures" "effects/tornado_ring.png"))

;; Original StormWingEffectRender: glRotated(-70, 1, 0, 0) — the columns
;; lean back toward the player's back. Stored as a positive magnitude and
;; SUBTRACTED in phi so the effective rotation is exactly -70deg.
(def ^:private tornado-back-tilt-degrees 70.0)

;; ---------------------------------------------------------------------------
;; ImprovedNoise (Ken Perlin 2002) - deterministic, no deps
;; ---------------------------------------------------------------------------

(def ^:private ^"[I" perm-padded
  (let [^"[I" a (int-array 512)
        perm [151 160 137 91 90 15 131 13 201 95 96 53 194 233 7 225
              140 36 103 30 69 142 8 99 37 240 21 10 23 190 6 148 247
              120 234 75 0 26 197 62 94 252 219 203 117 35 11 32 57 177
              33 88 237 149 56 87 174 20 125 136 171 168 68 175 74 165
              71 134 139 48 27 166 77 146 158 231 83 111 229 122 60 211
              133 230 220 105 92 41 55 46 245 40 244 102 143 54 65 25 63
              161 1 216 80 73 209 76 132 187 208 89 18 169 200 196 135
              130 116 188 159 86 164 100 109 198 173 186 3 64 52 217 226
              250 124 123 5 202 38 147 118 126 255 82 85 212 207 206 59
              227 47 16 58 17 182 189 28 42 223 183 170 213 119 248 152 2
              44 154 163 70 221 153 101 155 167 43 172 9 129 22 39 253 19
              98 108 110 79 113 224 232 178 185 112 104 218 246 97 228
              251 34 242 193 238 210 144 12 191 179 162 241 81 51 145 235
              249 14 239 107 49 192 214 31 181 199 106 157 184 84 204 176
              115 121 50 45 127 4 150 254 138 236 205 93 222 114 67 29 24
              72 243 141 128 195 78 66 215 61 156 180]]
    (dotimes [i 512]
      (aset a i (int (nth perm (bit-and i 255)))))
    a))

(defn- fade
  ^double [^double t]
  ;; 6t^5 - 15t^4 + 10t^3 == t*t*t*(t*(t*6 - 15) + 10). The +10 is an ADDEND,
  ;; not a factor: folding it into the product made fade(1) = -90 instead of 1,
  ;; so every lerp weight extrapolated wildly and the "noise" came out in the
  ;; hundreds — which is what turned the tornadoes into flailing giant rings.
  (* t t t (+ (* t (- (* t 6.0) 15.0)) 10.0)))

(defn- noise-grad
  ^double [^long hash ^double x ^double y ^double z]
  (let [h (bit-and hash 15)
        u (if (< h 8) x y)
        v (cond (< h 4) y
                (or (= h 12) (= h 14)) x
                :else z)]
    (+ (if (zero? (bit-and h 1)) u (- u))
       (if (zero? (bit-and h 2)) v (- v)))))

(defn- perlin-noise
  "Classic Perlin improved noise; z fixed to an integer lattice plane (0/1)."
  ^double [^double x ^double y ^double z]
  (let [X (bit-and (long (Math/floor x)) 255)
        Y (bit-and (long (Math/floor y)) 255)
        Z (bit-and (long (Math/floor z)) 255)
        xf (- x (Math/floor x))
        yf (- y (Math/floor y))
        zf (- z (Math/floor z))
        u (fade xf) v (fade yf) w (fade zf)
        ^"[I" p perm-padded
        A (+ (aget p X) Y)
        AA (+ (aget p A) Z) AB (+ (aget p (inc A)) Z)
        B (+ (aget p (inc X)) Y)
        BA (+ (aget p B) Z) BB (+ (aget p (inc B)) Z)
        lerp (fn [t a b] (+ a (* t (- b a))))]
    (lerp w
          (lerp v
                (lerp u (noise-grad (aget p AA) xf yf zf)
                      (noise-grad (aget p BA) (- xf 1.0) yf zf))
                (lerp u (noise-grad (aget p AB) xf (- yf 1.0) zf)
                      (noise-grad (aget p BB) (- xf 1.0) (- yf 1.0) zf)))
          (lerp v
                (lerp u (noise-grad (aget p (inc AA)) xf yf (- zf 1.0))
                      (noise-grad (aget p (inc BA)) (- xf 1.0) yf (- zf 1.0)))
                (lerp u (noise-grad (aget p (inc AB)) xf (- yf 1.0) (- zf 1.0))
                      (noise-grad (aget p (inc BB)) (- xf 1.0) (- yf 1.0) (- zf 1.0)))))))

(defn- noise2 ^double [x y] (perlin-noise x y 0.0))
(defn- noise2-alt ^double [x y] (perlin-noise x y 1.0))
(defn- noise1 ^double [x] (perlin-noise x 0.0 0.0))

;; ---------------------------------------------------------------------------
;; Tornado ring stacks (randomized once per :start, like TornadoEffect's ctor)
;; ---------------------------------------------------------------------------

(defn- ranged-random [lower upper]
  (+ (double lower) (* (rand) (- (double upper) (double lower)))))

(defn- rand-gaussian
  ^double []
  (* (Math/sqrt (* -2.0 (Math/log (max 1.0e-9 (rand)))))
     (Math/cos (* 2.0 Math/PI (rand)))))

(defn- build-ring-stack
  "Port of TornadoEffect's constructor: accumulate ring heights along the
  column with jittered spacing, ~35% doubled rings."
  []
  (let [stdstep (/ tornado-ht tornado-divide)
        rings (transient [])]
    (loop [accum 0.0]
      (if (< accum tornado-ht)
        ;; Original emits the ring unconditionally after stepping, so the
        ;; topmost ring legitimately sits slightly above ht (ny > 1).
        (let [accum' (+ accum (* stdstep (+ 1.0 (* (rand-gaussian) 0.2))))]
          (conj! rings {:y accum'
                        :w (* stdstep (ranged-random 1.8 2.2))
                        :phase (* (rand) 360.0)
                        :scale (ranged-random 0.9 1.2)})
          (when (< (rand) 0.35)
            (conj! rings {:y accum'
                          :w (* stdstep (ranged-random 1.8 2.2))
                          :phase (* (rand) 360.0)
                          :scale (ranged-random 1.2 1.7)}))
          (recur accum'))
        (persistent! rings)))))

;; ---------------------------------------------------------------------------
;; Per-tick animation sampling (TornadoRenderer.calcdx / r / rot)
;; ---------------------------------------------------------------------------

(defn- tornado-ring-states
  "Sample every ring of one tornado at `time` (GameTimer*4 equivalent).
  Returns [{:y :w :r :dx :dz :u-scroll} ...] in tornado-local space."
  [time {:keys [rings t-offset]}]
  (let [time (- (double time) (double t-offset))]
    (mapv (fn [{:keys [y w phase scale]}]
            (let [ny (/ y tornado-ht)
                  t0 (* 0.1 time)
                  ;; Original calcdx: displacement scaled by sz*dscale, ring
                  ;; RADIUS (r * sz * sizeScale) by sz only — exact original
                  ;; values.
                  sway (* (+ 0.3 (Math/pow (Math/abs (* 2.0 ny)) 1.4))
                          tornado-sz tornado-dscale)
                  rr (* (+ (+ 0.5 (* 0.3 (noise2 ny (* 0.2 time))))
                           (* 0.5 (Math/pow (* 1.5 ny) 2.0))
                           (noise1 ny))
                        tornado-sz scale)
                  ;; drawRing's `rot` argument: rot(ny,t) + ring.phase, both in
                  ;; texture-U units. Wrapped into [0,1) here — the texture
                  ;; repeats, so this is visually identical to the original's
                  ;; unbounded value but keeps float UV precision intact over a
                  ;; long session.
                  scroll (+ (* 0.1 (+ 1.0 (* 0.5 ny)) time) (double phase))]
              {:y y
               :w w
               :r rr
               :dx (* (noise2 ny t0) sway)
               :dz (* (noise2-alt ny t0) sway)
               :u-scroll (- scroll (Math/floor scroll))}))
          rings)))

;; ---------------------------------------------------------------------------
;; Enqueue / tick state
;; ---------------------------------------------------------------------------

(defn default-storm-wing-fx-runtime-state
  []
  {:effect-state {}})

(defn storm-wing-fx-snapshot
  []
  (or (level-effects/effect-state-snapshot storm-wing-effect-id)
      (default-storm-wing-fx-runtime-state)))

(defn reset-storm-wing-fx-for-test!
  []
  (level-effects/reset-level-effect-state-for-test!
    storm-wing-effect-id
    (default-storm-wing-fx-runtime-state))
  nil)

(defn clear-storm-wing-owner!
  [owner-key]
  (level-effects/update-effect-state!
    storm-wing-effect-id
    (fn [store]
      (update (or store (default-storm-wing-fx-runtime-state)) :effect-state dissoc owner-key)))
  nil)

(defn- stop-loop-sound! [ctx-id]
  (client-bridge/run-client-effect!
   :mcmod/stop-loop-sound
   {:key (loop-sound-key ctx-id)})
  nil)

(defn- enqueue-state!
  [store ctx-id channel owner-key payload]
  (let [store* (or store (default-storm-wing-fx-runtime-state))
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode phase charge-ticks charge-ratio source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :queue-owner (client-particles/current-effect-owner)
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :start
      (do
        ;; Original c_makealive: FollowEntitySound loop attached to the caster.
        (client-bridge/run-client-effect!
         :mcmod/start-loop-sound-at-player
         {:key (loop-sound-key ctx-id)
          :sound-id loop-sound
          :owner-uuid (str source-player-id)
          :volume 0.5
          :pitch 1.0})
        (assoc-in store* [:effect-state owner-key*]
                  (merge base-meta
                         {:active? true :phase :charging :charge-ticks 0
                          :charge-ticks-needed (long (or charge-ticks 70))
                          :ticks 0
                          :tornadoes (mapv (fn [tf]
                                             (merge tf
                                                    {:t-offset (* (rand) 20.0)
                                                     :rings (build-ring-stack)}))
                                           tornado-transforms)})))
      :update
      (assoc-in store* [:effect-state owner-key*]
                (assoc (merge base-meta (get-in store* [:effect-state owner-key*] {}))
                       :owner-key owner-key*
                       :queue-owner (or (get-in store* [:effect-state owner-key* :queue-owner])
                                        (:queue-owner base-meta))
                       :ctx-id ctx-id
                       :channel channel
                       :source-player-id source-player-id
                       :world-id world-id
                       :active? true
                       :phase (or phase :charging)
                       :charge-ticks (long (or charge-ticks 0))
                       :charge-ratio (double (or charge-ratio 0.0))))
      :end
      (do
        ;; Original c_terminate stops the loop sound at once, but
        ;; StormWingEffect keeps rendering for TERMINATE_TICK more ticks while
        ;; alpha fades to 0 before setDead().
        (stop-loop-sound! ctx-id)
        (if-let [st (get-in store* [:effect-state owner-key*])]
          (assoc-in store* [:effect-state owner-key*]
                    (assoc st :terminating? true :terminate-tick 0))
          (assoc-in store* [:effect-state owner-key*]
                    (merge base-meta {:active? false :ticks 0}))))
      store*)))

(defn- tick-state!
  [store]
  (let [store* (or store (default-storm-wing-fx-runtime-state))]
    (update store* :effect-state
      (fn [states]
        (into {}
              (keep (fn [[owner-key st]]
                      (when (:active? st)
                        (let [terminating? (boolean (:terminating? st))
                              term-tick (inc (long (or (:terminate-tick st) 0)))]
                          (when-not (and terminating? (> term-tick terminate-ticks))
                            (let [ticks (inc (long (or (:ticks st) 0)))
                                  phase (or (:phase st) :charging)
                                  ;; StormWingEffect.onUpdate's eff.alpha, kept
                                  ;; as the original 0..1 double — the extra
                                  ;; *0.7 for the vertex colour is applied once,
                                  ;; at render time.
                                  alpha (cond
                                          (= phase :charging)
                                          (* 0.7 (double (or (:charge-ratio st) 0.0)))
                                          (not terminating?) 0.7
                                          :else
                                          (* 0.7 (- 1.0 (/ (double term-tick)
                                                           (double terminate-ticks)))))]
                              [owner-key (cond-> (assoc st
                                                   :ticks ticks
                                                   :ring-alpha alpha
                                                   :tornado-rings
                                                   (mapv #(tornado-ring-states (* 0.2 (double ticks)) %)
                                                         (:tornadoes st)))
                                           terminating? (assoc :terminate-tick term-tick))]))))))
              states)))))

(defn- matching-active-state
  [effect-state hand-center-pos]
  (some (fn [st]
          (when (and (:active? st)
                     (or (nil? (:source-player-id st))
                         (nil? (:player-uuid hand-center-pos))
                         (= (str (:source-player-id st))
                            (str (:player-uuid hand-center-pos)))))
            st))
        (vals effect-state)))

(defn- maybe-spawn-particles!
  "Original StormWingContextC.c_tick: 12 dirt-dust particles per tick in a
  shell of radius 3..8 around the player's feet. It runs from make-alive to
  terminate, i.e. during the charge phase too — not only while flying — and
  gives each particle a tangential swirl velocity (the platform particle op
  multiplies :offset-* by :speed to get the spawn velocity)."
  [owner-key sw tick px py pz]
  (when (not= tick (:last-particle-tick sw))
    (level-effects/update-effect-state!
      storm-wing-effect-id
      (fn [store]
        (update-in (or store (default-storm-wing-fx-runtime-state))
          [:effect-state owner-key] assoc :last-particle-tick tick)))
    (dotimes [_ 12]
      (let [theta (* (rand) 2.0 Math/PI)
            phi (- (* (rand) 2.0 Math/PI) Math/PI)
            r (+ 3.0 (* (rand) 5.0))
            rzx (* r (Math/sin phi))
            cth (Math/cos theta)
            sth (Math/sin theta)]
        (client-particles/queue-particle-effect! (:queue-owner sw)
          {:type :particle
           :particle-type :block-crack
           :block-id "minecraft:dirt"
           :x (+ px (* rzx cth))
           :y (+ py (* r (Math/cos phi)))
           :z (+ pz (* rzx sth))
           :count 1
           :speed 1.0
           :offset-x (* sth 0.7)
           :offset-y (+ -0.01 (* (rand) 0.06))
           :offset-z (* (- cth) 0.7)}))))
  nil)

;; ---------------------------------------------------------------------------
;; Build plan - StormWingEffectRender's transform + TornadoRenderer's quads
;; ---------------------------------------------------------------------------

(defn- tornado-quad-ops
  "One tornado's rings transformed to world space.

  Original render chain: translate(player + (0,1.6,0)) -> rotY(-yaw) ->
  rotX(pitch*0.2 - 70deg) -> translate(0, 0.2, -0.5) -> CompTransform
  [translate(ox,oy,oz) -> rotY(sepY) -> rotZ(sepZ)] -> ring-local coords.

  That is affine, so it folds into an origin O and a linear map
  M = rotY(-yaw).rotX(phi).rotY(sepY).rotZ(sepZ): a ring corner is
  O + M*(x,y,z). Both are computed once per tornado per frame. The ring band's
  half-width runs along the column axis M*(0,1,0), which the tilt makes very
  much not world-up."
  [px py pz yaw-rad phi-rad alpha {:keys [ox oy oz sep-y sep-z]} rings]
  (let [cy (Math/cos (- yaw-rad)) sy (Math/sin (- yaw-rad))
        cp (Math/cos phi-rad) sp (Math/sin phi-rad)
        cys (Math/cos (Math/toRadians sep-y)) sys (Math/sin (Math/toRadians sep-y))
        czs (Math/cos (Math/toRadians sep-z)) szs (Math/sin (Math/toRadians sep-z))
        ;; rotY(-yaw) . rotX(phi) — the part outside the CompTransform.
        outer (fn [^double x ^double y ^double z]
                (let [qy (- (* y cp) (* z sp))
                      qz (+ (* y sp) (* z cp))]
                  [(+ (* x cy) (* qz sy)) qy (- (* qz cy) (* x sy))]))
        ;; M = outer . rotY(sepY) . rotZ(sepZ)
        m (fn [^double x ^double y ^double z]
            (let [a (- (* x czs) (* y szs))
                  b (+ (* x szs) (* y czs))]
              (outer (+ (* a cys) (* z sys)) b (- (* z cys) (* a sys)))))
        ;; O = player + (0,1.6,0) + outer*(0,0.2,-0.5) + outer*(ox,oy,oz).
        ;; oy/oz are what stagger the four columns in height and depth; the
        ;; previous port applied only ox, collapsing them onto one line.
        [hx hy hz] (outer 0.0 0.2 -0.5)
        [tx ty tz] (outer (double ox) (double oy) (double oz))
        origin-x (+ px hx tx)
        origin-y (+ py 1.6 hy ty)
        origin-z (+ pz hz tz)
        [ux uy uz] (m 0.0 1.0 0.0)
        ;; Original TornadoRenderer: glColor4d(1,1,1, eff.alpha * 0.7).
        color {:r 255 :g 255 :b 255
               :a (max 0 (min 255 (int (* 255.0 (double alpha) 0.7))))}]
    (mapcat
      (fn [{:keys [y w r dx dz u-scroll]}]
        (let [hw (* 0.5 (double w))
              scroll (double u-scroll)
              ;; One point per segment boundary, shared by the two quads that
              ;; meet there.
              edges (mapv (fn [[^double s ^double c]]
                            (m (+ (double dx) (* s (double r)))
                               (double y)
                               (+ (double dz) (* c (double r)))))
                          ring-circle)]
          (map (fn [i]
                 (let [[e0x e0y e0z] (nth edges i)
                       [e1x e1y e1z] (nth edges (rem (inc (long i)) ring-segments))
                       t0x (+ origin-x e0x) t0y (+ origin-y e0y) t0z (+ origin-z e0z)
                       t1x (+ origin-x e1x) t1y (+ origin-y e1y) t1z (+ origin-z e1z)
                       u0 (- (* u-step (double i)) scroll)]
                   ;; p0/p3 top (v=0), p1/p2 bottom (v=1) — matches the
                   ;; original's glTexCoord/glVertex emission order.
                   (ru/quad-op tornado-ring-texture
                     (vec3/v3 (+ t0x (* ux hw)) (+ t0y (* uy hw)) (+ t0z (* uz hw)))
                     (vec3/v3 (- t0x (* ux hw)) (- t0y (* uy hw)) (- t0z (* uz hw)))
                     (vec3/v3 (- t1x (* ux hw)) (- t1y (* uy hw)) (- t1z (* uz hw)))
                     (vec3/v3 (+ t1x (* ux hw)) (+ t1y (* uy hw)) (+ t1z (* uz hw)))
                     u0 (+ u0 u-step) 0.0 1.0 color)))
               (range ring-segments))))
      rings)))

(defn- build-plan
  [_camera-pos hand-center-pos tick _query-fn]
  (let [{:keys [effect-state]} (storm-wing-fx-snapshot)
        sw (matching-active-state effect-state hand-center-pos)]
    (when (and hand-center-pos sw (:active? sw))
      (let [px (double (or (:player-x hand-center-pos) 0.0))
            py (double (or (:player-y hand-center-pos) 0.0))
            pz (double (or (:player-z hand-center-pos) 0.0))
            ;; StormWingEffect tracks setRotation(player.renderYawOffset, ...) —
            ;; BODY yaw, so turning your head does not swing the columns.
            yaw (double (or (:player-body-yaw-rad hand-center-pos)
                            (:player-yaw-rad hand-center-pos)
                            0.0))
            pitch (double (or (:player-pitch-rad hand-center-pos) 0.0))
            phi (- (* 0.2 pitch) (Math/toRadians tornado-back-tilt-degrees))]
        (maybe-spawn-particles! (:owner-key sw) sw tick px py pz)
        (let [ops (into []
                        (mapcat (fn [[tf rings]]
                                  (tornado-quad-ops
                                   px py pz yaw phi (:ring-alpha sw) tf rings)))
                        (map vector (:tornadoes sw) (:tornado-rings sw)))]
          (when (seq ops)
            {:ops ops}))))))

(defn init!
  []
  (fx-spec/register!
    {:id storm-wing-effect-id
     :level {:initial-state (default-storm-wing-fx-runtime-state)
             :enqueue-state-fn enqueue-state!
             :tick-state-fn tick-state!
             :build-plan-fn build-plan
             :clear-owner-fn (fn [store owner-key]
                               (stop-loop-sound! (second owner-key))
                               (update (or store (default-storm-wing-fx-runtime-state))
                                       :effect-state dissoc owner-key))}
     :channels {:start {:topic :storm-wing/fx-start :mode :start
                        :level-payload (fn [_ _ p]
                                         {:charge-ticks (long (or (:charge-ticks p) 70))})}
                :update {:topic :storm-wing/fx-update :mode :update
                         :level-payload (fn [_ _ p]
                                          {:phase (or (:phase p) :charging)
                                           :charge-ticks (long (or (:charge-ticks p) 0))
                                           :charge-ratio (double (or (:charge-ratio p) 0.0))})}
                :end {:topic :storm-wing/fx-end :mode :end}}})
  nil)
