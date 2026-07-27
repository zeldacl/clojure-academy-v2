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
  {:width       (fn [beam life] (* 0.13 (width-factor beam life)))
   :core-width  (fn [beam life] (* 0.09 (width-factor beam life)))
   :outer-rgb   {:r 236 :g 170 :b 93}
   :outer-alpha (fn [_ life] (* 60.0 (fade-out-factor life)))
   :inner-rgb   {:r 241 :g 240 :b 222}
   :inner-alpha (fn [_ life] (* 200.0 (fade-out-factor life)))})









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

(def ^:private glow-start-texture
  (modid/asset-path "textures" "effects/railgun/blend_in.png"))
(def ^:private glow-tile-texture
  (modid/asset-path "textures" "effects/railgun/tile.png"))
(def ^:private glow-end-texture
  (modid/asset-path "textures" "effects/railgun/blend_out.png"))

(defn- visible-beam [beam]
  ;; EntityRayBase grows to full length over the first 150 ms.
  (let [age (- (double (:max-ttl beam)) (double (:ttl beam)))
        blend-in (min 1.0 (/ age 3.0))
        start (:start beam)]
    (assoc beam :end
      (vec3/v+ start (vec3/v* (vec3/v- (:end beam) start) blend-in)))))

(defn- glow-quad [texture start end right color]
  (ru/quad-op texture
    (vec3/v- start right)
    (vec3/v- end right)
    (vec3/v+ end right)
    (vec3/v+ start right)
    color))

(defn- railgun-glow-ops [^V3 camera-pos beam]
  (let [start (:start beam)
        end (:end beam)
        delta (vec3/v- end start)
        length (vec3/vlen delta)]
    (when (> length 1.0e-5)
      (let [life (/ (double (:ttl beam)) (double (:max-ttl beam)))
            dir (vec3/vnorm delta)
            glow-start (vec3/v+ start (vec3/v* dir -0.3))
            glow-end (vec3/v+ end (vec3/v* dir 0.3))
            total-length (vec3/vlen (vec3/v- glow-end glow-start))
            cap-length (min 1.1 (/ total-length 3.0))
            cap-start-end (vec3/v+ glow-start (vec3/v* dir cap-length))
            cap-end-start (vec3/v- glow-end (vec3/v* dir cap-length))
            seed (double (or (:wiggle-seed beam) 0.0))
            glow-wiggle (+ 0.9 (* 0.1
                                  (+ 0.5 (* 0.5 (Math/sin (+ seed (* life 15.0)))))))
            half-width (* 1.1 (width-factor beam life) glow-wiggle)
            right (vec3/v* (ru/beam-right-axis glow-start glow-end camera-pos)
                           half-width)
            alpha (int (* 255.0
                          (fade-out-factor life)
                          (fade-out-factor life)
                          glow-wiggle))
            color {:r 255 :g 255 :b 255 :a alpha}]
        [(glow-quad glow-start-texture glow-start cap-start-end right color)
         (glow-quad glow-tile-texture cap-start-end cap-end-start right color)
         (glow-quad glow-end-texture cap-end-start glow-end right color)]))))

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
                              (railgun-glow-ops cam-v visible)
                              (fx-beam/fading-beam-ops
                                cam-v visible railgun-beam-style))))
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
