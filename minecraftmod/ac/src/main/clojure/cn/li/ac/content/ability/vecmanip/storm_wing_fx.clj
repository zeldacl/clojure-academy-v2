(ns cn.li.ac.content.ability.vecmanip.storm-wing-fx
  "Client FX for Storm Wing: 4 tornado columns behind the caster (port of the
  original StormWingEffect + TornadoEffect/TornadoRenderer), dirt dust
  particles, and an entity-following loop sound (original FollowEntitySound).

  The tornado ring stacks are randomized once at :start (TornadoEffect's
  constructor); per tick the noise-driven ring displacement / radius /
  rotation are sampled (TornadoRenderer's calcdx/r/rot at GameTimer*4);
  per frame build-plan applies the player-yaw/pitch + 70-degree tilt
  transform (StormWingEffectRender) and emits the textured ring quads."
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
(def ^:private tornado-divide 20) ;; original 40; fewer, wider rings keep the op budget sane
(def ^:private tornado-sz 0.16)
(def ^:private tornado-dscale 2.0)
(def ^:private ring-segments 8)

;; Original StormWingEffect tornadoList: setTransform + setRotation(0, sep, sep).
(def ^:private tornado-transforms
  [{:ox -0.1 :oy -0.3 :oz 0.1 :sep 45}
   {:ox 0.1 :oy -0.3 :oz 0.1 :sep -45}
   {:ox -0.1 :oy -0.5 :oz -0.1 :sep -45}
   {:ox 0.1 :oy -0.5 :oz -0.1 :sep 45}])

(def ^:private tornado-ring-texture
  (modid/asset-path "textures" "effects/tornado_ring.png"))

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
  (* t t t (* t (- (* t 6.0) 15.0) 10.0)))

(defn- noise-grad
  ^double [^long h ^double x ^double y _z]
  (let [u (if (zero? (bit-and h 8)) x y)
        v (if (zero? (bit-and h 4)) y x)]
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
        (let [accum' (+ accum (* stdstep (+ 1.0 (* (rand-gaussian) 0.2))))]
          (when (< accum' tornado-ht)
            (conj! rings {:y accum'
                          :w (* stdstep (ranged-random 1.8 2.2))
                          :phase (* (rand) 360.0)
                          :scale (ranged-random 0.9 1.2)})
            (when (< (rand) 0.35)
              (conj! rings {:y accum'
                            :w (* stdstep (ranged-random 1.8 2.2))
                            :phase (* (rand) 360.0)
                            :scale (ranged-random 1.2 1.7)})))
          (recur accum'))
        (persistent! rings)))))

;; ---------------------------------------------------------------------------
;; Per-tick animation sampling (TornadoRenderer.calcdx / r / rot)
;; ---------------------------------------------------------------------------

(defn- tornado-ring-states
  "Sample every ring of one tornado at `time` (GameTimer*4 equivalent).
  Returns [{:y :w :r :dx :dz :rot} ...] in tornado-local space."
  [time {:keys [rings t-offset]}]
  (let [time (+ time t-offset)]
    (mapv (fn [{:keys [y w phase scale]}]
            (let [ny (/ y tornado-ht)
                  t0 (* 0.1 time)
                  ;; Original calcdx scales the DISPLACEMENT by sz*dscale, but
                  ;; the ring RADIUS (r * sz * sizeScale) uses sz only — the
                  ;; columns stay thin (~0.16x) while whipping around, which is
                  ;; what keeps the four of them visually distinct.
                  sway (* (+ 0.3 (Math/pow (Math/abs (* 2.0 ny)) 1.4))
                          tornado-sz tornado-dscale)
                  rr (* (+ (+ 0.5 (* 0.3 (noise2 ny (* 0.2 time))))
                           (* 0.5 (Math/pow (* 1.5 ny) 2.0))
                           (noise1 ny))
                        tornado-sz scale)]
              {:y y
               :w w
               :r rr
               :dx (* (noise2 ny t0) sway)
               :dz (* (noise2-alt ny t0) sway)
               :rot (+ (* 0.1 (+ 1.0 (* 0.5 ny)) time) (Math/toRadians phase))}))
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
        (stop-loop-sound! ctx-id)
        (assoc-in store* [:effect-state owner-key*]
                  (merge base-meta {:active? false :ticks 0})))
      store*)))

(defn- tick-state!
  [store]
  (let [store* (or store (default-storm-wing-fx-runtime-state))]
    (update store* :effect-state
      (fn [states]
        (into {}
              (keep (fn [[owner-key st]]
                      (when (:active? st)
                        (let [ticks (inc (long (or (:ticks st) 0)))
                              phase (or (:phase st) :charging)
                              alpha-raw (case phase
                                          :charging (* 0.7 (double (or (:charge-ratio st) 0.0)))
                                          :flying 0.7
                                          0.0)
                              alpha (int (* 255 alpha-raw))]
                          [owner-key (assoc st
                                       :ticks ticks
                                       :ring-alpha alpha
                                       :tornado-rings
                                       (mapv #(tornado-ring-states (* 0.2 (double ticks)) %)
                                             (:tornadoes st)))]))))
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

(defn- maybe-spawn-flying-particles!
  "Original c_tick's 12 dirt-dust particles per tick around the player."
  [owner-key sw tick center]
  (when (and (= (:phase sw) :flying)
             (not= tick (:last-particle-tick sw)))
    (level-effects/update-effect-state!
      storm-wing-effect-id
      (fn [store]
        (update-in (or store (default-storm-wing-fx-runtime-state))
          [:effect-state owner-key] assoc :last-particle-tick tick)))
    (dotimes [_ 12]
      (let [r (+ 3.0 (* (rand) 5.0))
            theta (* (rand) Math/PI)
            phi (* (rand) 2.0 Math/PI)]
        (client-particles/queue-particle-effect! (:queue-owner sw)
          {:type :particle
           :particle-type :block-crack
           :block-id "minecraft:dirt"
           :x (+ (double (:x center)) (* r (Math/sin theta) (Math/cos phi)))
           :y (+ (double (:y center)) (* r (Math/cos theta)))
           :z (+ (double (:z center)) (* r (Math/sin theta) (Math/sin phi)))
           :count 1
           :speed 0.05
           :offset-x 0.1 :offset-y 0.1 :offset-z 0.1}))))
  nil)

;; ---------------------------------------------------------------------------
;; Build plan - StormWingEffectRender's transform + TornadoRenderer's quads
;; ---------------------------------------------------------------------------

(defn- tornado-quad-ops
  "One tornado's rings transformed to world space.

  Original render chain: translate(player + (0,1.6,0)) -> rotY(-yaw) ->
  rotX(pitch*0.2 - 70deg) -> translate(0, 0.2, -0.5) -> per-tornado
  translate(ox,oy,oz) -> rotY(sep) -> rotZ(sep) -> ring-local coords.
  The whole chain is linear in the ring-local point, so per frame we
  precompute the anchor A and the local rotation constants, then apply the
  linear part per ring corner (ring y-offset + 2 circle offsets per segment)."
  [px py pz yaw-rad phi-rad alpha {:keys [ox oy oz sep]} rings]
  (let [cy (Math/cos (- yaw-rad)) sy (Math/sin (- yaw-rad))
        cp (Math/cos phi-rad) sp (Math/sin phi-rad)
        cys (Math/cos (Math/toRadians sep)) sys (Math/sin (Math/toRadians sep))
        czs cys szs sys
        ;; Anchor A = player + (0,1.6,0) + rotY(-yaw)*rotX(phi)*(0,0.2,-0.5)
        z1 (- (* 0.2 sp) (* 0.5 cp))
        ax (+ px (* z1 sy))
        ay (+ py 1.6 (* 0.2 cp) (* 0.5 sp))
        az (+ pz (* z1 cy))
        color {:r 255 :g 255 :b 255 :a alpha}
        lp (fn [^double x ^double y ^double z]
             ;; tornado-local -> world (linear part, anchor applied by caller)
             (let [a (- (* x czs) (* y szs))
                   b (+ (* x szs) (* y czs))
                   mxx (+ (* a cys) (* z sys))
                   myy b
                   mzz (+ (- (* a sys)) (* z cys))
                   qx (+ mxx ox)
                   qy (+ (* myy cp) (- (* mzz sp)))
                   qz (+ (* myy sp) (* mzz cp))]
               [(+ (* qx cy) (* qz sy))
                qy
                (+ (- (* qx sy)) (* qz cy))]))]
    (mapcat
      (fn [{:keys [y w r dx dz rot]}]
        (let [w2 (* 0.5 w)
              [_ wy _] (lp 0.0 w2 0.0)
              dy+ wy dy- (- wy)]
          (mapcat
            (fn [i]
              (let [a0 (+ rot (* 2.0 Math/PI (/ i ring-segments)))
                    a1 (+ rot (* 2.0 Math/PI (/ (inc i) ring-segments)))
                    [bx0 by0 bz0] (lp (+ dx (* (Math/cos a0) r))
                                      y
                                      (+ dz (* (Math/sin a0) r)))
                    [bx1 by1 bz1] (lp (+ dx (* (Math/cos a1) r))
                                      y
                                      (+ dz (* (Math/sin a1) r)))]
                [(ru/quad-op tornado-ring-texture
                  (vec3/v3 (+ ax bx0) (+ ay by0 dy+) (+ az bz0))
                  (vec3/v3 (+ ax bx0) (+ ay by0 dy-) (+ az bz0))
                  (vec3/v3 (+ ax bx1) (+ ay by1 dy-) (+ az bz1))
                  (vec3/v3 (+ ax bx1) (+ ay by1 dy+) (+ az bz1))
                  color)]))
            (range ring-segments))))
      rings)))

(defn- build-plan
  [camera-pos hand-center-pos tick _query-fn]
  (let [{:keys [effect-state]} (storm-wing-fx-snapshot)
        sw (matching-active-state effect-state hand-center-pos)]
    (when (and hand-center-pos sw (:active? sw))
      (let [center (dissoc hand-center-pos :player-uuid)
            px (double (or (:player-x hand-center-pos) 0.0))
            py (double (or (:player-y hand-center-pos) 0.0))
            pz (double (or (:player-z hand-center-pos) 0.0))
            yaw (double (or (:player-yaw-rad hand-center-pos) 0.0))
            pitch (double (or (:player-pitch-rad hand-center-pos) 0.0))
            phi (- (* 0.2 pitch) (Math/toRadians 70.0))]
        (maybe-spawn-flying-particles! (:owner-key sw) sw tick center)
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
