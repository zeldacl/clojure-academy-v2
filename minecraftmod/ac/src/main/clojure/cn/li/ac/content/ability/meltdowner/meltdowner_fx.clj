(ns cn.li.ac.content.ability.meltdowner.meltdowner-fx
  "Client FX for Meltdowner: charge ring + beam rays + walk speed."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private effect-state (atom nil))
(defonce ^:private rays (atom []))
(def ^:private charge-loop-sound "my_mod:md.md_charge")
(def ^:private fire-sound "my_mod:md.meltdowner")

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [payload]
  (let [{:keys [mode ticks charge-ratio performed? start end charge-ticks beam-length]} payload]
    (case mode
      :start
      (do
        (reset! effect-state {:active? true :ticks 0 :charge-ratio 0.0 :performed? false})
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id charge-loop-sound :volume 1.0 :pitch 1.0}))
      :update
      (swap! effect-state
             (fn [st]
               (assoc (or st {})
                      :active? true
                      :ticks (long (or ticks 0))
                      :charge-ratio (double (or charge-ratio 0.0))
                      :performed? false)))
      :end
      (reset! effect-state {:active? false :performed? (boolean performed?)
                            :ticks 0 :charge-ratio 0.0})
      :perform
      (do
        (when (and start end)
          (let [life (+ 16 (rand-int 8))]
            (swap! rays conj {:start start :end end
                              :ttl life :max-ttl life
                              :beam-length (double (or beam-length 30.0))
                              :charge-ticks (int (or charge-ticks 20))
                              :is-reflect? false})))
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id fire-sound :volume 0.5 :pitch 1.0}))
      :reflect
      (when (and start end)
        (let [life (+ 10 (rand-int 6))]
          (swap! rays conj {:start start :end end
                            :ttl life :max-ttl life
                            :beam-length 10.0 :charge-ticks 20
                            :is-reflect? true})))
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (swap! effect-state
         (fn [st]
           (when st
             (if (:active? st)
               (let [ticks (inc (long (or (:ticks st) 0)))]
                 (when (zero? (mod ticks 10))
                   (client-sounds/queue-sound-effect!
                     {:type :sound :sound-id charge-loop-sound :volume 0.75 :pitch 1.0}))
                 (assoc st :ticks ticks))
               nil))))
  (swap! rays
         (fn [xs]
           (->> xs
                (map #(update % :ttl dec))
                (filter #(pos? (long (:ttl %))))
                vec))))

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- charge-ops [center ticks charge-ratio]
  (let [base-radius (+ 0.72 (* 0.28 (double charge-ratio)))
        pulse (+ base-radius (* 0.08 (Math/sin (* 0.23 (double ticks)))))
        y-base (+ (double (:y center)) 0.18)
        ring-segments 18]
    (vec
      (mapcat
        (fn [idx]
          (let [a0 (/ (* 2.0 Math/PI idx) ring-segments)
                a1 (/ (* 2.0 Math/PI (inc idx)) ring-segments)
                h (+ y-base (* 0.22 (Math/sin (+ (* 0.17 ticks) idx))))
                p0 {:x (+ (:x center) (* pulse (Math/cos a0)))
                    :y h
                    :z (+ (:z center) (* pulse (Math/sin a0)))}
                p1 {:x (+ (:x center) (* pulse (Math/cos a1)))
                    :y h
                    :z (+ (:z center) (* pulse (Math/sin a1)))}
                ray-color {:r 170 :g 255 :b 190 :a 170}
                link-color {:r 140 :g 240 :b 170 :a 120}]
            [(ru/line-op p0 p1 ray-color)
             (ru/line-op center p0 link-color)]))
        (range ring-segments)))))

(defn- ray-ops [cam-pos {:keys [start end ttl max-ttl is-reflect?]}]
  (let [life (/ (double ttl) (double (max 1 max-ttl)))
        right (ru/beam-right-axis start end cam-pos)
        width (if is-reflect?
                (* 0.05 (+ 0.45 (* 0.55 life)))
                (* 0.09 (+ 0.6 (* 0.4 life))))
        core-width (* width 0.42)
        outer-a (ru/with-alpha {:r 161 :g 255 :b 142} (int (+ 35 (* 170 life))))
        inner-a (ru/with-alpha {:r 244 :g 255 :b 236} (int (+ 70 (* 170 life))))
        r0 (ru/v* right width)
        r1 (ru/v* right core-width)
        p0 (ru/v+ start r0) p1 (ru/v- start r0) p2 (ru/v- end r0) p3 (ru/v+ end r0)
        c0 (ru/v+ start r1) c1 (ru/v- start r1) c2 (ru/v- end r1) c3 (ru/v+ end r1)]
    [(ru/quad-op "minecraft:textures/entity/beacon_beam.png" p0 p1 p2 p3 outer-a)
     (ru/quad-op "minecraft:textures/entity/beacon_beam.png" c0 c1 c2 c3 inner-a)
     (ru/line-op start end (ru/with-alpha {:r 192 :g 255 :b 188} (int (+ 55 (* 150 life)))))]))

(defn- local-walk-speed [ticks]
  (float (max 0.001 (- 0.1 (* 0.001 (double ticks))))))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [camera-pos hand-center-pos _tick]
  (let [md @effect-state
        current-rays @rays
        charge-plan (if (and hand-center-pos md (:active? md))
                      (let [center (dissoc hand-center-pos :player-uuid)
                            ticks (long (or (:ticks md) 0))
                            ratio (double (or (:charge-ratio md) 0.0))]
                        (charge-ops center ticks ratio))
                      [])
        ws (when (and md (:active? md))
             (local-walk-speed (:ticks md)))
        ray-plan (mapcat #(ray-ops camera-pos %) current-rays)]
    (when (or (seq charge-plan) (seq ray-plan) ws)
      {:ops (vec (concat charge-plan ray-plan))
       :local-walk-speed ws})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(level-effects/register-level-effect! :meltdowner
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:meltdowner/fx-start :meltdowner/fx-update :meltdowner/fx-end
   :meltdowner/fx-perform :meltdowner/fx-reflect]
  (fn [_ctx-id channel payload]
    (case channel
      :meltdowner/fx-start
      (level-effects/enqueue-level-effect! :meltdowner {:mode :start})
      :meltdowner/fx-update
      (level-effects/enqueue-level-effect! :meltdowner
        {:mode :update
         :ticks (long (or (:ticks payload) 0))
         :charge-ratio (double (or (:charge-ratio payload) 0.0))})
      :meltdowner/fx-end
      (level-effects/enqueue-level-effect! :meltdowner
        {:mode :end :performed? (boolean (:performed? payload))})
      :meltdowner/fx-perform
      (level-effects/enqueue-level-effect! :meltdowner
        {:mode :perform
         :charge-ticks (int (or (:charge-ticks payload) 20))
         :beam-length (double (or (:beam-length payload) 30.0))
         :start (:start payload) :end (:end payload)})
      :meltdowner/fx-reflect
      (level-effects/enqueue-level-effect! :meltdowner
        {:mode :reflect :start (:start payload) :end (:end payload)}))))
