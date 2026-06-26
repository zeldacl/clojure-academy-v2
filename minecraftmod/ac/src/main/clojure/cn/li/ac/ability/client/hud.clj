(ns cn.li.ac.ability.client.hud
  "HUD render data builder (AC layer - no Minecraft imports)."
  (:require [cn.li.ac.ability.registry.category :as category]
            [cn.li.ac.ability.registry.skill :as skill-registry]
            [cn.li.ac.ability.registry.skill-query :as skill-query]
            [cn.li.ac.ability.client.combat-notice :as combat-notice]
            [cn.li.ac.ability.client.read-model :as read-model]
            [cn.li.ac.ability.model.cooldown :as cd-data]
            [cn.li.ac.ability.client.delegate-state :as dstate]
            [cn.li.mcmod.hooks.core :as runtime-hooks]))

(defn- player-contexts
  [player-uuid]
  (read-model/get-player-contexts-for-player (str player-uuid)
                                             runtime-hooks/*client-session-id*
                                             :hud))

(defn build-cp-bar-render-data
  "Build CP bar render data with color gradient and consumption hint."
  [model]
  (let [{:keys [cur max]} (:cp model)
        percent (if (and max (pos? max)) (/ cur max) 0.0)
        consumption-hint (:consumption-hint model 0.0)
        hint-percent (if (and max (pos? max) (pos? consumption-hint))
                       (/ (- cur consumption-hint) max)
                       nil)]
    {:type :cp-bar
     :x 10 :y 10
     :width 100 :height 10
     :percent (double percent)
     :hint-percent (when hint-percent (max 0.0 (double hint-percent)))
     ;; Reserve a small right-side icon region to mimic the old cpbar_cp mask.
     :icon-cutout {:x-offset 84 :w 16}
     :bg-texture "my_mod:textures/guis/cpbar/back_normal.png"
     :fg-texture "my_mod:textures/guis/cpbar/cp.png"
     :color-stops [{:pct 0.0  :r 1.0 :g 0.16 :b 0.16}   ;; red
                   {:pct 0.35 :r 1.0 :g 0.85 :b 0.1}    ;; yellow
                   {:pct 1.0  :r 1.0 :g 1.0  :b 1.0}]    ;; white
     :category-icon (:category-icon model)}))

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
     :fg-texture "my_mod:textures/guis/cpbar/front_overload.png"
     :color-stops [{:pct 0.0  :r 0.04 :g 0.87 :b 0.87}   ;; cyan
                   {:pct 0.55 :r 0.14 :g 0.94 :b 0.62}   ;; greenish
                   {:pct 1.0  :r 0.31 :g 0.96 :b 0.39}]}))  ;; pinkish-red

(defn build-skill-slot-render-data
  "Build skill slot render data with cooldown info and delegate visual state."
  [model screen-width screen-height cooldown-data player-uuid]
  (let [active-contexts (when player-uuid
                          (player-contexts player-uuid))]
    (vec
     (keep-indexed
      (fn [idx slot]
        (when (and slot (vector? slot) (= 2 (count slot)))
          (let [[cat-id ctrl-id] slot
                skill-id (skill-query/get-skill-by-controllable cat-id ctrl-id)
                skill-spec (when skill-id (skill-registry/get-skill skill-id))
                in-cooldown (cd-data/in-cooldown? cooldown-data ctrl-id :main)
                remaining (when in-cooldown
                            (cd-data/get-remaining cooldown-data ctrl-id :main))
                remaining-seconds (when remaining (/ remaining 20.0))
                visual (dstate/delegate-state-for-slot active-contexts skill-id player-uuid)]
            (when skill-id
              {:type :skill-slot
               :idx idx
               :x (- screen-width 120)
               :y (+ (- screen-height 100) (* idx 22))
               :key-label (nth ["Z" "X" "C" "B"] idx)
               :skill-icon (skill-query/get-skill-icon-path skill-id)
               :skill-name (or (:name skill-spec) (name skill-id))
               :in-cooldown in-cooldown
               :cooldown-remaining (or remaining 0)
               :cooldown-seconds (or remaining-seconds 0.0)
               :cooldown-total (or (get-in skill-spec [:cooldown-policy :ticks])
                                  (:cooldown-ticks skill-spec)
                                  100)
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

(defn- build-combat-notice-data
  [combat-notice-component now-ms]
  (when (and combat-notice-component runtime-hooks/*client-session-id*)
    (when-let [{:keys [text color alpha]} (combat-notice/active-notice combat-notice-component
                                                                       runtime-hooks/*client-session-id*
                                                                       :teleporter-crit
                                                                       now-ms)]
      {:type :combat-notice
       :x 120
       :y 26
       :text text
       :color {:a (int (* 255.0 (double alpha)))
               :r (int (nth color 0 255))
               :g (int (nth color 1 255))
               :b (int (nth color 2 255))}})))

(defn build-hud-render-data
  "Main function to build complete HUD render data. Called by forge layer."
  [hud-model screen-width screen-height cooldown-data
   & {:keys [player-uuid activate-hint preset-state now-ms combat-notice-component
             showing-numbers? last-show-value-change-ms]}]
  (let [now (or now-ms (System/currentTimeMillis))
        combat-notice (build-combat-notice-data combat-notice-component now)
        preset-indicators (when preset-state
                            (let [now* (System/currentTimeMillis)
                                  remaining (- (:show-until-ms preset-state 0) now*)]
                              (when (pos? remaining)
                                (let [current-idx (:current-preset preset-state 0)
                                      previous-idx (:previous-preset preset-state 0)
                                      elapsed (- 2000.0 remaining)
                                      ;; Previous fades out over full 2s
                                      prev-fade (max 0.0 (- 1.0 (/ elapsed 2000.0)))
                                      ;; Current fades in over first 500ms
                                      curr-fade (min 1.0 (/ elapsed 500.0))]
                                  (vec
                                   (keep identity
                                    (when (and (not= previous-idx current-idx) (pos? prev-fade))
                                      {:type :preset-indicator
                                       :current previous-idx :total 4 :fade prev-fade})
                                    [{:type :preset-indicator
                                      :current current-idx :total 4 :fade curr-fade}]))))))
        ;; Flatten preset indicators for backward compat: first element in list is used as :preset-indicator
        preset-indicator (first preset-indicators)
        ;; Numbers display (hold V to see CP/OL values, fades in/out)
        numbers-texts (when (and showing-numbers? hud-model)
                        (let [dt (- (long now) (long (or last-show-value-change-ms 0)))
                              alpha (cond (zero? (or last-show-value-change-ms 0)) 0.0
                                          (< dt 200) 0.0
                                          (< dt 600) (/ (double (- dt 200)) 400.0)
                                          :else 1.0)
                              a (int (* 255.0 alpha))]
                          (when (pos? alpha)
                            [{:kind :text
                              :text (str "CP " (int (get-in hud-model [:cp :cur])) "/" (int (get-in hud-model [:cp :max])))
                              :x 115 :y 14 :color {:r 255 :g 255 :b 255 :a a}}
                             {:kind :text
                              :text (str "OL " (int (get-in hud-model [:overload :cur])) "/" (int (get-in hud-model [:overload :max])))
                              :x 115 :y 29 :color {:r 255 :g 255 :b 255 :a a}}])))]
    (when (and hud-model (or (:activated hud-model) combat-notice preset-indicator showing-numbers?))
      {:cp-bar (when (:activated hud-model)
                 (build-cp-bar-render-data hud-model))
       :overload-bar (when (:activated hud-model)
                       (build-overload-bar-render-data hud-model now))
       :skill-slots (when (:activated hud-model)
                      (build-skill-slot-render-data hud-model screen-width screen-height
                                                    cooldown-data player-uuid))
       :activation-indicator (when (:activated hud-model)
                               (build-activation-indicator-data hud-model activate-hint))
       :combat-notice combat-notice
       :preset-indicator preset-indicator
       :preset-indicators preset-indicators
       :numbers-texts numbers-texts})))
