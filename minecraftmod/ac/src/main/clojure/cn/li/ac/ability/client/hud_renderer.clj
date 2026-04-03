(ns cn.li.ac.ability.client.hud-renderer
  "HUD render data builder (AC layer - no Minecraft imports)."
  (:require [cn.li.ac.ability.skill :as skill]
            [cn.li.ac.ability.model.cooldown-data :as cd-data]))

(defn build-cp-bar-render-data
  "Build CP bar render data."
  [model]
  (let [{:keys [cur max]} (:cp model)
        percent (if (and max (pos? max)) (/ cur max) 0.0)]
    {:type :cp-bar
     :x 10 :y 10
     :width 100 :height 10
     :percent (double percent)
     :texture "ac:textures/guis/cpbar/cp_bar.png"}))

(defn build-overload-bar-render-data
  "Build overload bar render data."
  [model]
  (let [{:keys [cur max fine]} (:overload model)
        percent (if (and max (pos? max)) (/ cur max) 0.0)]
    {:type :overload-bar
     :x 10 :y 25
     :width 100 :height 10
     :percent (double percent)
     :overloaded (not fine)
     :texture (if fine
                "ac:textures/guis/cpbar/ol_bar_normal.png"
                "ac:textures/guis/cpbar/ol_bar_overload.png")}))

(defn build-skill-slot-render-data
  "Build skill slot render data with cooldown info."
  [model screen-width screen-height cooldown-data]
  (vec
    (keep-indexed
      (fn [idx slot]
        (when (and slot (vector? slot) (= 2 (count slot)))
          (let [[cat-id ctrl-id] slot
                skill-info (skill/get-skill-by-controllable cat-id ctrl-id)
                in-cooldown (cd-data/in-cooldown? cooldown-data ctrl-id :main)
                remaining (when in-cooldown
                           (cd-data/get-remaining cooldown-data ctrl-id :main))
                ;; Convert ticks to seconds (20 ticks = 1 second)
                remaining-seconds (when remaining (/ remaining 20.0))]
            (when skill-info
              {:type :skill-slot
               :idx idx
               :x (- screen-width 150)
               :y (+ (- screen-height 100) (* idx 22))
               :key-label (nth ["Z" "X" "C" "V"] idx)
               :skill-icon (skill/get-skill-icon-path skill-info)
               :skill-name (:name skill-info)
               :in-cooldown in-cooldown
               :cooldown-remaining (or remaining 0)
               :cooldown-seconds (or remaining-seconds 0.0)
               :cooldown-total 100}))))
      (:active-slots model))))

(defn build-activation-indicator-data
  "Build activation indicator render data."
  [model]
  {:type :activation-indicator
   :x 120 :y 10
   :activated (:activated model)})

(defn build-hud-render-data
  "Main function to build complete HUD render data. Called by forge layer."
  [hud-model screen-width screen-height cooldown-data]
  (when hud-model
    {:cp-bar (build-cp-bar-render-data hud-model)
     :overload-bar (build-overload-bar-render-data hud-model)
     :skill-slots (build-skill-slot-render-data hud-model screen-width screen-height cooldown-data)
     :activation-indicator (build-activation-indicator-data hud-model)}))
