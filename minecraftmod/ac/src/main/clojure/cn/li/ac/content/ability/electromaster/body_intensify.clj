(ns cn.li.ac.content.ability.electromaster.body-intensify
  "BodyIntensify skill - hold to charge randomised potions.

  Pattern: :charge-window (min 10, max 40, tolerant 100)
  Cost: overload lerp(200,120) on down; CP lerp(20,15)/tick while charging (�?0 ticks)
  Cooldown: lerp(900,600) ticks (manual, applied on successful up �?0 ticks)
  Exp: +0.01 on successful release"
  (:require [clojure.string :as str]
            [cn.li.ac.ability.dsl :refer [defskill]]
            [cn.li.ac.ability.skill-config :as skill-config]
            [cn.li.ac.ability.service.context-dispatcher :as ctx]
            [cn.li.ac.ability.service.context-registry :as ctx-reg]
            [cn.li.ac.ability.service.skill-effects :as skill-effects]
            [cn.li.mcmod.platform.potion-effects :as potion-effects]))

(def ^:private body-intensify-skill-id :body-intensify)

(defn- cfg-double [field-id]
  (skill-config/tunable-double body-intensify-skill-id field-id))

(defn- cfg-int [field-id]
  (skill-config/tunable-int body-intensify-skill-id field-id))

(defn- cfg-lerp [field-id exp]
  (skill-config/lerp-double body-intensify-skill-id field-id exp))

(defn- min-time [] (cfg-int :charge.min-ticks))
(defn- max-time [] (cfg-int :charge.max-ticks))
(defn- max-tolerant-time [] (cfg-int :charge.max-tolerant-ticks))

(defn- parse-effect-entry [entry]
  (let [[effect-name amp-text] (str/split (str entry) #":" 2)
        effect-name (str/trim (or effect-name ""))]
    (when-not (str/blank? effect-name)
      {:effect (keyword effect-name)
       :max-amplifier (max 0 (try
                               (Integer/parseInt (str/trim (or amp-text "0")))
                               (catch Exception _ 0)))})))

(defn- base-effects []
  (vec (keep parse-effect-entry
             (skill-config/tunable-string-list body-intensify-skill-id
                                               :effect.available-effects))))

(defn- get-probability [ct]
  (/ (- (double ct) (cfg-double :effect.probability-offset-ticks))
     (cfg-double :effect.probability-divisor)))

(defn- get-buff-time [ct exp]
  (int (* (+ 1.0 (rand)) (double ct) (cfg-lerp :effect.duration-multiplier exp))))

(defn- get-hunger-buff-time [ct]
  (int (* (cfg-double :effect.hunger-multiplier) (double ct))))

(defn- get-buff-level [ct] (int (Math/floor (get-probability ct))))

(defn- enforce-overload-floor! [player-id floor-value]
  (skill-effects/enforce-overload-floor! player-id floor-value))

(defn- select-random-effects
  "Pick effects based on probability budget.

  The nth successful roll picks the nth effect from the shuffled pool,
  matching the legacy sequential-pick behavior without skipping index 0."
  [effects probability]
  (loop [p (double probability)
         picked-index 0
         selected []]
    (if (<= p 0.0)
      selected
      (if (< (rand) p)
        (let [selected* (if-let [effect-entry (nth effects picked-index nil)]
                          (conj selected effect-entry)
                          selected)]
          (recur (- p 1.0) (inc picked-index) selected*))
        (recur (- p 1.0) picked-index selected)))))

(defn- apply-body-intensify-buffs! [player-id charge-ticks exp]
  (when potion-effects/*potion-effects*
    (let [prob            (get-probability charge-ticks)
          duration        (get-buff-time charge-ticks exp)
          hunger-duration (get-hunger-buff-time charge-ticks)
          level           (get-buff-level charge-ticks)
          shuffled        (vec (shuffle (base-effects)))]
      (doseq [{:keys [effect max-amplifier]}
              (select-random-effects shuffled prob)]
        (potion-effects/apply-potion-effect!
         potion-effects/*potion-effects*
         player-id effect duration (min level max-amplifier)))
      (potion-effects/apply-potion-effect!
       potion-effects/*potion-effects*
             player-id :hunger hunger-duration (cfg-int :effect.hunger-amplifier)))))

(defskill body-intensify
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
  :cost        {:down {:overload (fn [{:keys [exp]}]
                                  (cfg-lerp :cost.down.overload (double (or exp 0.0))))}
                :tick {:cp (fn [{:keys [hold-ticks exp]}]
                             (when (<= (long (or hold-ticks 0)) (max-time))
                               (cfg-lerp :cost.tick.cp (double (or exp 0.0)))))}}
  :fx          {:end {:topic   :body-intensify/fx-end
                      :payload (fn [{:keys [hold-ticks]}]
                                 {:performed? (>= (long (or hold-ticks 0)) (min-time))})}}
  :actions
  {:cost-fail! (fn [{:keys [ctx-id]}]
                 (ctx-reg/terminate-context! ctx-id nil))
   :tick!      (fn [{:keys [player-id ctx-id hold-ticks exp]}]
                 (enforce-overload-floor! player-id
                                          (cfg-lerp :cost.down.overload (double (or exp 0.0))))
                 (when (>= (long (or hold-ticks 0)) (max-tolerant-time))
                   (ctx-reg/terminate-context! ctx-id nil)))
   :up!        (fn [{:keys [player-id hold-ticks exp]}]
                 (let [ticks (long (or hold-ticks 0))]
                   (when (>= ticks (min-time))
                     (let [effective-tick (min ticks (max-time))
                           exp*           (double (or exp 0.0))]
                       (apply-body-intensify-buffs! player-id effective-tick exp*)
                       (skill-effects/add-skill-exp! player-id body-intensify-skill-id
                                                     (cfg-double :progression.exp-use))
                       (skill-effects/set-main-cooldown! player-id body-intensify-skill-id
                                                         (skill-config/lerp-int body-intensify-skill-id
                                                                                :cooldown.ticks
                                                                                exp*))))))}
  :prerequisites [{:skill-id :arc-gen         :min-exp 1.0}
                  {:skill-id :current-charging :min-exp 1.0}])
