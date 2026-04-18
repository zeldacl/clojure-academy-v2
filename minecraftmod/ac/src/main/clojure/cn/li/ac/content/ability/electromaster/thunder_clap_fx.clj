(ns cn.li.ac.content.ability.electromaster.thunder-clap-fx
  "Client FX for Thunder Clap: surround ring + target mark + walk speed."
  (:require [cn.li.ac.ability.client.level-effects :as level-effects]
            [cn.li.ac.ability.client.fx-registry :as fx-registry]
            [cn.li.ac.ability.client.render-util :as ru]))

(defonce ^:private effect-state (atom nil))

;; ---------------------------------------------------------------------------
;; Enqueue
;; ---------------------------------------------------------------------------

(defn- enqueue! [payload]
  (let [{:keys [mode ticks charge-ratio target performed?]} payload]
    (case mode
      :start
      (reset! effect-state {:active? true :ticks 0 :charge-ratio 0.0 :target nil :performed? false})
      :update
      (swap! effect-state
             (fn [st]
               (assoc (or st {})
                      :active? true
                      :ticks (long (or ticks 0))
                      :charge-ratio (double (or charge-ratio 0.0))
                      :target target)))
      :end
      (reset! effect-state {:active? false :performed? (boolean performed?)
                            :ticks 0 :charge-ratio 0.0 :target nil})
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
               nil)))))

;; ---------------------------------------------------------------------------
;; Render ops
;; ---------------------------------------------------------------------------

(defn- surround-ops [player-center ticks]
  (let [radius (+ 0.55 (* 0.25 (Math/sin (* 0.22 (double ticks)))))
        y (+ (double (:y player-center)) 0.2)
        segments 20
        color {:r 190 :g 232 :b 255 :a 170}]
    (vec
      (for [idx (range segments)
            :let [a0 (/ (* 2.0 Math/PI idx) segments)
                  a1 (/ (* 2.0 Math/PI (inc idx)) segments)
                  p0 {:x (+ (:x player-center) (* radius (Math/cos a0)))
                      :y y
                      :z (+ (:z player-center) (* radius (Math/sin a0)))}
                  p1 {:x (+ (:x player-center) (* radius (Math/cos a1)))
                      :y y
                      :z (+ (:z player-center) (* radius (Math/sin a1)))}]]
        (ru/line-op p0 p1 color)))))

(defn- target-mark-ops [target ticks charge-ratio]
  (let [base-radius (+ 0.55 (* 0.35 (double charge-ratio)))
        pulse (+ base-radius (* 0.08 (Math/sin (* 0.24 (double ticks)))))
        y (+ (double (:y target)) 0.03)
        segments 24
        color {:r 204 :g 204 :b 204 :a 179}]
    (vec
      (for [idx (range segments)
            :let [a0 (/ (* 2.0 Math/PI idx) segments)
                  a1 (/ (* 2.0 Math/PI (inc idx)) segments)
                  p0 {:x (+ (:x target) (* pulse (Math/cos a0)))
                      :y y
                      :z (+ (:z target) (* pulse (Math/sin a0)))}
                  p1 {:x (+ (:x target) (* pulse (Math/cos a1)))
                      :y y
                      :z (+ (:z target) (* pulse (Math/sin a1)))}]]
        (ru/line-op p0 p1 color)))))

(defn- local-walk-speed [ticks]
  (let [max-speed 0.1
        min-speed 0.001
        value (- max-speed (* (/ (- max-speed min-speed) 60.0) (double ticks)))]
    (float (max min-speed value))))

;; ---------------------------------------------------------------------------
;; Build plan
;; ---------------------------------------------------------------------------

(defn- build-plan [_camera-pos hand-center-pos _tick]
  (let [tc @effect-state]
    (when (and hand-center-pos tc (:active? tc))
      (let [player-center (dissoc hand-center-pos :player-uuid)
            ticks (long (or (:ticks tc) 0))
            ratio (double (or (:charge-ratio tc) 0.0))
            ops (vec (concat
                       (surround-ops player-center ticks)
                       (when (map? (:target tc))
                         (target-mark-ops (:target tc) ticks ratio))))
            ws (local-walk-speed ticks)]
        (when (or (seq ops) ws)
          {:ops ops
           :local-walk-speed ws})))))

;; ---------------------------------------------------------------------------
;; Registration
;; ---------------------------------------------------------------------------

(level-effects/register-level-effect! :thunder-clap
  {:enqueue-fn    enqueue!
   :tick-fn       tick!
   :build-plan-fn build-plan})

(fx-registry/register-fx-channels!
  [:thunder-clap/fx-start :thunder-clap/fx-update :thunder-clap/fx-end]
  (fn [_ctx-id channel payload]
    (case channel
      :thunder-clap/fx-start
      (level-effects/enqueue-level-effect! :thunder-clap {:mode :start})
      :thunder-clap/fx-update
      (level-effects/enqueue-level-effect! :thunder-clap
        {:mode :update
         :ticks (long (or (:ticks payload) 0))
         :charge-ratio (double (or (:charge-ratio payload) 0.0))
         :target (get payload :target)})
      :thunder-clap/fx-end
      (level-effects/enqueue-level-effect! :thunder-clap
        {:mode :end
         :performed? (boolean (:performed? payload))}))))
