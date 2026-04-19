(ns cn.li.ac.content.ability.electromaster.mine-detect-fx
  "Client FX for MineDetect: ore highlight boxes rendered for 100 ticks."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]))

(defonce ^:private effect-state (atom nil))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(def ^:private tier-colors
  {0 {:r 255 :g 255 :b 255 :a 200}   ; common - white
   1 {:r 255 :g 215 :b 0   :a 220}   ; valuable - gold
   2 {:r 100 :g 200 :b 255 :a 240}}) ; rare - cyan

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [payload]
  (let [{:keys [mode ores range advanced?]} payload]
    (case mode
      :perform
      (reset! effect-state
              {:ores     (vec (or ores []))
               :range    (double (or range 20.0))
               :advanced? (boolean advanced?)
               :ticks    0})
      :end
      (reset! effect-state nil)
      nil)))

;; ---------------------------------------------------------------------------
;; Tick
;; ---------------------------------------------------------------------------

(defn- tick! []
  (swap! effect-state
         (fn [st]
           (when st
             (let [ticks (inc (long (or (:ticks st) 0)))]
               (if (> ticks 100)
                 nil
                 (assoc st :ticks ticks)))))))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- ore-box-ops [ore ticks]
  (let [x (double (:x ore))
        y (double (:y ore))
        z (double (:z ore))
        tier (int (or (:tier ore) 0))
        color (get tier-colors tier (tier-colors 0))
        alpha (int (* (:a color) (max 0.2 (- 1.0 (/ (double ticks) 110.0)))))
        c (assoc color :a alpha)
        ;; Box corners
        x0 x  y0 y  z0 z
        x1 (+ x 1.0) y1 (+ y 1.0) z1 (+ z 1.0)]
    ;; Draw 12 edges of the bounding box
    [(ru/line-op {:x x0 :y y0 :z z0} {:x x1 :y y0 :z z0} c)
     (ru/line-op {:x x1 :y y0 :z z0} {:x x1 :y y0 :z z1} c)
     (ru/line-op {:x x1 :y y0 :z z1} {:x x0 :y y0 :z z1} c)
     (ru/line-op {:x x0 :y y0 :z z1} {:x x0 :y y0 :z z0} c)
     (ru/line-op {:x x0 :y y1 :z z0} {:x x1 :y y1 :z z0} c)
     (ru/line-op {:x x1 :y y1 :z z0} {:x x1 :y y1 :z z1} c)
     (ru/line-op {:x x1 :y y1 :z z1} {:x x0 :y y1 :z z1} c)
     (ru/line-op {:x x0 :y y1 :z z1} {:x x0 :y y1 :z z0} c)
     (ru/line-op {:x x0 :y y0 :z z0} {:x x0 :y y1 :z z0} c)
     (ru/line-op {:x x1 :y y0 :z z0} {:x x1 :y y1 :z z0} c)
     (ru/line-op {:x x1 :y y0 :z z1} {:x x1 :y y1 :z z1} c)
     (ru/line-op {:x x0 :y y0 :z z1} {:x x0 :y y1 :z z1} c)]))

(defn- build-plan [_camera-pos _hand-center-pos _tick]
  (let [st @effect-state]
    (when (and st (:ores st))
      (let [ticks (long (or (:ticks st) 0))
            ores (:ores st)]
        {:ops (vec (mapcat #(ore-box-ops % ticks) ores))}))))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(level-effects/register-level-effect! :mine-detect
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:mine-detect/fx-perform :mine-detect/fx-end]
  (fn [_ctx-id channel payload]
    (case channel
      :mine-detect/fx-perform
      (level-effects/enqueue-level-effect! :mine-detect
        {:mode     :perform
         :ores     (:ores payload)
         :range    (:range payload)
         :advanced? (:advanced? payload)})
      :mine-detect/fx-end
      (level-effects/enqueue-level-effect! :mine-detect {:mode :end})
      nil)))
