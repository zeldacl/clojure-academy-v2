(ns cn.li.ac.content.ability.electromaster.current-charging
  "CurrentCharging skill port aligned to original AcademyCraft behavior.

  Mechanics summary:
  - Hold key to channel energy through looked-at block or held item
  - Initial overload: 65 -> 48 (scales with exp)
  - CP cost per tick: 3 -> 7 (scales with exp)
  - Charge amount per tick: floor(15 -> 35, scales with exp)
  - Experience gain: 0.0001 effective, 0.00003 ineffective

  No Minecraft imports."
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.service.learning :as learning]
            [cn.li.ac.ability.event :as ability-evt]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.model.resource-data :as rdata]
            [cn.li.ac.energy.operations :as energy]
            [cn.li.ac.content.ability.common :as ability-common]
            [cn.li.mcmod.platform.ability-interop :as interop]
            [cn.li.mcmod.platform.raycast :as raycast]
            [cn.li.mcmod.util.log :as log]))

(def ^:private max-distance 15.0)
(declare get-skill-exp get-main-hand-item)

(defn- lerp
  [a b t]
  (ability-common/lerp a b t))

(defn- charging-speed
  [exp]
  (Math/floor (lerp 15.0 35.0 exp)))

(defn- tick-consumption
  [exp]
  (lerp 3.0 7.0 exp))

(defn- initial-overload
  [exp]
  (lerp 65.0 48.0 exp))

(defn- exp-incr
  [effective?]
  (if effective? 0.0001 0.00003))

(defn current-charging-cost-down-overload
  [{:keys [player-id]}]
  (initial-overload (double (get-skill-exp player-id))))

(defn current-charging-cost-tick-cp
  [{:keys [player-id ctx-id]}]
  (if-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
    (let [is-item (boolean (:is-item skill-state))
          stack (when is-item (get-main-hand-item player-id))
          exp (double (or (:exp skill-state) (get-skill-exp player-id)))]
      (if (and is-item (nil? stack))
        0.0
        (tick-consumption exp)))
    0.0))

(defn- enforce-overload-floor!
  [player-id floor-value]
  (ps/update-resource-data!
   player-id
   (fn [res-data]
     (if (< (double (:cur-overload res-data)) (double floor-value))
       (-> res-data
           (rdata/set-cur-overload floor-value)
           (assoc :overload-fine true))
       res-data))))

(defn- get-player-view
  [player-id]
  (when interop/*ability-interop*
    (interop/get-player-view interop/*ability-interop* player-id)))

(defn- get-main-hand-item
  [player-id]
  (when interop/*ability-interop*
    (interop/get-player-main-hand-item interop/*ability-interop* player-id)))

(defn- get-block-entity-at
  [world-id x y z]
  (when interop/*ability-interop*
    (interop/get-block-entity-at interop/*ability-interop* world-id x y z)))

(defn- endpoint
  [view distance]
  {:x (+ (double (:x view)) (* (double (:look-x view)) distance))
   :y (+ (double (:y view)) (* (double (:look-y view)) distance))
   :z (+ (double (:z view)) (* (double (:look-z view)) distance))})

(defn- raycast-block
  [view]
  (when raycast/*raycast*
    (raycast/raycast-blocks raycast/*raycast*
                            (or (:world-id view) "minecraft:overworld")
                            (double (:x view)) (double (:y view)) (double (:z view))
                            (double (:look-x view)) (double (:look-y view)) (double (:look-z view))
                            max-distance)))

(defn- send-fx-start!
  [ctx-id is-item]
  (ctx/ctx-send-to-client! ctx-id :current-charging/fx-start {:is-item (boolean is-item)}))

(defn- send-fx-update!
  [ctx-id payload]
  (ctx/ctx-send-to-client! ctx-id :current-charging/fx-update payload))

(defn- send-fx-end!
  [ctx-id is-item]
  (ctx/ctx-send-to-client! ctx-id :current-charging/fx-end {:is-item (boolean is-item)}))

(defn- get-skill-exp [player-id]
  (ability-common/get-skill-exp player-id :current-charging))

(defn- add-skill-exp!
  [player-id amount]
  (ability-common/add-skill-exp! player-id :current-charging amount 1.0))

(defn- charge-block-target!
  [world-id hit charge]
  (let [bx (int (:x hit))
        by (int (:y hit))
        bz (int (:z hit))
        be (get-block-entity-at world-id bx by bz)]
    (if-not be
      {:effective? false :charged 0.0 :block-pos [bx by bz]}
      (cond
        (energy/is-node-supported? be)
        (let [leftover (double (energy/charge-node be charge true))
              charged (- (double charge) leftover)]
          {:effective? true :charged (max 0.0 charged) :block-pos [bx by bz]})

        (energy/is-receiver-supported? be)
        (let [leftover (double (energy/charge-receiver be charge))
              charged (- (double charge) leftover)]
          {:effective? true :charged (max 0.0 charged) :block-pos [bx by bz]})

        :else
        {:effective? false :charged 0.0 :block-pos [bx by bz]}))))

(defn- finish-charge!
  [ctx-id is-item]
  (send-fx-end! ctx-id is-item)
  (ctx/terminate-context! ctx-id nil))

(defn current-charging-on-key-down
  "Initialize charging state when key pressed."
  [{:keys [player-id ctx-id cost-ok?]}]
  (try
    (let [exp (double (get-skill-exp player-id))
          is-item (boolean (get-main-hand-item player-id))
          overload-floor (double (initial-overload exp))]
      (if-not cost-ok?
        (do
          (send-fx-end! ctx-id is-item)
          (ctx/terminate-context! ctx-id nil)
          (log/debug "CurrentCharging start failed: insufficient resource"))
        (do
          (ctx/update-context! ctx-id assoc :skill-state
                               {:is-item is-item
                                :exp exp
                                :overload-floor overload-floor
                                :charge-ticks 0})
          (send-fx-start! ctx-id is-item)
          (log/debug "CurrentCharging started, mode:" (if is-item :item :block)))))
    (catch Exception e
      (log/warn "CurrentCharging key-down failed:" (ex-message e)))))

(defn current-charging-on-key-tick
  "Continue charging each tick."
  [{:keys [player-id ctx-id cost-ok?]}]
  (try
    (when-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
      (let [is-item (boolean (:is-item skill-state))
            exp (double (or (:exp skill-state) (get-skill-exp player-id)))
            new-charge-ticks (inc (int (or (:charge-ticks skill-state) 0)))
            overload-floor (double (or (:overload-floor skill-state) 0.0))
            charge (charging-speed exp)]
        (ctx/update-context! ctx-id assoc-in [:skill-state :charge-ticks] new-charge-ticks)
        (enforce-overload-floor! player-id overload-floor)

        (if is-item
          (let [stack (get-main-hand-item player-id)]
            (if-not stack
              (finish-charge! ctx-id true)
              (if-not cost-ok?
                (finish-charge! ctx-id true)
                (let [good (energy/is-energy-item-supported? stack)]
                  (when good
                    (energy/charge-energy-to-item stack charge false))
                  (add-skill-exp! player-id (exp-incr good))
                  (send-fx-update! ctx-id {:is-item true
                                           :good? (boolean good)
                                           :charge-ticks new-charge-ticks})))))

          (let [view (get-player-view player-id)
                world-id (or (:world-id view) "minecraft:overworld")
                hit (when view (raycast-block view))
                ray-end (if (and hit (number? (:distance hit)))
                          (endpoint view (double (:distance hit)))
                          (endpoint view max-distance))
                {:keys [effective? charged block-pos]}
                (if hit
                  (charge-block-target! world-id hit charge)
                  {:effective? false :charged 0.0 :block-pos nil})
                ]
            (if-not cost-ok?
              (finish-charge! ctx-id false)
              (do
                (add-skill-exp! player-id (exp-incr effective?))
                (send-fx-update!
                 ctx-id
                 {:is-item false
                  :good? (boolean effective?)
                  :charged (double charged)
                  :charge-ticks new-charge-ticks
                  :target ray-end
                  :block-pos block-pos})))))

        (when (zero? (mod new-charge-ticks 20))
          (log/debug "CurrentCharging: charged for" (/ new-charge-ticks 20) "seconds"))))
    (catch Exception e
      (log/warn "CurrentCharging key-tick failed:" (ex-message e)))))

(defn current-charging-on-key-up
  "Stop charging when key released."
  [{:keys [ctx-id]}]
  (try
    (when-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
      (let [is-item (boolean (:is-item skill-state))
            charge-ticks (int (or (:charge-ticks skill-state) 0))]
        (send-fx-end! ctx-id is-item)
        (log/debug "CurrentCharging completed:" (if is-item :item :block)
                   "ticks=" charge-ticks)))
    (catch Exception e
      (log/warn "CurrentCharging key-up failed:" (ex-message e)))))

(defn current-charging-on-key-abort
  "Clean up charging state on abort."
  [{:keys [ctx-id]}]
  (try
    (when-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
      (send-fx-end! ctx-id (boolean (:is-item skill-state))))
    (ctx/update-context! ctx-id dissoc :skill-state)
    (log/debug "CurrentCharging aborted")
    (catch Exception e
      (log/warn "CurrentCharging key-abort failed:" (ex-message e)))))
