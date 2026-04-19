(ns cn.li.ac.content.ability.meltdowner.mine-ray-fx
  "Client FX for all mine-ray variants: beam glow + block progress indicator."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]
            [cn.li.ac.ability.client.effects.particles :as client-particles]
            [cn.li.ac.ability.client.effects.sounds :as client-sounds]))

(defonce ^:private effect-state (atom nil))

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [payload]
  (let [{:keys [mode variant x y z progress]} payload]
    (case mode
      :start
      (do
        (reset! effect-state {:active? true :ticks 0 :variant (or variant :basic)
                              :target nil :progress 0.0})
        (client-sounds/queue-sound-effect!
          {:type :sound :sound-id "my_mod:md.mine_ray_start" :volume 0.5 :pitch 1.0}))
      :progress
      (swap! effect-state
             (fn [st]
               (when st
                 (assoc st
                        :target {:x (int (or x 0)) :y (int (or y 0)) :z (int (or z 0))}
                        :progress (double (or progress 0.0))))))
      :end
      (reset! effect-state nil)
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (swap! effect-state
         (fn [st]
           (when (and st (:active? st))
             (let [ticks (inc (long (or (:ticks st) 0)))]
               (when (zero? (mod ticks 8))
                 (when-let [target (:target st)]
                   (client-particles/queue-particle-effect!
                     {:type :particle :particle-type :electric-spark
                      :x (+ (double (:x target)) 0.5)
                      :y (+ (double (:y target)) 0.5)
                      :z (+ (double (:z target)) 0.5)
                      :count 2 :speed 0.1
                      :offset-x 0.3 :offset-y 0.3 :offset-z 0.3})))
               (assoc st :ticks ticks))))))

;; ---------------------------------------------------------------------------
;; Render ops  
;; ---------------------------------------------------------------------------

(defn- progress-box-ops [target progress ticks variant]
  (let [x (double (:x target))
        y (double (:y target))
        z (double (:z target))
        ;; Color based on variant
        c (case variant
            :luck    {:r 255 :g 215 :b 0   :a 220}
            :expert  {:r 100 :g 255 :b 100 :a 200}
            {:r 150 :g 220 :b 255 :a 180})
        alpha (int (* (:a c) (+ 0.5 (* 0.5 (Math/sin (* 0.3 (double ticks)))))))
        col (assoc c :a alpha)
        ;; Shrink box based on progress (0→1)
        shrink (* 0.05 (- 1.0 progress))
        x0 (+ x shrink) y0 (+ y shrink) z0 (+ z shrink)
        x1 (- (+ x 1.0) shrink) y1 (- (+ y 1.0) shrink) z1 (- (+ z 1.0) shrink)]
    [(ru/line-op {:x x0 :y y0 :z z0} {:x x1 :y y0 :z z0} col)
     (ru/line-op {:x x1 :y y0 :z z0} {:x x1 :y y0 :z z1} col)
     (ru/line-op {:x x1 :y y0 :z z1} {:x x0 :y y0 :z z1} col)
     (ru/line-op {:x x0 :y y0 :z z1} {:x x0 :y y0 :z z0} col)
     (ru/line-op {:x x0 :y y1 :z z0} {:x x1 :y y1 :z z0} col)
     (ru/line-op {:x x1 :y y1 :z z0} {:x x1 :y y1 :z z1} col)
     (ru/line-op {:x x1 :y y1 :z z1} {:x x0 :y y1 :z z1} col)
     (ru/line-op {:x x0 :y y1 :z z1} {:x x0 :y y1 :z z0} col)
     (ru/line-op {:x x0 :y y0 :z z0} {:x x0 :y y1 :z z0} col)
     (ru/line-op {:x x1 :y y0 :z z0} {:x x1 :y y1 :z z0} col)
     (ru/line-op {:x x1 :y y0 :z z1} {:x x1 :y y1 :z z1} col)
     (ru/line-op {:x x0 :y y0 :z z1} {:x x0 :y y1 :z z1} col)]))

(defn- build-plan [_camera-pos _hand-center-pos _tick]
  (let [st @effect-state]
    (when (and st (:active? st) (:target st))
      {:ops (progress-box-ops (:target st)
                              (double (or (:progress st) 0.0))
                              (long (or (:ticks st) 0))
                              (or (:variant st) :basic))})))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(level-effects/register-level-effect! :mine-ray
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:mine-ray/fx-start :mine-ray/fx-progress :mine-ray/fx-end]
  (fn [_ctx-id channel payload]
    (case channel
      :mine-ray/fx-start
      (level-effects/enqueue-level-effect! :mine-ray
        {:mode :start :variant (:variant payload)})
      :mine-ray/fx-progress
      (level-effects/enqueue-level-effect! :mine-ray
        {:mode :progress
         :x (:x payload) :y (:y payload) :z (:z payload)
         :progress (:progress payload)})
      :mine-ray/fx-end
      (level-effects/enqueue-level-effect! :mine-ray {:mode :end})
      nil)))
