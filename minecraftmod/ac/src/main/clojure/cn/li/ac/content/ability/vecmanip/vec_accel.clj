(ns cn.li.ac.content.ability.vecmanip.vec-accel
  "VecAccel - dash/acceleration in look direction.

  Pattern: :hold-charge-release (max 20 ticks)
  Cost on up: CP lerp(120,80), overload lerp(30,15) by exp
  Cooldown: lerp(80,50) ticks (manual)
  Exp: +0.002 per use"
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.balance :as bal]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.player-motion :as player-motion]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.platform.teleportation :as teleportation]
            [cn.li.mcmod.util.log :as log]))

(def ^:private MAX-VELOCITY 2.5)
(def ^:private MAX-CHARGE 20)

(defn- calculate-speed [charge-ticks]
  (let [prog (bal/lerp 0.4 1.0 (bal/clamp01 (/ (double charge-ticks) MAX-CHARGE)))]
    (* (Math/sin prog) MAX-VELOCITY)))

(defn- get-player-position [player-id]
  (or (when-let [tp teleportation/*teleportation*]
        (teleportation/get-player-position tp player-id))
      (get (ps/get-player-state player-id)
           :position {:world-id "minecraft:overworld" :x 0.0 :y 64.0 :z 0.0})))

(defn- check-ground-raycast [player-id]
  (when raycast/*raycast*
    (let [pos (get-player-position player-id)
          world-id (or (:world-id pos) "minecraft:overworld")]
      (some? (raycast/raycast-blocks raycast/*raycast*
                                     world-id
                                     (double (:x pos)) (double (:y pos)) (double (:z pos))
                                     0 -1 0 2.0)))))

(defn- compute-init-vel [look-dir charge-ticks]
  (let [look-x   (double (:x look-dir))
        look-y   (double (:y look-dir))
        look-z   (double (:z look-dir))
        horiz-len (Math/sqrt (+ (* look-x look-x) (* look-z look-z)))
        safe-h    (max 1.0e-8 horiz-len)
        cur-pitch (Math/atan2 (- look-y) safe-h)
        new-pitch (+ cur-pitch -0.174533)
        cos-p     (Math/cos new-pitch)
        sin-p     (Math/sin new-pitch)
        hx        (/ look-x safe-h)
        hz        (/ look-z safe-h)
        speed     (calculate-speed charge-ticks)]
    {:x (* cos-p hx speed)
     :y (- (* sin-p speed))
     :z (* cos-p hz speed)}))

(defskill! vec-accel
  :id          :vec-accel
  :category-id :vecmanip
  :name-key    "ability.skill.vecmanip.vec_accel"
  :description-key "ability.skill.vecmanip.vec_accel.desc"
  :icon        "textures/abilities/vecmanip/skills/vec_accel.png"
  :ui-position [76 40]
  :level       2
  :controllable? false
  :ctrl-id     :vec-accel
  :pattern     :hold-charge-release
  :cooldown    {:mode :manual}
  :state       {:max-charge MAX-CHARGE}
  :cost        {:up {:cp       (fn [{:keys [exp]}] (bal/lerp 120.0 80.0 (bal/clamp01 exp)))
                     :overload (fn [{:keys [exp]}] (bal/lerp 30.0  15.0 (bal/clamp01 exp)))}}
  :actions
  {:tick!    (fn [{:keys [player-id ctx-id charge-ticks exp]}]
               (let [can-perform? (or (>= (double exp) 0.5) (check-ground-raycast player-id))
                     look-dir     (when raycast/*raycast*
                                    (raycast/get-player-look-vector raycast/*raycast* player-id))
                     init-vel     (when look-dir (compute-init-vel look-dir (long (or charge-ticks 0))))]
                 (ctx/update-context! ctx-id update :skill-state merge
                                      {:can-perform? can-perform?
                                       :look-dir     look-dir
                                       :init-vel     init-vel
                                       :performed?   false})))
   :perform! (fn [{:keys [player-id ctx-id exp charge-ticks cost-ok?]}]
               (when-let [ctx-data (ctx/get-context ctx-id)]
                 (let [ss           (:skill-state ctx-data)
                       can-perform? (boolean (:can-perform? ss))
                       charge       (long (or charge-ticks (:charge-ticks ss) 0))]
                   (if (and can-perform? cost-ok?)
                     (let [look-dir (or (:look-dir ss)
                                        (when raycast/*raycast*
                                          (raycast/get-player-look-vector raycast/*raycast* player-id)))]
                       (if look-dir
                         (let [{:keys [x y z]} (compute-init-vel look-dir charge)]
                           (when player-motion/*player-motion*
                             (player-motion/set-velocity! player-motion/*player-motion* player-id x y z))
                           (when teleportation/*teleportation*
                             (teleportation/reset-fall-damage! teleportation/*teleportation* player-id))
                           (skill-effects/set-main-cooldown! player-id :vec-accel
                                                             (int (bal/lerp 80.0 50.0 (bal/clamp01 exp))))
                           (skill-effects/add-skill-exp! player-id :vec-accel 0.002)
                           (ctx/update-context! ctx-id update :skill-state merge
                                                {:performed? true :final-vel {:x x :y y :z z}})
                           (log/debug "VecAccel launched" x y z))
                         (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] false)))
                     (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] false)))))
   :abort!   (fn [{:keys [ctx-id]}]
               (ctx/update-context! ctx-id assoc-in [:skill-state :performed?] false))}
  :fx {:start   {:topic :vec-accel/fx-start   :payload (fn [_] {})}
       :update  {:topic :vec-accel/fx-update
                 :payload (fn [{:keys [ctx-id charge-ticks]}]
                            (let [st (:skill-state (ctx/get-context ctx-id))]
                              {:charge-ticks (long (max 0 (or charge-ticks 0)))
                               :can-perform? (boolean (:can-perform? st))
                               :look-dir     (or (:look-dir st) {:x 0.0 :y 0.0 :z 1.0})
                               :init-vel     (or (:init-vel st) {:x 0.0 :y 0.0 :z 1.0})}))}
       :perform {:topic :vec-accel/fx-perform  :payload (fn [_] {})}
       :end     {:topic :vec-accel/fx-end
                 :payload (fn [{:keys [ctx-id]}]
                            {:performed? (boolean (:performed? (ctx/get-context ctx-id)))})}}
  :prerequisites [{:skill-id :directed-shock :min-exp 0.0}])
