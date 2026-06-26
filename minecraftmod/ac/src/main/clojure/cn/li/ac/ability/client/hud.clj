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
  "Build CP bar render data matching original AcademyCraft CPBar:
   - bg-texture (back_normal.png) drawn at full width as the bar frame
   - fg-texture (cp.png) clipped via scissor to the filled percentage —
     the texture itself carries the red→yellow→white gradient
   - right-side 16×16 cutout for the category icon
   - red consumption-hint line showing predicted CP after active skill cost
   - pulsing white overlay when CP reaches 100%"
  [model]
  (let [{:keys [cur max]} (:cp model)
        percent (if (and max (pos? max)) (/ cur max) 0.0)
        consumption-hint (:consumption-hint model 0.0)
        hint-percent (if (and max (pos? max) (pos? consumption-hint))
                       (/ (- cur consumption-hint) max)
                       nil)]
    {:type :cp-bar
     :x 8 :y 8
     :width 100 :height 10
     :percent (double percent)
     :hint-percent (when hint-percent (max 0.0 (double hint-percent)))
     :icon-cutout {:x-offset 84 :w 16}
     :bg-texture "my_mod:textures/guis/cpbar/back_normal.png"
     :fg-texture "my_mod:textures/guis/cpbar/cp.png"
     :category-icon (:category-icon model)
     :full-glow? (>= percent 1.0)}))

(defn build-overload-bar-render-data
  "Build overload bar render data matching original AcademyCraft:
   - bg-texture switches between back_normal.png (fine) and back_overload.png (overloaded)
   - fg-texture (front_overload.png) clipped via scissor, with scroll-offset
     driving a horizontal U-scroll animation — the texture itself carries
     the cyan→green→pink gradient
   - overloaded flag triggers CPU pulse highlight (cpbar_overload shader approximation)"
  [model now-ms]
  (let [{:keys [cur max fine]} (:overload model)
        percent (if (and max (pos? max)) (/ cur max) 0.0)
        scroll-offset (double (mod (/ (double (or now-ms 0)) 2000.0) 1.0))]
    {:type :overload-bar
     :x 8 :y 22
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
  "Build activation indicator render data matching original AcademyCraft:
   - centered status dot (green=activated, gray=inactive)
   - V-key hint text to the right of the dot
   - positioned near top-center of screen"
  [model activate-hint]
  {:type :activation-indicator
   :y 10
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
                            (let [remaining (- (:show-until-ms preset-state 0) now)]
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
        ;; Flatten for backward compat: always pick the CURRENT preset (last element,
        ;; since the vector is built as [previous current] during transitions).
        preset-indicator (last preset-indicators)
        ;; Numbers display (hold V to see CP/OL values, fades in/out)
        ;; Original AcademyCraft: fade in over ~400ms on press, fade out over ~600ms on release.
        numbers-texts (when hud-model
                        (let [last-change (long (or last-show-value-change-ms 0))
                              dt (- (long now) last-change)
                              alpha (if showing-numbers?
                                      ;; Fade in: 200ms delay, then 400ms ramp to full
                                      (cond (zero? last-change) 0.0
                                            (< dt 200) 0.0
                                            (< dt 600) (/ (double (- dt 200)) 400.0)
                                            :else 1.0)
                                      ;; Fade out: 600ms linear decay from full to transparent
                                      (cond (zero? last-change) 0.0
                                            (< dt 600) (- 1.0 (/ (double dt) 600.0))
                                            :else 0.0))
                              a (int (* 255.0 alpha))]
                          (when (pos? alpha)
                            [{:kind :text
                              :text (str "CP " (int (get-in hud-model [:cp :cur])) "/" (int (get-in hud-model [:cp :max])))
                              :x 115 :y 14 :color {:r 255 :g 255 :b 255 :a a}}
                             {:kind :text
                              :text (str "OL " (int (get-in hud-model [:overload :cur])) "/" (int (get-in hud-model [:overload :max])))
                              :x 115 :y 29 :color {:r 255 :g 255 :b 255 :a a}}])))]
    (when (and hud-model (or (:activated hud-model) combat-notice preset-indicator
                             showing-numbers?
                             ;; During fade-out: showing-numbers? is false but numbers
                             ;; still need to render with decaying alpha.
                             (pos? (long (or last-show-value-change-ms 0)))))
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
