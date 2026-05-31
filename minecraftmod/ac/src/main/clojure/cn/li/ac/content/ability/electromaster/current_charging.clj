(ns cn.li.ac.content.ability.electromaster.current-charging
  "CurrentCharging - channel energy into blocks or held item.

  Pattern: :hold-channel
  Cost: overload lerp(65,48) on down; CP lerp(3,7)/tick while charging
  Exp: +0.0001 effective / +0.00003 ineffective per tick"
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.runtime-interop :as interop]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(def ^:private current-charging-skill-id :current-charging)
(def ^:private arc-entity-id "my_mod:entity_arc")

(defn- cfg-double [field-id]
  (skill-config/tunable-double current-charging-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double current-charging-skill-id field-id exp))

(defn- targeting-range []
  (cfg-double :targeting.range))

(defn- main-hand-item [player-id]
  (when interop/*runtime-interop*
    (interop/get-player-main-hand-item interop/*runtime-interop* player-id)))

(defn- fx-payload
  [player-id payload]
  (cond-> (or payload {})
    (some? player-id) (assoc :source-player-id player-id)))

(defn- next-charge-ticks!
  [ctx-id]
  (let [ctx-data (or (ctx/get-context ctx-id) {})
        current (long (or (get-in ctx-data [:skill-state :charge-ticks]) 0))
        next (inc current)]
    (ctx/update-context! ctx-id assoc-in [:skill-state :charge-ticks] next)
    next))

(defn- end-and-terminate!
  [ctx-id is-item player-id]
  (ctx/ctx-send-to-client! ctx-id :current-charging/fx-end
                           (fx-payload player-id {:is-item (boolean is-item)}))
  (ctx/update-context! ctx-id dissoc :skill-state)
  (ctx/terminate-context! ctx-id nil))

(defn- charge-item-tick!
  [{:keys [player-id ctx-id exp charge charge-ticks]}]
  (let [stack (main-hand-item player-id)]
    (if (nil? stack)
      (end-and-terminate! ctx-id true player-id)
      (let [effective? (energy/is-energy-item-supported? stack)]
        (when effective?
          (energy/charge-energy-to-item stack charge false))
        (skill-effects/add-skill-exp! player-id :current-charging
                                      (if effective?
                                        (cfg-double :progression.exp-effective)
                                        (cfg-double :progression.exp-ineffective)))
        (ctx/update-context! ctx-id assoc-in [:skill-state :good?] (boolean effective?))
        (ctx/ctx-send-to-client! ctx-id :current-charging/fx-update
               (fx-payload player-id
                     {:is-item true
                      :good? (boolean effective?)
                      :charge-ticks charge-ticks
                      :exp exp}))))))

(defn- charge-block-target!
  "Raycast for a block from player view and charge any energy receiver/node.
  Returns {:effective? bool :charged double :block-pos [x y z] :ray-end {xyz}}."
  [view charge]
  (let [world-id (or (:world-id view) "minecraft:overworld")
        hit      (when raycast/*raycast*
                   (raycast/raycast-blocks raycast/*raycast*
                                           world-id
                                           (double (:x view)) (double (:y view)) (double (:z view))
                                           (double (:look-x view)) (double (:look-y view)) (double (:look-z view))
                                           (targeting-range)))
        dist     (when (and hit (number? (:distance hit))) (double (:distance hit)))
        ray-end  {:x (+ (:x view) (* (:look-x view) (or dist (targeting-range))))
                  :y (+ (:y view) (* (:look-y view) (or dist (targeting-range))))
                  :z (+ (:z view) (* (:look-z view) (or dist (targeting-range))))}]
    (if-not hit
      {:effective? false :charged 0.0 :block-pos nil :ray-end ray-end}
      (let [bx (int (:x hit)) by (int (:y hit)) bz (int (:z hit))
            be (interop/get-block-entity-at interop/*runtime-interop* world-id bx by bz)]
        (if-not be
          {:effective? false :charged 0.0 :block-pos [bx by bz] :ray-end ray-end}
          (cond
            (energy/is-node-supported? be)
            {:effective? true
             :charged    (max 0.0 (- (double charge) (double (energy/charge-node be charge true))))
             :block-pos  [bx by bz] :ray-end ray-end}
            (energy/is-receiver-supported? be)
            {:effective? true
             :charged    (max 0.0 (- (double charge) (double (energy/charge-receiver be charge))))
             :block-pos  [bx by bz] :ray-end ray-end}
            :else {:effective? false :charged 0.0 :block-pos [bx by bz] :ray-end ray-end}))))))

(defn- charge-block-tick!
  [{:keys [player-id ctx-id player charge charge-ticks]}]
  (let [view   (when interop/*runtime-interop*
                 (interop/get-player-view interop/*runtime-interop* player-id))
        result (when view (charge-block-target! view charge))
        {:keys [effective? charged block-pos ray-end]}
        (or result {:effective? false :charged 0.0 :block-pos nil :ray-end nil})]
    (skill-effects/add-skill-exp! player-id :current-charging
                                  (if effective?
                                    (cfg-double :progression.exp-effective)
                                    (cfg-double :progression.exp-ineffective)))
    (when (and player
               effective?
               (zero? (mod (long (or charge-ticks 0)) 6)))
      (entity/player-spawn-entity-by-id! player arc-entity-id 0.0))
    (ctx/update-context! ctx-id assoc-in [:skill-state :good?] (boolean effective?))
    (ctx/update-context! ctx-id assoc-in [:skill-state :target] ray-end)
    (ctx/update-context! ctx-id assoc-in [:skill-state :block-pos] block-pos)
    (ctx/update-context! ctx-id assoc-in [:skill-state :charged] (double charged))
    (ctx/ctx-send-to-client! ctx-id :current-charging/fx-update
                 (fx-payload player-id
                       {:is-item false
                        :good? (boolean effective?)
                        :charged (double charged)
                        :charge-ticks charge-ticks
                        :target ray-end
                        :block-pos block-pos}))))

  (declare current_charging_skill)

(defskill current_charging_skill
  :id          :current-charging
  :category-id :electromaster
  :name-key    "ability.skill.electromaster.current_charging"
  :description-key "ability.skill.electromaster.current_charging.desc"
  :icon        "textures/abilities/electromaster/skills/charging.png"
  :ui-position [55 18]
  :level       2
  :controllable? true
  :ctrl-id     :current-charging
  :pattern     :hold-channel
  :cooldown    {:mode :manual}
  :cost        {:down {:overload (fn [{:keys [exp]}]
                                  (cfg-lerp :cost.down.overload (double (or exp 0.0))))}
                :tick {:cp (fn [{:keys [player-id ctx-id exp]}]
                             (let [state (:skill-state (ctx/get-context ctx-id))]
                               (if (and (:is-item state) (nil? (main-hand-item player-id)))
                                 0.0
                                 (cfg-lerp :cost.tick.cp (double (or (:exp state) exp 0.0))))))}}
  :translations {:en_us {"ability.skill.electromaster.current_charging" "Current Charging"
                         "ability.skill.electromaster.current_charging.desc"
                         "Channel electricity into energy blocks or held energy items while holding."}
                 :zh_cn {"ability.skill.electromaster.current_charging" "电流充能"
                         "ability.skill.electromaster.current_charging.desc"
         "按住持续引导电流，为目标能量方块或手持能量物品充能。"}}
  :actions
  {:cost-fail! (fn [{:keys [ctx-id]}]
                 (let [skill-state (:skill-state (or (ctx/get-context ctx-id) {}))
               is-item (boolean (:is-item skill-state))
               player-id (:player-id (or (ctx/get-context ctx-id) {}))]
             (end-and-terminate! ctx-id is-item player-id)))
   :down!  (fn [{:keys [player-id ctx-id exp]}]
             (let [is-item (boolean (main-hand-item player-id))
                   exp* (double (or exp 0.0))
                   overload-floor (cfg-lerp :cost.down.overload exp*)]
               (ctx/update-context! ctx-id assoc :skill-state
                                    {:mode (if is-item :item :block)
                                     :is-item is-item
                                     :good? false
                                     :exp exp*
                                     :charge-ticks 0
                                     :overload-floor overload-floor
                                     :target nil
                                     :block-pos nil
                                     :charged 0.0})
               (ctx/ctx-send-to-client! ctx-id :current-charging/fx-start
                                        (fx-payload player-id {:is-item is-item}))))
   :tick!  (fn [{:keys [player-id ctx-id player]}]
             (when-let [skill-state (:skill-state (ctx/get-context ctx-id))]
               (let [is-item (boolean (:is-item skill-state))
                     exp (double (or (:exp skill-state) 0.0))
                     charge (Math/floor (cfg-lerp :effect.charge-amount exp))
                     charge-ticks (next-charge-ticks! ctx-id)
                     overload-floor (double (or (:overload-floor skill-state) 0.0))]
                 (skill-effects/enforce-overload-floor! player-id overload-floor)
                 (when (zero? (mod (long (or charge-ticks 0)) 20))
                   (log/debug "CurrentCharging tick"))
                 (if is-item
                   (charge-item-tick! {:player-id player-id
                                       :ctx-id ctx-id
                                       :exp exp
                                       :charge charge
                                       :charge-ticks charge-ticks})
                   (charge-block-tick! {:player-id player-id
                                        :ctx-id ctx-id
                                        :player player
                                        :charge charge
                                        :charge-ticks charge-ticks})))))
   :up!    (fn [{:keys [ctx-id]}]
             (when-let [{:keys [skill-state player-id]} (ctx/get-context ctx-id)]
               (ctx/ctx-send-to-client! ctx-id :current-charging/fx-end
                                        (fx-payload player-id
                                                    {:is-item (boolean (:is-item skill-state))}))
               (ctx/update-context! ctx-id dissoc :skill-state)))
   :abort! (fn [{:keys [ctx-id]}]
             (when-let [{:keys [skill-state player-id]} (ctx/get-context ctx-id)]
               (ctx/ctx-send-to-client! ctx-id :current-charging/fx-end
                                        (fx-payload player-id
                                                    {:is-item (boolean (:is-item skill-state))})))
             (ctx/update-context! ctx-id dissoc :skill-state))}
  :prerequisites [{:skill-id :arc-gen :min-exp 0.3}])
