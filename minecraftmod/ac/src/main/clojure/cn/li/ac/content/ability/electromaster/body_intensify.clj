(ns cn.li.ac.content.ability.electromaster.body-intensify
  "BodyIntensify skill - hold to charge randomised potions.

  Pattern: :charge-window (min 10, max 40, tolerant 100)
  Cost: overload lerp(200,120) on down; CP lerp(20,15)/tick while charging
  Cooldown: lerp(900,600) ticks (manual, applied on successful up)
  Exp: +0.01 on successful release"
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.balance :as bal]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.model.resource-data :as rdata]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]
            [cn.li.mcmod.util.log :as log]))

(def ^:private min-time 10)
(def ^:private max-time 40)
(def ^:private max-tolerant-time 100)

(def ^:private base-effects
  [{:effect :speed       :max-amplifier 3}
   {:effect :jump-boost  :max-amplifier 1}
   {:effect :regeneration :max-amplifier 1}
   {:effect :strength    :max-amplifier 1}
   {:effect :resistance  :max-amplifier 1}])

(defn- get-probability [ct] (/ (- (double ct) 10.0) 18.0))

(defn- get-buff-time [ct exp]
  (int (* (+ 1.0 (rand)) (double ct) (bal/lerp 1.5 2.5 exp))))

(defn- get-hunger-buff-time [ct] (int (* 1.25 (double ct))))

(defn- get-buff-level [ct] (int (Math/floor (get-probability ct))))

(defn- enforce-overload-floor! [player-id floor-value]
  (ps/update-resource-data!
   player-id
   (fn [res-data]
     (if (< (double (:cur-overload res-data)) (double floor-value))
       (-> res-data
           (rdata/set-cur-overload floor-value)
           (assoc :overload-fine true))
       res-data))))

(defn- apply-body-intensify-buffs! [player-id charge-ticks exp]
  (when potion-effects/*potion-effects*
    (let [prob           (get-probability charge-ticks)
          duration       (get-buff-time charge-ticks exp)
          hunger-duration (get-hunger-buff-time charge-ticks)
          level          (get-buff-level charge-ticks)
          shuffled       (vec (shuffle base-effects))]
      (loop [p prob picked 0]
        (when (> p 0.0)
          (let [roll (rand)]
            (if (< roll p)
              (let [next-picked (inc picked)]
                (when-let [{:keys [effect max-amplifier]} (nth shuffled next-picked nil)]
                  (potion-effects/apply-potion-effect!
                   potion-effects/*potion-effects*
                   player-id effect duration (min level max-amplifier)))
                (recur (- p 1.0) next-picked))
              (recur (- p 1.0) picked)))))
      (potion-effects/apply-potion-effect!
       potion-effects/*potion-effects*
       player-id :hunger hunger-duration 2))))

(defn- send-fx-end! [ctx-id performed?]
  (ctx/ctx-send-to-client! ctx-id :body-intensify/fx-end {:performed? (boolean performed?)}))

(defskill! body-intensify
  :id          :body-intensify
  :category-id :electromaster
  :name-key    "ability.skill.electromaster.body_intensify"
  :description-key "ability.skill.electromaster.body_intensify.desc"
  :icon        "textures/abilities/electromaster/skills/body_intensify.png"
  :ui-position [97 15]
  :level       4
  :controllable? true
  :ctrl-id     :body-intensify
  :pattern     :charge-window
  :cooldown    {:mode :manual}
  :cost        {:down {:overload (fn [{:keys [exp]}] (bal/lerp 200.0 120.0 exp))}
                :tick {:cp       (fn [{:keys [ctx-id]}]
                                   (if-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
                                     (let [tick      (int (or (:tick skill-state) 0))
                                           cp-per-tick (double (or (:cp-per-tick skill-state) 20.0))]
                                       (if (<= tick max-time) cp-per-tick 0.0))
                                     0.0))}}
  :actions
  {:down! (fn [{:keys [player-id ctx-id exp cost-ok?]}]
            (let [overload-floor (double (bal/lerp 200.0 120.0 exp))]
              (if-not cost-ok?
                (do (send-fx-end! ctx-id false)
                    (ctx/terminate-context! ctx-id nil))
                (do (ctx/update-context! ctx-id assoc :skill-state
                                         {:tick 0
                                          :overload-floor overload-floor
                                          :cp-per-tick (bal/lerp 20.0 15.0 exp)
                                          :exp exp})
                    (log/debug "BodyIntensify charge started")))))
   :tick! (fn [{:keys [player-id ctx-id cost-ok?]}]
            (when-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
              (let [tick          (inc (int (or (:tick skill-state) 0)))
                    overload-floor (double (or (:overload-floor skill-state) 0.0))]
                (enforce-overload-floor! player-id overload-floor)
                (ctx/update-context! ctx-id assoc-in [:skill-state :tick] tick)
                (cond
                  (>= tick max-tolerant-time)
                  (do (send-fx-end! ctx-id false)
                      (ctx/terminate-context! ctx-id nil))
                  (and (<= tick max-time) (not cost-ok?))
                  (do (send-fx-end! ctx-id false)
                      (ctx/terminate-context! ctx-id nil))))))
   :up!   (fn [{:keys [player-id ctx-id]}]
            (when-let [{:keys [skill-state]} (ctx/get-context ctx-id)]
              (let [tick (int (or (:tick skill-state) 0))
                    exp  (double (or (:exp skill-state)
                                     (get-in (ps/get-player-state player-id)
                                             [:ability-data :skills :body-intensify :exp])
                                     0.0))]
                (if (< tick min-time)
                  (send-fx-end! ctx-id false)
                  (let [effective-tick (min tick max-time)
                        cd-ticks (int (Math/round (double (bal/lerp 900.0 600.0 exp))))]
                    (apply-body-intensify-buffs! player-id effective-tick exp)
                    (skill-effects/add-skill-exp! player-id :body-intensify 0.01)
                    (skill-effects/set-main-cooldown! player-id :body-intensify cd-ticks)
                    (send-fx-end! ctx-id true)
                    (log/debug "BodyIntensify executed: tick" effective-tick))))))
   :abort! (fn [{:keys [ctx-id]}]
             (send-fx-end! ctx-id false)
             (ctx/update-context! ctx-id dissoc :skill-state))}
  :prerequisites [{:skill-id :arc-gen         :min-exp 1.0}
                  {:skill-id :current-charging :min-exp 1.0}])
