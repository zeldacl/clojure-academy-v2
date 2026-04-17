(ns cn.li.ac.content.ability.electromaster.body-intensify
  "BodyIntensify skill - hold to charge randomised potions.

  Pattern: :charge-window (min 10, max 40, tolerant 100)
  Cost: overload lerp(200,120) on down; CP lerp(20,15)/tick while charging (≤40 ticks)
  Cooldown: lerp(900,600) ticks (manual, applied on successful up ≥10 ticks)
  Exp: +0.01 on successful release"
  (:require [cn.li.ac.ability.player-state :as ps]
            [cn.li.ac.ability.dsl :refer [defskill!]]
            [cn.li.ac.ability.balance :as bal]
            [cn.li.ac.ability.context :as ctx]
            [cn.li.ac.ability.model.resource-data :as rdata]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]))

(def ^:private min-time 10)
(def ^:private max-time 40)
(def ^:private max-tolerant-time 100)

(def ^:private base-effects
  [{:effect :speed        :max-amplifier 3}
   {:effect :jump-boost   :max-amplifier 1}
   {:effect :regeneration :max-amplifier 1}
   {:effect :strength     :max-amplifier 1}
   {:effect :resistance   :max-amplifier 1}])

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
    (let [prob            (get-probability charge-ticks)
          duration        (get-buff-time charge-ticks exp)
          hunger-duration (get-hunger-buff-time charge-ticks)
          level           (get-buff-level charge-ticks)
          shuffled        (vec (shuffle base-effects))]
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
  :cost        {:down {:overload (bal/by-exp 200.0 120.0)}
                :tick {:cp (fn [{:keys [hold-ticks exp]}]
                             (when (<= (long (or hold-ticks 0)) max-time)
                               (bal/lerp 20.0 15.0 (double (or exp 0.0)))))}}
  :fx          {:end {:topic   :body-intensify/fx-end
                      :payload (fn [{:keys [hold-ticks]}]
                                 {:performed? (>= (long (or hold-ticks 0)) min-time)})}}
  :actions
  {:cost-fail! (fn [{:keys [ctx-id]}]
                 (ctx/terminate-context! ctx-id nil))
   :tick!      (fn [{:keys [player-id ctx-id hold-ticks exp]}]
                 (enforce-overload-floor! player-id (bal/lerp 200.0 120.0 (double (or exp 0.0))))
                 (when (>= (long (or hold-ticks 0)) max-tolerant-time)
                   (ctx/terminate-context! ctx-id nil)))
   :up!        (fn [{:keys [player-id hold-ticks exp]}]
                 (let [ticks (long (or hold-ticks 0))]
                   (when (>= ticks min-time)
                     (let [effective-tick (min ticks max-time)
                           exp*           (double (or exp 0.0))]
                       (apply-body-intensify-buffs! player-id effective-tick exp*)
                       (skill-effects/add-skill-exp! player-id :body-intensify 0.01)
                       (skill-effects/set-main-cooldown! player-id :body-intensify
                                                         (int (Math/round (double (bal/lerp 900.0 600.0 exp*)))))))))}
  :prerequisites [{:skill-id :arc-gen         :min-exp 1.0}
                  {:skill-id :current-charging :min-exp 1.0}])
