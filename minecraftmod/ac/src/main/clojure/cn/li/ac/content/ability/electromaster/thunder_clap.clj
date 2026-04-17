(ns cn.li.ac.content.ability.electromaster.thunder-clap
  "ThunderClap - channeled AOE lightning strike.

  Pattern: :charge-window (40..60 ticks)
  Start overload: lerp(390,252)
  Tick CP: lerp(18,25) while ticks <= 40
  Damage: lerp(36,72,exp) * lerp(1.0,1.2,extra-ratio)
  AOE radius: lerp(15,30,exp) with distance falloff
  Cooldown: ticks * lerp(10,6,exp)
  Exp: 0.003 per use"
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.balance :as bal]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.world-effects :as world-effects]
            [cn.li.mcmod.platform.entity-damage :as entity-damage]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(def ^:private min-ticks 40)
(def ^:private max-ticks 60)
(def ^:private eye-height 1.62)
(def ^:private target-distance 40.0)

(defn- player-world-id [player-id]
  (or (get-in (ps/get-player-state player-id) [:position :world-id])
      "minecraft:overworld"))

(defn- skill-exp [player-id]
  (double (get-in (ps/get-player-state player-id) [:ability-data :skills :thunder-clap :exp] 0.0)))

(defn- resolve-hit-pos [player-id world-id]
  (let [pstate (ps/get-player-state player-id)
        pos    (get pstate :position {:x 0.0 :y 64.0 :z 0.0})
        ex     (double (:x pos))
        ey     (+ (double (:y pos)) eye-height)
        ez     (double (:z pos))
        look   (when raycast/*raycast* (raycast/get-player-look-vector raycast/*raycast* player-id))
        dx     (double (or (:x look) 0.0))
        dy     (double (or (:y look) 0.0))
        dz     (double (or (:z look) 1.0))
        hit    (when (and raycast/*raycast* look)
                 (raycast/raycast-combined raycast/*raycast* world-id ex ey ez dx dy dz target-distance))
        dist   (double (or (:distance hit) target-distance))]
    (if (= (:hit-type hit) :entity)
      {:x (+ ex (* dx dist)) :y (+ ey (* dy dist) eye-height) :z (+ ez (* dz dist))}
      {:x (+ ex (* dx dist)) :y (+ ey (* dy dist))             :z (+ ez (* dz dist))})))

(defn- execute-thunder-clap! [player-id ctx-id ticks hit-pos exp]
  (let [world-id  (player-world-id player-id)
        aoe-range (bal/lerp 15.0 30.0 exp)
        base-dmg  (bal/lerp 36.0 72.0 exp)
        mult      (bal/lerp 1.0 1.2 (/ (- (double ticks) 40.0) 60.0))
        dmg-max   (* base-dmg mult)
        cooldown  (max 1 (int (* (double ticks) (bal/lerp 10.0 6.0 exp))))
        {:keys [x y z]} hit-pos
        cx (double x) cy (double y) cz (double z)]
    (when world-effects/*world-effects*
      (world-effects/spawn-lightning! world-effects/*world-effects* world-id x y z)
      (when entity-damage/*entity-damage*
        (doseq [{:keys [uuid x y z]}
                (world-effects/find-entities-in-radius world-effects/*world-effects* world-id x y z aoe-range)]
          (when (not= uuid player-id)
            (let [dx (- (double x) cx) dy (- (double y) cy) dz (- (double z) cz)
                  dist   (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
                  factor (- 1.0 (bal/clamp01 (/ dist (max 1.0e-6 aoe-range))))
                  applied (* dmg-max factor)]
              (when (> applied 0.0)
                (entity-damage/apply-direct-damage! entity-damage/*entity-damage*
                                                    world-id uuid applied :lightning)))))))
    (skill-effects/set-main-cooldown! player-id :thunder-clap cooldown)
    (skill-effects/add-skill-exp! player-id :thunder-clap 0.003)
    (ctx/ctx-send-to-client! ctx-id :thunder-clap/fx-end {:performed? true})
    (ctx/update-context! ctx-id update :skill-state assoc :performed? true)
    (log/debug "ThunderClap executed ticks" ticks "dmg-max" (int dmg-max) "aoe" (format "%.1f" aoe-range))))

(defskill! thunder-clap
  :id          :thunder-clap
  :category-id :electromaster
  :name-key    "ability.skill.electromaster.thunder_clap"
  :description-key "ability.skill.electromaster.thunder_clap.desc"
  :icon        "textures/abilities/electromaster/skills/thunder_clap.png"
  :ui-position [204 80]
  :level       1
  :controllable? true
  :ctrl-id     :thunder-clap
  :pattern     :charge-window
  :cooldown    {:mode :manual}
  :cost        {:down {:overload (fn [{:keys [exp]}] (bal/lerp 390.0 252.0 (bal/clamp01 exp)))}
                :tick {:cp       (fn [{:keys [exp ctx-id]}]
                                   (if-let [ctx-data (ctx/get-context ctx-id)]
                                     (let [ticks (inc (long (or (get-in ctx-data [:skill-state :ticks]) 0)))]
                                       (if (<= ticks min-ticks)
                                         (bal/lerp 18.0 25.0 (bal/clamp01 exp))
                                         0.0))
                                     0.0))}}
  :actions
  {:down!  (fn [{:keys [player-id ctx-id cost-ok?]}]
             (if-not cost-ok?
               (ctx/terminate-context! ctx-id nil)
               (let [world-id (player-world-id player-id)
                     hit-pos  (resolve-hit-pos player-id world-id)]
                 (ctx/update-context! ctx-id assoc :skill-state
                                      {:ticks 0 :hit-pos hit-pos :performed? false})
                 (ctx/ctx-send-to-client! ctx-id :thunder-clap/fx-start {:mode :start})
                 (ctx/ctx-send-to-client! ctx-id :thunder-clap/fx-update
                                          {:ticks 0 :charge-ratio 0.0 :target hit-pos}))))
   :tick!  (fn [{:keys [player-id ctx-id cost-ok? exp]}]
             (when-let [ctx-data (ctx/get-context ctx-id)]
               (let [ss         (:skill-state ctx-data)
                     performed? (get ss :performed? false)]
                 (when-not performed?
                   (if-not cost-ok?
                     (do (ctx/ctx-send-to-client! ctx-id :thunder-clap/fx-end {:performed? false})
                         (ctx/terminate-context! ctx-id nil))
                     (let [world-id (player-world-id player-id)
                           ticks    (inc (long (or (:ticks ss) 0)))
                           hit-pos  (resolve-hit-pos player-id world-id)]
                       (ctx/update-context! ctx-id update :skill-state assoc
                                            :ticks ticks :hit-pos hit-pos)
                       (ctx/ctx-send-to-client! ctx-id :thunder-clap/fx-update
                                                {:ticks ticks
                                                 :charge-ratio (bal/clamp01 (/ ticks (double max-ticks)))
                                                 :target hit-pos})
                       (when (>= ticks max-ticks)
                         (execute-thunder-clap! player-id ctx-id ticks hit-pos (bal/clamp01 exp))
                         (ctx/terminate-context! ctx-id nil))))))))
   :up!    (fn [{:keys [player-id ctx-id exp]}]
             (when-let [ctx-data (ctx/get-context ctx-id)]
               (let [ss         (:skill-state ctx-data)
                     ticks      (long (or (:ticks ss) 0))
                     performed? (get ss :performed? false)]
                 (when-not performed?
                   (if (< ticks min-ticks)
                     (ctx/ctx-send-to-client! ctx-id :thunder-clap/fx-end {:performed? false})
                     (let [world-id (player-world-id player-id)
                           hit-pos  (or (:hit-pos ss) (resolve-hit-pos player-id world-id))]
                       (execute-thunder-clap! player-id ctx-id ticks hit-pos (bal/clamp01 exp))))))))
   :abort! (fn [{:keys [ctx-id]}]
             (ctx/ctx-send-to-client! ctx-id :thunder-clap/fx-end {:performed? false})
             (ctx/update-context! ctx-id dissoc :skill-state))}
  :prerequisites [{:skill-id :thunder-bolt :min-exp 1.0}])
