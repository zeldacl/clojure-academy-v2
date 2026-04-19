(ns cn.li.ac.ability.client.hud
  "HUD render data builder (AC layer - no Minecraft imports)."
  (:require [cn.li.ac.ability.registry.skill :as skill]
            [cn.li.ac.ability.model.cooldown :as cd-data]
            [cn.li.ac.ability.client.delegate-state :as dstate]
            [cn.li.ac.ability.state.context :as ctx]))

(defn- lerp-color
  "Linearly interpolate between two [r g b] colors."
  [[r1 g1 b1] [r2 g2 b2] t]
  (let [t (max 0.0 (min 1.0 (double t)))]
    [(int (+ r1 (* t (- r2 r1))))
     (int (+ g1 (* t (- g2 g1))))
     (int (+ b1 (* t (- b2 b1))))]))

(defn- cp-bar-color
  "CP bar color: red(0%) → orange(35%) → white(100%)."
  [percent]
  (let [p (double percent)]
    (cond
      (<= p 0.35)
      (let [t (/ p 0.35)]
        (lerp-color [255 50 50] [255 180 50] t))
      :else
      (let [t (/ (- p 0.35) 0.65)]
        (lerp-color [255 180 50] [255 255 255] t)))))

(defn build-cp-bar-render-data
  "Build CP bar render data with color gradient and consumption hint."
  [model]
  (let [{:keys [cur max]} (:cp model)
        percent (if (and max (pos? max)) (/ cur max) 0.0)
        consumption-hint (:consumption-hint model 0.0)
        hint-percent (if (and max (pos? max) (pos? consumption-hint))
                       (/ (- cur consumption-hint) max)
                       nil)
        [r g b] (cp-bar-color percent)]
    {:type :cp-bar
     :x 10 :y 10
     :width 100 :height 10
     :percent (double percent)
     :bar-color {:r r :g g :b b :a 255}
     :hint-percent (when hint-percent (max 0.0 (double hint-percent)))
     ;; Reserve a small right-side icon region to mimic the old cpbar_cp mask.
     :icon-cutout {:x-offset 84 :w 16}
     :bg-texture "my_mod:textures/guis/cpbar/back_normal.png"
     :fg-texture "my_mod:textures/guis/cpbar/cp.png"}))

(defn build-overload-bar-render-data
  "Build overload bar render data."
  [model now-ms]
  (let [{:keys [cur max fine]} (:overload model)
        percent (if (and max (pos? max)) (/ cur max) 0.0)
        scroll-offset (double (mod (/ (double (or now-ms 0)) 2000.0) 1.0))]
    {:type :overload-bar
     :x 10 :y 25
     :width 100 :height 10
     :percent (double percent)
     :overloaded (not fine)
     :scroll-offset scroll-offset
     :bg-texture (if fine
                   "my_mod:textures/guis/cpbar/back_normal.png"
                   "my_mod:textures/guis/cpbar/back_overload.png")
     :fg-texture "my_mod:textures/guis/cpbar/front_overload.png"}))

(defn build-skill-slot-render-data
  "Build skill slot render data with cooldown info and delegate visual state."
  [model screen-width screen-height cooldown-data player-uuid]
  (let [active-contexts (when player-uuid
                          (ctx/get-all-contexts-for-player player-uuid))]
    (vec
     (keep-indexed
      (fn [idx slot]
        (when (and slot (vector? slot) (= 2 (count slot)))
          (let [[cat-id ctrl-id] slot
                skill-info (skill/get-skill-by-controllable cat-id ctrl-id)
                in-cooldown (cd-data/in-cooldown? cooldown-data ctrl-id :main)
                remaining (when in-cooldown
                            (cd-data/get-remaining cooldown-data ctrl-id :main))
                remaining-seconds (when remaining (/ remaining 20.0))
                skill-id (when skill-info (:id skill-info))
                visual (dstate/delegate-state-for-slot active-contexts skill-id)]
            (when skill-info
              {:type :skill-slot
               :idx idx
               :x (- screen-width 150)
               :y (+ (- screen-height 100) (* idx 22))
               :key-label (nth ["Z" "X" "C" "B"] idx)
               :skill-icon (skill/get-skill-icon-path skill-info)
               :skill-name (:name skill-info)
               :in-cooldown in-cooldown
               :cooldown-remaining (or remaining 0)
               :cooldown-seconds (or remaining-seconds 0.0)
               :cooldown-total 100
               :visual-state (:state visual)
               :alpha (:alpha visual)
               :glow-color (:glow-color visual)
               :sin-effect? (:sin-effect? visual)}))))
      (:active-slots model)))))

(defn build-activation-indicator-data
  "Build activation indicator render data with activate key hint."
  [model activate-hint]
  {:type :activation-indicator
   :x 120 :y 10
   :activated (:activated model)
   :hint activate-hint})

(defn build-hud-render-data
  "Main function to build complete HUD render data. Called by forge layer."
  [hud-model screen-width screen-height cooldown-data
   & {:keys [player-uuid activate-hint preset-state now-ms]}]
  (when hud-model
    {:cp-bar (build-cp-bar-render-data hud-model)
     :overload-bar (build-overload-bar-render-data hud-model now-ms)
     :skill-slots (build-skill-slot-render-data hud-model screen-width screen-height
                                                 cooldown-data player-uuid)
     :activation-indicator (build-activation-indicator-data hud-model activate-hint)
     :preset-indicator (when preset-state
                         (let [now (System/currentTimeMillis)]
                           (when (> (:show-until-ms preset-state 0) now)
                             {:type    :preset-indicator
                              :current (:current-preset preset-state 0)
                              :total   4
                              :fade    (/ (double (- (:show-until-ms preset-state) now)) 2000.0)})))}))
