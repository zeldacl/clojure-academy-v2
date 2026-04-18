(ns cn.li.ac.content.ability.vecmanip.vec-deviation-fx
  "Client FX for VecDeviation: ring overlay + deflection wave billboards."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private effect-state (atom nil))
(defonce ^:private wave-effects (atom []))
(def ^:private sound-id "my_mod:vecmanip.vec_deviation")

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [payload]
  (let [{:keys [mode x y z marked?]} payload]
    (case mode
      :start
      (reset! effect-state {:active? true :ticks 0})
      :end
      (reset! effect-state {:active? false :ticks 0})
      :stop-entity
      (when marked?
        (let [life (+ 10 (rand-int 6))]
          (swap! wave-effects conj
                 {:x (double x) :y (double y) :z (double z)
                  :ttl life :max-ttl life})))
      :play
      (client-sounds/queue-sound-effect!
        {:type :sound :sound-id sound-id :volume 0.5 :pitch 1.0
         :x (double x) :y (double y) :z (double z)})
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (swap! effect-state
         (fn [st]
           (when st
             (if (:active? st)
               (update st :ticks (fnil inc 0))
               nil))))
  (swap! wave-effects
         (fn [xs] (->> xs (map #(update % :ttl dec)) (filter #(pos? (long (:ttl %)))) vec))))

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- ring-ops [center ticks]
  (let [radius (+ 0.7 (* 0.08 (Math/sin (* 0.19 (double ticks)))))
        y (+ (double (:y center)) 0.25)
        segments 28
        color {:r 210 :g 240 :b 255 :a 170}
        spoke-color {:r 170 :g 210 :b 245 :a 120}
        spoke-step (/ segments 4)]
    (vec
      (mapcat
        (fn [idx]
          (let [a0 (/ (* 2.0 Math/PI idx) segments)
                a1 (/ (* 2.0 Math/PI (inc idx)) segments)
                p0 {:x (+ (:x center) (* radius (Math/cos a0)))
                    :y y
                    :z (+ (:z center) (* radius (Math/sin a0)))}
                p1 {:x (+ (:x center) (* radius (Math/cos a1)))
                    :y y
                    :z (+ (:z center) (* radius (Math/sin a1)))}
                ring-op (ru/line-op p0 p1 color)
                spoke-op (when (zero? (mod idx spoke-step))
                           (ru/line-op center p0 spoke-color))]
            (if spoke-op [ring-op spoke-op] [ring-op])))
        (range segments)))))

(defn- wave-ops [cam-pos {:keys [x y z ttl max-ttl]}]
  (let [life (/ (double ttl) (double (max 1 max-ttl)))
        alpha (int (max 0 (min 255 (* 170.0 life))))
        center {:x (double x) :y (+ (double y) 0.6) :z (double z)}
        right (ru/camera-facing-right-axis center cam-pos)
        up (ru/billboard-up-axis center cam-pos right)
        half-size (+ 0.35 (* 0.65 (- 1.0 life)))
        side (ru/v* right half-size)
        lift (ru/v* up half-size)
        p0 (ru/v+ (ru/v- center side) lift)
        p1 (ru/v+ (ru/v+ center side) lift)
        p2 (ru/v- (ru/v+ center side) lift)
        p3 (ru/v- (ru/v- center side) lift)]
    [(ru/quad-op "my_mod:textures/effects/glow_circle.png"
                 p0 p1 p2 p3
                 {:r 190 :g 225 :b 255 :a alpha})]))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [camera-pos hand-center-pos _tick]
  (let [vd @effect-state
        current-waves @wave-effects
        ring-plan (if (and hand-center-pos vd (:active? vd))
                    (ring-ops (dissoc hand-center-pos :player-uuid)
                              (long (or (:ticks vd) 0)))
                    [])
        wave-plan (mapcat #(wave-ops camera-pos %) current-waves)]
    (when (or (seq ring-plan) (seq wave-plan))
      {:ops (vec (concat ring-plan wave-plan))})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(level-effects/register-level-effect! :vec-deviation
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:vec-deviation/fx-start :vec-deviation/fx-end
   :vec-deviation/fx-stop-entity :vec-deviation/fx-play]
  (fn [_ctx-id channel payload]
    (case channel
      :vec-deviation/fx-start
      (level-effects/enqueue-level-effect! :vec-deviation {:mode :start})
      :vec-deviation/fx-end
      (level-effects/enqueue-level-effect! :vec-deviation {:mode :end})
      :vec-deviation/fx-stop-entity
      (level-effects/enqueue-level-effect! :vec-deviation
        {:mode :stop-entity
         :x (double (or (:x payload) 0.0))
         :y (double (or (:y payload) 0.0))
         :z (double (or (:z payload) 0.0))
         :marked? (boolean (:marked? payload))})
      :vec-deviation/fx-play
      (level-effects/enqueue-level-effect! :vec-deviation
        {:mode :play
         :x (double (or (:x payload) 0.0))
         :y (double (or (:y payload) 0.0))
         :z (double (or (:z payload) 0.0))}))))
