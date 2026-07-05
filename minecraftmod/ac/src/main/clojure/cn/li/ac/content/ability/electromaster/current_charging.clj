(ns cn.li.ac.content.ability.electromaster.current-charging
  "CurrentCharging - channel energy into blocks or held item.

  Pattern: :hold-channel
  Cost: overload lerp(65,48) on down; CP lerp(3,7)/tick while charging
  Exp: +0.0001 effective / +0.00003 ineffective per tick"
  (:require [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
                        [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.energy.operations :as energy]
                        [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.platform.runtime-interop :as interop]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(def ^:private current-charging-skill-id :current-charging)
(def ^:private arc-entity-id "my_mod:entity_arc")
(def ^:private charging-arc-entity-id "my_mod:entity_charging_arc")
(def ^:private surround-arc-entity-id "my_mod:entity_surround_arc")
(def ^:private surround-arc-thin-entity-id "my_mod:entity_surround_arc_thin")

(defn- cfg-double [field-id]
  (skill-config/tunable-double current-charging-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double current-charging-skill-id field-id exp))

(defn- targeting-range []
  (cfg-double :targeting.range))

(defn- main-hand-item [player-id]
  (when (interop/available?)
    (interop/get-player-main-hand-item* player-id)))

(defn- fx-payload
  [player-id payload]
  (cond-> (or payload {})
    (some? player-id) (assoc :source-player-id player-id)))

(defn- set-skill-state!
  [ctx-id k v]
  (ctx-skill/assoc-skill-state! ctx-id k v))

(defn- set-skill-state-root!
  [ctx-id state-map]
  (ctx-skill/update-skill-state-root! ctx-id identity state-map))

(defn- clear-skill-state!
  [ctx-id]
  (ctx-skill/clear-skill-state! ctx-id))

(defn- next-charge-ticks!
  [ctx-id]
  (let [current (long (or (get-in (ctx-skill/get-context ctx-id) [:skill-state :charge-ticks]) 0))
        next (inc current)]
    (set-skill-state! ctx-id [:charge-ticks] next)
    next))

(defn- end-and-terminate!
  [ctx-id is-item player-id]
  (fx/send! ctx-id {:topic :current-charging/fx-end :mode :end} nil
            (fx-payload player-id {:is-item (boolean is-item)}))
  (clear-skill-state! ctx-id)
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
        (set-skill-state! ctx-id [:good?] (boolean effective?))
        (fx/send! ctx-id {:topic :current-charging/fx-update :mode :update} nil
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
        hit      (when (raycast/available?)
                   (raycast/raycast-blocks*
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
            be (interop/get-block-entity-at* world-id bx by bz)]
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
  (let [view   (when (interop/available?)
                 (interop/get-player-view* player-id))
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
      (entity/player-spawn-entity-by-id! player charging-arc-entity-id 0.0))
    (set-skill-state! ctx-id [:good?] (boolean effective?))
    (set-skill-state! ctx-id [:target] ray-end)
    (set-skill-state! ctx-id [:block-pos] block-pos)
    (set-skill-state! ctx-id [:charged] (double charged))
    (fx/send! ctx-id {:topic :current-charging/fx-update :mode :update} nil
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
  :level       1  ;; matching original Skill("charging", 1)
  :controllable? true
  :ctrl-id     :current-charging
  :pattern     :hold-channel
  :cooldown    {:mode :manual}
  :cost        {:down {:overload (fn [{:keys [exp]}]
                                  (cfg-lerp :cost.down.overload (double (or exp 0.0))))}
                :tick {:cp (fn [{:keys [player-id ctx-id exp]}]
                             (let [state (:skill-state (ctx-skill/get-context ctx-id))]
                               (if (and (:is-item state) (nil? (main-hand-item player-id)))
                                 0.0
                                 (cfg-lerp :cost.tick.cp (double (or (:exp state) exp 0.0))))))}}
  :translations {:en_us {"ability.skill.electromaster.current_charging" "Current Charging"
                         "ability.skill.electromaster.current_charging.desc"
                         "Channel electricity into energy blocks or held energy items while holding."}
                 :zh_cn {"ability.skill.electromaster.current_charging" "电流充能"
                         "ability.skill.electromaster.current_charging.desc"
         "按住持续引导电流，为目标能量方块或手持能量物品充能。"}
                 :zh_tw {"ability.skill.electromaster.current_charging" "電流充能"
                         "ability.skill.electromaster.current_charging.desc"
         "按住持續引導電流，為目標能量方塊或手持能量物品充能。"}
                 :ja_jp {"ability.skill.electromaster.current_charging" "電流充能"
                         "ability.skill.electromaster.current_charging.desc"
         "ホールドして電流を誘導し、対象のエネルギーブロックまたは手持ちのエネルギーアイテムを充能します。"}
                 :ko_kr {"ability.skill.electromaster.current_charging" "전류 충전"
                         "ability.skill.electromaster.current_charging.desc"
         "홀드하여 전류를 유도하고 대상 에너지 블록 또는 손에 든 에너지 아이템을 충전합니다."}
                 :ru_ru {"ability.skill.electromaster.current_charging" "Текущая зарядка"
                         "ability.skill.electromaster.current_charging.desc"
         "Удерживайте для направления тока, заряжая целевые энергоблоки или удерживаемые энергопредметы."}}
  :actions
  {:cost-fail! (fn [{:keys [ctx-id]}]
                 (let [skill-state (:skill-state (or (ctx-skill/get-context ctx-id) {}))
               is-item (boolean (:is-item skill-state))
               player-id (:player-id (or (ctx-skill/get-context ctx-id) {}))]
             (end-and-terminate! ctx-id is-item player-id)))
   :down!  (fn [{:keys [player-id ctx-id exp player]}]
             (let [is-item (boolean (main-hand-item player-id))
                   exp* (double (or exp 0.0))
                   overload-floor (cfg-lerp :cost.down.overload exp*)]
           (set-skill-state-root! ctx-id
                      {:mode (if is-item :item :block)
                       :is-item is-item
                       :good? false
                       :exp exp*
                       :charge-ticks 0
                       :overload-floor overload-floor
                       :target nil
                       :block-pos nil
                       :charged 0.0})
               ;; Spawn surround arc entity (matching original EntitySurroundArc)
               ;; NORMAL (3 rings) for block mode, THIN (1 ring) for item mode
               (when player
                 (entity/player-spawn-entity-by-id!
                   player
                   (if is-item "my_mod:entity_surround_arc_thin" surround-arc-entity-id)
                   0.0))
               (fx/send! ctx-id {:topic :current-charging/fx-start :mode :start} nil
                         (fx-payload player-id {:is-item is-item}))))
   :tick!  (fn [{:keys [player-id ctx-id player]}]
             (when-let [skill-state (:skill-state (ctx-skill/get-context ctx-id))]
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
             (when-let [{:keys [skill-state player-id]} (ctx-skill/get-context ctx-id)]
               (fx/send! ctx-id {:topic :current-charging/fx-end :mode :end} nil
                         (fx-payload player-id
                                     {:is-item (boolean (:is-item skill-state))}))
               (clear-skill-state! ctx-id)))
   :abort! (fn [{:keys [ctx-id]}]
             (when-let [{:keys [skill-state player-id]} (ctx-skill/get-context ctx-id)]
               (fx/send! ctx-id {:topic :current-charging/fx-end :mode :end} nil
                         (fx-payload player-id
                                     {:is-item (boolean (:is-item skill-state))})))
             (clear-skill-state! ctx-id))}
  :prerequisites [{:skill-id :arc-gen :min-exp 0.3}])
