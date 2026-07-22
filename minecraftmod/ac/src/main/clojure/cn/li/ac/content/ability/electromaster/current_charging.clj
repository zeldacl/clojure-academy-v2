(ns cn.li.ac.content.ability.electromaster.current-charging
  "CurrentCharging - channel energy into blocks or held item.

  Pattern: :hold-channel
  Cost: overload lerp(65,48) on down; CP lerp(3,7)/tick while charging
  Exp: +0.0001 effective / +0.00003 ineffective per tick"
  (:require
            [cn.li.ac.config.modid :as modid] [cn.li.ac.ability.dsl :refer [defskill def-skill-config-ops]]
            [cn.li.ac.ability.fx :as fx]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-skill-state :as ctx-skill]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.platform.entity :as entity]
            [cn.li.mcmod.framework :as fw]
            [cn.li.mcmod.framework.platform :as platform]
            [cn.li.ac.ability.effects.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(def-skill-config-ops :current-charging)
(def ^:private current-charging-skill-id :current-charging)
(def ^:private surround-arc-entity-id (modid/namespaced-path "entity_surround_arc"))
(def ^:private charging-arc-entity-id (modid/namespaced-path "entity_charging_arc"))

(defn- targeting-range []
  (cfg-double :targeting.range))

(defn- main-hand-item [player-id]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter fw-atom :runtime-interop :get-player-main-hand-item player-id)))

(defn- player-view [player-id]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter fw-atom :runtime-interop :get-player-view player-id)))

(defn- block-entity-at [world-id x y z]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter fw-atom :runtime-interop :get-block-entity-at world-id x y z)))

(defn- player-entity [player-id]
  (when-let [fw-atom (fw/fw-atom)]
    (platform/call-adapter fw-atom :runtime-interop :get-player-entity player-id)))

(defn- fx-payload [player-id payload]
  (cond-> (or payload {})
    (some? player-id) (assoc :source-player-id player-id)))

(defn- set-skill-state! [ctx-id k v]
  (ctx-skill/assoc-skill-state! ctx-id k v))

(defn- next-charge-ticks! [ctx-id]
  (let [current (long (or (get-in (ctx-skill/get-context ctx-id) [:skill-state :charge-ticks]) 0))
        next (inc current)]
    (set-skill-state! ctx-id [:charge-ticks] next)
    next))

(defn- active-ctx-id [player-id skill-id]
  (some (fn [[ctx-id ctx-data]]
          (when (and (= (:player-uuid ctx-data) player-id)
                     (= skill-id (:skill-id ctx-data)))
            ctx-id))
        (ctx/get-all-contexts)))

(defn- down-overload-cost [_player-id _skill-id exp]
  (cfg-lerp :cost.down.overload (double (or exp 0.0))))

(defn- tick-cp-cost [player-id _skill-id exp]
  (if-let [ctx-id (active-ctx-id player-id current-charging-skill-id)]
    (let [state (:skill-state (ctx-skill/get-context ctx-id))]
      (if (and (:is-item state) (nil? (main-hand-item player-id)))
        0.0
        (cfg-lerp :cost.tick.cp (double (or (:exp state) exp 0.0)))))
    (cfg-lerp :cost.tick.cp (double (or exp 0.0)))))

(defn- end-and-terminate! [ctx-id is-item player-id]
  (fx/send! ctx-id {:topic :current-charging/fx-end :mode :end} nil
            (fx-payload player-id {:is-item (boolean is-item)}))
  (ctx-skill/clear-skill-state! ctx-id)
  (ctx/terminate-context! ctx-id nil))

(defn- charge-item-tick!
  [player-id ctx-id exp charge charge-ticks]
  (let [stack (main-hand-item player-id)]
    (if (nil? stack)
      (end-and-terminate! ctx-id true player-id)
      (let [effective? (energy/is-energy-item-supported? stack)]
        (when effective?
          (energy/charge-energy-to-item stack charge false))
        (skill-effects/add-skill-exp! player-id current-charging-skill-id
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
  "Raycast for a block from player view and charge any energy receiver/node."
  [view charge]
  (let [world-id (or (:world-id view) "minecraft:overworld")
        hit (when (raycast/available?)
              (raycast/raycast-blocks
               world-id
               (double (:x view)) (double (:y view)) (double (:z view))
               (double (:look-x view)) (double (:look-y view)) (double (:look-z view))
               (targeting-range)))
        dist (when (and hit (number? (:distance hit))) (double (:distance hit)))
        ray-end {:x (+ (:x view) (* (:look-x view) (or dist (targeting-range))))
                 :y (+ (:y view) (* (:look-y view) (or dist (targeting-range))))
                 :z (+ (:z view) (* (:look-z view) (or dist (targeting-range))))}]
    (if-not hit
      {:effective? false :charged 0.0 :block-pos nil :ray-end ray-end}
      (let [bx (int (:x hit)) by (int (:y hit)) bz (int (:z hit))
            be (block-entity-at world-id bx by bz)]
        (if-not be
          {:effective? false :charged 0.0 :block-pos [bx by bz] :ray-end ray-end}
          (cond
            (energy/is-node-supported? be)
            {:effective? true
             :charged (max 0.0 (- (double charge) (double (energy/charge-node be charge true))))
             :block-pos [bx by bz] :ray-end ray-end}

            (energy/is-receiver-supported? be)
            {:effective? true
             :charged (max 0.0 (- (double charge) (double (energy/charge-receiver be charge))))
             :block-pos [bx by bz] :ray-end ray-end}

            :else {:effective? false :charged 0.0 :block-pos [bx by bz] :ray-end ray-end}))))))

(defn- charge-block-tick!
  ;; player (the positional player-ref) is always nil here — server-tick-driven
  ;; contexts never populate it (see context-manager's tick-context-entry!) — so
  ;; the arc-spawn visual resolves its own player-entity server-side instead.
  [player-id ctx-id _player charge charge-ticks]
  (let [view (player-view player-id)
        result (when view (charge-block-target! view charge))
        {:keys [effective? charged block-pos ray-end]}
        (or result {:effective? false :charged 0.0 :block-pos nil :ray-end nil})]
    (skill-effects/add-skill-exp! player-id current-charging-skill-id
                                  (if effective?
                                    (cfg-double :progression.exp-effective)
                                    (cfg-double :progression.exp-ineffective)))
    (when (and effective? (zero? (mod (long charge-ticks) 6)))
      (when-let [player (player-entity player-id)]
        (entity/player-spawn-entity-by-id! player charging-arc-entity-id 0.0)))
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

(defn- current-charging-cost-fail!
  [ctx-id _player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (let [skill-state (:skill-state (or (ctx-skill/get-context ctx-id) {}))
        is-item (boolean (:is-item skill-state))
        player-id (:player-uuid (or (ctx-skill/get-context ctx-id) {}))]
    (end-and-terminate! ctx-id is-item player-id)))

(defn- current-charging-down!
  [ctx-id player-id _skill-id exp _cost-ok? _hold-ticks _cost-stage player]
  (let [is-item (boolean (main-hand-item player-id))
        exp* (double (or exp 0.0))
        overload-floor (cfg-lerp :cost.down.overload exp*)]
    (ctx-skill/replace-skill-state! ctx-id
                                    {:mode (if is-item :item :block)
                                     :is-item is-item
                                     :good? false
                                     :exp exp*
                                     :charge-ticks 0
                                     :overload-floor overload-floor
                                     :target nil
                                     :block-pos nil
                                     :charged 0.0})
    (when player
      (entity/player-spawn-entity-by-id!
       player
       (if is-item (modid/namespaced-path "entity_surround_arc_thin") surround-arc-entity-id)
       0.0))
    (fx/send! ctx-id {:topic :current-charging/fx-start :mode :start} nil
              (fx-payload player-id {:is-item is-item}))))

(defn- current-charging-tick!
  [ctx-id player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage player]
  (when-let [skill-state (:skill-state (ctx-skill/get-context ctx-id))]
    (let [is-item (boolean (:is-item skill-state))
          exp (double (or (:exp skill-state) 0.0))
          charge (Math/floor (cfg-lerp :effect.charge-amount exp))
          charge-ticks (next-charge-ticks! ctx-id)
          overload-floor (double (or (:overload-floor skill-state) 0.0))]
      (skill-effects/enforce-overload-floor! player-id overload-floor)
      (when (zero? (mod (long charge-ticks) 20))
        (log/debug "CurrentCharging tick"))
      (if is-item
        (charge-item-tick! player-id ctx-id exp charge charge-ticks)
        (charge-block-tick! player-id ctx-id player charge charge-ticks)))))

(defn- current-charging-up!
  [ctx-id _player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (when-let [{:keys [skill-state player-uuid]} (ctx-skill/get-context ctx-id)]
    (fx/send! ctx-id {:topic :current-charging/fx-end :mode :end} nil
              (fx-payload player-uuid {:is-item (boolean (:is-item skill-state))}))
    (ctx-skill/clear-skill-state! ctx-id)))

(defn- current-charging-abort!
  [ctx-id _player-id _skill-id _exp _cost-ok? _hold-ticks _cost-stage _player-ref]
  (when-let [{:keys [skill-state player-uuid]} (ctx-skill/get-context ctx-id)]
    (fx/send! ctx-id {:topic :current-charging/fx-end :mode :end} nil
              (fx-payload player-uuid {:is-item (boolean (:is-item skill-state))})))
  (ctx-skill/clear-skill-state! ctx-id))

(declare current_charging_skill)

(defskill current_charging_skill
  :id          :current-charging
  :category-id :electromaster
  :name-key    "ability.skill.electromaster.current_charging"
  :description-key "ability.skill.electromaster.current_charging.desc"
  :icon        "textures/abilities/electromaster/skills/charging.png"
  :ui-position [55 18]
  :ctrl-id     :current-charging
  :pattern     :hold-channel
  :cooldown    {:mode :manual}
  :cost        {:down {:overload down-overload-cost}
                :tick {:cp tick-cp-cost}}
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
  :actions {:cost-fail! current-charging-cost-fail!
            :down!      current-charging-down!
            :tick!      current-charging-tick!
            :up!        current-charging-up!
            :abort!     current-charging-abort!}
  :prerequisites [{:skill-id :arc-gen :min-exp 0.3}])
