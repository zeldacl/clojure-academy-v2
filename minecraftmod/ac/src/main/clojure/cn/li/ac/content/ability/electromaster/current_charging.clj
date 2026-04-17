(ns cn.li.ac.content.ability.electromaster.current-charging
  "CurrentCharging - channel energy into blocks or held item.

  Pattern: :charge-window
  Cost: overload lerp(65,48) on down; CP lerp(3,7)/tick while charging
  Exp: +0.0001 effective / +0.00003 ineffective per tick"
  (:require [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.balance :as bal]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.effect :as effect]
            [cn.li.ac.ability.effect.state]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.platform.ability-interop :as interop]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(def ^:private max-distance 15.0)

(defn- main-hand-item [player-id]
  (when interop/*ability-interop*
    (interop/get-player-main-hand-item interop/*ability-interop* player-id)))

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
                                           max-distance))
        dist     (when (and hit (number? (:distance hit))) (double (:distance hit)))
        ray-end  {:x (+ (:x view) (* (:look-x view) (or dist max-distance)))
                  :y (+ (:y view) (* (:look-y view) (or dist max-distance)))
                  :z (+ (:z view) (* (:look-z view) (or dist max-distance)))}]
    (if-not hit
      {:effective? false :charged 0.0 :block-pos nil :ray-end ray-end}
      (let [bx (int (:x hit)) by (int (:y hit)) bz (int (:z hit))
            be (interop/get-block-entity-at interop/*ability-interop* world-id bx by bz)]
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

(defskill! current-charging
  :id          :current-charging
  :category-id :electromaster
  :name-key    "ability.skill.electromaster.current_charging"
  :description-key "ability.skill.electromaster.current_charging.desc"
  :icon        "textures/abilities/electromaster/skills/charging.png"
  :ui-position [55 18]
  :level       2
  :controllable? true
  :ctrl-id     :current-charging
  :pattern     :charge-window
  :cooldown    {:mode :manual}
  :cost        {:down {:overload (bal/by-exp 65.0 48.0)}
                :tick {:cp (fn [{:keys [player-id ctx-id exp]}]
                             (let [state (:skill-state (ctx/get-context ctx-id))]
                               (if (and (:is-item state) (nil? (main-hand-item player-id)))
                                 0.0
                                 (bal/lerp 3.0 7.0 (double (or (:exp state) exp 0.0))))))}}
  :actions
  {:down!  (fn [{:keys [player-id ctx-id exp cost-ok?]}]
             (let [is-item        (boolean (main-hand-item player-id))
                   overload-floor (bal/lerp 65.0 48.0 (double exp))]
               (if-not cost-ok?
                 (do (ctx/ctx-send-to-client! ctx-id :current-charging/fx-end {:is-item is-item})
                     (ctx/terminate-context! ctx-id nil))
                 (do (ctx/update-context! ctx-id assoc :skill-state
                                          {:is-item is-item :exp exp
                                           :overload-floor overload-floor :charge-ticks 0})
                     (ctx/ctx-send-to-client! ctx-id :current-charging/fx-start {:is-item is-item})))))
   :tick!  (fn [{:keys [player-id ctx-id cost-ok?]}]
             (when-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
               (let [is-item        (boolean (:is-item skill-state))
                     exp            (double (or (:exp skill-state) 0.0))
                     charge         (Math/floor (bal/lerp 15.0 35.0 exp))
                     overload-floor (double (or (:overload-floor skill-state) 0.0))
                     base-evt       {:player-id player-id :ctx-id ctx-id}
                     {:keys [charge-ticks]} (effect/run-op! base-evt [:charge-tick {}])]
                 (effect/run-op! base-evt [:overload-floor {:floor overload-floor}])
                 (when (zero? (mod (long (or charge-ticks 0)) 20))
                   (log/debug "CurrentCharging tick"))
                 (if is-item
                   (let [stack (main-hand-item player-id)]
                     (if (or (nil? stack) (not cost-ok?))
                       (do (ctx/ctx-send-to-client! ctx-id :current-charging/fx-end {:is-item true})
                           (ctx/terminate-context! ctx-id nil))
                       (let [effective? (energy/is-energy-item-supported? stack)]
                         (when effective? (energy/charge-energy-to-item stack charge false))
                         (skill-effects/add-skill-exp! player-id :current-charging
                                                       (if effective? 0.0001 0.00003))
                         (ctx/ctx-send-to-client! ctx-id :current-charging/fx-update
                                                  {:is-item true :good? (boolean effective?)
                                                   :charge-ticks charge-ticks}))))
                   (let [view   (when interop/*ability-interop*
                                  (interop/get-player-view interop/*ability-interop* player-id))
                         result (when view (charge-block-target! view charge))
                         {:keys [effective? charged block-pos ray-end]}
                         (or result {:effective? false :charged 0.0 :block-pos nil :ray-end nil})]
                     (if-not cost-ok?
                       (do (ctx/ctx-send-to-client! ctx-id :current-charging/fx-end {:is-item false})
                           (ctx/terminate-context! ctx-id nil))
                       (do (skill-effects/add-skill-exp! player-id :current-charging
                                                         (if effective? 0.0001 0.00003))
                           (ctx/ctx-send-to-client! ctx-id :current-charging/fx-update
                                                    {:is-item false :good? (boolean effective?)
                                                     :charged (double charged)
                                                     :charge-ticks charge-ticks
                                                     :target ray-end :block-pos block-pos}))))))))
   :up!    (fn [{:keys [ctx-id]}]
             (when-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
               (ctx/ctx-send-to-client! ctx-id :current-charging/fx-end
                                        {:is-item (boolean (:is-item skill-state))})))
   :abort! (fn [{:keys [ctx-id]}]
             (when-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
               (ctx/ctx-send-to-client! ctx-id :current-charging/fx-end
                                        {:is-item (boolean (:is-item skill-state))}))
             (ctx/update-context! ctx-id dissoc :skill-state))}
  :prerequisites [{:skill-id :arc-gen :min-exp 0.3}])
