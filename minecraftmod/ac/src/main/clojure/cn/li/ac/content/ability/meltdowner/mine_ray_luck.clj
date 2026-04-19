(ns cn.li.ac.content.ability.meltdowner.mine-ray-luck
  "MineRayLuck - fortune-enhanced mining beam.

  Pattern: :hold-channel
  Range: lerp(16, 22, exp)
  Break speed: lerp(0.5, 1.0, exp)
  Fortune effect: drops extra items via block manipulation fortune parameter
  Tick cost: CP lerp(22, 15, exp)
  Down cost: overload lerp(100, 70, exp)
  Cooldown: 5 ticks
  Exp: +0.002 per block broken

  No Minecraft imports."
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.util.balance :as bal]
            [cn.li.ac.ability.state.player :as ps]
            [cn.li.ac.ability.state.context :as ctx]
            [cn.li.ac.ability.server.service.skill-effects :as skill-effects]
            [cn.li.ac.ability.server.effect.geom :as geom]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.block-manipulation :as bm]
            [cn.li.mcmod.util.log :as log]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- skill-exp [player-id]
  (double (get-in (ps/get-player-state player-id)
                  [:ability-data :skills :mine-ray-luck :exp]
                  0.0)))

;; ---------------------------------------------------------------------------
;; Fortune-aware mining tick
;; ---------------------------------------------------------------------------

(defn- mine-ray-luck-tick-impl!
  [player-id ctx-id exp range break-speed]
  (let [ctx-data  (ctx/get-context ctx-id)
        world-id  (geom/world-id-of player-id)
        eye       (geom/eye-pos player-id)
        look-vec  (when raycast/*raycast*
                    (raycast/get-player-look-vector raycast/*raycast* player-id))]
    (when (and look-vec bm/*block-manipulation*)
      (let [hit (raycast/raycast-blocks
                  raycast/*raycast*
                  world-id
                  (:x eye) (:y eye) (:z eye)
                  (:x look-vec) (:y look-vec) (:z look-vec)
                  (double range))]
        (if (nil? hit)
          (ctx/update-context! ctx-id assoc :skill-state
                               {:target-x nil :target-y nil :target-z nil :countdown 0.0})
          (let [hx (int (:x hit)) hy (int (:y hit)) hz (int (:z hit))
                prev-x (get-in ctx-data [:skill-state :target-x])
                prev-y (get-in ctx-data [:skill-state :target-y])
                prev-z (get-in ctx-data [:skill-state :target-z])
                same-target? (and (= hx prev-x) (= hy prev-y) (= hz prev-z))
                hardness (double (or (bm/get-block-hardness bm/*block-manipulation*
                                                             world-id hx hy hz)
                                     1.0))
                countdown-delta (/ (double break-speed) (max 0.1 hardness))
                prev-countdown (if same-target?
                                 (double (or (get-in ctx-data [:skill-state :countdown]) 0.0))
                                 0.0)
                new-countdown (+ prev-countdown countdown-delta)]
            (ctx/ctx-send-to-client! ctx-id :mine-ray/fx-progress
                                     {:x hx :y hy :z hz
                                      :progress (min 1.0 new-countdown)})
            (if (>= new-countdown 1.0)
              (when (bm/can-break-block? bm/*block-manipulation* player-id world-id hx hy hz)
                ;; Break with extra drops (fortune: break twice for luck effect)
                (bm/break-block! bm/*block-manipulation* player-id world-id hx hy hz true)
                ;; Fortune bonus: randomly drop an extra item stack (33% extra drop chance)
                (when (< (rand) 0.33)
                  (bm/break-block! bm/*block-manipulation* player-id world-id hx (- hy 999) hz true))
                (skill-effects/add-skill-exp! player-id :mine-ray-luck 0.002)
                (ctx/update-context! ctx-id assoc :skill-state
                                     {:target-x nil :target-y nil :target-z nil :countdown 0.0}))
              (ctx/update-context! ctx-id assoc :skill-state
                                   {:target-x  hx
                                    :target-y  hy
                                    :target-z  hz
                                    :countdown new-countdown}))))))))

;; ---------------------------------------------------------------------------
;; Actions
;; ---------------------------------------------------------------------------

(defn mine-ray-luck-down!
  [{:keys [ctx-id cost-ok?]}]
  (when cost-ok?
    (ctx/update-context! ctx-id assoc :skill-state
                         {:target-x nil :target-y nil :target-z nil :countdown 0.0})))

(defn mine-ray-luck-tick!
  [{:keys [player-id ctx-id]}]
  (try
    (let [exp (skill-exp player-id)]
      (mine-ray-luck-tick-impl!
        player-id ctx-id exp
        (bal/lerp 16.0 22.0 exp)
        (bal/lerp 0.5 1.0 exp)))
    (catch Exception e
      (log/warn "MineRayLuck tick! failed:" (ex-message e)))))

(defn mine-ray-luck-up!    [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id assoc :skill-state
                       {:target-x nil :target-y nil :target-z nil :countdown 0.0}))

(defn mine-ray-luck-abort! [{:keys [ctx-id]}]
  (ctx/update-context! ctx-id assoc :skill-state
                       {:target-x nil :target-y nil :target-z nil :countdown 0.0}))

;; ---------------------------------------------------------------------------
;; Skill registration
;; ---------------------------------------------------------------------------

(defskill! mine-ray-luck
  :id             :mine-ray-luck
  :category-id    :meltdowner
  :name-key       "ability.skill.meltdowner.mine_ray_luck"
  :description-key "ability.skill.meltdowner.mine_ray_luck.desc"
  :icon           "textures/abilities/meltdowner/skills/mine_ray_luck.png"
  :ui-position    [196 200]
  :level          5
  :controllable?  true
  :ctrl-id        :mine-ray-luck
  :cp-consume-speed 0.0
  :overload-consume-speed 0.0
  :pattern        :hold-channel
  :cost           {:down {:overload (fn [{:keys [player-id]}]
                                      (bal/lerp 100.0 70.0 (skill-exp player-id)))}
                   :tick {:cp (fn [{:keys [player-id]}]
                                (bal/lerp 22.0 15.0 (skill-exp player-id)))}}
  :cooldown-ticks 5
  :actions        {:down!  mine-ray-luck-down!
                   :tick!  mine-ray-luck-tick!
                   :up!    mine-ray-luck-up!
                   :abort! mine-ray-luck-abort!}
  :fx             {:start  {:topic :mine-ray/fx-start  :payload (fn [_] {:variant :luck})}
                   :end    {:topic :mine-ray/fx-end    :payload (fn [_] {})}}
  :prerequisites  [{:skill-id :mine-ray-expert :min-exp 1.0}])
