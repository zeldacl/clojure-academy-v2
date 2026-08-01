(ns cn.li.ac.ability.client.fx-templates.arc-beam.impl.railgun-shot
  (:require [cn.li.ac.ability.client.effects.arc-fx :as arc-fx]
            [cn.li.ac.ability.client.effects.beam-ops :as fx-beam]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.config.modid :as modid]
            [cn.li.ac.ability.client.effects.rv3 :as vec3])
  (:import [cn.li.mcmod.math V3]))

(def ^:private beam-life-ticks 50)

(defn- fade-out-factor [life]
  ;; EntityRailgunFX: blend-out is the final 1000 ms (20 ticks).
  (min 1.0 (/ (double life) (/ 20.0 beam-life-ticks))))

(defn- width-factor [beam life]
  ;; RendererRayCylinder multiplies both radii by getWidth(). Upstream keeps a
  ;; small [0, 0.3] random-walk wiggle after the 800 ms shrink reaches zero.
  (let [shrink (min 1.0 (/ (double life) (/ 16.0 beam-life-ticks)))
        seed (double (or (:wiggle-seed beam) 0.0))
        wiggle (* 0.15 (+ 1.0 (Math/sin (+ seed (* life 20.0)))))]
    (+ shrink wiggle)))

(def ^:private railgun-beam-style
  ;; Flat billboard widths are apparent width (viewed at an angle), so they
  ;; read thinner than upstream's cylinders — beefed up to stay visible.
  {:width       (fn [beam life] (* 0.45 (width-factor beam life)))
   :core-width  (fn [beam life] (* 0.28 (width-factor beam life)))
   :outer-rgb   {:r 236 :g 170 :b 93}
   :outer-alpha (fn [_ life] (* 90.0 (fade-out-factor life)))
   :inner-rgb   {:r 241 :g 240 :b 222}
   :inner-alpha (fn [_ life] (* 230.0 (fade-out-factor life)))
   ;; Retain the port's enhanced cyan center highlight in addition to the
   ;; original inner/outer cylinders.
   :line-rgb    {:r 165 :g 230 :b 255}
   :line-alpha  (fn [_ life] (+ 40.0 (* 120.0 (fade-out-factor life))))})









(def ^:private charge-ttl 32)

(defn- all-beam-effects []
  (mapcat val (:beam-effects (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :railgun-shot))))

(defn- all-charge-effects []
  (vals (:charging (cn.li.ac.ability.client.fx-templates.arc-beam/snapshot :railgun-shot))))

(defn- ensure-store [store]
  (if (contains? (or store {}) :beam-effects)
    (or store {:beam-effects {} :charging {}})
    {:beam-effects {} :charging {}}))

(defn- arc-placements [^V3 start ^V3 end]
  (let [length (vec3/vlen (vec3/v- end start))]
    (loop [cursor 1.0
           placements []]
      (if (> cursor length)
        placements
        (recur (+ cursor 1.0 (rand))
               (conj placements
                     {:distance cursor
                      :theta (* 2.0 Math/PI (rand))
                      :radius (+ 0.1 (* 0.15 (rand)))}))))))

(defn- enqueue-state!
  "Charge events keep a self-contained one-shot arc-burst state. A live
  :charging entry also keeps level-effect rendering active before a beam exists."
  [store ctx-id channel owner-key payload]
  (let [store* (ensure-store store)
        owner-key* (or owner-key [:ctx ctx-id])
        {:keys [mode start end hit-distance source-player-id world-id]} (or payload {})
        base-meta {:owner-key owner-key*
                   :ctx-id ctx-id
                   :channel channel
                   :source-player-id source-player-id
                   :world-id world-id}]
    (case mode
      :charge-start
      (assoc-in store* [:charging owner-key*]
                {:ttl charge-ttl
                 :max-ttl charge-ttl
                 :source-player-id source-player-id})

      ;; RailgunHandEffect is a self-contained 1.6-second animation. Charging
      ;; updates and cancellation do not restart or delete it upstream.
      (:charge-update :charge-end)
      store*

      (if (and start end)
        (update-in store* [:beam-effects owner-key*] (fnil conj [])
                   (merge base-meta
                          {:start (vec3/map->v3 start)
                           :end (vec3/map->v3 end)
                           :mode (or mode :block-hit)
                           :hit-distance (double (or hit-distance 18.0))
                           :ttl beam-life-ticks
                           :max-ttl beam-life-ticks
                           :arc-placements
                           (arc-placements
                             (vec3/map->v3 start)
                             (vec3/map->v3 end))
                           :wiggle-seed (* 2.0 Math/PI (rand))}))  ;; random phase [0, 2π)
        store*))))

(defn- tick-state!
  [store]
  (let [store* (ensure-store store)]
    (-> store*
        (update :beam-effects
          (fn [by-owner]
            (into {}
                  (keep (fn [[owner-key xs]]
                          (let [live (->> xs
                                          (map #(update % :ttl dec))
                                          (filter #(pos? (long (:ttl %))))
                                          vec)]
                            (when (seq live)
                              [owner-key live]))))
                  by-owner)))
        (update :charging
          (fn [by-owner]
            (into {}
                  (keep (fn [[owner-key st]]
                          (let [ttl (dec (long (or (:ttl st) 0)))]
                            (when (pos? ttl)
                              [owner-key (assoc st :ttl ttl)]))))
                  by-owner))))))

(defn- visible-beam [beam]
  ;; EntityRayBase grows to full length over the first 150 ms.
  (let [age (- (double (:max-ttl beam)) (double (:ttl beam)))
        blend-in (min 1.0 (/ age 3.0))
        start (:start beam)]
    (assoc beam :end
      (vec3/v+ start (vec3/v* (vec3/v- (:end beam) start) blend-in)))))

(def ^:private glow-halo-texture
  (modid/asset-path "textures" "effects/glow_circle.png"))

(defn- railgun-glow-ops [^V3 cam-pos beam]
  ;; Wide flat halo (upstream glow width 1.1). The beam starts at the caster's
  ;; hand (hand-muzzle-pos in railgun.clj), so the camera is off the beam axis
  ;; and the billboard is visible in first person too — an eye-start beam sat
  ;; exactly on the axis (edge-on billboard / camera-inside-tube).
  (let [start (:start beam)
        end (:end beam)
        dir (vec3/vnorm (vec3/v- end start))
        life (/ (double (:ttl beam)) (double (:max-ttl beam)))
        fade (fade-out-factor life)
        seed (double (or (:wiggle-seed beam) 0.0))
        glow-wiggle (+ 0.9 (* 0.1
                              (+ 0.5 (* 0.5 (Math/sin (+ seed (* life 15.0)))))))
        half-width (* 1.5 (width-factor beam life) glow-wiggle)
        right (vec3/v* (ru/beam-right-axis start end cam-pos) half-width)
        gs (vec3/v+ start (vec3/v* dir -0.3))
        ge (vec3/v+ end (vec3/v* dir 0.3))
        alpha (int (* 170.0 fade fade glow-wiggle))
        color {:r 255 :g 255 :b 255 :a alpha}]
    [(ru/quad-op glow-halo-texture
                 (vec3/v- gs right) (vec3/v- ge right)
                 (vec3/v+ ge right) (vec3/v+ gs right)
                 color)]))

(defn- railgun-beam-ops [^V3 cam-pos beam]
  ;; Flat billboard ray (billboard-beam-ops). The beam starts at the caster's
  ;; hand (hand-muzzle-pos in railgun.clj), so the camera is off the beam axis
  ;; and the billboard faces it — a tube around the axis read as a transparent
  ;; hollow pipe in first person, a flat beam reads solid.
  (fx-beam/fading-beam-ops cam-pos beam railgun-beam-style))

(defn- impact-ring-ops [^V3 cam-pos ^V3 end ttl max-ttl]
  ;; Enhanced port effect: expanding cyan ring at the shot endpoint, oriented
  ;; to face the camera — the beam-perpendicular ring is edge-on from the
  ;; caster's first-person view. Deliberately additive to the original beam
  ;; visuals, not a gameplay hit indicator.
  (let [life (/ (double ttl) (double (max 1 max-ttl)))
        radius (+ 0.12 (* 0.22 (- 1.0 life)))
        color (ru/with-alpha {:r 188 :g 252 :b 238} (+ 20 (* 160 life)))
        segments 12
        to-cam (vec3/vnorm (vec3/v- cam-pos end))
        candidate (if (< (Math/abs (.-y ^V3 to-cam)) 0.9) vec3/unit-y vec3/unit-x)
        right (vec3/vnorm (vec3/vcross candidate to-cam))
        up (vec3/vnorm (vec3/vcross to-cam right))]
    (vec
      (for [idx (range segments)
            :let [t0 (/ (* 2.0 Math/PI idx) segments)
                  t1 (/ (* 2.0 Math/PI (inc idx)) segments)
                  p0 (vec3/v+ end
                               (vec3/v+ (vec3/v* right (* radius (Math/cos t0)))
                                        (vec3/v* up (* radius (Math/sin t0)))))
                  p1 (vec3/v+ end
                               (vec3/v+ (vec3/v* right (* radius (Math/cos t1)))
                                        (vec3/v* up (* radius (Math/sin t1)))))]]
        (ru/line-op p0 p1 color)))))

(defn- charge-hand-ops [^V3 hand-center charge-state]
  (let [elapsed-ms (* 50.0 (- (double (:max-ttl charge-state))
                              (double (:ttl charge-state))))
        ;; 40 frames at 40ms each = 1.6s total animation
        frame (min 39 (int (/ elapsed-ms 40.0)))
        texture-path (modid/asset-path
                       "textures"
                       (str "effects/arc_burst/" frame ".png"))
        ;; Original uses scale 0.4 with offset (0.26, -0.15, -0.24)
        half-size 0.4
        cx (+ (.-x hand-center) 0.26)
        cy (+ (.-y hand-center) -0.15)
        cz (+ (.-z hand-center) -0.24)
        p0 (vec3/v3 (- cx half-size) (- cy half-size) cz)
        p1 (vec3/v3 (+ cx half-size) (- cy half-size) cz)
        p2 (vec3/v3 (+ cx half-size) (+ cy half-size) cz)
        p3 (vec3/v3 (- cx half-size) (+ cy half-size) cz)]
    [{:kind :quad
      :texture texture-path
      :p0 p0 :p1 p1 :p2 p2 :p3 p3
      :u0 0.0 :u1 1.0 :v0 0.0 :v1 1.0
      :color {:r 255 :g 255 :b 255 :a 255}}]))

(defn- build-plan
  "Beam :start/:end are precomputed to V3 at enqueue time (see enqueue-state!
  above) — a beam's endpoints never change after it's fired, so converting
  once there instead of once per frame here removes an otherwise-per-frame
  allocation for every live beam."
  [camera-pos hand-center-pos game-ticks]
  (let [beams (all-beam-effects)
        ^V3 cam-v (vec3/map->v3 camera-pos)
        player-uuid (:player-uuid hand-center-pos)
        charge-state (when player-uuid
                       (some (fn [state]
                               (when (or (nil? (:source-player-id state))
                                         (= player-uuid (:source-player-id state)))
                                 state))
                             (all-charge-effects)))
        beam-plan (mapcat (fn [beam]
                            (let [visible (visible-beam beam)]
                              (concat
                              ;; Arc/lightning branches
                              (arc-fx/railgun-arc-ops cam-v beam {})
                              ;; Wide halo + flat solid ray
                              (railgun-glow-ops cam-v visible)
                              (railgun-beam-ops cam-v visible)
                              (impact-ring-ops
                                cam-v (:end visible) (:ttl beam) (:max-ttl beam)))))
                          beams)
        charge-plan (if (and hand-center-pos charge-state)
                      (charge-hand-ops
                        (vec3/map->v3 (dissoc hand-center-pos :player-uuid))
                        charge-state)
                      [])]
    (when (or (seq beam-plan) (seq charge-plan))
      {:ops (vec (concat beam-plan charge-plan))})))

(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-initial-state [:railgun-shot :level] [_ _] {:beam-effects {} :charging {}})
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-enqueue-state! [:railgun-shot :level]
  [_ _ store ctx-id channel owner-key payload] (enqueue-state! store ctx-id channel owner-key payload))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-tick-state! [:railgun-shot :level] [_ _ store] (tick-state! store))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-build-plan :railgun-shot
  [_effect-id camera-pos hand-center-pos tick & _more]
  (build-plan camera-pos hand-center-pos tick))
(defmethod cn.li.ac.ability.client.fx-templates.arc-beam/effect-clear-owner! :railgun-shot [_ store owner-key]
  (-> store
      (update :beam-effects dissoc owner-key)
      (update :charging dissoc owner-key)))
