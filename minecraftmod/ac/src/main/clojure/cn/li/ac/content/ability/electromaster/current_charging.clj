(ns cn.li.ac.content.ability.electromaster.current-charging
  "CurrentCharging - channel energy into blocks or held item.

  Pattern: :charge-window
  Cost: overload lerp(65,48) on down; CP lerp(3,7)/tick while charging
  Exp: +0.0001 effective / +0.00003 ineffective per tick"
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.balance :as bal]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.model.resource-data :as rdata]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.mcmod.platform.ability-interop :as interop]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(def ^:private max-distance 15.0)

(defn- charging-speed [exp] (Math/floor (bal/lerp 15.0 35.0 exp)))
(defn- tick-consumption [exp] (bal/lerp 3.0 7.0 exp))
(defn- initial-overload [exp] (bal/lerp 65.0 48.0 exp))

(defn- enforce-overload-floor! [player-id floor-value]
  (ps/update-resource-data!
   player-id
   (fn [res-data]
     (if (< (double (:cur-overload res-data)) (double floor-value))
       (-> res-data (rdata/set-cur-overload floor-value) (assoc :overload-fine true))
       res-data))))

(defn- get-main-hand-item [player-id]
  (when interop/*ability-interop*
    (interop/get-player-main-hand-item interop/*ability-interop* player-id)))

(defn- get-player-view [player-id]
  (when interop/*ability-interop*
    (interop/get-player-view interop/*ability-interop* player-id)))

(defn- get-block-entity-at [world-id x y z]
  (when interop/*ability-interop*
    (interop/get-block-entity-at interop/*ability-interop* world-id x y z)))

(defn- endpoint [view distance]
  {:x (+ (double (:x view)) (* (double (:look-x view)) distance))
   :y (+ (double (:y view)) (* (double (:look-y view)) distance))
   :z (+ (double (:z view)) (* (double (:look-z view)) distance))})

(defn- raycast-block [view]
  (when raycast/*raycast*
    (raycast/raycast-blocks raycast/*raycast*
                            (or (:world-id view) "minecraft:overworld")
                            (double (:x view)) (double (:y view)) (double (:z view))
                            (double (:look-x view)) (double (:look-y view)) (double (:look-z view))
                            max-distance)))

(defn- charge-block-target! [world-id hit charge]
  (let [bx (int (:x hit)) by (int (:y hit)) bz (int (:z hit))
        be (get-block-entity-at world-id bx by bz)]
    (if-not be
      {:effective? false :charged 0.0 :block-pos [bx by bz]}
      (cond
        (energy/is-node-supported? be)
        (let [charged (- (double charge) (double (energy/charge-node be charge true)))]
          {:effective? true :charged (max 0.0 charged) :block-pos [bx by bz]})
        (energy/is-receiver-supported? be)
        (let [charged (- (double charge) (double (energy/charge-receiver be charge)))]
          {:effective? true :charged (max 0.0 charged) :block-pos [bx by bz]})
        :else {:effective? false :charged 0.0 :block-pos [bx by bz]}))))

(defn- send-fx-start! [ctx-id is-item]
  (ctx/ctx-send-to-client! ctx-id :current-charging/fx-start {:is-item (boolean is-item)}))
(defn- send-fx-update! [ctx-id payload]
  (ctx/ctx-send-to-client! ctx-id :current-charging/fx-update payload))
(defn- send-fx-end! [ctx-id is-item]
  (ctx/ctx-send-to-client! ctx-id :current-charging/fx-end {:is-item (boolean is-item)}))
(defn- finish-charge! [ctx-id is-item]
  (send-fx-end! ctx-id is-item)
  (ctx/terminate-context! ctx-id nil))

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
  :cost        {:down {:overload (fn [{:keys [exp]}] (initial-overload exp))}
                :tick {:cp       (fn [{:keys [player-id ctx-id exp]}]
                                   (if-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
                                     (let [is-item (boolean (:is-item skill-state))
                                           stack   (when is-item (get-main-hand-item player-id))]
                                       (if (and is-item (nil? stack))
                                         0.0
                                         (tick-consumption (double (or (:exp skill-state) exp)))))
                                     0.0))}}
  :actions
  {:down!  (fn [{:keys [player-id ctx-id exp cost-ok?]}]
             (let [is-item        (boolean (get-main-hand-item player-id))
                   overload-floor (double (initial-overload exp))]
               (if-not cost-ok?
                 (do (send-fx-end! ctx-id is-item) (ctx/terminate-context! ctx-id nil))
                 (do (ctx/update-context! ctx-id assoc :skill-state
                                          {:is-item is-item :exp exp
                                           :overload-floor overload-floor :charge-ticks 0})
                     (send-fx-start! ctx-id is-item)))))
   :tick!  (fn [{:keys [player-id ctx-id cost-ok?]}]
             (when-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
               (let [is-item        (boolean (:is-item skill-state))
                     exp            (double (or (:exp skill-state) 0.0))
                     new-ticks      (inc (int (or (:charge-ticks skill-state) 0)))
                     overload-floor (double (or (:overload-floor skill-state) 0.0))
                     charge         (charging-speed exp)]
                 (ctx/update-context! ctx-id assoc-in [:skill-state :charge-ticks] new-ticks)
                 (enforce-overload-floor! player-id overload-floor)
                 (if is-item
                   (let [stack (get-main-hand-item player-id)]
                     (if (or (nil? stack) (not cost-ok?))
                       (finish-charge! ctx-id true)
                       (let [good (energy/is-energy-item-supported? stack)]
                         (when good (energy/charge-energy-to-item stack charge false))
                         (skill-effects/add-skill-exp!
                          player-id :current-charging (if good 0.0001 0.00003))
                         (send-fx-update! ctx-id {:is-item true :good? (boolean good)
                                                  :charge-ticks new-ticks}))))
                   (let [view     (get-player-view player-id)
                         world-id (or (:world-id view) "minecraft:overworld")
                         hit      (when view (raycast-block view))
                         ray-end  (if (and hit (number? (:distance hit)))
                                    (endpoint view (double (:distance hit)))
                                    (endpoint view max-distance))
                         {:keys [effective? charged block-pos]}
                         (if hit
                           (charge-block-target! world-id hit charge)
                           {:effective? false :charged 0.0 :block-pos nil})]
                     (if-not cost-ok?
                       (finish-charge! ctx-id false)
                       (do (skill-effects/add-skill-exp!
                            player-id :current-charging (if effective? 0.0001 0.00003))
                           (send-fx-update! ctx-id {:is-item false :good? (boolean effective?)
                                                    :charged (double charged)
                                                    :charge-ticks new-ticks
                                                    :target ray-end :block-pos block-pos})))))))
             (when (zero? (mod (get-in (ctx/get-context ctx-id) [:skill-state :charge-ticks] 0) 20))
               (log/debug "CurrentCharging tick")))
   :up!    (fn [{:keys [ctx-id]}]
             (when-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
               (send-fx-end! ctx-id (boolean (:is-item skill-state)))))
   :abort! (fn [{:keys [ctx-id]}]
             (when-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
               (send-fx-end! ctx-id (boolean (:is-item skill-state))))
             (ctx/update-context! ctx-id dissoc :skill-state))}
  :prerequisites [{:skill-id :arc-gen :min-exp 0.3}])
